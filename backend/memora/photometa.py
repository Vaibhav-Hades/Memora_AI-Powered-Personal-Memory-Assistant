"""What a photo's own metadata says: when it was taken, where, and how big. Nothing is inferred or looked up online."""

from __future__ import annotations

import re
from typing import Any

from PIL import Image

_EXIF_IFD, _GPS_IFD = 0x8769, 0x8825
_DATETIME_ORIGINAL, _DATETIME_DIGITIZED, _OFFSET_ORIGINAL = 36867, 36868, 36880
_STAMP = re.compile(r"^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$")
_OFFSET = re.compile(r"^[+-](0\d|1\d|2[0-3]):[0-5]\d$")


def parse_exif_datetime(value: Any, offset: Any = None) -> dict[str, str | None] | None:
    """EXIF "YYYY:MM:DD HH:MM:SS" as written by the camera (no time-zone conversion). None for blank or impossible values."""
    m = _STAMP.match(str(value or "").strip())
    if not m or m.group(1) == "0000":
        return None
    y, mo, d, h, mi, s = (int(x) for x in m.groups())
    if not (1 <= mo <= 12 and 1 <= d <= 31 and h < 24 and mi < 60 and s < 60):
        return None
    tz = str(offset).strip() if offset is not None and _OFFSET.match(str(offset).strip()) else None
    return {"date": f"{y:04d}-{mo:02d}-{d:02d}", "time": f"{h:02d}:{mi:02d}:{s:02d}", "offset": tz}


def _text(value: Any) -> str | None:
    """A camera make/model string with the padding and control characters cameras leave in, or None."""
    if isinstance(value, bytes):
        value = value.decode("ascii", errors="ignore")
    cleaned = "".join(c for c in str(value or "") if c.isprintable()).strip()
    return cleaned[:80] or None


def _degrees(value: Any, ref: Any) -> float | None:
    try:
        d, m, s = (float(x) for x in value)
    except (TypeError, ValueError):
        return None
    deg = d + m / 60 + s / 3600
    return -deg if str(ref).upper() in ("S", "W") else deg


def read_photo_meta(path: str) -> dict[str, Any]:
    """Capture time, GPS position and pixel size from the file itself. Every field is None when the file doesn't record it."""
    out: dict[str, Any] = {"captured": None, "location": None, "width": None, "height": None,
                           "altitude": None, "orientation": None, "camera_make": None, "camera_model": None,
                           "format": None}
    try:
        with Image.open(path) as img:
            out["width"], out["height"] = img.size
            out["format"] = img.format
            exif = img.getexif()
            out["camera_make"] = _text(exif.get(271)) if exif else None
            out["camera_model"] = _text(exif.get(272)) if exif else None
            orientation = exif.get(274) if exif else None
            out["orientation"] = orientation if isinstance(orientation, int) and 1 <= orientation <= 8 else None
            ifd = exif.get_ifd(_EXIF_IFD) if exif else {}
            taken = ifd.get(_DATETIME_ORIGINAL) or ifd.get(_DATETIME_DIGITIZED)
            out["captured"] = parse_exif_datetime(taken, ifd.get(_OFFSET_ORIGINAL))
            gps = exif.get_ifd(_GPS_IFD) if exif else {}
            lat = _degrees(gps.get(2), gps.get(1))
            lon = _degrees(gps.get(4), gps.get(3))
            if lat is not None and lon is not None and abs(lat) <= 90 and abs(lon) <= 180 and (lat, lon) != (0.0, 0.0):
                out["location"] = {"lat": round(lat, 5), "lon": round(lon, 5)}
                try:
                    alt = float(gps.get(6))
                    out["altitude"] = round(-alt if gps.get(5) in (1, b"\x01") else alt, 1)
                except (TypeError, ValueError):
                    pass
    except Exception:  # not an image, or unreadable: report "not available"
        pass
    return out
