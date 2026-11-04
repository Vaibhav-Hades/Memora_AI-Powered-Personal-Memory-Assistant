"""SQLite store: approved folders, indexed files, text chunks with their embeddings, and an FTS5 keyword index."""

import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS folders (
    id INTEGER PRIMARY KEY,
    path TEXT NOT NULL UNIQUE,
    added_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS files (
    id INTEGER PRIMARY KEY,
    folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
    path TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    ext TEXT NOT NULL,
    kind TEXT NOT NULL,
    size INTEGER NOT NULL,
    mtime REAL NOT NULL,
    sha256 TEXT,
    status TEXT NOT NULL,
    error TEXT,
    summary TEXT,
    indexed_at REAL
);
CREATE INDEX IF NOT EXISTS files_folder ON files(folder_id);
CREATE TABLE IF NOT EXISTS chunks (
    id INTEGER PRIMARY KEY,
    file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    idx INTEGER NOT NULL,
    text TEXT NOT NULL,
    embedding BLOB NOT NULL  -- normalised float32 vector
);
CREATE INDEX IF NOT EXISTS chunks_file ON chunks(file_id);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, name, tokenize='porter unicode61');
CREATE INDEX IF NOT EXISTS files_sha ON files(sha256);

-- One row per image file. Three kinds of columns, kept apart so a re-run never overwrites the others:
--   read from the file (capture time, GPS, camera, size), produced by a model (ocr_*, caption, description, ...),
--   and typed by the user (user_*, event_*, location_label, people, favorite).
CREATE TABLE IF NOT EXISTS photos (
    file_id INTEGER PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
    stage TEXT NOT NULL DEFAULT 'pending',
    error TEXT,
    captured_date TEXT, captured_time TEXT, captured_offset TEXT,
    gps_lat REAL, gps_lon REAL, altitude REAL,
    width INTEGER, height INTEGER, orientation INTEGER,
    camera_make TEXT, camera_model TEXT, img_format TEXT,
    ocr_status TEXT NOT NULL DEFAULT 'pending', ocr_text TEXT, ocr_error TEXT,
    vision_status TEXT NOT NULL DEFAULT 'pending', vision_model TEXT, vision_error TEXT,
    analyzed_at REAL, prompt_version INTEGER,
    caption TEXT, description TEXT, objects TEXT, visible_text TEXT, context TEXT, summary TEXT, uncertainty TEXT,
    user_caption TEXT, user_notes TEXT, user_tags TEXT,
    event_title TEXT, event_date TEXT, location_label TEXT, people TEXT,
    favorite INTEGER NOT NULL DEFAULT 0, user_updated_at REAL,
    created_at REAL NOT NULL, updated_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS photos_captured ON photos(captured_date);
CREATE INDEX IF NOT EXISTS photos_favorite ON photos(favorite);
CREATE INDEX IF NOT EXISTS photos_stage ON photos(stage);
"""

# Columns the pipeline may write. The user_* group is deliberately absent: only update_photo_user() touches it.
PHOTO_FACT_COLS = (
    "captured_date", "captured_time", "captured_offset", "gps_lat", "gps_lon", "altitude", "width", "height",
    "orientation", "camera_make", "camera_model", "img_format",
)
PHOTO_MACHINE_COLS = (
    "stage", "error", "ocr_status", "ocr_text", "ocr_error", "vision_status", "vision_model", "vision_error",
    "analyzed_at", "prompt_version", "caption", "description", "objects", "visible_text", "context", "summary",
    "uncertainty",
)
PHOTO_USER_COLS = (
    "user_caption", "user_notes", "user_tags", "event_title", "event_date", "location_label", "people", "favorite",
)


QUERY_STOPWORDS = frozenset(
    "a an and are as at be been but by can could did do does for from had has have how i if in into is it its me my of on or "
    "our should so than that the their them then there these they this to us was we were what when where which who whom why "
    "will with would you your about any some get got give need want tell show find".split()
)


class Database:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        # Bumped whenever chunks change, so the in-memory vector index knows to reload.
        self.version = 0
        # The embedding model that new chunks are stamped with (set by the app); None only in bare databases.
        self.embed_model: str | None = None
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA foreign_keys=ON")
            self._conn.executescript(SCHEMA)
            # Migration: remember which embedding model produced each vector, so incompatible ones are never compared.
            cols = {r["name"] for r in self._conn.execute("PRAGMA table_info(chunks)").fetchall()}
            if "embed_model" not in cols:
                self._conn.execute("ALTER TABLE chunks ADD COLUMN embed_model TEXT")
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def _all(self, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(r) for r in self._conn.execute(sql, params).fetchall()]

    def _one(self, sql: str, params: tuple = ()) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute(sql, params).fetchone()
            return dict(row) if row else None

    # Folders

    def add_folder(self, path: str) -> dict[str, Any]:
        with self._lock:
            self._conn.execute(
                "INSERT OR IGNORE INTO folders(path, added_at) VALUES (?, ?)", (path, time.time())
            )
            self._conn.commit()
        return self._one("SELECT * FROM folders WHERE path = ?", (path,))

    def list_folders(self) -> list[dict[str, Any]]:
        return self._all(
            """SELECT f.*,
                      COUNT(fi.id) AS file_count,
                      SUM(fi.status = 'indexed') AS indexed_count,
                      SUM(fi.status = 'error') AS error_count
               FROM folders f LEFT JOIN files fi ON fi.folder_id = f.id
               GROUP BY f.id ORDER BY f.path"""
        )

    def get_folder(self, folder_id: int) -> dict[str, Any] | None:
        return self._one("SELECT * FROM folders WHERE id = ?", (folder_id,))

    def remove_folder(self, folder_id: int) -> list[int]:
        """Forget a folder and everything indexed from it. Returns the removed chunk ids."""
        with self._lock:
            chunk_ids = [
                r[0]
                for r in self._conn.execute(
                    "SELECT c.id FROM chunks c JOIN files f ON f.id = c.file_id WHERE f.folder_id = ?",
                    (folder_id,),
                )
            ]
            self._delete_fts(chunk_ids)
            self._conn.execute("DELETE FROM folders WHERE id = ?", (folder_id,))
            self._conn.commit()
            self.version += 1
        return chunk_ids

    # Files

    def get_file(self, file_id: int) -> dict[str, Any] | None:
        return self._one("SELECT * FROM files WHERE id = ?", (file_id,))

    def get_file_by_path(self, path: str) -> dict[str, Any] | None:
        return self._one("SELECT * FROM files WHERE path = ?", (path,))

    def list_files(self, folder_id: int | None = None, limit: int = 500) -> list[dict[str, Any]]:
        if folder_id is None:
            return self._all("SELECT * FROM files ORDER BY mtime DESC LIMIT ?", (limit,))
        return self._all(
            "SELECT * FROM files WHERE folder_id = ? ORDER BY mtime DESC LIMIT ?", (folder_id, limit)
        )

    def get_file_by_sha(self, folder_id: int, sha256: str) -> dict[str, Any] | None:
        return self._one("SELECT * FROM files WHERE folder_id = ? AND sha256 = ?", (folder_id, sha256))

    def find_indexed_twin(self, sha256: str, not_path: str) -> dict[str, Any] | None:
        """Another successfully indexed file with identical content, if any."""
        return self._one(
            "SELECT * FROM files WHERE sha256 = ? AND path != ? AND status = 'indexed' LIMIT 1", (sha256, not_path)
        )

    def file_paths_in_folder(self, folder_id: int) -> dict[str, int]:
        return {
            r["path"]: r["id"] for r in self._all("SELECT id, path FROM files WHERE folder_id = ?", (folder_id,))
        }

    def upsert_file(self, **fields: Any) -> int:
        cols = list(fields)
        with self._lock:
            self._conn.execute(
                f"INSERT INTO files({', '.join(cols)}) VALUES ({', '.join('?' * len(cols))}) "
                f"ON CONFLICT(path) DO UPDATE SET {', '.join(f'{c}=excluded.{c}' for c in cols if c != 'path')}",
                tuple(fields.values()),
            )
            self._conn.commit()
            return self._conn.execute("SELECT id FROM files WHERE path = ?", (fields["path"],)).fetchone()[0]

    def update_file_status(self, file_id: int, status: str, error: str | None, summary: str | None = None) -> None:
        with self._lock:
            if status == "indexed":
                self._conn.execute(
                    "UPDATE files SET status = ?, error = ?, summary = ?, indexed_at = ? WHERE id = ?",
                    (status, error, summary, time.time(), file_id),
                )
            else:
                self._conn.execute("UPDATE files SET status = ?, error = ? WHERE id = ?", (status, error, file_id))
            self._conn.commit()

    def remove_file(self, file_id: int) -> list[int]:
        with self._lock:
            chunk_ids = self.chunk_ids_for_file(file_id)
            self._delete_fts(chunk_ids)
            self._conn.execute("DELETE FROM files WHERE id = ?", (file_id,))
            self._conn.commit()
            self.version += 1
        return chunk_ids

    def stats(self) -> dict[str, int]:
        row = self._one(
            """SELECT COUNT(*) AS files,
                      COALESCE(SUM(status = 'indexed'), 0) AS indexed,
                      COALESCE(SUM(status = 'error'), 0) AS errors,
                      COALESCE(SUM(kind = 'image' AND status = 'indexed'), 0) AS images,
                      COALESCE(SUM(kind = 'document' AND status = 'indexed'), 0) AS documents
               FROM files"""
        )
        row["chunks"] = self._one("SELECT COUNT(*) AS n FROM chunks")["n"]
        return row

    # Chunks

    def chunk_ids_for_file(self, file_id: int) -> list[int]:
        return [r["id"] for r in self._all("SELECT id FROM chunks WHERE file_id = ?", (file_id,))]

    def replace_chunks(self, file_id: int, name: str, texts: list[str], embeddings: list[bytes]) -> None:
        """Swap a file's chunks (and their embeddings) for new ones."""
        with self._lock:
            self._delete_fts(self.chunk_ids_for_file(file_id))
            self._conn.execute("DELETE FROM chunks WHERE file_id = ?", (file_id,))
            for i, (text, emb) in enumerate(zip(texts, embeddings)):
                cur = self._conn.execute(
                    "INSERT INTO chunks(file_id, idx, text, embedding, embed_model) VALUES (?, ?, ?, ?, ?)",
                    (file_id, i, text, emb, self.embed_model),
                )
                self._conn.execute(
                    "INSERT INTO chunks_fts(rowid, text, name) VALUES (?, ?, ?)", (cur.lastrowid, text, name)
                )
            self._conn.commit()
            self.version += 1

    def chunk_data(self, file_id: int) -> tuple[list[str], list[bytes]]:
        """A file's chunk texts and embeddings, in order."""
        rows = self._all("SELECT text, embedding FROM chunks WHERE file_id = ? ORDER BY idx", (file_id,))
        return [r["text"] for r in rows], [r["embedding"] for r in rows]

    def all_embeddings(self) -> list[tuple[int, bytes]]:
        """Vectors made by the current embedding model only; vectors from another model are never compared."""
        with self._lock:
            return self._conn.execute(
                "SELECT id, embedding FROM chunks WHERE embed_model IS ?", (self.embed_model,)
            ).fetchall()

    def meta_get(self, key: str) -> str | None:
        row = self._one("SELECT value FROM meta WHERE key = ?", (key,))
        return row["value"] if row else None

    def meta_set(self, key: str, value: str) -> None:
        with self._lock:
            self._conn.execute("INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", (key, value))
            self._conn.commit()

    def embedding_status(self) -> dict[str, Any]:
        """How many chunks use the current embedding model and how many need re-embedding."""
        rows = self._all("SELECT embed_model, COUNT(*) AS n FROM chunks GROUP BY embed_model")
        current = sum(r["n"] for r in rows if r["embed_model"] == self.embed_model)
        total = sum(r["n"] for r in rows)
        return {"model": self.embed_model, "current": current, "stale": total - current,
                "models": {str(r["embed_model"]): r["n"] for r in rows}}

    def adopt_unlabelled_chunks(self) -> int:
        """Chunks from before model tracking existed (NULL) were made with the configured model at the time;
        label them once with the current model. Only called when no model has been recorded yet."""
        with self._lock:
            cur = self._conn.execute("UPDATE chunks SET embed_model = ? WHERE embed_model IS NULL", (self.embed_model,))
            self._conn.commit()
            self.version += 1
            return cur.rowcount

    def stale_chunks(self, limit: int = 64) -> list[dict[str, Any]]:
        return self._all(
            """SELECT c.id, c.text, f.name FROM chunks c JOIN files f ON f.id = c.file_id
               WHERE c.embed_model IS NOT ? LIMIT ?""", (self.embed_model, limit))

    def set_chunk_embeddings(self, updates: list[tuple[bytes, int]]) -> None:
        with self._lock:
            self._conn.executemany("UPDATE chunks SET embedding = ?, embed_model = ? WHERE id = ?",
                                   [(blob, self.embed_model, cid) for blob, cid in updates])
            self._conn.commit()
            self.version += 1

    def chunk_models(self, file_id: int) -> set[str | None]:
        return {r["embed_model"] for r in self._all("SELECT DISTINCT embed_model FROM chunks WHERE file_id = ?", (file_id,))}

    def get_chunks(self, chunk_ids: list[int]) -> dict[int, dict[str, Any]]:
        if not chunk_ids:
            return {}
        marks = ",".join("?" * len(chunk_ids))
        rows = self._all(
            f"""SELECT c.id, c.idx, c.text, c.file_id, f.path, f.name, f.kind, f.ext, f.mtime, f.summary, f.indexed_at,
                       p.captured_date, p.captured_time, p.caption AS photo_caption, p.user_caption
                FROM chunks c JOIN files f ON f.id = c.file_id
                LEFT JOIN photos p ON p.file_id = f.id WHERE c.id IN ({marks})""",
            tuple(chunk_ids),
        )
        return {r["id"]: r for r in rows}

    def file_ids_matching(self, query: str, limit: int = 2000) -> set[int]:
        """Files with a chunk matching the keywords (used by the photo gallery's search box)."""
        chunk_ids = self.keyword_search(query, limit)
        if not chunk_ids:
            return set()
        marks = ",".join("?" * len(chunk_ids))
        return {r["file_id"] for r in self._all(f"SELECT DISTINCT file_id FROM chunks WHERE id IN ({marks})", tuple(chunk_ids))}

    # Photos

    def get_photo(self, file_id: int) -> dict[str, Any] | None:
        return self._one("SELECT * FROM photos WHERE file_id = ?", (file_id,))

    def ensure_photo(self, file_id: int) -> dict[str, Any]:
        now = time.time()
        with self._lock:
            self._conn.execute(
                "INSERT OR IGNORE INTO photos(file_id, created_at, updated_at) VALUES (?, ?, ?)", (file_id, now, now)
            )
            self._conn.commit()
        return self.get_photo(file_id)

    def update_photo(self, file_id: int, **fields: Any) -> None:
        """Write facts and machine results. User-typed columns are refused here on purpose."""
        bad = set(fields) - set(PHOTO_FACT_COLS) - set(PHOTO_MACHINE_COLS)
        if bad:
            raise ValueError(f"Not a pipeline column: {sorted(bad)}")
        self._write_photo(file_id, fields)

    def update_photo_user(self, file_id: int, **fields: Any) -> None:
        bad = set(fields) - set(PHOTO_USER_COLS)
        if bad:
            raise ValueError(f"Not a user column: {sorted(bad)}")
        self._write_photo(file_id, {**fields, "user_updated_at": time.time()})

    def _write_photo(self, file_id: int, fields: dict[str, Any]) -> None:
        if not fields:
            return
        self.ensure_photo(file_id)
        cols = list(fields)
        with self._lock:
            self._conn.execute(
                f"UPDATE photos SET {', '.join(c + ' = ?' for c in cols)}, updated_at = ? WHERE file_id = ?",
                (*fields.values(), time.time(), file_id),
            )
            self._conn.commit()

    def copy_photo(self, from_id: int, to_id: int) -> None:
        """Give an identical file the same facts and analysis (not the user's notes: those belong to one photo)."""
        src = self.get_photo(from_id)
        if not src:
            return
        keep = {k: src[k] for k in (*PHOTO_FACT_COLS, *PHOTO_MACHINE_COLS) if k in src}
        self._write_photo(to_id, keep)

    def stuck_photo_ids(self) -> list[int]:
        """Photos left half-processed by a crash or restart."""
        return [r["file_id"] for r in self._all(
            "SELECT file_id FROM photos WHERE stage IN ('metadata','ocr','vision','indexing')")]

    def list_photos(self, *, sort: str = "captured", descending: bool = True, favorites: bool = False,
                    date_from: str | None = None, date_to: str | None = None, stage: str | None = None,
                    file_ids: set[int] | None = None, name_like: str | None = None, undated: bool = False,
                    limit: int = 60, offset: int = 0) -> tuple[list[dict[str, Any]], int]:
        where, params = ["f.kind = 'image'"], []
        if undated:
            where.append("p.captured_date IS NULL AND p.event_date IS NULL")
        if favorites:
            where.append("COALESCE(p.favorite, 0) = 1")
        if date_from:
            where.append("COALESCE(p.event_date, p.captured_date) >= ?")
            params.append(date_from)
        if date_to:
            where.append("COALESCE(p.event_date, p.captured_date) <= ?")
            params.append(date_to)
        if stage == "failed":
            where.append("(p.stage = 'failed' OR f.status = 'error' OR p.vision_status = 'failed')")
        elif stage:
            where.append("p.stage = ?")
            params.append(stage)
        if file_ids is not None or name_like:
            parts = []
            if file_ids:
                parts.append(f"f.id IN ({','.join('?' * len(file_ids))})")
                params.extend(sorted(file_ids))
            if name_like:
                parts.append("f.name LIKE ? ESCAPE '\\'")
                params.append("%" + name_like.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            where.append("(" + " OR ".join(parts) + ")" if parts else "0")
        # Photos without a capture date sort after the dated ones, never by their import time pretending to be one.
        direction = "DESC" if descending else "ASC"
        order = {
            "captured": f"(p.captured_date IS NULL), p.captured_date {direction}, p.captured_time {direction}, f.id DESC",
            "imported": f"f.mtime {direction}, f.id DESC",
            "name": f"f.name COLLATE NOCASE {direction}",
        }.get(sort, "f.id DESC")
        base = f"FROM files f LEFT JOIN photos p ON p.file_id = f.id WHERE {' AND '.join(where)}"
        total = self._one(f"SELECT COUNT(*) AS n {base}", tuple(params))["n"]
        rows = self._all(
            f"""SELECT f.id AS file_id, f.name, f.size, f.mtime, f.status, f.error AS file_error, f.indexed_at,
                       p.stage, p.error, p.captured_date, p.captured_time, p.width, p.height, p.caption,
                       p.user_caption, p.ocr_status, p.vision_status, p.favorite, p.event_date, p.event_title
                {base} ORDER BY {order} LIMIT ? OFFSET ?""",
            (*params, limit, offset),
        )
        return rows, total

    def keyword_search(self, query: str, limit: int) -> list[int]:
        """BM25 keyword search over chunk text and file names. Returns chunk ids, best first.
        Question filler ("what", "my", "the") is dropped: it matches almost every chunk and only adds noise to the ranking."""
        words = [t for t in "".join(c if c.isalnum() else " " for c in query).split() if len(t) > 1]
        terms = [t for t in words if t.lower() not in QUERY_STOPWORDS] or words
        if not terms:
            return []
        match = " OR ".join(f'"{t}"' for t in terms)
        rows = self._all(
            "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts) LIMIT ?",
            (match, limit),
        )
        return [r["rowid"] for r in rows]

    def _delete_fts(self, chunk_ids: list[int]) -> None:
        for cid in chunk_ids:
            self._conn.execute("DELETE FROM chunks_fts WHERE rowid = ?", (cid,))
