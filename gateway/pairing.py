"""
Gateway <-> smartphone pairing and message authentication (see PROTOCOL.md).

Each paired phone shares its own random 32-byte secret with the gateway. Every control
message (UDP discovery, HTTP requests and responses) carries an HMAC-SHA256 signature
made with that secret; the secret itself only ever travels inside the pairing QR code.
"""

import base64
import contextlib
import fcntl
import hashlib
import hmac
import json
import os
import secrets
import socket
import tempfile
import unicodedata
import urllib.parse
from dataclasses import dataclass

PROTOCOL_VERSION = 1
LINK_SCHEME = "wanfailover"
SERVICE_NAME = "wan-failover"

# HTTP headers carrying the authentication fields
HEADER_GATEWAY = "X-WF-Gateway"
HEADER_PHONE = "X-WF-Phone"
HEADER_CHALLENGE = "X-WF-Challenge"
HEADER_NONCE = "X-WF-Nonce"
HEADER_SIGNATURE = "X-WF-Signature"

ID_BYTES = 16
KEY_BYTES = 32


def b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    """Strict base64url (no padding) decoding; raises ValueError on anything else."""
    if not isinstance(text, str) or not text or any(c not in _B64URL_ALPHABET for c in text):
        raise ValueError("Invalid base64url value")
    raw = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    if b64url_encode(raw) != text:
        raise ValueError("Non-canonical base64url value")
    return raw


_B64URL_ALPHABET = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")


def new_id() -> str:
    return b64url_encode(secrets.token_bytes(ID_BYTES))


def new_key() -> bytes:
    return secrets.token_bytes(KEY_BYTES)


def body_hash(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


# --- Signed messages: each one is its fields joined by newlines, starting with its type ---

def request_fields(gateway_id: str, phone_id: str, method: str, path: str, challenge: str, nonce: str, body: bytes) -> list[str]:
    return ["wf1-req", gateway_id, phone_id, method, path, challenge, nonce, body_hash(body)]


def response_fields(gateway_id: str, phone_id: str, nonce: str, status: int, body: bytes) -> list[str]:
    return ["wf1-resp", gateway_id, phone_id, nonce, str(status), body_hash(body)]


def discovery_fields(gateway_id: str, phone_id: str, phone_ip: str) -> list[str]:
    return ["wf1-disc", gateway_id, phone_id, phone_ip]


STANDBY_HOT = "hot"
STANDBY_COLD = "cold"


def standby_fields(gateway_id: str, phone_id: str, phone_ip: str, standby: str) -> list[str]:
    """Signed apart from the discovery entry, so older gateways and phones simply ignore it."""
    return ["wf1-standby", gateway_id, phone_id, phone_ip, standby]


def replace_fields(gateway_id: str, old_phone_id: str, new_phone_id: str) -> list[str]:
    return ["wf1-replace", gateway_id, old_phone_id, new_phone_id]


def pairing_code_fields(gateway_id: str, phone_id: str) -> list[str]:
    return ["wf1-code", gateway_id, phone_id]


def pairing_code(gateway_id: str, phone_id: str, key: bytes) -> str:
    """
    Short code shown by `wanctl pair` and by the phone before it confirms: matching codes
    prove the phone is pairing with the QR code this gateway displayed, not another link.
    """
    digest = b64url_decode(sign(key, pairing_code_fields(gateway_id, phone_id)))
    code = digest[:4].hex().upper()
    return f"{code[:4]}-{code[4:]}"


def sign(key: bytes, fields: list[str]) -> str:
    if any("\n" in f for f in fields):
        raise ValueError("Signed fields must not contain newlines")
    return b64url_encode(hmac.new(key, "\n".join(fields).encode("utf-8"), hashlib.sha256).digest())


def verify(key: bytes, signature: object, fields: list[str]) -> bool:
    if not isinstance(signature, str):
        return False
    try:
        expected = sign(key, fields)
    except ValueError:
        return False
    return hmac.compare_digest(expected, signature)


def build_pair_link(gateway_id: str, gateway_name: str, phone_id: str, key: bytes) -> str:
    query = urllib.parse.urlencode(
        {"v": PROTOCOL_VERSION, "gw": gateway_id, "gn": gateway_name, "pid": phone_id, "k": b64url_encode(key)},
        quote_via=urllib.parse.quote,
    )
    return f"{LINK_SCHEME}://pair?{query}"


def default_gateway_name() -> str:
    return os.environ.get("GATEWAY_NAME", "").strip() or socket.gethostname()


def sanitize_label(value: object, max_length: int = 64) -> str:
    """Display text received from a phone: no control characters (terminal escapes), bounded length."""
    if not isinstance(value, str):
        return ""
    cleaned = "".join(c for c in value if unicodedata.category(c)[0] != "C")
    return cleaned.strip()[:max_length]


@dataclass(frozen=True)
class PairedPhone:
    id: str
    name: str
    key: bytes
    ssid: str
    paired_at: float

    def to_json(self) -> dict:
        return {"id": self.id, "name": self.name, "key": b64url_encode(self.key), "ssid": self.ssid, "paired_at": self.paired_at}

    @staticmethod
    def from_json(data: dict) -> "PairedPhone":
        return PairedPhone(
            id=data["id"],
            name=data.get("name", ""),
            key=b64url_decode(data["key"]),
            ssid=data.get("ssid", ""),
            paired_at=float(data.get("paired_at", 0)),
        )

    @property
    def label(self) -> str:
        return self.name or self.id


class PairingStore:
    """
    <config_dir>/pairing.json: this gateway's ID and its paired phones (with their secrets).
    `wanctl pair/unpair` write it; the watchdog reads it and picks up changes on the fly.
    """

    FILE_NAME = "pairing.json"

    def __init__(self, config_dir: str):
        self.config_dir = config_dir
        self.path = os.path.join(config_dir, self.FILE_NAME)
        self._lock_path = self.path + ".lock"
        self._cache: dict | None = None
        self._cache_stamp: tuple[int, int] | None = None

    @contextlib.contextmanager
    def _locked(self):
        os.makedirs(self.config_dir, mode=0o700, exist_ok=True)
        fd = os.open(self._lock_path, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)

    def _stamp(self) -> tuple[int, int] | None:
        try:
            st = os.stat(self.path)
        except FileNotFoundError:
            return None
        return (st.st_ino, st.st_mtime_ns)

    def _load(self) -> dict:
        stamp = self._stamp()
        if self._cache is not None and stamp == self._cache_stamp:
            return self._cache
        if stamp is None:
            data = {"gateway_id": None, "phones": []}
        else:
            with open(self.path, "r", encoding="utf-8") as f:
                data = json.load(f)
        self._cache, self._cache_stamp = data, stamp
        return data

    def _save(self, data: dict):
        # Atomic replace so a reader never sees a partial file; created 0600 (it holds secrets)
        fd, tmp_path = tempfile.mkstemp(dir=self.config_dir, prefix=".pairing-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, self.path)
        except BaseException:
            with contextlib.suppress(FileNotFoundError):
                os.unlink(tmp_path)
            raise
        self._cache, self._cache_stamp = None, None

    def gateway_id(self) -> str:
        """This gateway's stable ID, created on first use."""
        existing = self._load().get("gateway_id")
        if existing:
            return existing
        with self._locked():
            data = self._load()
            if not data.get("gateway_id"):
                data = {**data, "gateway_id": new_id()}
                self._save(data)
            return data["gateway_id"]

    def phones(self) -> list[PairedPhone]:
        return [PairedPhone.from_json(p) for p in self._load().get("phones", [])]

    def get(self, phone_id: str) -> PairedPhone | None:
        return next((p for p in self.phones() if p.id == phone_id), None)

    def add(self, phone: PairedPhone, replaces: str | None = None):
        """Saves a newly paired phone, dropping the earlier pairing of that same phone ([replaces])."""
        with self._locked():
            data = self._load()
            drop = {phone.id, replaces}
            phones = [p for p in data.get("phones", []) if p["id"] not in drop]
            phones.append(phone.to_json())
            self._save({**data, "phones": phones})

    def find(self, selector: str) -> list[PairedPhone]:
        """Phones whose ID or name matches [selector] (name: case-insensitive)."""
        wanted = selector.strip().casefold()
        return [p for p in self.phones() if p.id == selector or p.name.casefold() == wanted]

    def remove(self, phone_id: str) -> bool:
        with self._locked():
            data = self._load()
            phones = [p for p in data.get("phones", []) if p["id"] != phone_id]
            if len(phones) == len(data.get("phones", [])):
                return False
            self._save({**data, "phones": phones})
            return True
