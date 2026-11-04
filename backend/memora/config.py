"""Runtime settings, read from environment variables with local-first defaults."""

import os
from dataclasses import dataclass, field
from pathlib import Path


def _default_data_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "data"


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _ollama_url() -> str:
    """MEMORA_OLLAMA_URL wins, then Ollama's own OLLAMA_BASE_URL, then the local default."""
    return os.environ.get("MEMORA_OLLAMA_URL") or os.environ.get("OLLAMA_BASE_URL") or "http://127.0.0.1:11434"


# Fields read the environment when Settings() is created (not at import time), so tests and callers can override it.
@dataclass
class Settings:
    data_dir: Path = field(default_factory=lambda: Path(_env("MEMORA_DATA_DIR", str(_default_data_dir()))))
    ollama_url: str = field(default_factory=_ollama_url)
    chat_model: str = field(default_factory=lambda: _env("MEMORA_CHAT_MODEL", "gemma4:e4b"))
    embed_model: str = field(default_factory=lambda: _env("MEMORA_EMBED_MODEL", "embeddinggemma"))
    # Gemma 4 "thinking" makes replies ~10x slower; off by default.
    think: bool = field(default_factory=lambda: _env("MEMORA_THINK", "0") == "1")
    # Context window for answering; Ollama's 4096 default is tight for 8 evidence chunks.
    num_ctx: int = field(default_factory=lambda: int(_env("MEMORA_NUM_CTX", "8192")))
    # How long Ollama keeps a model in memory after use; a long value avoids reloading it mid-demo.
    keep_alive: str = field(default_factory=lambda: _env("MEMORA_KEEP_ALIVE", "30m"))
    # Chunking of extracted text, in characters.
    chunk_size: int = field(default_factory=lambda: int(_env("MEMORA_CHUNK_SIZE", "1200")))
    chunk_overlap: int = field(default_factory=lambda: int(_env("MEMORA_CHUNK_OVERLAP", "200")))
    # How many chunks are handed to the model as evidence for one answer.
    top_k: int = field(default_factory=lambda: int(_env("MEMORA_TOP_K", "8")))
    # Network: 127.0.0.1 keeps Memora on this computer; `python -m memora --lan` uses 0.0.0.0 for phones.
    host: str = field(default_factory=lambda: _env("MEMORA_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: int(_env("MEMORA_PORT", "8000")))
    # Also accept the paired phone over Tailscale (100.64.0.0/10). Off by default; `--tailscale` or MEMORA_ALLOW_TAILSCALE=1.
    allow_tailscale: bool = field(default_factory=lambda: _env("MEMORA_ALLOW_TAILSCALE", "0") == "1")
    # Largest photo a phone may upload.
    max_upload_mb: int = field(default_factory=lambda: int(_env("MEMORA_MAX_UPLOAD_MB", "20")))
    # Files larger than this are skipped during indexing.
    max_file_mb: int = field(default_factory=lambda: int(_env("MEMORA_MAX_FILE_MB", "50")))
    # Model that looks at photos. Defaults to the chat model, but is only used if Ollama reports it can take images.
    vision_model: str = field(default_factory=lambda: os.environ.get("MEMORA_VISION_MODEL") or _env("MEMORA_CHAT_MODEL", "gemma4:e4b"))
    # Seconds one photo may take to analyse, and the longest side it is shrunk to first (the original is never changed).
    vision_timeout: int = field(default_factory=lambda: int(_env("MEMORA_VISION_TIMEOUT", "300")))
    vision_max_side: int = field(default_factory=lambda: int(_env("MEMORA_VISION_MAX_SIDE", "1280")))
    # Text recognition for photos: "auto" uses Windows' built-in OCR when present, "off" never runs it.
    ocr: str = field(default_factory=lambda: _env("MEMORA_OCR", "auto"))
    # Scanned PDFs: how many pages without a text layer get rendered and described.
    max_pdf_image_pages: int = field(default_factory=lambda: int(_env("MEMORA_MAX_PDF_IMAGE_PAGES", "5")))

    @property
    def db_path(self) -> Path:
        return self.data_dir / "memora.sqlite3"

    @property
    def uploads_dir(self) -> Path:
        return self.data_dir / "phone-uploads"

    @property
    def lan(self) -> bool:
        return self.host not in ("127.0.0.1", "localhost", "::1")


IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".tif", ".tiff"}
DOC_EXTS = {".pdf", ".docx", ".txt", ".md", ".csv", ".json", ".log"}
SUPPORTED_EXTS = IMAGE_EXTS | DOC_EXTS
