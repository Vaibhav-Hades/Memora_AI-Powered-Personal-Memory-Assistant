"""Who may talk to the server: the laptop itself, or a paired phone on the local network."""

import hmac
import ipaddress
import os
import secrets
import socket
import sys
from pathlib import Path

# No 0/O/1/I/L, so the code is easy to read off a screen and type on a phone.
_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"


def load_pairing_code(data_dir: Path) -> str:
    """The code phones must send (X-Memora-Code header). Created once and kept in the data folder."""
    path = data_dir / "pairing-code.txt"
    if path.is_file():
        code = path.read_text(encoding="utf-8").strip()
        if code:
            return code
    raw = "".join(secrets.choice(_ALPHABET) for _ in range(10))
    code = f"{raw[:5]}-{raw[5:]}"
    path.write_text(code, encoding="utf-8")
    return code


def code_matches(expected: str, given: str | None) -> bool:
    if not given:
        return False
    normalise = lambda c: c.strip().upper().replace("-", "").replace(" ", "")  # noqa: E731
    return hmac.compare_digest(normalise(expected), normalise(given))


def is_loopback(host: str | None) -> bool:
    try:
        return ipaddress.ip_address(host or "").is_loopback
    except ValueError:
        return False


TAILSCALE_V4 = ipaddress.ip_network("100.64.0.0/10")
TAILSCALE_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")


def is_tailscale(host: str | None) -> bool:
    """An address inside Tailscale's reserved ranges (the peer's address as seen through the tailnet)."""
    try:
        ip = ipaddress.ip_address(host or "")
    except ValueError:
        return False
    return ip in (TAILSCALE_V4 if ip.version == 4 else TAILSCALE_V6)


def is_local_network(host: str | None, allow_tailscale: bool = False) -> bool:
    """Private LAN ranges only (192.168.x, 10.x, 172.16-31.x, link-local). Public addresses and other
    VPN/CGNAT ranges are refused. Tailscale's own ranges are accepted only when the owner opted in
    (`--tailscale`), because a tailnet can contain devices the owner doesn't want near their photos."""
    try:
        ip = ipaddress.ip_address(host or "")
    except ValueError:
        return False
    if allow_tailscale and is_tailscale(host):
        return True
    # CGNAT space (100.64/10) is not "private" for the ipaddress module, but check explicitly for clarity.
    return ip.is_private and not is_tailscale(host)


def lan_addresses() -> list[str]:
    """IPv4 addresses a phone on the same network could use to reach this computer."""
    found: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except OSError:
        pass
    try:
        # Connecting a UDP socket sends nothing; it only reveals which interface has the default route.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            found.add(s.getsockname()[0])
    except OSError:
        pass
    ips = [ipaddress.ip_address(a) for a in found]
    return sorted(str(ip) for ip in ips if ip.is_private and not ip.is_loopback and not ip.is_link_local)


def tailscale_addresses() -> list[str]:
    """This computer's own Tailscale IPv4 addresses, for showing in the pairing panel. Empty when Tailscale isn't installed or connected."""
    import shutil
    import subprocess

    found: set[str] = set()
    exe = shutil.which("tailscale")
    if exe:
        try:
            out = subprocess.run([exe, "ip", "-4"], capture_output=True, text=True, timeout=3).stdout
            found.update(line.strip() for line in out.splitlines())
        except (OSError, subprocess.SubprocessError):
            pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except OSError:
        pass
    return sorted(a for a in found if is_tailscale(a) and ipaddress.ip_address(a).version == 4)


def suggested_folders() -> list[dict[str, str]]:
    """The user's Pictures, Documents and Downloads folders, wherever Windows has redirected them."""
    found = {}
    if sys.platform == "win32":
        import winreg

        names = {
            "Pictures": "My Pictures",
            "Documents": "Personal",
            "Downloads": "{374DE290-123F-4565-9164-39C4925E467B}",
        }
        try:
            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders"
            ) as key:
                for label, value in names.items():
                    try:
                        found[label] = os.path.expandvars(winreg.QueryValueEx(key, value)[0])
                    except OSError:
                        pass
        except OSError:
            pass
    home = Path.home()
    for label in ("Pictures", "Documents", "Downloads"):
        found.setdefault(label, str(home / label))
    return [{"name": label, "path": str(Path(p).resolve())} for label, p in found.items() if Path(p).is_dir()]
