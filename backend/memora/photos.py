"""The photo pipeline: facts from the file, text recognition, a vision-model description, and the searchable text
that all of it (plus the user's own notes) becomes.

A step failing never loses the photo: the original stays stored and viewable, the failure is recorded on the row,
and a retry redoes only what is missing. Nothing here invents a value: unknown stays unknown.
"""

import json
import logging
import time
from pathlib import Path
from typing import Any

from .config import Settings
from .db import Database
from .extract import chunk_text
from .ollama_client import OllamaError
from .photometa import read_photo_meta
from .vision import PROMPT_VERSION

log = logging.getLogger("memora.photos")

MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]
OCR_CHARS = 6000  # a page of fine print must not drown the caption in the index


def loads_list(value: str | None) -> list[str]:
    try:
        data = json.loads(value) if value else []
    except ValueError:
        return []
    return [str(x) for x in data] if isinstance(data, list) else []


def clean_list(values: Any, limit: int = 30, width: int = 60) -> list[str]:
    """User-typed tags or people: trimmed, de-duplicated (case-insensitively), bounded."""
    if isinstance(values, str):
        values = values.split(",")
    out, seen = [], set()
    for v in values or []:
        v = " ".join(str(v).split())[:width]
        if v and v.lower() not in seen:
            seen.add(v.lower())
            out.append(v)
    return out[:limit]


def _norm(text: str) -> str:
    return " ".join("".join(c.lower() if c.isalnum() else " " for c in text).split())


def date_words(iso: str | None) -> str:
    """'2026-10-14' -> '2026-10-14 (14 October 2026)' so a question like 'photos from October' can match the index."""
    try:
        y, m, d = (int(x) for x in str(iso).split("-"))
        return f"{iso} ({d} {MONTHS[m - 1]} {y})"
    except (ValueError, IndexError):
        return ""


def display_caption(photo: dict[str, Any] | None, fallback: str) -> str:
    """What the gallery shows as the title: the user's caption wins, then the AI's, then the file name."""
    if photo:
        for key in ("user_caption", "caption"):
            if (photo.get(key) or "").strip():
                return photo[key].strip()
    return fallback


def build_chunks(name: str, photo: dict[str, Any], chunk_size: int, overlap: int) -> list[str]:
    """The searchable text for one photo. Machine text and the user's own words are labelled apart."""
    lines = [f"Photo: {name}"]
    if (photo.get("user_caption") or "").strip():
        lines.append(f"Caption (written by you): {photo['user_caption'].strip()}")
    if (photo.get("caption") or "").strip():
        lines.append(f"Caption: {photo['caption'].strip()}")
    if (photo.get("summary") or "").strip() and photo["summary"] != photo.get("caption"):
        lines.append(f"Summary: {photo['summary'].strip()}")
    if (photo.get("description") or "").strip():
        lines.append(f"Description: {photo['description'].strip()}")
    objects = loads_list(photo.get("objects"))
    if objects:
        lines.append("Objects: " + ", ".join(objects))
    if (photo.get("context") or "").strip():
        lines.append(f"Context: {photo['context'].strip()}")

    ocr = (photo.get("ocr_text") or "").strip()[:OCR_CHARS]
    seen = (photo.get("visible_text") or "").strip()[:OCR_CHARS]
    if ocr:
        lines.append("Text read from the photo (OCR):\n" + ocr)
    # The model's own reading is indexed only when it adds something the OCR didn't find.
    if seen and _norm(seen) not in _norm(ocr):
        lines.append("Text the AI read in the photo:\n" + seen)

    if (photo.get("user_notes") or "").strip():
        lines.append("Notes (written by you): " + photo["user_notes"].strip())
    tags = loads_list(photo.get("user_tags"))
    if tags:
        lines.append("Tags: " + ", ".join(tags))
    if photo.get("event_title"):
        lines.append(f"Event: {photo['event_title']}" + (f" ({date_words(photo['event_date'])})" if photo.get("event_date") else ""))
    elif photo.get("event_date"):
        lines.append(f"Event date (set by you): {date_words(photo['event_date'])}")
    if photo.get("location_label"):
        lines.append(f"Place (set by you): {photo['location_label']}")
    people = loads_list(photo.get("people"))
    if people:
        lines.append("People (labelled by you): " + ", ".join(people))

    if photo.get("captured_date"):
        when = date_words(photo["captured_date"])
        lines.append(f"Captured: {when}" + (f" {photo['captured_time']}" if photo.get("captured_time") else ""))
    else:
        lines.append("Captured: date not recorded in the photo")
    camera = " ".join(x for x in (photo.get("camera_make"), photo.get("camera_model")) if x)
    if camera:
        lines.append(f"Camera: {camera}")
    return chunk_text("\n".join(lines), chunk_size, overlap) or [f"Photo: {name}"]


class PhotoPipeline:
    """Runs the steps for one photo file and writes the results to its `photos` row."""

    def __init__(self, settings: Settings, db: Database, model: Any, ocr: Any):
        self.settings, self.db, self.model, self.ocr = settings, db, model, ocr

    # Steps -------------------------------------------------------------------------------------------------------

    def read_facts(self, file: dict[str, Any]) -> dict[str, Any]:
        meta = read_photo_meta(file["path"])
        cap = meta["captured"] or {}
        loc = meta["location"] or {}
        facts = dict(
            captured_date=cap.get("date"), captured_time=cap.get("time"), captured_offset=cap.get("offset"),
            gps_lat=loc.get("lat"), gps_lon=loc.get("lon"), altitude=meta.get("altitude"),
            width=meta["width"], height=meta["height"], orientation=meta.get("orientation"),
            camera_make=meta.get("camera_make"), camera_model=meta.get("camera_model"), img_format=meta.get("format"),
        )
        self.db.update_photo(file["id"], **facts)
        return facts

    def run_ocr(self, file: dict[str, Any]) -> None:
        fid = file["id"]
        if not self.ocr.available():
            self.db.update_photo(fid, ocr_status="unavailable", ocr_text=None, ocr_error=None)
            return
        try:
            text = self.ocr.read(file["path"])
        except Exception as e:  # noqa: BLE001 - OCR must never stop a photo from appearing
            log.warning("OCR failed for file %s: %s", fid, e)
            self.db.update_photo(fid, ocr_status="failed", ocr_error=str(e)[:300])
            return
        self.db.update_photo(fid, ocr_status="done" if text else "empty", ocr_text=text or None, ocr_error=None)

    def run_vision(self, file: dict[str, Any]) -> None:
        fid = file["id"]
        analyze = getattr(self.model, "analyze_image", None)
        try:
            if analyze is not None:
                result = analyze(Path(file["path"]).read_bytes())
            else:  # a plain-text describer (older runtime): kept as the description, with no objects or fields
                text = self.model.describe_image(Path(file["path"]).read_bytes())
                result = dict(caption=text.split(". ")[0][:240], description=text, objects=[], visible_text="",
                              context="", summary=text[:500], uncertainty="")
        except OllamaError as e:
            reason = "unavailable" if _unavailable(str(e)) else "failed"
            log.warning("Vision analysis %s for file %s: %s", reason, fid, e)
            self.db.update_photo(fid, vision_status=reason, vision_error=str(e)[:400],
                                 vision_model=getattr(self.settings, "vision_model", None))
            return
        except Exception as e:  # noqa: BLE001
            log.warning("Vision analysis failed for file %s: %s", fid, e)
            self.db.update_photo(fid, vision_status="failed", vision_error=str(e)[:400])
            return
        model_name = result.pop("model", None) or getattr(self.settings, "vision_model", None)
        self.db.update_photo(
            fid, vision_status="ok", vision_error=None, vision_model=model_name,
            analyzed_at=time.time(), prompt_version=PROMPT_VERSION, caption=result["caption"],
            description=result["description"], objects=json.dumps(result["objects"]),
            visible_text=result["visible_text"] or None, context=result["context"] or None,
            summary=result["summary"], uncertainty=result["uncertainty"] or None,
        )

    # Whole runs --------------------------------------------------------------------------------------------------

    def process(self, file: dict[str, Any], *, vision: bool = True, ocr: bool = True) -> list[str]:
        """Run the selected steps and return the photo's searchable chunks. Never raises for a failed AI step."""
        fid = file["id"]
        self.db.ensure_photo(fid)
        self.db.update_photo(fid, stage="metadata", error=None)
        try:
            self.read_facts(file)
        except Exception as e:  # noqa: BLE001
            self.db.update_photo(fid, stage="failed", error=f"Couldn't read the photo: {e}"[:300])
            raise
        if ocr:
            self.db.update_photo(fid, stage="ocr")
            self.run_ocr(file)
        if vision:
            self.db.update_photo(fid, stage="vision")
            self.run_vision(file)
        return self.finish(file)

    def finish(self, file: dict[str, Any]) -> list[str]:
        """Rebuild the searchable text from what is stored (no AI calls) and mark the photo ready."""
        photo = self.db.get_photo(file["id"])
        self.db.update_photo(file["id"], stage="indexing")
        return build_chunks(file["name"], photo, self.settings.chunk_size, self.settings.chunk_overlap)

    def mark_ready(self, file_id: int) -> None:
        self.db.update_photo(file_id, stage="ready", error=None)


def _unavailable(message: str) -> bool:
    low = message.lower()
    return any(s in low for s in ("isn't installed", "can't read images", "cannot reach ollama", "could not run the model"))
