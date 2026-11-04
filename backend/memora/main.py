"""FastAPI app. Run with `python -m memora` (this computer only) or `python -m memora --lan` (also phones)."""

import argparse
import hashlib
import io
import json
import datetime
import logging
import mimetypes
import os
import re
import socket
import subprocess
import sys
import threading
import time
from contextlib import asynccontextmanager
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageOps
from pydantic import BaseModel, Field

from .access import (
    code_matches,
    is_local_network,
    is_loopback,
    lan_addresses,
    tailscale_addresses,
    load_pairing_code,
    suggested_folders,
)
from .config import IMAGE_EXTS, Settings
from .db import Database
from .extract import kind_for, missing_extractors
from .indexer import Indexer
from .photometa import read_photo_meta
from .photos import clean_list, display_caption, loads_list
from .vision import PROMPT_VERSION
from .ollama_client import OllamaClient, OllamaError
from .search import answer_question, retrieve
from .vectors import VectorIndex

VERSION = "0.4.0"
# Plain HTML/CSS/JS, no build step.
FRONTEND_DIR = Path(__file__).resolve().parent.parent.parent / "frontend"
LOCAL_HOSTNAMES = {"localhost", "127.0.0.1", "[::1]"}
# Formats accepted from the phone app (it re-encodes everything else, e.g. HEIC, to JPEG first).
UPLOAD_FORMATS = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp"}


class NoCacheStaticFiles(StaticFiles):
    """Make browsers revalidate the UI files so edits (and upgrades) show up on a normal reload."""

    def file_response(self, *args: Any, **kwargs: Any):
        response = super().file_response(*args, **kwargs)
        response.headers["Cache-Control"] = "no-cache"
        return response


class FolderIn(BaseModel):
    path: str = Field(min_length=1)


class AskIn(BaseModel):
    question: str = Field(min_length=1, max_length=2000)


def _validate_folder(raw: str, existing: list[str]) -> Path:
    path = Path(os.path.expandvars(os.path.expanduser(raw.strip().strip('"')))).resolve()
    if not path.is_dir():
        raise HTTPException(400, f"Folder not found: {path}")
    if path.parent == path:
        raise HTTPException(400, "Indexing a whole drive is not allowed; pick a specific folder.")
    for other in map(Path, existing):
        if path == other:
            raise HTTPException(409, "This folder is already approved.")
        if path.is_relative_to(other) or other.is_relative_to(path):
            raise HTTPException(409, f"Overlaps with an approved folder: {other}")
    return path


def _valid_date(value: str) -> bool:
    try:
        datetime.date.fromisoformat(value)
        return len(value) == 10
    except ValueError:
        return False


def _hostname(host_header: str) -> str:
    host = host_header.strip().lower()
    if host.startswith("["):
        return host[: host.find("]") + 1]
    return host.split(":")[0]


@lru_cache(maxsize=256)
@lru_cache(maxsize=512)
def _file_sha256(path: str, mtime: float, size: int) -> str:
    """SHA-256 of the file's current bytes (cached per path/mtime/size so unchanged files are hashed once)."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _sync_entry(f: dict[str, Any]) -> dict[str, Any]:
    """What a phone needs to decide what to download and to verify it. Never includes the laptop path."""
    entry = {
        "id": f["id"], "folder_id": f["folder_id"], "name": f["name"], "ext": f["ext"], "kind": f["kind"],
        "mime": mimetypes.guess_type(f["name"])[0] or "application/octet-stream",
        "size": f["size"], "mtime": f["mtime"], "status": f["status"], "sha256": None, "available": False,
    }
    try:
        st = os.stat(f["path"])
        entry.update(size=st.st_size, mtime=st.st_mtime, available=True,
                     sha256=_file_sha256(f["path"], st.st_mtime, st.st_size))
    except OSError:
        pass  # moved or deleted on the laptop: listed, but not downloadable
    return entry


def _thumbnail(path: str, mtime: float, size: int) -> bytes | None:
    """Small JPEG preview of an image or the first page of a PDF; None for other files."""
    ext = Path(path).suffix.lower()
    if ext in IMAGE_EXTS:
        with Image.open(path) as img:
            img = ImageOps.exif_transpose(img).convert("RGB")
    elif ext == ".pdf":
        import pypdfium2 as pdfium

        pdf = pdfium.PdfDocument(path)
        try:
            page = pdf[0]
            img = page.render(scale=size * 2 / max(page.get_size())).to_pil().convert("RGB")
        finally:
            pdf.close()
    else:
        return None
    img.thumbnail((size, size))
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=80)
    return out.getvalue()


def create_app(settings: Settings | None = None, model: Any = None, ocr: Any = None) -> FastAPI:
    settings = settings or Settings()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.uploads_dir.mkdir(parents=True, exist_ok=True)
    db = Database(settings.db_path)
    # Vectors are stamped with the embedding model that made them. Chunks from before this existed are labelled once;
    # after that, a different configured model makes the old vectors "stale" (never compared) until re-embedded.
    db.embed_model = settings.embed_model
    recorded = db.meta_get("embed_model")
    if recorded is None:
        db.adopt_unlabelled_chunks()
        db.meta_set("embed_model", settings.embed_model)
    vectors = VectorIndex(db)
    model = model or OllamaClient(settings)
    indexer = Indexer(settings, db, model, ocr)
    pairing_code = load_pairing_code(settings.data_dir)
    uploads_folder = db.add_folder(str(settings.uploads_dir.resolve()))

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # Photos a crash left half-processed go back in the queue; then pick up files added or changed while closed.
        indexer.recover_interrupted()
        for f in db.list_folders():
            indexer.enqueue(f["id"])
        yield
        db.close()

    app = FastAPI(title="Memora", version=VERSION, lifespan=lifespan)
    app.state.db, app.state.vectors, app.state.indexer = db, vectors, indexer
    app.state.pairing_code, app.state.uploads_folder = pairing_code, uploads_folder
    app.state.settings = settings

    @app.middleware("http")
    async def guard(request: Request, call_next):
        """Laptop: full access, but only via a local hostname (blocks DNS-rebinding from web pages).
        Anything else (a phone on the Wi-Fi): API only, and only with the pairing code."""
        client = request.client.host if request.client else None
        if is_loopback(client):
            if _hostname(request.headers.get("host", "")) not in LOCAL_HOSTNAMES:
                return JSONResponse({"detail": "Invalid host header"}, status_code=400)
            request.state.remote = False
        else:
            if not is_local_network(client, settings.allow_tailscale):
                return JSONResponse(
                    {"detail": "Memora only accepts devices on the local network"
                               + (" or your Tailscale network." if settings.allow_tailscale else ".")},
                    status_code=403,
                )
            if not request.url.path.startswith("/api/"):
                return JSONResponse(
                    {"detail": "The Memora web page only opens on the laptop itself. Use the Memora Android app."},
                    status_code=403,
                )
            if not code_matches(pairing_code, request.headers.get("x-memora-code")):
                return JSONResponse(
                    {"detail": "Missing or wrong pairing code. Copy it from Memora on the laptop."}, status_code=401
                )
            request.state.remote = True
        return await call_next(request)

    def get_file_or_404(file_id: int) -> dict[str, Any]:
        f = db.get_file(file_id)
        if f is None or db.get_folder(f["folder_id"]) is None:
            raise HTTPException(404, "File not found in the index")
        if not Path(f["path"]).is_file():
            raise HTTPException(410, "The original file has moved or been deleted")
        return f

    def laptop_only(request: Request, what: str) -> None:
        if request.state.remote:
            raise HTTPException(403, f"{what} can only be done on the laptop.")

    # Connection

    @app.get("/api/ping")
    def ping() -> dict[str, Any]:
        """Cheap check for the phone's "Test connection" button (no AI call)."""
        return {"app": "memora", "version": VERSION, "server_name": socket.gethostname(), "lan": settings.lan,
                "tailscale": settings.allow_tailscale}

    @app.get("/api/pairing")
    def pairing(request: Request) -> dict[str, Any]:
        laptop_only(request, "Viewing the pairing code")
        return {
            "lan": settings.lan,
            "port": settings.port,
            "addresses": lan_addresses() if settings.lan else [],
            "tailscale": settings.allow_tailscale,
            "tailscale_addresses": tailscale_addresses() if settings.allow_tailscale else [],
            "code": pairing_code,
        }

    @app.get("/api/health")
    def health(probe: bool = False) -> dict[str, Any]:
        """Model/index status. With ?probe=1 it also runs one tiny real chat and one real embedding (slow: may load models)."""
        model_health = model.health() if hasattr(model, "health") else {"ok": True}
        out = {"model": model_health, "stats": db.stats(), "indexing": indexer.status(), "embeddings": db.embedding_status(),
               "missing_packages": missing_extractors()}
        if out["embeddings"]["stale"]:
            out["embeddings"]["reindex_required"] = True
        if probe and hasattr(model, "probe"):
            out["probe"] = model.probe()
        return out

    @app.post("/api/embeddings/reindex", status_code=202)
    def reindex_embeddings(request: Request) -> dict[str, Any]:
        """Re-embed chunks made by a different embedding model, from stored text. Laptop only; runs in the background."""
        laptop_only(request, "Re-embedding")
        stale = db.embedding_status()["stale"]
        if stale:
            threading.Thread(target=indexer.reindex_embeddings, daemon=True, name="memora-reembed").start()
        return {"stale": stale, "started": bool(stale)}

    # Folders

    @app.get("/api/folders")
    def list_folders() -> list[dict[str, Any]]:
        folders = db.list_folders()
        for f in folders:
            f["is_uploads"] = f["id"] == uploads_folder["id"]
        return folders

    @app.get("/api/folders/suggested")
    def list_suggested() -> list[dict[str, Any]]:
        approved = {f["path"] for f in db.list_folders()}
        return [{**s, "approved": s["path"] in approved} for s in suggested_folders()]

    @app.post("/api/folders", status_code=201)
    def add_folder(body: FolderIn, request: Request) -> dict[str, Any]:
        if request.state.remote and body.path not in {s["path"] for s in suggested_folders()}:
            raise HTTPException(403, "From the phone you can add Pictures, Documents or Downloads. Add other folders on the laptop.")
        path = _validate_folder(body.path, [f["path"] for f in db.list_folders()])
        folder = db.add_folder(str(path))
        indexer.enqueue(folder["id"])
        return folder

    @app.delete("/api/folders/{folder_id}")
    def remove_folder(folder_id: int, request: Request) -> dict[str, Any]:
        laptop_only(request, "Removing folders")
        if db.get_folder(folder_id) is None:
            raise HTTPException(404, "Folder not found")
        if folder_id == uploads_folder["id"]:
            raise HTTPException(409, "The phone uploads folder can't be removed.")
        removed = db.remove_folder(folder_id)
        return {"removed_chunks": len(removed), "note": "Only the index was removed; your files are untouched."}

    @app.post("/api/folders/{folder_id}/reindex")
    def reindex(folder_id: int) -> dict[str, Any]:
        if db.get_folder(folder_id) is None:
            raise HTTPException(404, "Folder not found")
        indexer.enqueue(folder_id)
        return indexer.status()

    # Files

    @app.get("/api/files")
    def list_files(folder_id: int | None = None, limit: int = 500) -> list[dict[str, Any]]:
        return db.list_files(folder_id, min(limit, 2000))

    @app.get("/api/files/{file_id}")
    def file_detail(file_id: int) -> dict[str, Any]:
        f = db.get_file(file_id)
        if f is None:
            raise HTTPException(404, "File not found in the index")
        return f

    @app.get("/api/files/{file_id}/meta")
    def file_meta(file_id: int) -> dict[str, Any]:
        """Dates and place stored inside a photo (EXIF). Distinct from the filesystem dates in the file record."""
        f = get_file_or_404(file_id)
        meta = read_photo_meta(f["path"]) if Path(f["path"]).suffix.lower() in IMAGE_EXTS else             {"captured": None, "location": None, "width": None, "height": None}
        return {"id": f["id"], "modified": f["mtime"], "indexed_at": f["indexed_at"], **meta}

    @app.get("/api/files/{file_id}/content")
    def file_content(file_id: int) -> FileResponse:
        f = get_file_or_404(file_id)
        return FileResponse(f["path"], filename=f["name"], content_disposition_type="inline")

    @app.get("/api/files/{file_id}/download")
    def file_download(file_id: int) -> FileResponse:
        """The original bytes as an attachment, with their SHA-256 so the phone can verify the copy."""
        f = get_file_or_404(file_id)
        st = os.stat(f["path"])
        headers = {"X-Memora-Sha256": _file_sha256(f["path"], st.st_mtime, st.st_size), "Cache-Control": "no-store"}
        return FileResponse(f["path"], filename=f["name"], content_disposition_type="attachment", headers=headers)

    @app.get("/api/sync/manifest")
    def sync_manifest(folder_id: int | None = None, limit: int = 2000) -> dict[str, Any]:
        """Files the phone may download, with size and hash. Folders and paths on the laptop stay private."""
        if folder_id is not None and db.get_folder(folder_id) is None:
            raise HTTPException(404, "Folder not found")
        files = [_sync_entry(f) for f in db.list_files(folder_id, max(1, min(limit, 5000)))]
        for entry in files:
            if entry["kind"] == "image":
                p = db.get_photo(entry["id"])
                # Facts and the laptop's analysis, for the phone to show. The user's own notes never travel in the manifest.
                entry["photo"] = None if p is None else {
                    "captured_date": p["captured_date"], "captured_time": p["captured_time"],
                    "captured_offset": p["captured_offset"], "width": p["width"], "height": p["height"],
                    "caption": p["caption"], "summary": p["summary"], "vision_status": p["vision_status"],
                    "ocr_status": p["ocr_status"],
                }
        return {"generated_at": time.time(), "files": files}

    @app.get("/api/files/{file_id}/thumbnail")
    def file_thumbnail(file_id: int, size: int = 320) -> Response:
        f = get_file_or_404(file_id)
        try:
            data = _thumbnail(f["path"], f["mtime"], max(64, min(size, 1024)))
        except Exception as e:  # noqa: BLE001 - unreadable file: no preview, not a server error
            raise HTTPException(404, f"No preview available: {e}") from e
        if data is None:
            raise HTTPException(404, "No preview for this file type")
        return Response(data, media_type="image/jpeg", headers={"Cache-Control": "private, max-age=3600"})

    @app.post("/api/files/{file_id}/reveal")
    def reveal(file_id: int) -> dict[str, bool]:
        """Show the original file in the laptop's file manager."""
        path = get_file_or_404(file_id)["path"]
        if sys.platform == "win32":
            subprocess.Popen(["explorer", "/select,", path])
        elif sys.platform == "darwin":
            subprocess.Popen(["open", "-R", path])
        else:
            subprocess.Popen(["xdg-open", str(Path(path).parent)])
        return {"ok": True}

    # Phone uploads

    def save_upload(data: bytes, filename: str) -> dict[str, Any]:
        if not data:
            raise HTTPException(400, "The upload was empty.")
        try:
            with Image.open(io.BytesIO(data)) as img:
                fmt = img.format
                img.verify()
        except Image.DecompressionBombError as e:
            raise HTTPException(400, "Image dimensions are too large.") from e
        except Exception as e:  # noqa: BLE001
            if Path(filename).suffix.lower() in (".heic", ".heif"):
                raise HTTPException(415, "HEIC/HEIF photos can't be read here. Convert to JPEG first "
                                         "(the Android app does this when it imports).") from e
            raise HTTPException(415, "That file is not a readable image.") from e
        if fmt not in UPLOAD_FORMATS:
            raise HTTPException(415, f"{fmt} images aren't accepted; send JPEG, PNG or WEBP.")

        sha = hashlib.sha256(data).hexdigest()
        existing = db.get_file_by_sha(uploads_folder["id"], sha)
        if existing and Path(existing["path"]).is_file():
            return {**existing, "duplicate": True}

        stem = re.sub(r"[^\w\- ]", "_", Path(filename).stem)[:60].strip(" ._") or "photo"
        target_dir = settings.uploads_dir / time.strftime("%Y-%m")
        target_dir.mkdir(parents=True, exist_ok=True)
        path = (target_dir / f"{stem}-{sha[:8]}{UPLOAD_FORMATS[fmt]}").resolve()
        path.write_bytes(data)
        stat = path.stat()
        file_id = db.upsert_file(
            folder_id=uploads_folder["id"], path=str(path), name=path.name, ext=path.suffix,
            kind=kind_for(path.suffix), size=stat.st_size, mtime=stat.st_mtime, sha256=sha,
            status="pending", error=None, summary=None, indexed_at=None,
        )
        indexer.enqueue_file(uploads_folder["id"], path)
        return {**db.get_file(file_id), "duplicate": False}

    @app.post("/api/uploads", status_code=201)
    async def upload(request: Request, filename: str = "photo.jpg") -> dict[str, Any]:
        """Raw image bytes in the body (no multipart), original name in ?filename=. Indexed in the background."""
        limit = settings.max_upload_mb * 1024 * 1024
        too_big = HTTPException(413, f"Photos must be under {settings.max_upload_mb} MB.")
        if int(request.headers.get("content-length") or 0) > limit:
            raise too_big
        data = bytearray()
        async for chunk in request.stream():
            data += chunk
            if len(data) > limit:
                raise too_big
        return await run_in_threadpool(save_upload, bytes(data), filename)

    # Photos

    def photo_summary(file_id: int, f: dict[str, Any], p: dict[str, Any] | None) -> dict[str, Any]:
        """One gallery tile. Dates are labelled: capture date only when the photo itself records one."""
        p = p or {}
        return {
            "file_id": file_id, "name": f["name"], "caption": display_caption(p, ""), "ai_caption": p.get("caption"),
            "user_caption": p.get("user_caption"),
            "captured_date": p.get("captured_date"), "captured_time": p.get("captured_time"),
            "event_date": p.get("event_date"), "event_title": p.get("event_title"),
            "imported_at": p.get("created_at") or f.get("mtime"), "width": p.get("width"), "height": p.get("height"),
            "stage": p.get("stage") or "pending", "ocr_status": p.get("ocr_status"), "vision_status": p.get("vision_status"),
            "favorite": bool(p.get("favorite")), "error": p.get("error") or f.get("error"),
            "file_status": f["status"], "thumbnail": f"/api/files/{file_id}/thumbnail",
        }

    def photo_or_404(file_id: int) -> tuple[dict[str, Any], dict[str, Any]]:
        f = db.get_file(file_id)
        if f is None or f["kind"] != "image" or db.get_folder(f["folder_id"]) is None:
            raise HTTPException(404, "Photo not found")
        return f, db.ensure_photo(file_id)

    class PhotoEdit(BaseModel):
        """Anything the user types about a photo. Omitted fields stay as they are; empty text clears a field."""
        user_caption: str | None = Field(default=None, max_length=300)
        user_notes: str | None = Field(default=None, max_length=5000)
        user_tags: list[str] | None = None
        event_title: str | None = Field(default=None, max_length=120)
        event_date: str | None = Field(default=None, max_length=10)
        location_label: str | None = Field(default=None, max_length=120)
        people: list[str] | None = None
        favorite: bool | None = None

    class ReanalyzeIn(BaseModel):
        vision: bool = True
        ocr: bool = True

    @app.get("/api/vision")
    def vision_status() -> dict[str, Any]:
        """Can the configured vision model look at photos right now? (Settings and the import screen show this.)"""
        check = getattr(model, "vision_status", None)
        status = check() if check else {"ok": True, "model": getattr(settings, "vision_model", "unknown"), "reason": None, "error": None}
        return {**status, "ocr": {"available": indexer.pipeline.ocr.available(), "engine": indexer.pipeline.ocr.name},
                "prompt_version": PROMPT_VERSION, "local_only": True}

    @app.get("/api/photos")
    def list_photos(q: str = "", sort: str = "captured", order: str = "desc", favorites: bool = False,
                    date_from: str | None = None, date_to: str | None = None, undated: bool = False,
                    stage: str | None = None, limit: int = 60, offset: int = 0) -> dict[str, Any]:
        """The photo gallery: filterable, sortable and paged so a large library never loads at once."""
        for d in (date_from, date_to):
            if d and not _valid_date(d):
                raise HTTPException(400, "Dates must look like 2026-10-31.")
        ids = None
        if q.strip():
            ids = db.file_ids_matching(q.strip())
        rows, total = db.list_photos(
            sort=sort, descending=order != "asc", favorites=favorites, date_from=date_from, date_to=date_to,
            undated=undated, stage=stage, file_ids=ids, name_like=q.strip() or None,
            limit=max(1, min(limit, 200)), offset=max(0, offset),
        )
        items = []
        for r in rows:
            f = {"name": r["name"], "mtime": r["mtime"], "status": r["status"], "error": r["file_error"]}
            p = {k: r[k] for k in ("stage", "error", "captured_date", "captured_time", "width", "height", "caption",
                                    "user_caption", "ocr_status", "vision_status", "favorite", "event_date", "event_title")}
            items.append(photo_summary(r["file_id"], f, p))
        return {"total": total, "offset": offset, "items": items}

    @app.get("/api/photos/status")
    def photos_status(ids: str) -> list[dict[str, Any]]:
        """Compact progress for the photos an import just created (polled by the import screens)."""
        out = []
        for raw in ids.split(",")[:200]:
            if not raw.strip().isdigit():
                continue
            f = db.get_file(int(raw))
            if f is None or f["kind"] != "image":
                continue
            p = db.get_photo(f["id"]) or {}
            out.append({"file_id": f["id"], "stage": p.get("stage") or "pending", "file_status": f["status"],
                        "ocr_status": p.get("ocr_status"), "vision_status": p.get("vision_status"),
                        "error": p.get("error") or f.get("error"), "vision_error": p.get("vision_error")})
        return out

    @app.get("/api/photos/{file_id}")
    def photo_detail(file_id: int) -> dict[str, Any]:
        f, p = photo_or_404(file_id)
        tz_known = bool(p["captured_offset"])
        lat, lon = p["gps_lat"], p["gps_lon"]
        return {
            "file_id": file_id, "name": f["name"], "size": f["size"], "sha256": f["sha256"],
            "mime": mimetypes.guess_type(f["name"])[0] or "application/octet-stream",
            "file_status": f["status"], "stage": p["stage"], "error": p["error"] or f["error"],
            "caption": display_caption(p, f["name"]),
            "dates": {
                # Five different moments, never merged: only "captured" says when the picture was taken.
                "captured": ({"date": p["captured_date"], "time": p["captured_time"], "offset": p["captured_offset"],
                              "timezone_known": tz_known} if p["captured_date"] else None),
                "event_date": p["event_date"],
                "file_modified": f["mtime"],
                "imported_at": p["created_at"],
                "analyzed_at": p["analyzed_at"],
            },
            "location": ({"lat": lat, "lon": lon, "altitude": p["altitude"], "source": "photo"}
                         if lat is not None and lon is not None else None),
            "location_label": p["location_label"],
            "image": {"width": p["width"], "height": p["height"], "orientation": p["orientation"],
                      "format": p["img_format"], "camera_make": p["camera_make"], "camera_model": p["camera_model"]},
            "ocr": {"status": p["ocr_status"], "text": p["ocr_text"], "error": p["ocr_error"]},
            "ai": {
                "status": p["vision_status"], "model": p["vision_model"], "error": p["vision_error"],
                "caption": p["caption"], "description": p["description"], "objects": loads_list(p["objects"]),
                "visible_text": p["visible_text"], "context": p["context"], "summary": p["summary"],
                "uncertainty": p["uncertainty"], "prompt_version": p["prompt_version"],
                "outdated": p["vision_status"] == "ok" and (p["prompt_version"] or 0) < PROMPT_VERSION,
            },
            "user": {
                "caption": p["user_caption"], "notes": p["user_notes"], "tags": loads_list(p["user_tags"]),
                "event_title": p["event_title"], "event_date": p["event_date"], "people": loads_list(p["people"]),
                "location_label": p["location_label"], "favorite": bool(p["favorite"]), "updated_at": p["user_updated_at"],
            },
            "urls": {"thumbnail": f"/api/files/{file_id}/thumbnail", "content": f"/api/files/{file_id}/content",
                     "download": f"/api/files/{file_id}/download"},
        }

    @app.patch("/api/photos/{file_id}")
    def edit_photo(file_id: int, body: PhotoEdit) -> dict[str, Any]:
        """Save the user's own caption, notes, tags, event, place, people and favourite. AI and metadata are untouched."""
        photo_or_404(file_id)
        sent = body.model_dump(exclude_unset=True)
        changes: dict[str, Any] = {}
        for key in ("user_caption", "user_notes", "event_title", "location_label"):
            if key in sent:
                value = (sent[key] or "").strip()
                changes[key] = value or None
        if "event_date" in sent:
            value = (sent["event_date"] or "").strip()
            if value and not _valid_date(value):
                raise HTTPException(400, "The event date must look like 2026-10-31.")
            changes["event_date"] = value or None
        for key, column in (("user_tags", "user_tags"), ("people", "people")):
            if key in sent:
                items = clean_list(sent[key])
                changes[column] = json.dumps(items) if items else None
        if "favorite" in sent and sent["favorite"] is not None:
            changes["favorite"] = 1 if sent["favorite"] else 0
        db.update_photo_user(file_id, **changes)
        if set(changes) - {"favorite"}:
            indexer.enqueue_rebuild(file_id)  # notes and tags become searchable; no AI analysis is repeated
        return photo_detail(file_id)

    @app.post("/api/photos/{file_id}/reanalyze", status_code=202)
    def reanalyze_photo(file_id: int, body: ReanalyzeIn | None = None) -> dict[str, Any]:
        """Run text recognition and/or the vision model again. The user's own edits are kept."""
        photo_or_404(file_id)
        body = body or ReanalyzeIn()
        if not (body.vision or body.ocr):
            raise HTTPException(400, "Choose at least one step to run.")
        indexer.enqueue_photo(file_id, vision=body.vision, ocr=body.ocr)
        return {"queued": True, "file_id": file_id}

    @app.post("/api/photos/reanalyze", status_code=202)
    def reanalyze_many(only: str = "unanalyzed") -> dict[str, Any]:
        """Queue several photos: 'unanalyzed' (never described or only by the older pipeline), 'failed', or 'all'."""
        if only not in ("unanalyzed", "failed", "all"):
            raise HTTPException(400, "only must be unanalyzed, failed or all")
        queued = 0
        rows, _ = db.list_photos(sort="name", limit=200000)
        for r in rows:
            p = db.get_photo(r["file_id"]) or {}
            vs = p.get("vision_status")
            wanted = (only == "all"
                      or (only == "unanalyzed" and vs in (None, "pending", "legacy", "unavailable"))
                      or (only == "failed" and (vs == "failed" or p.get("stage") == "failed" or r["status"] == "error")))
            if wanted:
                indexer.enqueue_photo(r["file_id"])
                queued += 1
        return {"queued": queued}

    @app.get("/api/photos/{file_id}/related")
    def related_photos(file_id: int, limit: int = 6) -> list[dict[str, Any]]:
        """Other memories the existing search finds for this photo's own description (real retrieval, not guesses)."""
        f, p = photo_or_404(file_id)
        text = " ".join(x for x in (p["user_caption"], p["caption"], p["summary"]) if x).strip()
        if not text:
            return []
        try:
            hits = retrieve(db, vectors, model, text[:400], 20)
        except OllamaError:
            return []
        out, seen = [], {file_id}
        for h in hits:
            if h["file_id"] in seen:
                continue
            seen.add(h["file_id"])
            hp = db.get_photo(h["file_id"])
            out.append({"file_id": h["file_id"], "name": h["name"], "kind": h["kind"],
                        "caption": display_caption(hp, "") or h["text"][:120],
                        "captured_date": (hp or {}).get("captured_date")})
            if len(out) >= max(1, min(limit, 20)):
                break
        return out

    # Search

    @app.get("/api/search")
    def search(request: Request, q: str, limit: int = 10) -> list[dict[str, Any]]:
        try:
            hits = retrieve(db, vectors, model, q, min(limit, 50))
        except OllamaError as e:
            raise HTTPException(503, str(e)) from e
        out = [{k: h[k] for k in ("file_id", "name", "path", "kind", "ext", "text", "score")} for h in hits]
        if request.state.remote:
            for h in out:
                h["path"] = h["name"]  # a phone never needs the laptop's folder layout
        return out

    @app.post("/api/ask")
    def ask(request: Request, body: AskIn) -> dict[str, Any]:
        try:
            result = answer_question(db, vectors, model, body.question.strip(), settings.top_k)
        except OllamaError as e:
            raise HTTPException(503, str(e)) from e
        if request.state.remote:
            for src in result["sources"]:
                src["path"] = src["name"]
        return result

    app.mount("/", NoCacheStaticFiles(directory=FRONTEND_DIR, html=True), name="frontend")

    return app


def run() -> None:
    import uvicorn

    parser = argparse.ArgumentParser(prog="python -m memora")
    parser.add_argument("--lan", action="store_true", help="also accept the Android app on this Wi-Fi network")
    parser.add_argument("--port", type=int, help="port (default 8000 or MEMORA_PORT)")
    parser.add_argument("--tailscale", action="store_true",
                        help="with --lan: also accept the paired phone over Tailscale (needs the Tailscale app on both devices)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = Settings()
    if args.lan:
        settings.host = "0.0.0.0"
    if args.port:
        settings.port = args.port
    if args.tailscale:
        settings.allow_tailscale = True
        settings.host = "0.0.0.0"  # Tailscale traffic arrives on its own interface; loopback-only can't receive it
    app = create_app(settings)

    print(f"\n  Memora on this computer: http://127.0.0.1:{settings.port}")
    if settings.lan:
        for addr in lan_addresses():
            print(f"  Phone app address:       {addr}  port {settings.port}")
        print(f"  Pairing code:            {app.state.pairing_code}")
        if settings.allow_tailscale:
            print("  Tailscale:               accepting the paired phone from 100.64.0.0/10 (needs the pairing code)")
        print()
    else:
        print("  Phone access is off. Restart with --lan to connect the Android app.\n")
    # No access log: request lines would record users' questions (?q=) and photo names in the console.
    uvicorn.run(app, host=settings.host, port=settings.port, access_log=False)
