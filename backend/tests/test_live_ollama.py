"""LIVE tests: real Ollama, real gemma4 and embeddinggemma. No fakes. Skipped (not passed) when Ollama or a model is missing.
Run:  python -m pytest tests/test_live_ollama.py -v -s     (slow: loads the models; needs several GB of free RAM)"""

import json
import urllib.request

import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageFont

from memora.config import Settings
from memora.main import create_app
from memora.ollama_client import OllamaClient


def _live() -> str | None:
    s = Settings()
    try:
        with urllib.request.urlopen(s.ollama_url.rstrip("/") + "/api/tags", timeout=3) as r:
            names = {m["name"] for m in json.loads(r.read())["models"]}
    except Exception as e:  # noqa: BLE001
        return f"Ollama not reachable: {e}"
    for m in (s.chat_model, s.embed_model):
        if m not in names and f"{m}:latest" not in names:
            return f"model {m} not installed"
    return None


pytestmark = pytest.mark.skipif(_live() is not None, reason=_live() or "")


def _card(path, lines):
    img = Image.new("RGB", (900, 120 + 70 * len(lines)), "white")
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("arial.ttf", 44)
    except OSError:
        font = ImageFont.load_default()
    for i, line in enumerate(lines):
        d.text((40, 40 + 70 * i), line, fill="black", font=font)
    img.save(path)


@pytest.fixture(scope="module")
def live(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("live")
    docs = tmp / "docs"; docs.mkdir()
    (docs / "invoice_zenbrew.txt").write_text(
        "INVOICE 7781\nCustomer: A. Rao\nItem: ZenBrew K500 electric kettle\nPurchased: 2026-05-14 from HomeMart\nTotal paid: INR 3,499.00\n",
        encoding="utf-8")
    (docs / "gym_membership.txt").write_text("Your yoga and fitness club subscription renews every January. Fee: 12000 per year.", encoding="utf-8")
    (docs / "trip_notes.txt").write_text("Packing for the Kerala trip: sunscreen, light cotton clothes, a rain jacket and a power bank.", encoding="utf-8")
    _card(docs / "kettle_warranty.png", ["ZenBrew K500 Kettle", "WARRANTY CARD", "Warranty period: 2 years", "Serial: ZB-90417"])
    settings = Settings(data_dir=tmp / "data", ocr="off")
    client = OllamaClient(settings)
    with TestClient(create_app(settings, client), base_url="http://127.0.0.1:8000", client=("127.0.0.1", 50000)) as c:
        folder = c.post("/api/folders", json={"path": str(docs)}).json()
        c.app.state.indexer.wait(timeout=900)
        c.client_model = client
        yield c


def test_models_really_run(live):
    probe = live.get("/api/health", params={"probe": 1}).json()["probe"]
    assert probe["chat"]["ok"], probe
    assert probe["chat"]["answered_by"].startswith("gemma4"), probe   # the configured Gemma, not a substitute
    assert probe["embed"]["ok"] and probe["embed"]["answered_by"].startswith("embeddinggemma"), probe
    print("PROBE", probe)


def test_A_invoice_question_answered_by_gemma_with_correct_source(live):
    r = live.post("/api/ask", json={"question": "How much did I pay for the kettle and where did I buy it?"}).json()
    print("ANSWER A:", r["answer"])
    cited = {s["name"] for s in r["sources"] if s["cited"]}
    assert "invoice_zenbrew.txt" in cited and r["sufficient"]
    assert "3,499" in r["answer"] or "3499" in r["answer"]


def test_B_semantic_retrieval_without_shared_wording(live):
    hits = live.get("/api/search", params={"q": "what should I bring on my holiday in the south"}).json()
    print("B hits:", [(h["name"], round(h["score"], 4)) for h in hits[:4]])
    assert hits[0]["name"] == "trip_notes.txt"


def test_C_image_is_read_by_vision_model_and_indexed(live):
    files = {f["name"]: f for f in live.get("/api/files").json()}
    fid = files["kettle_warranty.png"]["id"]
    p = live.get(f"/api/photos/{fid}").json()
    print("C vision:", {k: p["ai"].get(k) for k in ("status", "model", "caption", "visible_text")})
    assert p["ai"]["status"] == "ok" and p["ai"]["model"].startswith("gemma4")
    assert "ZenBrew" in (p["ai"]["caption"] or "") + (p["ai"]["visible_text"] or "") + (p["ai"]["summary"] or "")
    hits = live.get("/api/search", params={"q": "ZB-90417 warranty"}).json()
    assert any(h["name"] == "kettle_warranty.png" for h in hits)


def test_D_cross_source_invoice_and_photo(live):
    r = live.post("/api/ask", json={"question": "When did I buy the ZenBrew kettle and how long is its warranty?"}).json()
    print("ANSWER D:", r["answer"])
    cited = {s["name"] for s in r["sources"] if s["cited"]}
    assert {"invoice_zenbrew.txt", "kettle_warranty.png"} <= cited
    assert r["sufficient"]


def test_K_absent_information_is_not_invented(live):
    r = live.post("/api/ask", json={"question": "What is my passport number?"}).json()
    print("ANSWER K:", r["answer"], r["sufficient"])
    assert r["sufficient"] is False
    assert not any(s["cited"] for s in r["sources"])
