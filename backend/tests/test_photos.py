"""Photo memory: import, metadata, analysis, OCR, user edits, search, answers, retries and migration.
A deterministic fake model and fake OCR are used, so none of this needs Ollama, Windows OCR or the internet."""

import io
import json
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from memora.config import Settings
from memora.db import Database
from memora.main import create_app
from memora.ollama_client import OllamaClient, OllamaError
from memora.photos import build_chunks, clean_list
from memora.search import supports
from memora.vision import InvalidAnalysis, normalize_analysis
from test_memora import FakeModel, FakeOcr, add_and_wait, phone


class VisionModel(FakeModel):
    """FakeModel plus a vision step whose answer depends on the picture's colour."""

    def __init__(self):
        super().__init__()
        self.vision_calls = 0
        self.vision_error: str | None = None  # set to make the vision step fail

    def vision_status(self):
        if self.vision_error:
            return {"ok": False, "model": "fake-vision", "reason": "missing", "error": self.vision_error}
        return {"ok": True, "model": "fake-vision", "reason": None, "error": None}

    def analyze_image(self, data):
        self._check()
        if self.vision_error:
            raise OllamaError(self.vision_error)
        self.vision_calls += 1
        with Image.open(io.BytesIO(data)) as img:
            r, g, b = img.convert("RGB").getpixel((0, 0))
        if r > 200 and g < 80:
            return {"model": "fake-vision", **normalize_analysis({
                "caption": "A red car parked on a street.", "description": "A red car parked beside a shop at the roadside.",
                "objects": ["red car", "street", "shop"], "visible_text": "", "context": "", "summary": "Red car on a street",
                "uncertainty": "The number plate is not readable."})}
        return {"model": "fake-vision", **normalize_analysis({
            "caption": "A plain white wall.", "description": "An empty white surface with nothing on it.",
            "objects": ["wall"], "visible_text": "", "context": "", "summary": "White wall", "uncertainty": ""})}


def png(color, size=(64, 48), exif=None):
    buf = io.BytesIO()
    kwargs = {"exif": exif} if exif else {}
    Image.new("RGB", size, color).save(buf, format="JPEG", **kwargs)
    return buf.getvalue()


def exif_bytes(make="Acme", model="Cam 9", taken="2024:03:02 23:50:10", offset="+05:30"):
    ex = Image.Exif()
    ex[271], ex[272] = make, model
    ifd = {36867: taken}
    if offset:
        ifd[36880] = offset
    ex[0x8769] = ifd
    return ex


@pytest.fixture()
def env(tmp_path: Path):
    settings = Settings(data_dir=tmp_path / "data", max_upload_mb=1)
    model, ocr = VisionModel(), FakeOcr()
    with TestClient(create_app(settings, model, ocr), base_url="http://127.0.0.1:8000", client=("127.0.0.1", 50000)) as client:
        client.model, client.ocr, client.settings = model, ocr, settings
        yield client


def upload(client, data, name="photo.jpg"):
    res = client.post(f"/api/uploads?filename={name}", content=data)
    assert res.status_code == 201, res.text
    client.app.state.indexer.wait()
    return res.json()


def detail(client, fid):
    return client.get(f"/api/photos/{fid}").json()


# --- import, storage and deduplication ----------------------------------------------------------------------------


def test_imported_photo_keeps_original_bytes_and_gets_a_full_record(env):
    original = png("red")
    up = upload(env, original, "IMG_0001.jpg")
    d = detail(env, up["id"])
    assert d["stage"] == "ready" and d["file_status"] == "indexed"
    assert d["ai"]["status"] == "ok" and d["ai"]["model"] == "fake-vision" and d["ai"]["objects"] == ["red car", "street", "shop"]
    assert d["ai"]["uncertainty"] == "The number plate is not readable."
    assert env.get(d["urls"]["download"]).content == original  # byte-for-byte
    assert env.get(d["urls"]["thumbnail"]).headers["content-type"] == "image/jpeg"
    assert d["sha256"] == up["sha256"] and len(d["sha256"]) == 64
    assert "path" not in json.dumps(d)  # no filesystem paths in the API


def test_identical_import_creates_no_second_record_or_chunks(env):
    up = upload(env, png("red"), "a.jpg")
    chunks = env.app.state.db.stats()["chunks"]
    again = env.post("/api/uploads?filename=b.jpg", content=png("red")).json()
    env.app.state.indexer.wait()
    assert again["duplicate"] is True and again["id"] == up["id"]
    assert env.get("/api/photos").json()["total"] == 1
    assert env.app.state.db.stats()["chunks"] == chunks and env.model.vision_calls == 1


def test_different_images_with_the_same_name_do_not_overwrite_each_other(env):
    a = upload(env, png("red"), "IMG.jpg")
    b = upload(env, png("blue"), "IMG.jpg")
    assert a["id"] != b["id"] and a["path"] != b["path"]
    assert env.get("/api/photos").json()["total"] == 2


def test_bad_uploads_are_rejected_with_clear_messages(env):
    assert env.post("/api/uploads?filename=x.jpg", content=b"not an image").status_code == 415
    heic = env.post("/api/uploads?filename=IMG.HEIC", content=b"\x00\x00\x00\x18ftypheic")
    assert heic.status_code == 415 and "HEIC" in heic.json()["detail"]
    assert env.post("/api/uploads?filename=x.jpg", content=b"").status_code == 400
    big = png("white", size=(1800, 1800), exif=None) + b"\0" * (1024 * 1024)
    assert env.post("/api/uploads?filename=big.jpg", content=big).status_code == 413
    assert env.get("/api/photos").json()["total"] == 0


def test_hostile_file_names_stay_inside_the_uploads_folder(env):
    up = env.post("/api/uploads?filename=../../../evil.jpg", content=png("red")).json()
    env.app.state.indexer.wait()
    stored = Path(up["path"]).resolve()
    assert stored.is_relative_to(env.settings.uploads_dir.resolve()) and stored.name.startswith("evil-")


# --- metadata -----------------------------------------------------------------------------------------------------


def test_exif_fields_are_stored_separately_and_timezone_stays_unknown_when_absent(env):
    full = upload(env, png("red", exif=exif_bytes()), "full.jpg")
    nozone = upload(env, png("blue", exif=exif_bytes(taken="2023:12:25 08:00:00", offset=None)), "nozone.jpg")
    d = detail(env, full["id"])
    assert d["dates"]["captured"] == {"date": "2024-03-02", "time": "23:50:10", "offset": "+05:30", "timezone_known": True}
    assert (d["image"]["camera_make"], d["image"]["camera_model"], d["image"]["format"]) == ("Acme", "Cam 9", "JPEG")
    z = detail(env, nozone["id"])["dates"]["captured"]
    assert z["offset"] is None and z["timezone_known"] is False and z["date"] == "2023-12-25"  # not silently UTC


def test_missing_metadata_is_unknown_not_invented_and_import_time_is_not_capture_time(env):
    d = detail(env, upload(env, png("blue"), "plain.jpg")["id"])
    assert d["dates"]["captured"] is None and d["location"] is None and d["image"]["camera_make"] is None
    assert d["dates"]["imported_at"] and d["dates"]["file_modified"]  # shown, but as their own fields
    listing = env.get("/api/photos").json()["items"][0]
    assert listing["captured_date"] is None and listing["imported_at"]


def test_corrupt_exif_does_not_stop_the_import(env, tmp_path):
    ex = Image.Exif()
    ex[0x8769] = {36867: "garbage"}
    up = upload(env, png("red", exif=ex), "bad-exif.jpg")
    d = detail(env, up["id"])
    assert d["stage"] == "ready" and d["dates"]["captured"] is None


# --- analysis, OCR and failure handling ---------------------------------------------------------------------------


def test_ocr_text_is_kept_apart_from_the_ai_description_and_searchable(env):
    env.ocr.texts["receipt.jpg"] = "TECHMART INVOICE 9931\nTotal 129.00"
    up = upload(env, png("blue"), "receipt.jpg")
    d = detail(env, up["id"])
    assert d["ocr"] == {"status": "done", "text": "TECHMART INVOICE 9931\nTotal 129.00", "error": None}
    assert "TECHMART" not in (d["ai"]["description"] or "")
    hits = env.get("/api/photos", params={"q": "TECHMART"}).json()
    assert [h["file_id"] for h in hits["items"]] == [up["id"]]


def test_ocr_failure_or_absence_never_blocks_the_photo(env):
    env.ocr.fail = True
    a = detail(env, upload(env, png("red"), "a.jpg")["id"])
    assert a["ocr"]["status"] == "failed" and "crashed" in a["ocr"]["error"] and a["stage"] == "ready"
    env.ocr.fail, env.ocr.on = False, False
    b = detail(env, upload(env, png("blue"), "b.jpg")["id"])
    assert b["ocr"]["status"] == "unavailable" and b["stage"] == "ready"
    assert env.get(b["urls"]["content"]).status_code == 200


def test_empty_ocr_result_is_recorded_as_empty(env):
    d = detail(env, upload(env, png("blue"), "c.jpg")["id"])
    assert d["ocr"]["status"] == "empty" and d["ocr"]["text"] is None


def test_missing_vision_model_is_reported_not_faked_and_photo_stays_viewable(env):
    env.model.vision_error = "The vision model 'x' isn't installed. Install it with: ollama pull x"
    up = upload(env, png("red"), "nomodel.jpg")
    d = detail(env, up["id"])
    assert d["ai"]["status"] == "unavailable" and "ollama pull" in d["ai"]["error"]
    assert d["ai"]["caption"] is None and d["ai"]["description"] is None and d["ai"]["objects"] == []
    assert d["file_status"] == "indexed" and d["stage"] == "ready"
    assert env.get(d["urls"]["content"]).status_code == 200
    assert env.get("/api/vision").json()["ok"] is False

    env.model.vision_error = None  # the model got installed: retry fills in the description
    assert env.post(f"/api/photos/{up['id']}/reanalyze").status_code == 202
    env.app.state.indexer.wait()
    assert detail(env, up["id"])["ai"]["status"] == "ok"


def test_vision_failure_is_recorded_and_retry_is_safe(env):
    original = env.model.analyze_image
    env.model.analyze_image = lambda data: (_ for _ in ()).throw(OllamaError("Ollama could not run the model: out of memory"))
    up = upload(env, png("red"), "oom.jpg")
    d = detail(env, up["id"])
    assert d["ai"]["status"] == "unavailable" and "out of memory" in d["ai"]["error"] and d["stage"] == "ready"
    env.model.analyze_image = original
    for _ in range(2):  # repeating a retry never duplicates rows or chunks
        env.post(f"/api/photos/{up['id']}/reanalyze")
        env.app.state.indexer.wait()
    assert env.get("/api/photos").json()["total"] == 1
    assert len(env.app.state.db.chunk_ids_for_file(up["id"])) == 1


def test_unusable_model_json_marks_the_description_failed(env):
    env.model.analyze_image = lambda data: (_ for _ in ()).throw(ValueError("garbled"))
    d = detail(env, upload(env, png("red"), "garbled.jpg")["id"])
    assert d["ai"]["status"] == "failed" and d["ai"]["caption"] is None and d["stage"] == "ready"


def test_interrupted_processing_is_queued_again_after_a_restart(env):
    up = upload(env, png("red"), "stuck.jpg")
    env.app.state.db.update_photo(up["id"], stage="vision")  # as if the app died mid-analysis
    assert env.app.state.indexer.recover_interrupted() == 1
    env.app.state.indexer.wait()
    assert detail(env, up["id"])["stage"] == "ready"


# --- user edits ---------------------------------------------------------------------------------------------------


def test_user_notes_and_tags_survive_reanalysis_and_are_searchable(env):
    up = upload(env, png("red"), "car.jpg")
    res = env.patch(f"/api/photos/{up['id']}", json={
        "user_caption": "Dad's first car", "user_notes": "Bought it from Ramesh in the winter", "user_tags": ["family", "Cars", "cars"],
        "event_title": "Road trip", "event_date": "2019-12-20", "location_label": "Mysuru", "people": ["Dad"], "favorite": True})
    assert res.status_code == 200 and res.json()["user"]["tags"] == ["family", "Cars"]
    env.app.state.indexer.wait()
    for q in ("Ramesh", "family", "Mysuru", "Road trip", "Dad"):
        assert [h["file_id"] for h in env.get("/api/photos", params={"q": q}).json()["items"]] == [up["id"]], q

    env.post(f"/api/photos/{up['id']}/reanalyze")
    env.app.state.indexer.wait()
    u = detail(env, up["id"])["user"]
    assert u["caption"] == "Dad's first car" and u["notes"].startswith("Bought") and u["favorite"] is True
    assert detail(env, up["id"])["caption"] == "Dad's first car"  # the user's caption wins the title, the AI one is kept too
    assert detail(env, up["id"])["ai"]["caption"] == "A red car parked on a street."


def test_clearing_notes_removes_them_from_search_but_keeps_the_photo(env):
    up = upload(env, png("red"), "n.jpg")
    env.patch(f"/api/photos/{up['id']}", json={"user_notes": "secret marmalade recipe"})
    env.app.state.indexer.wait()
    assert env.get("/api/photos", params={"q": "marmalade"}).json()["total"] == 1
    env.patch(f"/api/photos/{up['id']}", json={"user_notes": "", "user_tags": []})
    env.app.state.indexer.wait()
    assert env.get("/api/photos", params={"q": "marmalade"}).json()["total"] == 0
    assert env.get(detail(env, up["id"])["urls"]["content"]).status_code == 200


def test_edit_validation_and_pipeline_cannot_touch_user_columns(env):
    up = upload(env, png("red"), "v.jpg")
    assert env.patch(f"/api/photos/{up['id']}", json={"event_date": "31/10/2026"}).status_code == 400
    assert env.patch(f"/api/photos/{up['id']}", json={"event_date": "2026-02-30"}).status_code == 400
    assert env.patch("/api/photos/99999", json={"favorite": True}).status_code == 404
    with pytest.raises(ValueError):
        env.app.state.db.update_photo(up["id"], user_notes="overwritten by a model")
    assert clean_list("a, B, a ,") == ["a", "B"]


# --- gallery, search, filters -------------------------------------------------------------------------------------


def test_gallery_sorting_filters_and_undated_section(env):
    old = upload(env, png("red", exif=exif_bytes(taken="2020:05:01 10:00:00")), "old.jpg")
    new = upload(env, png("blue", exif=exif_bytes(taken="2025:01:15 10:00:00")), "new.jpg")
    none = upload(env, png("white"), "none.jpg")
    env.patch(f"/api/photos/{old['id']}", json={"favorite": True})
    ids = lambda **p: [i["file_id"] for i in env.get("/api/photos", params=p).json()["items"]]  # noqa: E731
    assert ids() == [new["id"], old["id"], none["id"]]  # newest capture first, undated last
    assert ids(order="asc")[:2] == [old["id"], new["id"]]
    assert ids(sort="name", order="asc") == [new["id"], none["id"], old["id"]]
    assert ids(favorites="true") == [old["id"]]
    assert ids(date_from="2024-01-01") == [new["id"]]
    assert ids(date_from="2020-01-01", date_to="2020-12-31") == [old["id"]]
    assert ids(undated="true") == [none["id"]]
    assert env.get("/api/photos", params={"date_from": "yesterday"}).status_code == 400
    page = env.get("/api/photos", params={"limit": 2, "offset": 2}).json()
    assert page["total"] == 3 and len(page["items"]) == 1


def test_search_finds_photos_by_caption_object_and_filename(env):
    car = upload(env, png("red"), "IMG_4410.jpg")
    upload(env, png("white"), "wall.jpg")
    assert [h["file_id"] for h in env.get("/api/photos", params={"q": "red car"}).json()["items"]] == [car["id"]]
    assert [h["file_id"] for h in env.get("/api/photos", params={"q": "IMG_4410"}).json()["items"]] == [car["id"]]
    hits = env.get("/api/search", params={"q": "red car on a street"}).json()
    assert hits and hits[0]["file_id"] == car["id"]


def test_capture_month_is_searchable_in_words(env):
    up = upload(env, png("red", exif=exif_bytes(taken="2025:10:14 09:00:00")), "oct.jpg")
    assert [h["file_id"] for h in env.get("/api/photos", params={"q": "October 2025"}).json()["items"]] == [up["id"]]


# --- question answering with photo evidence -----------------------------------------------------------------------


def test_answer_cites_the_photo_and_never_presents_import_time_as_capture_time(env):
    env.model.analyze_image = lambda data: normalize_analysis({
        "caption": "Warranty card", "description": "Warranty card for AcmeSound X200 headphones with a 2 years warranty.",
        "objects": ["card"], "visible_text": "", "context": "", "summary": "Warranty card", "uncertainty": ""})
    up = upload(env, png("white"), "card.jpg")
    answer = env.post("/api/ask", json={"question": "Which product has a two-year warranty?"}).json()
    cited = [s for s in answer["sources"] if s["cited"]]
    assert [s["file_id"] for s in cited] == [up["id"]]
    assert cited[0]["photo"]["caption"] == "Warranty card" and cited[0]["photo"]["thumbnail"].endswith("/thumbnail")
    prompt = env.model.chat_calls[-1]
    assert "capture date not recorded" in prompt and "captured " not in prompt.split("Question:")[0].replace("capture date", "")


def test_dated_photo_evidence_uses_the_capture_date(env):
    env.model.analyze_image = lambda data: normalize_analysis({
        "caption": "Warranty card", "description": "AcmeSound X200 with a 2 years warranty.", "objects": [],
        "visible_text": "", "context": "", "summary": "", "uncertainty": ""})
    upload(env, png("white", exif=exif_bytes(taken="2024:03:02 23:50:10")), "dated.jpg")
    env.post("/api/ask", json={"question": "Which product has a two-year warranty?"})
    assert "captured 2024-03-02 23:50:10" in env.model.chat_calls[-1]


def test_a_citation_that_does_not_support_the_answer_is_dropped(env):
    assert supports("The X200 headphones have a 2 year warranty [1]", "AcmeSound X200 headphones warranty 2 years")
    assert not supports("The kettle costs 45 dollars [1]", "A white wall with nothing on it")


def test_insufficient_photo_evidence_is_stated(env):
    upload(env, png("white"), "wall.jpg")
    answer = env.post("/api/ask", json={"question": "What is the passport number?"}).json()
    assert answer["sufficient"] is False and not any(s["cited"] for s in answer["sources"])


# --- access, migration, units -------------------------------------------------------------------------------------


def test_photo_routes_need_the_pairing_code_from_a_phone(env):
    up = upload(env, png("red"), "p.jpg")
    code = env.get("/api/pairing").json()["code"]
    for method, path in (("get", "/api/photos"), ("get", f"/api/photos/{up['id']}"), ("patch", f"/api/photos/{up['id']}"),
                         ("post", f"/api/photos/{up['id']}/reanalyze")):
        assert getattr(phone(env), method)(path).status_code == 401, path
    ok = phone(env, code)
    assert ok.get("/api/photos").status_code == 200
    assert ok.patch(f"/api/photos/{up['id']}", json={"favorite": True}).status_code == 200


def test_unknown_or_non_photo_ids_are_404(env):
    assert env.get("/api/photos/12345").status_code == 404
    assert env.get("/api/photos/12345/related").status_code == 404
    assert env.post("/api/photos/12345/reanalyze").status_code == 404


def test_migration_keeps_existing_data_and_does_not_reanalyse_old_photos(tmp_path):
    data = tmp_path / "data"
    data.mkdir()
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "old.png").write_bytes(png("white"))
    # A database as an older Memora wrote it: no photos table.
    con = sqlite3.connect(data / "memora.sqlite3")
    con.executescript(
        """CREATE TABLE folders (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, added_at REAL NOT NULL);
           CREATE TABLE files (id INTEGER PRIMARY KEY, folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
               path TEXT NOT NULL UNIQUE, name TEXT NOT NULL, ext TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL,
               mtime REAL NOT NULL, sha256 TEXT, status TEXT NOT NULL, error TEXT, summary TEXT, indexed_at REAL);
           CREATE TABLE chunks (id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
               idx INTEGER NOT NULL, text TEXT NOT NULL, embedding BLOB NOT NULL);
           CREATE VIRTUAL TABLE chunks_fts USING fts5(text, name, tokenize='porter unicode61');"""
    )
    stat = (docs / "old.png").stat()
    con.execute("INSERT INTO folders VALUES (1, ?, 0)", (str(docs.resolve()),))
    con.execute("INSERT INTO files VALUES (1, 1, ?, 'old.png', '.png', 'image', ?, ?, 'x', 'indexed', NULL, 'old summary', 1)",
                (str((docs / 'old.png').resolve()), stat.st_size, stat.st_mtime))
    con.execute("INSERT INTO chunks VALUES (1, 1, 0, 'legacy description of a blue sofa', x'00')")
    con.execute("INSERT INTO chunks_fts(rowid, text, name) VALUES (1, 'legacy description of a blue sofa', 'old.png')")
    con.commit()
    con.close()

    settings = Settings(data_dir=data)
    model = VisionModel()
    with TestClient(create_app(settings, model, FakeOcr()), base_url="http://127.0.0.1:8000", client=("127.0.0.1", 1)) as client:
        client.app.state.indexer.wait()
        assert model.vision_calls == 0  # nothing is analysed behind the user's back
        item = client.get("/api/photos").json()["items"][0]
        assert item["vision_status"] == "legacy" and item["stage"] == "ready"
        assert client.get("/api/files/1").json()["summary"] == "old summary"
        assert client.app.state.db.chunk_ids_for_file(1) == [1]  # the old search text is untouched
        # ...and the user can opt in to analyse it.
        assert client.post("/api/photos/reanalyze?only=unanalyzed").json()["queued"] == 1
        client.app.state.indexer.wait()
        assert client.get("/api/photos/1").json()["ai"]["status"] == "ok" and model.vision_calls == 1


def test_build_chunks_labels_user_text_and_does_not_repeat_ocr_text():
    photo = {"caption": "A menu", "description": "A restaurant menu on a table", "objects": json.dumps(["menu"]),
             "ocr_text": "PIZZA 9.00", "visible_text": "pizza 9.00", "user_notes": "great place", "captured_date": "2025-10-14"}
    text = "\n".join(build_chunks("m.jpg", photo, 5000, 100))
    assert text.count("PIZZA") + text.count("pizza") == 1  # model reading equal to the OCR is not indexed twice
    assert "Notes (written by you): great place" in text and "14 October 2025" in text
    assert "date not recorded" in "\n".join(build_chunks("x.jpg", {}, 5000, 100))


def test_long_ocr_text_cannot_swamp_the_index(env):
    env.ocr.texts["wall.jpg"] = "fine print " * 5000
    up = upload(env, png("white"), "wall.jpg")
    total = sum(len(t) for t in env.app.state.db.chunk_data(up["id"])[0])
    assert total < 8000


def test_normalize_analysis_rejects_malformed_answers():
    for bad in (None, [], {"caption": 5}, {"caption": "", "description": ""}, {"caption": "x", "objects": "car"}):
        with pytest.raises(InvalidAnalysis):
            normalize_analysis(bad)
    ok = normalize_analysis({"caption": "A cat", "objects": ["cat", "Cat", " ", "sofa"]})
    assert ok["objects"] == ["cat", "sofa"] and ok["summary"] == "A cat" and ok["description"] == ""


# --- the real Ollama client's vision call (HTTP is faked) ----------------------------------------------------------


def _client(tmp_path, responses):
    c = OllamaClient(Settings(data_dir=tmp_path, vision_model="v:1"))
    calls = []

    def fake(path, payload=None, timeout=600.0):
        calls.append((path, payload))
        r = responses[path]
        if isinstance(r, Exception):
            raise r
        return r(payload) if callable(r) else r

    c._request = fake
    return c, calls


def test_client_refuses_a_text_only_model(tmp_path):
    c, calls = _client(tmp_path, {"/api/show": {"capabilities": ["completion", "tools"]}})
    status = c.vision_status()
    assert status["ok"] is False and status["reason"] == "no_vision" and "MEMORA_VISION_MODEL" in status["error"]
    with pytest.raises(OllamaError):
        c.analyze_image(png("red"))
    assert all(p != "/api/chat" for p, _ in calls)  # the image was never sent to a model that can't see it


def test_client_reports_a_missing_vision_model(tmp_path):
    c, _ = _client(tmp_path, {"/api/show": OllamaError("Ollama /api/show failed (404): model 'v:1' not found")})
    assert c.vision_status()["reason"] == "missing" and "ollama pull v:1" in c.vision_status()["error"]


def test_client_sends_a_shrunk_copy_and_validates_the_reply(tmp_path):
    good = {"message": {"content": json.dumps({"caption": "A cat", "description": "A cat on a sofa", "objects": ["cat"],
                                                "visible_text": "", "context": "", "summary": "cat", "uncertainty": ""})}}
    c, calls = _client(tmp_path, {"/api/show": {"capabilities": ["vision"]}, "/api/chat": good})
    out = c.analyze_image(png("red", size=(4000, 3000)))
    assert out["caption"] == "A cat"
    payload = dict(calls)["/api/chat"]
    assert payload["model"] == "v:1" and payload["format"]["type"] == "object" and len(payload["messages"][1]["images"]) == 1
    import base64
    with Image.open(io.BytesIO(base64.b64decode(payload["messages"][1]["images"][0]))) as sent:
        assert max(sent.size) <= c.settings.vision_max_side


def test_client_retries_once_on_bad_json_then_gives_up(tmp_path):
    bad = {"message": {"content": "not json"}}
    c, calls = _client(tmp_path, {"/api/show": {"capabilities": ["vision"]}, "/api/chat": bad})
    with pytest.raises(OllamaError, match="unusable"):
        c.analyze_image(png("red"))
    assert sum(1 for p, _ in calls if p == "/api/chat") == 2
