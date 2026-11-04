"""Hybrid retrieval (vectors + keywords) and evidence-grounded answering."""

import re
import time
from typing import Any, Protocol

from .db import Database
from .vectors import VectorIndex

RRF_K = 60
MAX_CHUNKS_PER_FILE = 3

SYSTEM_PROMPT = """You are Memora, a personal search assistant. You answer questions using ONLY the numbered
sources provided, which are excerpts and image descriptions from the user's own files.

Rules:
- Use only facts stated in the sources. Never use outside knowledge or guess.
- Cite every fact with its source number in square brackets, e.g. [2]. Mention file names when the user asks where something is.
- If the sources do not contain enough information to answer, say clearly what is missing,
  set "sufficient" to false, and do not invent an answer. A partial answer is fine if you say which part is unsupported.
- Image sources show "Captured" (when the photo itself says it was taken) separately from when the file was added.
  Only "Captured" answers "when was this taken"; if it says the date is not recorded, say so and never use the added date.
- Text labelled as written by the user (caption, notes, tags) is the user's own wording; AI descriptions can be wrong, so word them as descriptions.
- Be concise: a few sentences or a short list.
- "citations" lists the numbers of the sources you actually relied on."""

ANSWER_SCHEMA = {
    "type": "object",
    "properties": {
        "answer": {"type": "string"},
        "sufficient": {"type": "boolean"},
        "citations": {"type": "array", "items": {"type": "integer"}},
    },
    "required": ["answer", "sufficient", "citations"],
}

NO_EVIDENCE = "I couldn't find anything in your indexed files that relates to this question."


class Model(Protocol):
    def embed(self, texts: list[str], kind: str = "document") -> list[list[float]]: ...
    def chat_json(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]: ...


def retrieve(db: Database, vectors: VectorIndex, model: Model, query: str, top_k: int) -> list[dict[str, Any]]:
    """Fuse semantic and keyword rankings with reciprocal rank fusion; cap chunks per file for diversity."""
    pool = max(top_k * 4, 30)
    vec_hits = vectors.search(model.embed([query], kind="query")[0], pool)
    kw_hits = db.keyword_search(query, pool)

    scores: dict[int, float] = {}
    for rank, (cid, _) in enumerate(vec_hits):
        scores[cid] = scores.get(cid, 0.0) + 1.0 / (RRF_K + rank)
    for rank, cid in enumerate(kw_hits):
        scores[cid] = scores.get(cid, 0.0) + 1.0 / (RRF_K + rank)

    chunks = db.get_chunks(list(scores))  # drops ids whose rows no longer exist
    per_file: dict[int, int] = {}
    results = []
    for cid in sorted(chunks, key=lambda c: scores[c], reverse=True):
        c = chunks[cid]
        if per_file.get(c["file_id"], 0) >= MAX_CHUNKS_PER_FILE:
            continue
        per_file[c["file_id"]] = per_file.get(c["file_id"], 0) + 1
        results.append({**c, "score": scores[cid]})
        if len(results) >= top_k:
            break
    return results


def _when(c: dict[str, Any]) -> str:
    """How a source's date is shown. For photos the capture date is the only one presented as the taken date."""
    if c["kind"] == "image":
        if c.get("captured_date"):
            return f"captured {c['captured_date']}" + (f" {c['captured_time']}" if c.get("captured_time") else "")
        added = time.strftime("%Y-%m-%d", time.localtime(c.get("indexed_at") or c["mtime"]))
        return f"capture date not recorded; added to Memora {added}"
    return "modified " + time.strftime("%Y-%m-%d", time.localtime(c["mtime"]))


def _format_sources(chunks: list[dict[str, Any]]) -> str:
    parts = []
    for i, c in enumerate(chunks, 1):
        kind = "photo" if c["kind"] == "image" else "document text"
        parts.append(f"[{i}] File: {c['name']} ({kind}, {_when(c)})\nPath: {c['path']}\n{c['text']}")
    return "\n\n---\n\n".join(parts)


_STOP = {"that", "this", "with", "from", "have", "has", "were", "was", "are", "the", "and", "for", "not", "your", "they",
         "their", "there", "which", "what", "when", "where", "would", "could", "about", "into", "than", "then", "also",
         "only", "photo", "image", "file", "files", "photos", "source", "sources", "found", "shows", "show", "mention"}


def _words(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-z0-9][a-z0-9\-]*", text.lower()) if (len(w) >= 4 and w not in _STOP) or w.isdigit()}


def supports(answer: str, source_text: str) -> bool:
    """A cited source must share at least one distinctive word or number with the answer.
    This catches a citation that points at an unrelated file; it does not prove the claim is true."""
    claim = _words(re.sub(r"\[\d+\]", " ", answer))
    return not claim or bool(claim & _words(source_text))


_NUMBER_WORDS = {w: str(i) for i, w in enumerate(
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen "
    "eighteen nineteen twenty".split())}


def _figures(text: str) -> set[str]:
    """Numbers in a text, normalised: '1,299.00' -> '1299', 'two' -> '2'. Dates split into their numeric parts."""
    text = re.sub(r"\[\d+\]", " ", text.lower())
    found = {_NUMBER_WORDS[w] for w in re.findall(r"[a-z]+", text) if w in _NUMBER_WORDS}
    for raw in re.findall(r"\d[\d,]*(?:\.\d+)?", text):
        num = raw.replace(",", "")
        if "." in num:
            num = num.rstrip("0").rstrip(".")
        found.add(num.lstrip("0") or "0")
    return found


def unsupported_figures(answer: str, source_texts: list[str]) -> list[str]:
    """Numbers in the answer that appear in none of the cited sources (a changed amount, date or period).
    Limits: it checks that a figure occurs in the evidence, not that it is attached to the right fact,
    and it cannot judge claims that contain no numbers."""
    allowed: set[str] = set()
    for t in source_texts:
        allowed |= _figures(t)
    return sorted(_figures(answer) - allowed)


def answer_question(db: Database, vectors: VectorIndex, model: Model, query: str, top_k: int) -> dict[str, Any]:
    chunks = retrieve(db, vectors, model, query, top_k)
    if not chunks:
        return {"answer": NO_EVIDENCE, "sufficient": False, "sources": []}

    user = f"Sources:\n\n{_format_sources(chunks)}\n\n===\n\nQuestion: {query}"
    result = model.chat_json(SYSTEM_PROMPT, user, ANSWER_SCHEMA)
    answer = str(result.get("answer", "")).strip() or NO_EVIDENCE
    sufficient = bool(result.get("sufficient", False))

    # Trust only citation numbers that point at real sources; fall back to [n] markers in the text.
    cited = {n for n in result.get("citations", []) if isinstance(n, int) and 1 <= n <= len(chunks)}
    if sufficient:
        cited |= {int(n) for n in re.findall(r"\[(\d+)\]", answer) if 1 <= int(n) <= len(chunks)}
        # Drop citations whose source has nothing in common with the answer.
        cited = {n for n in cited if supports(answer, chunks[n - 1]["text"])}
    else:
        # A "not enough evidence" answer often mentions sources only to say they don't help;
        # those mentions must not be presented as evidence.
        cited = set()

    unsupported = unsupported_figures(answer, [chunks[n - 1]["text"] for n in cited]) if cited else []
    if unsupported:
        # A number the cited evidence does not contain: do not present this as a supported answer.
        sufficient = False
        answer += "\n\n(Check this answer: " + ", ".join(unsupported) + " does not appear in the cited sources.)"

    sources = []
    for i, c in enumerate(chunks, 1):
        sources.append(
            {
                "ref": i,
                "cited": i in cited,
                "file_id": c["file_id"],
                "name": c["name"],
                "path": c["path"],
                "kind": c["kind"],
                "ext": c["ext"],
                "snippet": c["text"][:500],
                "score": round(c["score"], 4),
                "photo": (
                    {"caption": (c.get("user_caption") or c.get("photo_caption") or "").strip() or None,
                     "captured_date": c.get("captured_date"), "captured_time": c.get("captured_time"),
                     "thumbnail": f"/api/files/{c['file_id']}/thumbnail"}
                    if c["kind"] == "image" else None
                ),
            }
        )
    # Cited evidence first, then the rest by retrieval rank.
    sources.sort(key=lambda s: (not s["cited"], s["ref"]))
    return {"answer": answer, "sufficient": sufficient and bool(cited), "unsupported_figures": unsupported, "sources": sources}
