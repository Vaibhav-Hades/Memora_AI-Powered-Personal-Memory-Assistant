"""End-to-end tests of indexing, retrieval and answering with a deterministic fake model (no Ollama needed)."""

import hashlib
import io
import math
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from memora.config import Settings
from memora.extract import chunk_text
from memora.main import create_app
from memora.ollama_client import OllamaError


class FakeModel:
    """Hashing bag-of-words embeddings, a canned image description, and an extractive 'LLM'."""

    def __init__(self):
        self.chat_calls = []
        self.image_calls = 0
        self.down = False  # simulate Ollama being stopped

    def _check(self):
        if self.down:
            raise OllamaError("Cannot reach Ollama at http://127.0.0.1:11434: connection refused")

    def health(self):
        return {"ok": not self.down, "error": "Cannot reach Ollama" if self.down else None}

    def embed(self, texts, kind="document"):
        self._check()
        out = []
        for t in texts:
            v = [0.0] * 64
            for w in re.findall(r"[a-z0-9]+", t.lower()):
                v[int(hashlib.md5(w.encode()).hexdigest(), 16) % 64] += 1.0
            n = math.sqrt(sum(x * x for x in v)) or 1.0
            out.append([x / n for x in v])
        return out

    def describe_image(self, data):
        self._check()
        self.image_calls += 1
        return "Photo of a warranty card. Text: AcmeSound X200 headphones. Warranty: 2 years from purchase."

    def chat_json(self, system, user, schema):
        self._check()
        self.chat_calls.append(user)
        question = user.rsplit("Question:", 1)[1].lower()
        sources = re.findall(r"^\[(\d+)\] File: (.+?) \(.*?\n.*?\n(.*?)(?=\n\n---|\n\n===)", user, re.S | re.M)
        for num, name, text in sources:
            if "warranty" in question and "2 years" in text:
                return {"answer": f"The AcmeSound X200 has a 2-year warranty [{num}].",
                        "sufficient": True, "citations": [int(num)]}
        return {"answer": "None of the sources [1] mention this.", "sufficient": False, "citations": [1]}


class FakeOcr:
    """Stands in for Windows OCR: returns canned text by file name, or an error / "unavailable" on demand."""

    name = "fake"

    def __init__(self):
        self.texts: dict[str, str] = {}
        self.calls = 0
        self.fail = False
        self.on = True

    def available(self):
        return self.on

    def read(self, path):
        self.calls += 1
        if self.fail:
            raise RuntimeError("OCR engine crashed")
        name = Path(path).name  # uploads are stored as <name>-<hash>.<ext>, so match on the original name's start
        return next((text for key, text in self.texts.items() if name == key or name.startswith(Path(key).stem + "-")), "")


@pytest.fixture()
def env(tmp_path: Path):
    docs = tmp_path / "files"
    (docs / "receipts").mkdir(parents=True)
    (docs / ".hidden").mkdir()
    (docs / "receipts" / "invoice_acme.txt").write_text(
        "INVOICE #4411\nAcmeSound X200 headphones\nTotal: $129.00\nDate: 2026-03-02", encoding="utf-8"
    )
    (docs / "notes.md").write_text("Grocery list: eggs, milk, bread.", encoding="utf-8")
    (docs / ".hidden" / "secret.txt").write_text("should never be indexed", encoding="utf-8")
    (docs / "program.exe").write_bytes(b"MZ")
    Image.new("RGB", (40, 30), "white").save(docs / "warranty.png")

    settings = Settings(data_dir=tmp_path / "data", max_upload_mb=1)
    model = FakeModel()
    ocr = FakeOcr()
    # Requests from 127.0.0.1 count as the laptop itself.
    with TestClient(create_app(settings, model, ocr), base_url="http://127.0.0.1:8000", client=("127.0.0.1", 50000)) as client:
        client.ocr = ocr
        yield client, docs, model


def phone(client, code=None):
    """A client that looks like the Android app on the same Wi-Fi."""
    headers = {"X-Memora-Code": code} if code else {}
    return TestClient(client.app, base_url="http://192.168.1.20:8000", client=("192.168.1.50", 40000), headers=headers)


def jpeg_bytes(color="white", size=(64, 48)):
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, format="JPEG")
    return buf.getvalue()


def add_and_wait(client, path):
    resp = client.post("/api/folders", json={"path": str(path)})
    assert resp.status_code == 201, resp.text
    client.app.state.indexer.wait()
    return resp.json()


def test_indexes_only_supported_visible_files(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    names = sorted(f["name"] for f in client.get("/api/files").json())
    assert names == ["invoice_acme.txt", "notes.md", "warranty.png"]
    assert all(f["status"] == "indexed" for f in client.get("/api/files").json())
    stats = client.get("/api/health").json()["stats"]
    assert stats["images"] == 1 and stats["documents"] == 2


def test_answer_cites_evidence_and_serves_original(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    res = client.post("/api/ask", json={"question": "Which product has a two-year warranty?"}).json()
    assert res["sufficient"] is True
    assert "[" in res["answer"]
    cited = [s for s in res["sources"] if s["cited"]]
    assert [s["name"] for s in cited] == ["warranty.png"]
    assert res["sources"][0]["cited"]  # cited evidence is listed first
    # The invoice is found by keyword + vector search even though the model only cited the card.
    assert "invoice_acme.txt" in {s["name"] for s in res["sources"]}

    content = client.get(f"/api/files/{cited[0]['file_id']}/content")
    assert content.status_code == 200
    assert content.content == (docs / "warranty.png").read_bytes()


def test_insufficient_evidence_is_reported(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    res = client.post("/api/ask", json={"question": "What is my passport number?"}).json()
    assert res["sufficient"] is False
    assert not any(s["cited"] for s in res["sources"])


def test_empty_index_does_not_call_model(env):
    client, _, model = env
    res = client.post("/api/ask", json={"question": "anything?"}).json()
    assert res["sufficient"] is False and res["sources"] == []
    assert model.chat_calls == []


def test_incremental_reindex_detects_changes_and_deletions(env):
    client, docs, _ = env
    folder = add_and_wait(client, docs)
    indexer = client.app.state.indexer
    assert indexer.index_folder(folder["id"]) == {"indexed": 0, "reused": 0, "unchanged": 3, "errors": 0, "removed": 0}

    (docs / "notes.md").unlink()
    (docs / "receipts" / "invoice_acme.txt").write_text("INVOICE #4411 updated, Total: $99", encoding="utf-8")
    assert indexer.index_folder(folder["id"]) == {"indexed": 1, "reused": 0, "unchanged": 1, "errors": 0, "removed": 1}
    hits = client.get("/api/search", params={"q": "grocery eggs milk"}).json()
    assert "notes.md" not in {h["name"] for h in hits}


def test_removing_folder_clears_index_but_keeps_files(env):
    client, docs, _ = env
    folder = add_and_wait(client, docs)
    file_id = client.get("/api/files").json()[0]["id"]
    assert client.delete(f"/api/folders/{folder['id']}").status_code == 200
    assert client.get("/api/files").json() == []
    assert client.app.state.vectors.count() == 0
    assert client.get(f"/api/files/{file_id}/content").status_code == 404
    assert (docs / "notes.md").exists()


def test_folder_validation(env, tmp_path):
    client, docs, _ = env
    assert client.post("/api/folders", json={"path": str(tmp_path / "missing")}).status_code == 400
    assert client.post("/api/folders", json={"path": Path(docs).anchor}).status_code == 400
    add_and_wait(client, docs)
    assert client.post("/api/folders", json={"path": str(docs)}).status_code == 409
    assert client.post("/api/folders", json={"path": str(docs / "receipts")}).status_code == 409


def test_unknown_file_ids_cannot_be_read(env):
    client, _, _ = env
    assert client.get("/api/files/999/content").status_code == 404


def test_rejects_foreign_host_header(env):
    client, _, _ = env
    assert client.get("/api/health", headers={"host": "evil.example"}).status_code == 400


def test_chunking_overlaps_and_covers_text():
    text = " ".join(f"word{i}." for i in range(800))
    chunks = chunk_text(text, size=500, overlap=100)
    assert len(chunks) > 1
    assert all(len(c) <= 500 for c in chunks)
    assert "word0." in chunks[0] and "word799." in chunks[-1]


def test_serves_plain_frontend(env):
    client, _, _ = env
    page = client.get("/")
    assert page.status_code == 200 and "app.js" in page.text
    assert page.headers["cache-control"] == "no-cache"
    assert client.get("/app.js").status_code == 200
    assert client.get("/styles.css").status_code == 200


# Phone access over the LAN


def test_phone_needs_pairing_code(env):
    client, _, _ = env
    code = client.get("/api/pairing").json()["code"]
    assert phone(client).get("/api/ping").status_code == 401
    assert phone(client, "WRONG-CODE0").get("/api/ping").status_code == 401
    ok = phone(client, code.lower().replace("-", "")).get("/api/ping")  # case and dash don't matter
    assert ok.status_code == 200 and ok.json()["app"] == "memora"


def test_phone_gets_api_only_and_limited_admin(env, tmp_path):
    client, docs, _ = env
    code = client.get("/api/pairing").json()["code"]
    p = phone(client, code)
    assert p.get("/").status_code == 403  # web UI stays on the laptop
    assert p.get("/api/pairing").status_code == 403
    assert p.post("/api/folders", json={"path": str(docs)}).status_code == 403
    folder = add_and_wait(client, docs)
    assert p.delete(f"/api/folders/{folder['id']}").status_code == 403
    assert p.post("/api/ask", json={"question": "Which product has a two-year warranty?"}).json()["sufficient"]


def test_phone_can_add_suggested_folder(env, monkeypatch):
    client, docs, _ = env
    import memora.main

    monkeypatch.setattr(memora.main, "suggested_folders", lambda: [{"name": "Pictures", "path": str(docs.resolve())}])
    code = client.get("/api/pairing").json()["code"]
    p = phone(client, code)
    assert p.get("/api/folders/suggested").json() == [{"name": "Pictures", "path": str(docs.resolve()), "approved": False}]
    assert p.post("/api/folders", json={"path": str(docs.resolve())}).status_code == 201


def test_upload_is_validated_indexed_and_searchable(env):
    client, _, _ = env
    code = client.get("/api/pairing").json()["code"]
    p = phone(client, code)

    res = p.post("/api/uploads?filename=IMG_2041.heic", content=jpeg_bytes())
    assert res.status_code == 201, res.text
    up = res.json()
    assert up["status"] == "pending" and up["name"].startswith("IMG_2041-") and up["name"].endswith(".jpg")
    client.app.state.indexer.wait()
    assert p.get(f"/api/files/{up['id']}").json()["status"] == "indexed"

    answer = p.post("/api/ask", json={"question": "Which product has a two-year warranty?"}).json()
    assert [s["file_id"] for s in answer["sources"] if s["cited"]] == [up["id"]]

    thumb = p.get(f"/api/files/{up['id']}/thumbnail?size=100")
    assert thumb.status_code == 200 and thumb.headers["content-type"] == "image/jpeg"

    again = p.post("/api/uploads?filename=copy.jpg", content=jpeg_bytes())
    assert again.json()["duplicate"] is True and again.json()["id"] == up["id"]

    folders = client.get("/api/folders").json()
    assert [f["is_uploads"] for f in folders if f["id"] == up["folder_id"]] == [True]
    assert client.delete(f"/api/folders/{up['folder_id']}").status_code == 409


def test_upload_rejects_bad_files(env, tmp_path):
    client, _, _ = env
    assert client.post("/api/uploads", content=b"").status_code == 400
    assert client.post("/api/uploads", content=b"not an image at all").status_code == 415
    gif = io.BytesIO()
    Image.new("RGB", (10, 10)).save(gif, format="GIF")
    assert client.post("/api/uploads?filename=a.gif", content=gif.getvalue()).status_code == 415
    big = io.BytesIO()
    Image.effect_noise((1200, 1200), 100).convert("RGB").save(big, format="PNG")  # > 1 MB test limit
    assert len(big.getvalue()) > 1024 * 1024
    assert client.post("/api/uploads?filename=big.png", content=big.getvalue()).status_code == 413
    assert list((tmp_path / "data" / "phone-uploads").rglob("*.*")) == []


def test_thumbnails_only_for_images_and_pdfs(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    files = {f["name"]: f["id"] for f in client.get("/api/files").json()}
    assert client.get(f"/api/files/{files['warranty.png']}/thumbnail").headers["content-type"] == "image/jpeg"
    assert client.get(f"/api/files/{files['notes.md']}/thumbnail").status_code == 404


def test_devices_outside_local_network_are_refused(env):
    client, _, _ = env
    code = client.get("/api/pairing").json()["code"]
    for addr in ("100.101.102.103", "8.8.8.8"):  # Tailscale/CGNAT and a public address
        outsider = TestClient(client.app, base_url="http://memora:8000", client=(addr, 40000), headers={"X-Memora-Code": code})
        assert outsider.get("/api/ping").status_code == 403


def _peer(client, addr, code=None):
    headers = {"X-Memora-Code": code} if code else {}
    return TestClient(client.app, base_url="http://memora:8000", client=(addr, 40000), headers=headers)


def test_tailscale_peer_accepted_only_when_enabled_and_still_needs_code(env):
    client, _, _ = env
    code = client.get("/api/pairing").json()["code"]
    client.app.state.settings.allow_tailscale = True
    assert _peer(client, "100.101.102.103", code).get("/api/ping").status_code == 200
    assert _peer(client, "fd7a:115c:a1e0::1234", code).get("/api/ping").status_code == 200
    assert _peer(client, "100.101.102.103").get("/api/ping").status_code == 401  # code still required
    assert _peer(client, "100.101.102.103", "WRONG-CODE0").get("/api/ping").status_code == 401
    assert _peer(client, "100.101.102.103", code).get("/").status_code == 403  # web UI stays on the laptop
    assert _peer(client, "100.101.102.103", code).get("/api/pairing").status_code == 403
    for addr in ("8.8.8.8", "100.63.0.1", "100.128.0.1"):  # public, and just outside 100.64.0.0/10
        assert _peer(client, addr, code).get("/api/ping").status_code == 403


def test_tailscale_refused_by_default(env):
    client, _, _ = env
    code = client.get("/api/pairing").json()["code"]
    assert _peer(client, "100.101.102.103", code).get("/api/ping").status_code == 403


# Duplicates and AI outages


def test_identical_files_reuse_ai_results(env):
    client, docs, model = env
    (docs / "copies").mkdir()
    (docs / "copies" / "warranty_copy.png").write_bytes((docs / "warranty.png").read_bytes())
    folder = add_and_wait(client, docs)
    assert model.image_calls == 1  # the copy was not sent to the AI again
    files = {f["name"]: f for f in client.get("/api/files").json()}
    assert files["warranty_copy.png"]["status"] == "indexed"
    assert files["warranty_copy.png"]["summary"] == files["warranty.png"]["summary"]

    # A moved/renamed photo is not re-read either, and its old entry disappears.
    (docs / "copies" / "warranty_copy.png").rename(docs / "copies" / "renamed.png")
    result = client.app.state.indexer.index_folder(folder["id"])
    assert result["reused"] == 1 and result["removed"] == 1 and model.image_calls == 1
    hits = client.get("/api/search", params={"q": "AcmeSound warranty"}).json()
    assert {"warranty.png", "renamed.png"} <= {h["name"] for h in hits}


def test_ai_outage_is_reported_and_indexing_recovers(env):
    client, docs, model = env
    model.down = True
    folder = add_and_wait(client, docs)
    health = client.get("/api/health").json()
    assert health["model"]["ok"] is False
    files = client.get("/api/files").json()
    assert files and all(f["status"] == "error" and "Ollama" in f["error"] for f in files)

    for res in (client.post("/api/ask", json={"question": "warranty?"}), client.get("/api/search", params={"q": "warranty"})):
        assert res.status_code == 503 and "Ollama" in res.json()["detail"]

    model.down = False  # Ollama is back: a rescan retries the failed files
    result = client.app.state.indexer.index_folder(folder["id"])
    assert result["indexed"] == 3 and result["errors"] == 0
    assert client.post("/api/ask", json={"question": "Which product has a two-year warranty?"}).json()["sufficient"]


# --- configuration -------------------------------------------------------------------------------------------------


def test_default_models_and_endpoint(monkeypatch):
    for name in ("MEMORA_CHAT_MODEL", "MEMORA_EMBED_MODEL", "MEMORA_OLLAMA_URL", "OLLAMA_BASE_URL"):
        monkeypatch.delenv(name, raising=False)
    s = Settings()
    assert s.chat_model == "gemma4:e4b"
    assert s.embed_model == "embeddinggemma"
    assert s.ollama_url == "http://127.0.0.1:11434"


def test_model_comes_from_environment(monkeypatch):
    monkeypatch.setenv("MEMORA_CHAT_MODEL", "gemma4:12b")
    assert Settings().chat_model == "gemma4:12b"


def test_endpoint_precedence(monkeypatch):
    monkeypatch.delenv("MEMORA_OLLAMA_URL", raising=False)
    monkeypatch.setenv("OLLAMA_BASE_URL", "http://ollama.local:1234")
    assert Settings().ollama_url == "http://ollama.local:1234"  # OLLAMA_BASE_URL is the fallback
    monkeypatch.setenv("MEMORA_OLLAMA_URL", "http://127.0.0.1:9999")
    assert Settings().ollama_url == "http://127.0.0.1:9999"  # an explicit MEMORA_OLLAMA_URL wins


# --- Ollama client (HTTP layer faked) --------------------------------------------------------------------------------


def test_client_uses_configured_model_for_chat_and_vision(monkeypatch):
    from memora.ollama_client import OllamaClient

    settings = Settings(chat_model="gemma4:e4b", embed_model="embeddinggemma", keep_alive="10m")
    client = OllamaClient(settings)
    sent = []

    def fake_request(path, payload=None, timeout=600.0):
        sent.append((path, payload))
        if path == "/api/embed":
            return {"embeddings": [[1.0, 0.0] for _ in payload["input"]]}
        return {"message": {"content": '{"answer": "ok", "sufficient": true, "citations": [1]}'}}

    monkeypatch.setattr(client, "_request", fake_request)
    client.chat_json("system", "Sources: [1] kettle", {"type": "object"})
    client.describe_image(jpeg_bytes())
    client.embed(["a question"], kind="query")

    chat = [p for path, p in sent if path == "/api/chat"]
    assert [p["model"] for p in chat] == ["gemma4:e4b", "gemma4:e4b"]
    assert chat[0]["messages"][1]["content"] == "Sources: [1] kettle"  # the evidence text itself reaches the model
    assert all(p["keep_alive"] == "10m" for _, p in sent)
    embed = next(p for path, p in sent if path == "/api/embed")
    assert embed["model"] == "embeddinggemma"
    assert embed["input"][0].endswith("a question")


def test_client_reports_unreachable_ollama():
    from memora.ollama_client import OllamaClient

    client = OllamaClient(Settings(ollama_url="http://127.0.0.1:9"))  # nothing listens on the discard port
    with pytest.raises(OllamaError, match="Cannot reach Ollama"):
        client.embed(["x"])
    assert client.health()["ok"] is False


# --- answering ---------------------------------------------------------------------------------------------------------


def test_invented_citation_numbers_are_not_shown_as_evidence(env):
    client, docs, model = env
    add_and_wait(client, docs)
    model.chat_json = lambda system, user, schema: {"answer": "It costs $129 [9].", "sufficient": True, "citations": [9, 0, "x"]}
    body = client.post("/api/ask", json={"question": "How much did the AcmeSound headphones cost?"}).json()
    assert not any(s["cited"] for s in body["sources"])
    assert body["sufficient"] is False  # no valid citation, so it is not presented as a supported answer


def test_answer_carries_real_source_metadata_and_evidence_text(env):
    client, docs, model = env
    add_and_wait(client, docs)
    body = client.post("/api/ask", json={"question": "What is the total on the AcmeSound invoice?"}).json()
    sent_to_model = model.chat_calls[-1]
    assert "Total: $129.00" in sent_to_model  # the retrieved chunk text, not just the file name
    for s in body["sources"]:
        assert Path(s["path"]).exists() and s["name"] == Path(s["path"]).name
        assert s["snippet"] and s["file_id"]


# --- download and sync ---------------------------------------------------------------------------------------------------


def _file_id(client, name):
    return next(f["id"] for f in client.get("/api/files").json() if f["name"] == name)


def test_download_returns_original_bytes_with_hash(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    fid = _file_id(client, "invoice_acme.txt")
    res = client.get(f"/api/files/{fid}/download")
    original = (docs / "receipts" / "invoice_acme.txt").read_bytes()
    assert res.status_code == 200 and res.content == original
    assert res.headers["x-memora-sha256"] == hashlib.sha256(original).hexdigest()
    assert 'attachment; filename="invoice_acme.txt"' in res.headers["content-disposition"]
    # Range requests (resuming an interrupted download) return the right slice.
    part = client.get(f"/api/files/{fid}/download", headers={"Range": "bytes=5-12"})
    assert part.status_code == 206 and part.content == original[5:13]


def test_download_rejects_unknown_and_missing_files(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    assert client.get("/api/files/99999/download").status_code == 404
    assert client.get("/api/files/abc/download").status_code == 422
    fid = _file_id(client, "notes.md")
    (docs / "notes.md").unlink()
    assert client.get(f"/api/files/{fid}/download").status_code == 410  # moved or deleted on the laptop


def test_manifest_lists_size_hash_and_hides_paths(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    manifest = client.get("/api/sync/manifest").json()["files"]
    entry = next(f for f in manifest if f["name"] == "invoice_acme.txt")
    original = (docs / "receipts" / "invoice_acme.txt").read_bytes()
    assert entry["size"] == len(original) and entry["sha256"] == hashlib.sha256(original).hexdigest()
    assert entry["available"] and entry["mime"] == "text/plain"
    assert not any("path" in f for f in manifest) and str(docs) not in str(manifest)
    assert client.get("/api/sync/manifest?folder_id=999").status_code == 404
    (docs / "notes.md").write_text("changed", encoding="utf-8")
    again = next(f for f in client.get("/api/sync/manifest").json()["files"] if f["name"] == "notes.md")
    assert again["sha256"] == hashlib.sha256(b"changed").hexdigest()  # changes are detectable


def test_download_and_manifest_need_pairing_code_from_phone(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    fid = _file_id(client, "invoice_acme.txt")
    code = client.app.state.pairing_code
    anon = phone(client)
    assert anon.get(f"/api/files/{fid}/download").status_code == 401
    assert anon.get("/api/sync/manifest").status_code == 401
    paired = phone(client, code)
    assert paired.get(f"/api/files/{fid}/download").status_code == 200
    assert paired.get("/api/sync/manifest").status_code == 200


# Photo metadata and Tailscale address discovery


def _jpeg_with_exif(path, taken="2024:03:02 23:50:10", offset="+05:30", gps=True):
    img = Image.new("RGB", (64, 48), "white")
    exif = Image.Exif()
    ifd = {36867: taken}
    if offset:
        ifd[36880] = offset
    exif[0x8769] = ifd
    if gps:
        exif[0x8825] = {1: "N", 2: (12.0, 58.0, 18.0), 3: "E", 4: (77.0, 35.0, 40.0)}
    img.save(path, format="JPEG", exif=exif)


def test_photo_meta_reports_capture_time_as_written_and_gps(env):
    client, docs, _ = env
    _jpeg_with_exif(docs / "trip.jpg")
    add_and_wait(client, docs)
    fid = next(f["id"] for f in client.get("/api/files").json() if f["name"] == "trip.jpg")
    meta = client.get(f"/api/files/{fid}/meta").json()
    assert meta["captured"] == {"date": "2024-03-02", "time": "23:50:10", "offset": "+05:30"}  # no zone conversion
    assert meta["location"] == {"lat": 12.97167, "lon": 77.59444}
    assert (meta["width"], meta["height"]) == (64, 48)
    assert meta["modified"] > 0 and meta["indexed_at"] > 0  # filesystem / index dates are separate fields


def test_photo_meta_never_invents_missing_values(env):
    client, docs, _ = env
    (docs / "plain.jpg").write_bytes(jpeg_bytes())
    _jpeg_with_exif(docs / "nozone.jpg", offset=None, gps=False)
    add_and_wait(client, docs)
    by_name = {f["name"]: f["id"] for f in client.get("/api/files").json()}
    plain = client.get(f"/api/files/{by_name['plain.jpg']}/meta").json()
    assert plain["captured"] is None and plain["location"] is None
    nozone = client.get(f"/api/files/{by_name['nozone.jpg']}/meta").json()
    assert nozone["captured"]["offset"] is None and nozone["location"] is None
    text = client.get(f"/api/files/{by_name['notes.md']}/meta").json()
    assert text["captured"] is None and text["width"] is None
    assert client.get("/api/files/99999/meta").status_code == 404


def test_exif_datetime_parser_rejects_garbage():
    from memora.photometa import parse_exif_datetime

    for bad in (None, "", "0000:00:00 00:00:00", "2024:13:40 99:99:99", "2024-03-02 10:00:00", "x"):
        assert parse_exif_datetime(bad) is None
    assert parse_exif_datetime("2024:03:02 10:00:00", "garbage")["offset"] is None


def test_pairing_lists_tailscale_addresses_only_when_enabled(env, monkeypatch):
    import memora.main

    client, _, _ = env
    monkeypatch.setattr(memora.main, "tailscale_addresses", lambda: ["100.101.102.103"])
    off = client.get("/api/pairing").json()
    assert off["tailscale"] is False and off["tailscale_addresses"] == []
    client.app.state.settings.allow_tailscale = True
    on = client.get("/api/pairing").json()
    assert on["tailscale"] is True and on["tailscale_addresses"] == ["100.101.102.103"]
    assert client.get("/api/ping").json()["tailscale"] is True


def test_access_ranges():
    from memora.access import is_local_network, is_tailscale

    assert is_tailscale("100.64.0.1") and is_tailscale("100.127.255.254") and is_tailscale("fd7a:115c:a1e0::1")
    assert not is_tailscale("100.63.255.255") and not is_tailscale("100.128.0.0") and not is_tailscale("192.168.1.1")
    assert is_local_network("192.168.1.5") and is_local_network("10.0.0.2") and not is_local_network("8.8.8.8")
    assert not is_local_network("100.64.0.1") and is_local_network("100.64.0.1", allow_tailscale=True)
    assert not is_local_network("100.128.0.1", allow_tailscale=True) and not is_local_network("not-an-ip", allow_tailscale=True)
