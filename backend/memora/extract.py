"""Turn a local file into searchable text. Images and scanned PDF pages go through the local vision model."""

import csv
import io
import json
from pathlib import Path
from typing import Protocol

from .config import IMAGE_EXTS


class Describer(Protocol):
    def describe_image(self, data: bytes) -> str: ...


class MissingDependency(RuntimeError):
    """An optional library this file type needs is not installed. The message says what to do."""


def _need(module: str, package: str, what: str):
    import importlib

    try:
        return importlib.import_module(module)
    except ImportError as e:
        raise MissingDependency(f"{what} needs the '{package}' package, which is not installed. Run: pip install -r requirements.txt") from e


def missing_extractors() -> list[str]:
    """Packages that PDF/DOCX reading needs but this Python does not have (reported by /api/health)."""
    import importlib.util

    needs = {"pypdf": "pypdf", "pypdfium2": "pypdfium2", "docx": "python-docx"}
    return sorted(pkg for mod, pkg in needs.items() if importlib.util.find_spec(mod) is None)


def kind_for(ext: str) -> str:
    return "image" if ext in IMAGE_EXTS else "document"


def extract_text(path: Path, describer: Describer, max_pdf_image_pages: int = 5) -> str:
    ext = path.suffix.lower()
    if ext in IMAGE_EXTS:
        return describer.describe_image(path.read_bytes())
    if ext == ".pdf":
        return _pdf_text(path, describer, max_pdf_image_pages)
    if ext == ".docx":
        return _docx_text(path)
    if ext == ".csv":
        return _csv_text(path)
    if ext == ".json":
        return json.dumps(json.loads(_read_text(path)), indent=1, ensure_ascii=False)
    return _read_text(path)


def _read_text(path: Path) -> str:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "utf-16", "cp1252"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def _pdf_text(path: Path, describer: Describer, max_image_pages: int) -> str:
    PdfReader = _need("pypdf", "pypdf", "Reading PDFs").PdfReader

    reader = PdfReader(str(path))
    pages = []
    scanned = []
    for i, page in enumerate(reader.pages):
        text = (page.extract_text() or "").strip()
        if len(text) < 20:
            scanned.append(i)
        pages.append(text)

    # Pages with no text layer are probably scans: render them and let the vision model read them.
    if scanned and max_image_pages > 0:
        pdfium = _need("pypdfium2", "pypdfium2", "Reading scanned PDF pages")

        pdf = pdfium.PdfDocument(str(path))
        try:
            for i in scanned[:max_image_pages]:
                bitmap = pdf[i].render(scale=2).to_pil()
                buf = io.BytesIO()
                bitmap.save(buf, format="PNG")
                pages[i] = describer.describe_image(buf.getvalue())
        finally:
            pdf.close()

    return "\n\n".join(f"[Page {i + 1}]\n{t}" for i, t in enumerate(pages) if t)


def _docx_text(path: Path) -> str:
    docx = _need("docx", "python-docx", "Reading Word documents")

    doc = docx.Document(str(path))
    parts = [p.text for p in doc.paragraphs if p.text.strip()]
    for table in doc.tables:
        for row in table.rows:
            parts.append(" | ".join(cell.text.strip() for cell in row.cells))
    return "\n".join(parts)


def _csv_text(path: Path) -> str:
    rows = list(csv.reader(io.StringIO(_read_text(path))))
    if not rows:
        return ""
    header, body = rows[0], rows[1:]
    lines = [" | ".join(header)]
    for row in body:
        lines.append("; ".join(f"{h}: {v}" for h, v in zip(header, row)))
    return "\n".join(lines)


def chunk_text(text: str, size: int, overlap: int) -> list[str]:
    """Split text into overlapping windows, preferring paragraph and sentence boundaries."""
    text = text.strip()
    if not text:
        return []
    if len(text) <= size:
        return [text]
    chunks = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        if end < len(text):
            window = text[start:end]
            for sep in ("\n\n", "\n", ". ", " "):
                cut = window.rfind(sep)
                if cut > size // 2:
                    end = start + cut + len(sep)
                    break
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= len(text):
            break
        start = max(end - overlap, start + 1)
    return chunks
