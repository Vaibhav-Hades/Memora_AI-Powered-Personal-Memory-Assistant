"""Embedding-model tracking, evidence validation, cross-source answers and privacy of phone responses (fake model)."""

import sqlite3

from memora.config import Settings
from memora.main import create_app
from memora.search import unsupported_figures

from test_memora import FakeModel, add_and_wait, env, phone  # noqa: F401  (env is a fixture)


def test_vectors_carry_their_embedding_model(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    status = client.get("/api/health").json()["embeddings"]
    assert status["model"] == "embeddinggemma" and status["stale"] == 0 and status["current"] > 0


def test_changed_embedding_model_is_never_compared_and_can_be_reindexed(env, tmp_path):
    client, docs, model = env
    add_and_wait(client, docs)
    db = client.app.state.db
    db.embed_model = "some-other-embedder"          # as if MEMORA_EMBED_MODEL had been changed
    health = client.get("/api/health").json()["embeddings"]
    assert health["stale"] > 0 and health["reindex_required"] is True
    assert client.app.state.vectors.count() == 0    # old vectors are not silently compared
    assert client.get("/api/search", params={"q": "AcmeSound"}).json() != []  # keyword search still works

    assert client.post("/api/embeddings/reindex").json()["started"] is True
    for _ in range(100):
        if client.get("/api/health").json()["embeddings"]["stale"] == 0:
            break
        import time; time.sleep(0.05)
    assert client.get("/api/health").json()["embeddings"]["stale"] == 0
    assert client.app.state.vectors.count() > 0


def test_phone_cannot_trigger_reindex(env):
    client, docs, _ = env
    code = client.app.state.pairing_code
    assert phone(client, code).post("/api/embeddings/reindex").status_code == 403


def test_legacy_database_is_migrated_without_losing_chunks(tmp_path):
    data = tmp_path / "data"; data.mkdir()
    conn = sqlite3.connect(data / "memora.sqlite3")
    conn.executescript("""
        CREATE TABLE folders (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, added_at REAL NOT NULL);
        CREATE TABLE files (id INTEGER PRIMARY KEY, folder_id INTEGER NOT NULL, path TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
            ext TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL, mtime REAL NOT NULL, sha256 TEXT, status TEXT NOT NULL,
            error TEXT, summary TEXT, indexed_at REAL);
        CREATE TABLE chunks (id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, idx INTEGER NOT NULL, text TEXT NOT NULL, embedding BLOB NOT NULL);
        INSERT INTO chunks(file_id, idx, text, embedding) VALUES (1, 0, 'old text', x'00000000');
    """)
    conn.commit(); conn.close()
    app = create_app(Settings(data_dir=data), FakeModel())
    st = app.state.db.embedding_status()
    assert st["current"] == 1 and st["stale"] == 0  # legacy rows adopted once, not dropped


def test_unsupported_figures_flags_changed_numbers_only():
    src = ["Warranty: two years from purchase. Total: $1,299.00 on 2026-03-02"]
    assert unsupported_figures("The warranty is 2 years [1].", src) == []
    assert unsupported_figures("You paid 1299 on 2026-03-02 [1].", src) == []
    assert unsupported_figures("The warranty is 5 years [1].", src) == ["5"]
    assert unsupported_figures("It was bought in a shop [1].", src) == []


def test_answer_with_number_missing_from_evidence_is_not_sufficient(env):
    client, docs, model = env
    add_and_wait(client, docs)
    real = model.chat_json

    def lying(system, user, schema):
        out = real(system, user, schema)
        out["answer"] = out["answer"].replace("2-year", "7-year")
        return out

    model.chat_json = lying
    r = client.post("/api/ask", json={"question": "What is the warranty on the headphones?"}).json()
    assert r["sufficient"] is False and "7" in r["unsupported_figures"]


def test_cross_source_question_retrieves_and_cites_both_files(env):
    client, docs, model = env
    add_and_wait(client, docs)
    r = client.post("/api/ask", json={"question": "AcmeSound invoice total and warranty"}).json()
    names = {s["name"] for s in r["sources"]}
    assert {"invoice_acme.txt", "warranty.png"} <= names
    assert all(s["file_id"] for s in r["sources"])


def test_phone_responses_do_not_expose_laptop_paths(env):
    client, docs, _ = env
    add_and_wait(client, docs)
    p = phone(client, client.app.state.pairing_code)
    ask = p.post("/api/ask", json={"question": "AcmeSound warranty"}).json()
    hits = p.get("/api/search", params={"q": "AcmeSound"}).json()
    for item in ask["sources"] + hits:
        assert str(docs) not in item["path"] and item["path"] == item["name"]
    assert str(docs) not in p.get("/api/sync/manifest").text


def test_no_evidence_never_invents_citations(env):
    client, docs, model = env
    r = client.post("/api/ask", json={"question": "What is my passport number?"}).json()
    assert r["sufficient"] is False and r["sources"] == [] and not model.chat_calls


def test_missing_pdf_docx_libraries_give_an_actionable_error(tmp_path, monkeypatch):
    import builtins
    from memora import extract

    real = builtins.__import__

    def blocked(name, *a, **k):
        if name in ("docx", "pypdf"):
            raise ImportError(name)
        return real(name, *a, **k)

    monkeypatch.setattr(builtins, "__import__", blocked)
    import pytest as _pytest
    with _pytest.raises(extract.MissingDependency, match="pip install -r requirements.txt"):
        extract._docx_text(tmp_path / "x.docx")
    with _pytest.raises(extract.MissingDependency, match="pypdf"):
        extract._pdf_text(tmp_path / "x.pdf", None, 0)


def test_health_lists_missing_packages(env):
    client, _, _ = env
    assert isinstance(client.get("/api/health").json()["missing_packages"], list)
