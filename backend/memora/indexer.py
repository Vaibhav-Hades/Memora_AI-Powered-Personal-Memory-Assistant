"""Walks approved folders, extracts content, embeds it, and keeps SQLite in sync."""

import hashlib
import logging
import os
import queue
import threading
import time
from pathlib import Path
from typing import Any, Protocol

from .config import SUPPORTED_EXTS, Settings
from .db import Database
from .extract import chunk_text, extract_text, kind_for
from .ocr import default_ocr
from .photos import PhotoPipeline
from .vectors import to_blob

log = logging.getLogger("memora.indexer")


class Model(Protocol):
    def embed(self, texts: list[str], kind: str = "document") -> list[list[float]]: ...
    def describe_image(self, data: bytes) -> str: ...


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def scan_folder(root: Path, max_bytes: int) -> list[Path]:
    """Supported files under root. Hidden folders and anything resolving outside root are skipped."""
    root = root.resolve()
    found = []
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for name in filenames:
            p = Path(dirpath) / name
            if name.startswith(".") or p.suffix.lower() not in SUPPORTED_EXTS:
                continue
            try:
                real = p.resolve()
                if not real.is_relative_to(root) or not real.is_file() or real.stat().st_size > max_bytes:
                    continue
            except OSError:
                continue
            found.append(real)
    return sorted(found)


class Indexer:
    def __init__(self, settings: Settings, db: Database, model: Model, ocr: Any = None):
        self.settings = settings
        self.db = db
        self.model = model
        self.pipeline = PhotoPipeline(settings, db, model, ocr if ocr is not None else default_ocr(settings.ocr))
        self._queue: "queue.Queue[int]" = queue.Queue()
        self._pending: set[int] = set()
        # Single jobs (phone uploads, photo retries, edits) that jump ahead of folder scans.
        self._urgent: "queue.Queue[tuple]" = queue.Queue()
        self._lock = threading.Lock()
        self._status: dict[str, Any] = {"running": False, "queued": 0}
        self._worker: threading.Thread | None = None

    # Background job control

    def enqueue(self, folder_id: int) -> None:
        with self._lock:
            if folder_id in self._pending:
                return
            self._pending.add(folder_id)
            self._queue.put(folder_id)
            self._status["queued"] = len(self._pending)
            self._ensure_worker()

    def enqueue_file(self, folder_id: int, path: Path) -> None:
        """Index one file as soon as possible, even in the middle of a folder scan."""
        with self._lock:
            self._urgent.put(("file", folder_id, path))
            self._ensure_worker()

    def enqueue_photo(self, file_id: int, vision: bool = True, ocr: bool = True) -> None:
        """Redo a photo's analysis steps (a retry, a new model, or a first run for a photo imported before analysis existed)."""
        self.db.update_photo(file_id, stage="pending", error=None)
        with self._lock:
            self._urgent.put(("photo", file_id, vision, ocr))
            self._ensure_worker()

    def enqueue_rebuild(self, file_id: int) -> None:
        """Re-create a photo's searchable text from what is stored (after the user edits notes or tags). No AI analysis."""
        with self._lock:
            self._urgent.put(("rebuild", file_id))
            self._ensure_worker()

    def _ensure_worker(self) -> None:
        if self._worker is None or not self._worker.is_alive():
            self._worker = threading.Thread(target=self._run, daemon=True, name="memora-indexer")
            self._worker.start()

    def status(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._status)

    def wait(self, timeout: float = 60.0) -> None:
        """Block until queued work is done (used by tests and the CLI)."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self._lock:
                if not self._pending and not self._status.get("running") and not self._urgent.unfinished_tasks:
                    return
            time.sleep(0.05)
        raise TimeoutError("indexing did not finish")

    def _run(self) -> None:
        while True:
            self._drain_urgent()
            try:
                folder_id = self._queue.get(timeout=0.5)
            except queue.Empty:
                with self._lock:
                    if self._queue.empty() and self._urgent.empty():
                        self._worker = None
                        return
                continue
            try:
                self.index_folder(folder_id)
            except Exception:  # noqa: BLE001 - keep the worker alive whatever happens
                log.exception("Indexing folder %s failed", folder_id)
            finally:
                with self._lock:
                    self._pending.discard(folder_id)
                    self._status["queued"] = len(self._pending)

    def _drain_urgent(self) -> None:
        while True:
            try:
                job = self._urgent.get_nowait()
            except queue.Empty:
                return
            try:
                if job[0] == "file":
                    self.index_file(job[1], job[2])
                elif job[0] == "photo":
                    self.reprocess_photo(job[1], vision=job[2], ocr=job[3])
                else:
                    self.rebuild_photo(job[1])
            except Exception:  # noqa: BLE001
                log.exception("Background job %s failed", job[0])
            finally:
                self._urgent.task_done()

    def _set(self, **kw: Any) -> None:
        with self._lock:
            self._status.update(kw)

    # Indexing

    def index_folder(self, folder_id: int) -> dict[str, int]:
        folder = self.db.get_folder(folder_id)
        if folder is None:
            return {}
        root = Path(folder["path"])
        counts = {"indexed": 0, "reused": 0, "unchanged": 0, "errors": 0, "removed": 0}
        self._set(running=True, folder=str(root), done=0, total=0, current=None, started_at=time.time())
        try:
            files = scan_folder(root, self.settings.max_file_mb * 1024 * 1024) if root.is_dir() else []
            self._set(total=len(files))

            known = self.db.file_paths_in_folder(folder_id)
            seen = {str(p) for p in files}
            for path, file_id in known.items():
                if path not in seen:
                    self.db.remove_file(file_id)
                    counts["removed"] += 1

            for i, path in enumerate(files):
                if self.db.get_folder(folder_id) is None:
                    break  # folder was removed mid-run
                self._drain_urgent()
                self._set(current=path.name, done=i)
                result = self.index_file(folder_id, path)
                counts[result] += 1
            self._set(done=len(files))
        finally:
            self._set(running=False, current=None, finished_at=time.time(), last_result=counts)
        return counts

    def index_file(self, folder_id: int, path: Path) -> str:
        stat = path.stat()
        existing = self.db.get_file_by_path(str(path))
        if (
            existing
            and existing["status"] == "indexed"
            and existing["size"] == stat.st_size
            and existing["mtime"] == stat.st_mtime
        ):
            self._backfill_photo(existing)
            return "unchanged"

        sha = _sha256(path)
        ext = path.suffix.lower()
        kind = kind_for(ext)
        base = dict(
            folder_id=folder_id,
            path=str(path),
            name=path.name,
            ext=ext,
            kind=kind,
            size=stat.st_size,
            mtime=stat.st_mtime,
            sha256=sha,
        )
        if existing and existing["status"] == "indexed" and existing["sha256"] == sha:
            file_id = self.db.upsert_file(**base, status="indexed", error=None, summary=existing["summary"],
                                          indexed_at=existing["indexed_at"])
            self._backfill_photo(self.db.get_file(file_id))
            return "unchanged"

        # Same bytes already indexed elsewhere (a copy in Downloads and Pictures, a moved or renamed file):
        # reuse that text and those embeddings instead of running the AI on it again.
        twin = self.db.find_indexed_twin(sha, str(path))
        if twin and self.db.chunk_models(twin["id"]) <= {self.db.embed_model}:
            texts, embeddings = self.db.chunk_data(twin["id"])
            file_id = self.db.upsert_file(**base, status="indexed", error=None, summary=twin["summary"],
                                          indexed_at=time.time())
            self.db.replace_chunks(file_id, path.name, texts, embeddings)
            if kind == "image":
                self.db.copy_photo(twin["id"], file_id)
            return "reused"

        if kind == "image":
            return self._index_photo(base)

        try:
            text = extract_text(path, self.model, self.settings.max_pdf_image_pages)
            chunks = chunk_text(text, self.settings.chunk_size, self.settings.chunk_overlap)
            if not chunks:
                chunks = [f"(No readable content was found in {path.name}.)"]
            vectors = self.model.embed([f"{path.name}\n{c}" for c in chunks], kind="document")
        except Exception as e:  # noqa: BLE001 - one bad file must not stop the run
            log.warning("Failed to index %s: %s", path, e)
            file_id = self.db.upsert_file(**base, status="error", error=str(e)[:500], summary=None, indexed_at=None)
            self.db.replace_chunks(file_id, path.name, [], [])
            return "errors"

        file_id = self.db.upsert_file(
            **base, status="indexed", error=None, summary=text.strip()[:400], indexed_at=time.time()
        )
        self.db.replace_chunks(file_id, path.name, chunks, [to_blob(v) for v in vectors])
        return "indexed"

    # Photos

    def _index_photo(self, base: dict[str, Any]) -> str:
        """New or changed image: read its facts, recognise its text, ask the vision model, then index all of it."""
        file_id = self.db.upsert_file(**base, status="pending", error=None, summary=None, indexed_at=None)
        # Replaced bytes under the same path: drop the previous analysis, but keep the user's own notes and tags.
        self.db.update_photo(file_id, vision_status="pending", ocr_status="pending", caption=None, description=None,
                             objects=None, visible_text=None, context=None, summary=None, uncertainty=None,
                             ocr_text=None, analyzed_at=None)
        return self._finish_photo(file_id, base["name"], vision=True, ocr=True)

    def _finish_photo(self, file_id: int, name: str, *, vision: bool, ocr: bool) -> str:
        file = self.db.get_file(file_id)
        try:
            chunks = self.pipeline.process(file, vision=vision, ocr=ocr)
            vectors = self.model.embed([f"{name}\n{c}" for c in chunks], kind="document")
        except Exception as e:  # noqa: BLE001 - the original stays; the failure is recorded for a retry
            log.warning("Failed to index photo %s: %s", file_id, e)
            self.db.update_file_status(file_id, "error", str(e)[:500])
            self.db.update_photo(file_id, stage="failed", error=str(e)[:300])
            self.db.replace_chunks(file_id, name, [], [])
            return "errors"
        photo = self.db.get_photo(file_id)
        summary = (photo.get("summary") or photo.get("description") or photo.get("ocr_text") or "").strip()[:400]
        self.db.replace_chunks(file_id, name, chunks, [to_blob(v) for v in vectors])
        self.db.update_file_status(file_id, "indexed", None, summary=summary or None)
        self.pipeline.mark_ready(file_id)
        return "indexed"

    def reprocess_photo(self, file_id: int, *, vision: bool = True, ocr: bool = True) -> str:
        file = self.db.get_file(file_id)
        if file is None or file["kind"] != "image":
            return "errors"
        return self._finish_photo(file_id, file["name"], vision=vision, ocr=ocr)

    def rebuild_photo(self, file_id: int) -> None:
        file = self.db.get_file(file_id)
        if file is None or file["kind"] != "image" or self.db.get_photo(file_id) is None:
            return
        chunks = self.pipeline.finish(file)
        vectors = self.model.embed([f"{file['name']}\n{c}" for c in chunks], kind="document")
        self.db.replace_chunks(file_id, file["name"], chunks, [to_blob(v) for v in vectors])
        self.pipeline.mark_ready(file_id)

    def _backfill_photo(self, file: dict[str, Any] | None) -> None:
        """A photo indexed before photo records existed: add its facts (cheap, local) and mark it as not yet analysed.
        Its existing search text keeps working; analysing it again is the user's choice."""
        if not file or file["kind"] != "image" or self.db.get_photo(file["id"]) is not None:
            return
        self.db.ensure_photo(file["id"])
        try:
            self.pipeline.read_facts(file)
        except Exception as e:  # noqa: BLE001
            log.info("Could not read facts of %s: %s", file["id"], e)
        self.db.update_photo(file["id"], stage="ready", vision_status="legacy", ocr_status="pending")

    def reindex_embeddings(self) -> dict[str, int]:
        """Re-embed chunks made by a different embedding model, from their stored text (no re-extraction, no OCR, no vision)."""
        done = 0
        while True:
            batch = self.db.stale_chunks(64)
            if not batch:
                break
            vectors = self.model.embed([c["name"] + "\n" + c["text"] for c in batch], kind="document")
            self.db.set_chunk_embeddings([(to_blob(v), c["id"]) for v, c in zip(vectors, batch)])
            done += len(batch)
        return {"reembedded": done}

    def recover_interrupted(self) -> int:
        """Photos left mid-processing by a crash or restart are queued again, so the library never stays stuck."""
        ids = self.db.stuck_photo_ids()
        for fid in ids:
            self.enqueue_photo(fid)
        return len(ids)
