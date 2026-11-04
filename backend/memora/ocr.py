"""Text recognition for photos, separate from what a vision model says about them.

On Windows this uses the OCR engine built into the operating system (Windows.Media.Ocr) through a short PowerShell
script, so nothing extra is installed and nothing leaves this computer. Elsewhere, or if it is switched off, the engine
reports "unavailable" and photos are still imported, viewed and described.
"""

import json
import logging
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageOps

log = logging.getLogger("memora.ocr")

SCRIPT = Path(__file__).with_name("winocr.ps1")
MAX_SIDE = 3000  # larger photos are shrunk for recognition only
TIMEOUT = 60


class OcrUnavailable(RuntimeError):
    pass


class WindowsOcr:
    name = "windows"

    def __init__(self) -> None:
        self._ok: bool | None = None

    def available(self) -> bool:
        if self._ok is None:
            self._ok = sys.platform == "win32" and SCRIPT.is_file()
        return self._ok

    def read(self, path: str) -> str:
        """Text found in the image, line by line ("" when there is none). Raises OcrUnavailable or RuntimeError."""
        if not self.available():
            raise OcrUnavailable("Text recognition isn't available on this computer.")
        with Image.open(path) as img:
            img = ImageOps.exif_transpose(img).convert("RGB")
            img.thumbnail((MAX_SIDE, MAX_SIDE))
            # A temporary PNG: the engine needs a file it can decode, and the original must stay untouched.
            fd, tmp = tempfile.mkstemp(suffix=".png", prefix="memora-ocr-")
            os.close(fd)
            try:
                img.save(tmp, format="PNG")
                proc = subprocess.run(
                    ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT),
                     "-ImagePath", tmp],
                    capture_output=True, text=True, timeout=TIMEOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
            except subprocess.TimeoutExpired as e:
                raise RuntimeError("Text recognition timed out.") from e
            finally:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
        try:
            result = json.loads(proc.stdout.strip().splitlines()[-1])
        except (IndexError, ValueError) as e:
            raise RuntimeError(f"Text recognition returned no result: {proc.stderr.strip()[:200]}") from e
        if not result.get("ok"):
            raise RuntimeError(str(result.get("error") or "Text recognition failed")[:300])
        return str(result.get("text") or "").strip()


class NoOcr:
    name = "none"

    def available(self) -> bool:
        return False

    def read(self, path: str) -> str:
        raise OcrUnavailable("Text recognition is switched off.")


def default_ocr(mode: str):
    return NoOcr() if mode == "off" else WindowsOcr()
