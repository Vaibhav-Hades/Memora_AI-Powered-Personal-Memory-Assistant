"""What a vision model is asked about a photo, and how its answer is checked before anything is stored."""

from typing import Any

PROMPT_VERSION = 1

SYSTEM_PROMPT = """You describe one personal photo so its owner can find it later. Look at the image and report only what is visible.

Rules:
- Be factual. Never name a real person, guess who someone is, or infer age, health, religion, ethnicity or other sensitive traits.
- Do not guess where or when the photo was taken. Mention a place or date only if it is written in the image itself.
- Do not state intentions, relationships, emotions or events that cannot be seen.
- "visible_text" is text actually readable in the image, copied as written, one line per line of text. Use "" if there is none and write [unreadable] for parts you cannot read. Never invent text.
- "objects" lists clearly visible objects or scene elements, most important first, at most 15, short noun phrases.
- "uncertainty" says what is unclear, blurry, cropped or ambiguous ("" if nothing is).
- "context" is anything useful that the image itself supports (for example "looks like a restaurant menu"); "" if there is nothing beyond the description.
- Output JSON only."""

USER_PROMPT = "Describe this photo."

ANALYSIS_SCHEMA = {
    "type": "object",
    "properties": {
        "caption": {"type": "string"},
        "description": {"type": "string"},
        "objects": {"type": "array", "items": {"type": "string"}},
        "visible_text": {"type": "string"},
        "context": {"type": "string"},
        "summary": {"type": "string"},
        "uncertainty": {"type": "string"},
    },
    "required": ["caption", "description", "objects", "visible_text", "context", "summary", "uncertainty"],
}

LIMITS = {"caption": 240, "description": 2000, "visible_text": 4000, "context": 600, "summary": 500, "uncertainty": 500}


class InvalidAnalysis(ValueError):
    """The model answered, but not with something usable."""


def normalize_analysis(raw: Any) -> dict[str, Any]:
    """Validate a model's answer against the schema and trim it. Raises InvalidAnalysis instead of guessing."""
    if not isinstance(raw, dict):
        raise InvalidAnalysis("The model's answer was not a JSON object.")
    out: dict[str, Any] = {}
    for key, limit in LIMITS.items():
        value = raw.get(key, "")
        if value is None:
            value = ""
        if not isinstance(value, str):
            raise InvalidAnalysis(f"'{key}' must be text.")
        out[key] = value.strip()[:limit]
    objects = raw.get("objects", [])
    if not isinstance(objects, list) or not all(isinstance(o, str) for o in objects):
        raise InvalidAnalysis("'objects' must be a list of text.")
    seen, cleaned = set(), []
    for o in objects:
        o = o.strip()[:80]
        if o and o.lower() not in seen:
            seen.add(o.lower())
            cleaned.append(o)
    out["objects"] = cleaned[:15]
    if not out["caption"] and not out["description"]:
        raise InvalidAnalysis("The model returned neither a caption nor a description.")
    if not out["caption"]:
        out["caption"] = out["description"].split(". ")[0][: LIMITS["caption"]]
    if not out["summary"]:
        out["summary"] = out["caption"]
    return out
