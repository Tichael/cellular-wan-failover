#!/usr/bin/env python3
"""
Cellular WAN Failover Watchdog (WireGuard + Policy Routing)
Monitors WAN 1 (Fiber/Cable) connectivity via eth0 and automatically fails over
to the Android cellular relay via WireGuard when an outage is detected.
"""

import argparse
import base64
import dataclasses
import ipaddress
import json
import logging
import os
import select
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

import pairing

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("wan-watchdog")

USER_AGENT = "Cellular-WAN-Gateway/1.0"


class PhoneAuthError(Exception):
    """The phone rejected our signature, or its answer is not signed by the paired phone."""


class StandbyDeclined(Exception):
    """The phone's signed status says it doesn't offer hot standby (anymore)."""


class PhoneRequestError(Exception):
    """The phone answered (with a valid signature) but refused the request."""

    def __init__(self, status: int, message: str | None):
        super().__init__(f"HTTP {status}: {message or 'no details'}")
        self.status = status


class AndroidFailoverClient:
    """
    Signed HTTP client for the Android app's local API (port 8989), for one paired phone.
    Each request is bound to a fresh single-use challenge from the phone, and each answer
    must be signed by the phone for our nonce, so neither can be replayed (see PROTOCOL.md).
    """

    def __init__(self, phone_ip: str, phone_id: str, key: bytes, gateway_id: str, http_port: int = 8989, timeout: float = 5.0):
        self.phone_ip = phone_ip
        self.phone_id = phone_id
        self.key = key
        self.gateway_id = gateway_id
        self.http_port = http_port
        self.timeout = timeout

    @property
    def base_url(self) -> str:
        return f"http://{self.phone_ip}:{self.http_port}"

    def _get_challenge(self, timeout: float) -> str:
        req = urllib.request.Request(f"{self.base_url}/v1/challenge", headers={"User-Agent": USER_AGENT}, method="GET")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            challenge = json.loads(resp.read().decode("utf-8")).get("challenge")
        if not isinstance(challenge, str) or not challenge:
            raise PhoneAuthError("Phone did not return a challenge")
        return challenge

    def _send_once(self, path: str, method: str, body: bytes, timeout: float) -> tuple[int, bytes, str | None, str]:
        challenge = self._get_challenge(timeout)
        nonce = pairing.new_id()
        fields = pairing.request_fields(self.gateway_id, self.phone_id, method, path, challenge, nonce, body)
        headers = {
            "User-Agent": USER_AGENT,
            "Content-Type": "application/json",
            pairing.HEADER_GATEWAY: self.gateway_id,
            pairing.HEADER_PHONE: self.phone_id,
            pairing.HEADER_CHALLENGE: challenge,
            pairing.HEADER_NONCE: nonce,
            pairing.HEADER_SIGNATURE: pairing.sign(self.key, fields),
        }
        req = urllib.request.Request(f"{self.base_url}{path}", data=body if method == "POST" else None, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, resp.read(), resp.headers.get(pairing.HEADER_SIGNATURE), nonce
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers.get(pairing.HEADER_SIGNATURE), nonce

    def _request(self, path: str, method: str = "GET", body: bytes = b"", timeout: float | None = None, attempts: int = 3) -> dict:
        t = timeout or self.timeout
        for attempt in range(attempts):
            try:
                status, raw, signature, nonce = self._send_once(path, method, body, t)
                break
            except (urllib.error.URLError, ConnectionError, OSError, ValueError):
                if attempt == attempts - 1:
                    raise
                time.sleep(0.5)

        expected = pairing.response_fields(self.gateway_id, self.phone_id, nonce, status, raw)
        if not pairing.verify(self.key, signature, expected):
            if status == 401:
                raise PhoneAuthError("Phone rejected our signature (pairing removed on the phone, or phone on another Wi-Fi?)")
            raise PhoneAuthError(f"Answer to {path} is not signed by the paired phone (HTTP {status})")
        data = json.loads(raw.decode("utf-8")) if raw else {}
        if status >= 400:
            raise PhoneRequestError(status, data.get("message") if isinstance(data, dict) else None)
        return data

    def get_status(self) -> dict:
        return self._request("/v1/status")

    def start_failover(self, gateway_public_key: str) -> dict:
        body = json.dumps({"gateway_public_key": gateway_public_key}).encode("utf-8")
        return self._request("/v1/failover/start", method="POST", body=body, timeout=15.0)

    def stop_failover(self, timeout: float = 10.0, attempts: int = 3) -> dict:
        return self._request("/v1/failover/stop", method="POST", body=b"{}", timeout=timeout, attempts=attempts)

    def complete_pairing(self) -> dict:
        return self._request("/v1/pair/complete", method="POST", body=b"{}", attempts=1)


@dataclasses.dataclass(frozen=True)
class PhoneState:
    """What a phone's last signed broadcast announced."""
    ip: str
    last_seen: float
    standby: str = pairing.STANDBY_COLD
    # Battery state of charge (%) and whether the phone is plugged in
    battery: int = 100
    plugged: bool = False
    # The phone's settings: its minimum battery (%), and whether it may still carry an outage
    # below it when no other phone can
    min_battery: int = 20
    last_resort: bool = True
    # When the phone (re)appeared or its address or standby last changed
    since: float = 0.0

    @property
    def battery_low(self) -> bool:
        """On battery, below the phone's minimum."""
        return not self.plugged and self.battery < self.min_battery


def format_battery(battery: int, plugged: bool) -> str:
    return f"{battery}%{' plugged in' if plugged else ''}"


class PhoneDiscovery:
    """
    Background UDP listener that keeps track of every paired smartphone broadcasting on the LAN.
    A broadcast only counts when it carries a valid signature for this gateway, made by a
    paired phone for the address it is sent from.
    """

    def __init__(self, gateway_id: str, key_for, port: int = 8990, max_age: float = 15.0):
        self.gateway_id = gateway_id
        # phone_id -> shared key, or None for an unknown phone
        self.key_for = key_for
        self.port = port
        # A phone is available while its broadcasts (every 5s) keep arriving
        self.max_age = max_age
        self._phones: dict[str, PhoneState] = {}
        self._running = False
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()

    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._listen_loop, daemon=True, name="udp-discovery")
        self._thread.start()

    def stop(self):
        self._running = False

    def handle_packet(self, ip: str, data: bytes, now: float | None = None) -> list[str]:
        """Records the phones a broadcast from [ip] proves; returns their IDs."""
        try:
            payload = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return []
        if not isinstance(payload, dict) or payload.get("service") != pairing.SERVICE_NAME:
            return []
        entries = payload.get("pairings")
        if not isinstance(entries, list):
            return []
        proven = []
        for entry in entries:
            if not isinstance(entry, dict) or entry.get("gw") != self.gateway_id:
                continue
            phone_id = entry.get("pid")
            standby, battery, plugged = entry.get("standby"), entry.get("battery"), entry.get("plugged")
            min_battery, last_resort = entry.get("min_battery"), entry.get("last_resort")
            if (
                standby not in (pairing.STANDBY_HOT, pairing.STANDBY_COLD)
                or not pairing.valid_battery(battery) or not isinstance(plugged, bool)
                or not pairing.valid_battery(min_battery) or not isinstance(last_resort, bool)
            ):
                continue
            key = self.key_for(phone_id) if isinstance(phone_id, str) else None
            fields = pairing.discovery_fields(self.gateway_id, phone_id, ip, standby, battery, plugged, min_battery, last_resort)
            if key and pairing.verify(key, entry.get("sig"), fields):
                self.record_phone(
                    phone_id, ip, now, standby=standby, battery=battery, plugged=plugged, min_battery=min_battery, last_resort=last_resort
                )
                proven.append(phone_id)
        return proven

    def record_phone(
        self, phone_id: str, ip: str, now: float | None = None, standby: str = pairing.STANDBY_COLD,
        battery: int = 100, plugged: bool = False, min_battery: int = 20, last_resort: bool = True,
    ):
        now = time.monotonic() if now is None else now
        with self._lock:
            previous = self._phones.get(phone_id)
            since = now
            if previous is None or previous.ip != ip or now - previous.last_seen > self.max_age:
                logger.info(
                    f"[Discovery] Paired smartphone {phone_id} detected at {ip} "
                    f"({standby} standby, {format_battery(battery, plugged)}, minimum {min_battery}%{'' if last_resort else ' strict'})"
                )
            elif previous.standby != standby:
                logger.info(f"[Discovery] Smartphone {phone_id} switched to {standby} standby")
            else:
                since = previous.since
            if previous is not None and previous.plugged != plugged and now - previous.last_seen <= self.max_age:
                logger.info(f"[Discovery] Smartphone {phone_id} {'plugged in' if plugged else 'unplugged'} ({battery}%)")
            self._phones[phone_id] = PhoneState(
                ip=ip, last_seen=now, standby=standby, battery=battery, plugged=plugged,
                min_battery=min_battery, last_resort=last_resort, since=since,
            )

    def state_of(self, phone_id: str, now: float | None = None) -> "PhoneState | None":
        """Last announcement of a phone heard within max_age (hints: confirm them over signed HTTP)."""
        now = time.monotonic() if now is None else now
        with self._lock:
            phone = self._phones.get(phone_id)
        if phone is None or now - phone.last_seen > self.max_age:
            return None
        return phone

    def standby_of(self, phone_id: str, now: float | None = None) -> str:
        """Last standby announced by a phone heard within max_age (a hint: confirm it over signed HTTP)."""
        phone = self.state_of(phone_id, now)
        return phone.standby if phone else pairing.STANDBY_COLD

    def get_available_phones(self, now: float | None = None) -> list[tuple[str, str]]:
        """(phone_id, ip) of the phones heard within max_age, most recently seen first."""
        now = time.monotonic() if now is None else now
        with self._lock:
            fresh = [(pid, p) for pid, p in self._phones.items() if now - p.last_seen <= self.max_age]
        fresh.sort(key=lambda item: item[1].last_seen, reverse=True)
        return [(pid, p.ip) for pid, p in fresh]

    def _listen_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", self.port))
            sock.setblocking(False)
            logger.info(f"[Discovery] UDP listener active on port {self.port}")
        except Exception as e:
            logger.warning(f"[Discovery] Failed to bind UDP port {self.port}: {e}")
            sock.close()
            return

        while self._running:
            try:
                ready = select.select([sock], [], [], 1.0)
                if ready[0]:
                    data, addr = sock.recvfrom(4096)
                    self.handle_packet(addr[0], data)
            except Exception as e:
                logger.debug(f"[Discovery] Error handling UDP packet: {e}")
        sock.close()

    def wait_for_phone(self, timeout: float = 30.0, phone_id: str | None = None) -> tuple[str, str]:
        """First available phone (or the given one): (phone_id, ip)."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for pid, ip in self.get_available_phones():
                if phone_id is None or pid == phone_id:
                    return pid, ip
            time.sleep(0.5)
        raise TimeoutError(f"No paired smartphone detected on UDP port {self.port} after {timeout:.0f}s")


class RoutingManager:
    """Manages WireGuard interface, dedicated routing table, and iptables rules."""

    def __init__(self, failover_iface: str = "eth1", wg_iface: str = "wg0", table_id: int = 100, clamp_mss: bool = True):
        self.failover_iface = failover_iface
        self.wg_iface = wg_iface
        self.table_id = str(table_id)
        self.clamp_mss = clamp_mss

    def _run(self, cmd: list[str], check: bool = False) -> subprocess.CompletedProcess:
        try:
            return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=check)
        except subprocess.CalledProcessError as e:
            logger.error(f"Command error {' '.join(cmd)}: {e.stderr.strip()}")
            raise

    def setup_wireguard_interface(self, config_text: str, conf_dir: str = "/etc/wireguard"):
        """Writes a locally built WireGuard config (see build_wg_config) and brings up the interface."""
        conf_path = os.path.join(conf_dir, f"{self.wg_iface}.conf")
        os.makedirs(conf_dir, exist_ok=True)
        # Create with 0600 from the start so the private key is never world-readable
        fd = os.open(conf_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            os.fchmod(f.fileno(), 0o600)
            f.write(config_text)

        # Preemptive teardown if wg0 was already active
        self._run(["wg-quick", "down", self.wg_iface])

        logger.info(f"Bringing up WireGuard ({self.wg_iface})...")
        self._run(["wg-quick", "up", conf_path], check=True)
        # Loose reverse-path filtering: replies arrive on wg0 from internet sources whose
        # main-table route points at the primary interface (Table = off)
        self._run(["sysctl", "-w", f"net.ipv4.conf.{self.wg_iface}.rp_filter=2"])
        logger.info(f"Interface {self.wg_iface} active.")

    def teardown_wireguard_interface(self):
        logger.info(f"Tearing down interface {self.wg_iface}...")
        self._run(["wg-quick", "down", self.wg_iface])

    def enable_routing_and_nat(self):
        """Enables policy routing on eth1 and iptables NAT rules towards wg0."""
        logger.info(f"Applying policy routing (Table {self.table_id}) and iptables rules...")

        # 1. Sysctl forwarding
        self._run(["sysctl", "-w", "net.ipv4.ip_forward=1"])

        # 2. Dedicated routing table for wg0
        self._run(["ip", "route", "replace", "default", "dev", self.wg_iface, "table", self.table_id], check=True)

        # 3. Policy rule: any packet arriving via eth1 looks up isolated table
        rules = self._run(["ip", "rule", "show"]).stdout
        if f"iif {self.failover_iface} lookup {self.table_id}" not in rules:
            self._run(["ip", "rule", "add", "iif", self.failover_iface, "table", self.table_id], check=True)

        # 4. iptables transit and NAT rules
        # FORWARD eth1 -> wg0
        res = self._run(["iptables", "-C", "FORWARD", "-i", self.failover_iface, "-o", self.wg_iface, "-j", "ACCEPT"])
        if res.returncode != 0:
            self._run(["iptables", "-A", "FORWARD", "-i", self.failover_iface, "-o", self.wg_iface, "-j", "ACCEPT"], check=True)

        # FORWARD wg0 -> eth1 (Established/Related)
        res = self._run(["iptables", "-C", "FORWARD", "-i", self.wg_iface, "-o", self.failover_iface, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT"])
        if res.returncode != 0:
            self._run(["iptables", "-A", "FORWARD", "-i", self.wg_iface, "-o", self.failover_iface, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT"], check=True)

        # NAT MASQUERADE on wg0
        res = self._run(["iptables", "-t", "nat", "-C", "POSTROUTING", "-o", self.wg_iface, "-j", "MASQUERADE"])
        if res.returncode != 0:
            self._run(["iptables", "-t", "nat", "-A", "POSTROUTING", "-o", self.wg_iface, "-j", "MASQUERADE"], check=True)

        # 5. TCP MSS Clamping to PMTU (prevents packet drop/fragmentation over WireGuard)
        if self.clamp_mss:
            res = self._run(["iptables", "-t", "mangle", "-C", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"])
            if res.returncode != 0:
                self._run(["iptables", "-t", "mangle", "-A", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"], check=True)

        logger.info(f"Transit and NAT configured: {self.failover_iface} -> {self.wg_iface} -> Cellular.")

    def disable_routing_and_nat(self):
        """Removes iptables rules and dedicated routing rule."""
        logger.info(f"Cleaning up iptables rules and removing dedicated route (Table {self.table_id})...")

        # 1. Remove TCP MSS clamping rule if enabled
        if self.clamp_mss:
            self._run(["iptables", "-t", "mangle", "-D", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"])

        # 2. Remove iptables forwarding & NAT rules
        self._run(["iptables", "-D", "FORWARD", "-i", self.failover_iface, "-o", self.wg_iface, "-j", "ACCEPT"])
        self._run(["iptables", "-D", "FORWARD", "-i", self.wg_iface, "-o", self.failover_iface, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT"])
        self._run(["iptables", "-t", "nat", "-D", "POSTROUTING", "-o", self.wg_iface, "-j", "MASQUERADE"])

        # 3. Remove ip rule
        while True:
            res = self._run(["ip", "rule", "del", "iif", self.failover_iface, "table", self.table_id])
            if res.returncode != 0:
                break

        # 4. Flush routing table
        self._run(["ip", "route", "flush", "table", self.table_id])
        logger.info("Network cleanup complete.")


def generate_wg_keypair() -> tuple[str, str]:
    """Generates an ephemeral WireGuard key pair locally. The private key never leaves the gateway."""
    private_key = subprocess.run(["wg", "genkey"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True).stdout.strip()
    public_key = subprocess.run(["wg", "pubkey"], input=private_key, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True).stdout.strip()
    return private_key, public_key


def validate_wg_key(value: object) -> str:
    """Returns the canonical base64 form of a 32-byte WireGuard key, or raises ValueError."""
    if not isinstance(value, str):
        raise ValueError("WireGuard key must be a string")
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as e:
        raise ValueError(f"Invalid WireGuard key encoding: {e}") from e
    if len(raw) != 32:
        raise ValueError(f"Invalid WireGuard key length: {len(raw)} bytes (expected 32)")
    return base64.b64encode(raw).decode("ascii")


def validate_ipv4(value: object, require_private: bool = False) -> ipaddress.IPv4Address:
    if not isinstance(value, str):
        raise ValueError("IP address must be a string")
    try:
        addr = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as e:
        raise ValueError(f"Invalid IPv4 address: {value!r}") from e
    if require_private and not addr.is_private:
        raise ValueError(f"IPv4 address must be private: {addr}")
    return addr


def validate_port(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 65535:
        raise ValueError(f"Invalid port: {value!r}")
    return value


def build_wg_config(private_key: str, phone_public_key: str, phone_ip: str, port: int, peer_ip: str) -> str:
    """
    Builds the gateway wg-quick config from validated fields only.
    Every value is parsed and re-serialized, so no text received from the phone
    reaches the file verbatim (prevents PostUp/PreUp command injection).
    """
    private_key = validate_wg_key(private_key)
    phone_public_key = validate_wg_key(phone_public_key)
    endpoint_ip = validate_ipv4(phone_ip)
    tunnel_ip = validate_ipv4(peer_ip, require_private=True)
    port = validate_port(port)
    return (
        "[Interface]\n"
        f"PrivateKey = {private_key}\n"
        f"Address = {tunnel_ip}/24\n"
        "Table = off\n"
        "\n"
        "[Peer]\n"
        f"PublicKey = {phone_public_key}\n"
        f"Endpoint = {endpoint_ip}:{port}\n"
        "AllowedIPs = 0.0.0.0/0\n"
        "PersistentKeepalive = 25\n"
    )


def start_phone_relay(client: "AndroidFailoverClient", standby: bool = False) -> str:
    """
    Generates a fresh gateway key and starts the phone relay with it; returns the wg0 config
    to bring up. With [standby] (no outage, or moving to a hot standby phone), the phone's
    signed status must confirm it offers hot standby: discovery only hints at it.
    Nothing changes on the gateway, so a failure here leaves the current tunnel alone.
    """
    private_key, public_key = generate_wg_keypair()

    status = client.get_status()
    if standby and status.get("standby") != pairing.STANDBY_HOT:
        raise StandbyDeclined("Smartphone does not offer hot standby")
    wg_info = status.get("wireguard")
    if not isinstance(wg_info, dict):
        raise ValueError("Smartphone status did not include WireGuard details")

    # Validate before asking the phone to bring up cellular
    config = build_wg_config(
        private_key=private_key,
        phone_public_key=wg_info.get("public_key"),
        phone_ip=client.phone_ip,
        port=wg_info.get("port"),
        peer_ip=wg_info.get("peer_ip"),
    )

    res = client.start_failover(public_key)
    logger.info(f"Smartphone wake response: {res}")
    return config


def bring_up_tunnel(routing: "RoutingManager", config: str):
    """(Re)builds wg0 from [config], then the routing/NAT towards it."""
    routing.setup_wireguard_interface(config)
    routing.enable_routing_and_nat()


class FailoverCoordinator:
    """
    Owns the active failover: which smartphone carries it, whether its tunnel still
    answers, and handing it over to another phone when it is lost (app crash, empty
    battery, out of Wi-Fi range...) or when a better one is available during an outage.
    Several phones may be available (e.g. a family's).

    Phones are ranked by: battery not low, offering hot standby (always-on: the phone's
    owner chose it as the backup), plugged in, then the higher battery level. Each phone
    sets its own minimum battery: below it (on battery), the phone is only used when no
    other phone can be, and only if its owner allows it (last resort).
    """

    def __init__(
        self,
        discovery: "PhoneDiscovery",
        routing: "RoutingManager",
        store: "pairing.PairingStore",
        http_port: int = 8989,
        wg_iface: str = "wg0",
        canary_ip: str = "198.18.0.1",
        ping_timeout: int = 2,
        tunnel_fail_threshold: int = 3,
        client_factory=None,
        hot_standby: bool = True,
        standby_confirm_interval: float = 60.0,
        standby_retry_min: float = 30.0,
        standby_retry_max: float = 300.0,
        switch_battery_margin: int = 10,
        switch_stable_after: float = 60.0,
        switch_probe_attempts: int = 3,
    ):
        self.discovery = discovery
        self.routing = routing
        self.store = store
        self.http_port = http_port
        self.wg_iface = wg_iface
        self.canary_ip = canary_ip
        self.ping_timeout = ping_timeout
        self.tunnel_fail_threshold = tunnel_fail_threshold
        self.client_factory = client_factory or AndroidFailoverClient
        # (phone_id, ip) of the phone carrying the failover, and the wg0 config built for it
        self.active_phone: tuple[str, str] | None = None
        self.active_config: str | None = None
        self.tunnel_failures = 0
        # Hot standby: keep the tunnel up without an outage for phones that offer it
        self.hot_standby = hot_standby
        # True while the tunnel serves an outage (started by one, or the router uses WAN 2);
        # False for a hot standby tunnel the router doesn't use
        self.carrying = False
        self.standby_confirm_interval = standby_confirm_interval
        self.standby_confirmed_at = 0.0
        self.standby_retry_min = standby_retry_min
        self.standby_retry_max = standby_retry_max
        self.standby_retry_delay = standby_retry_min
        self.standby_retry_at = 0.0
        # Hot phones that were available when the last attempt failed: the backoff applies to them
        self.standby_failed_phones: set[str] = set()
        # A phone on battery takes over from a low one only with this much more than its minimum
        self.switch_battery_margin = switch_battery_margin
        # Planned switches only go to phones announcing the same thing for this long
        self.switch_stable_after = switch_stable_after
        self.switch_probe_attempts = switch_probe_attempts
        # phone_id -> (retry_at, next delay) after a failed planned switch to it
        self.switch_backoff: dict[str, tuple[float, float]] = {}
        # No low-battery switch check before this (the active phone's signed status said it's fine)
        self.switch_check_at = 0.0
        self.low_battery_reported = False

    @property
    def active(self) -> bool:
        return self.active_phone is not None

    @property
    def active_phone_label(self) -> str | None:
        return self.label(*self.active_phone) if self.active_phone else None

    def label(self, phone_id: str, ip: str) -> str:
        phone = self.store.get(phone_id)
        return f"{phone.label if phone else phone_id} ({ip})"

    def client(self, phone_id: str, ip: str) -> "AndroidFailoverClient":
        phone = self.store.get(phone_id)
        if phone is None:
            raise PhoneAuthError(f"Smartphone {phone_id} is no longer paired")
        return self.client_factory(
            phone_ip=ip, phone_id=phone_id, key=phone.key, gateway_id=self.discovery.gateway_id, http_port=self.http_port
        )

    def release_phone(self, phone_id: str, ip: str):
        """Best effort: the phone may be gone, so don't wait on it."""
        try:
            res = self.client(phone_id, ip).stop_failover(timeout=3.0, attempts=1)
            logger.info(f"Smartphone {self.label(phone_id, ip)} returned to standby: {res}")
        except Exception as e:
            logger.warning(f"Could not put smartphone {self.label(phone_id, ip)} back to standby: {e}")

    def _teardown_local(self):
        self.routing.disable_routing_and_nat()
        self.routing.teardown_wireguard_interface()

    def is_hot(self, phone_id: str) -> bool:
        """The phone announces hot standby (and this gateway allows it)."""
        return self.hot_standby and self.discovery.standby_of(phone_id) == pairing.STANDBY_HOT

    def battery_low(self, phone_id: str) -> bool:
        """The phone announces it runs on battery below its minimum."""
        state = self.discovery.state_of(phone_id)
        return state is not None and state.battery_low

    def must_not_carry(self, phone_id: str) -> bool:
        """Low on battery, and its owner doesn't allow it to carry an outage like that."""
        state = self.discovery.state_of(phone_id)
        return state is not None and state.battery_low and not state.last_resort

    def reports_low_battery(self, status: dict) -> bool:
        """Same as battery_low, from the phone's signed status."""
        battery, plugged, minimum = status.get("battery"), status.get("plugged"), status.get("min_battery")
        return pairing.valid_battery(battery) and pairing.valid_battery(minimum) and plugged is False and battery < minimum

    def rank(self, phone_id: str) -> tuple:
        """Sort key: best phone first (see the class docstring)."""
        state = self.discovery.state_of(phone_id)
        battery, plugged = (state.battery, state.plugged) if state else (0, False)
        return (self.battery_low(phone_id), not self.is_hot(phone_id), not plugged, -battery)

    def ranked_phones(self) -> list[tuple[str, str]]:
        """Phones that may carry an outage, best first."""
        phones = [(pid, ip) for pid, ip in self.discovery.get_available_phones() if not self.must_not_carry(pid)]
        return sorted(phones, key=lambda c: self.rank(c[0]))

    def standby_phones(self) -> list[tuple[str, str]]:
        """Phones a hot standby tunnel may use: offering it, and not low on battery."""
        return [(pid, ip) for pid, ip in self.ranked_phones() if self.is_hot(pid) and not self.battery_low(pid)]

    def activate(self, avoid: str | None = None, prefer: str | None = None, standby: bool = False) -> bool:
        """
        Starts failover on the best available phone that succeeds. [avoid] (a phone just lost)
        is tried last, [prefer] first (phone IDs). With [standby] (no outage), only phones
        offering hot standby are used, and not on low battery.
        """
        candidates = self.standby_phones() if standby else self.ranked_phones()
        # Stable sort: keeps the ranking between the others
        candidates.sort(key=lambda c: 0 if c[0] == prefer else 2 if c[0] == avoid else 1)
        if not candidates:
            if not standby:
                logger.error("Cannot activate failover: no paired smartphone available")
            return False

        for phone_id, ip in candidates:
            label = self.label(phone_id, ip)
            try:
                config = start_phone_relay(self.client(phone_id, ip), standby=standby)
                bring_up_tunnel(self.routing, config)
            except StandbyDeclined as e:
                # Its announcement was stale: nothing was started
                logger.info(f"Smartphone {label}: {e}")
                self._teardown_local()
                continue
            except PhoneRequestError as e:
                # Refused before anything was set up here (e.g. 409: serving another gateway)
                logger.error(f"Smartphone {label} refused failover: {e}")
                self._teardown_local()
                continue
            except Exception as e:
                logger.error(f"Failover activation via smartphone {label} failed: {e}")
                self._teardown_local()
                self.release_phone(phone_id, ip)
                continue
            self._set_active(phone_id, ip, config)
            self.carrying = not standby
            if standby:
                logger.info(f"Hot standby active via smartphone {label}: the router sees WAN 2 online")
            else:
                logger.info(f"Failover active via smartphone {label}")
            if self.battery_low(phone_id):
                logger.warning(f"Smartphone {label} is low on battery: no other smartphone could take the failover")
            return True
        return False

    def _set_active(self, phone_id: str, ip: str, config: str):
        self.active_phone = (phone_id, ip)
        self.active_config = config
        self.tunnel_failures = 0
        self.standby_confirmed_at = time.monotonic()
        self.switch_check_at = 0.0
        self.low_battery_reported = False

    def deactivate(self):
        self._teardown_local()
        if self.active_phone:
            self.release_phone(*self.active_phone)
        self.active_phone = None
        self.active_config = None
        self.tunnel_failures = 0
        self.carrying = False

    def try_standby(self, now: float | None = None) -> bool:
        """Brings up a hot standby tunnel if a phone offers one; failures back off (30 s → 5 min)."""
        now = time.monotonic() if now is None else now
        if not self.hot_standby:
            return False
        hot = {pid for pid, _ in self.standby_phones()}
        # A phone that left (or stopped offering it) and comes back starts afresh
        self.standby_failed_phones &= hot
        if not hot or (now < self.standby_retry_at and hot <= self.standby_failed_phones):
            return False
        if self.activate(standby=True):
            self.standby_retry_delay = self.standby_retry_min
            self.standby_retry_at = 0.0
            self.standby_failed_phones = set()
            return True
        if not hot <= self.standby_failed_phones:
            self.standby_retry_delay = self.standby_retry_min
        logger.warning(f"Hot standby could not start; retrying in {self.standby_retry_delay:.0f}s")
        self.standby_failed_phones = hot
        self.standby_retry_at = now + self.standby_retry_delay
        self.standby_retry_delay = min(self.standby_retry_delay * 2, self.standby_retry_max)
        return False

    def standby_still_offered(self, now: float | None = None, force: bool = False) -> bool:
        """
        Whether the active phone still offers hot standby, and isn't low on battery: its
        announcement must say so (a "cold" one ends it right away), and its signed status
        confirms it every [standby_confirm_interval], so a replayed "hot" broadcast can't
        keep the tunnel up.
        """
        if not self.active_phone or not self.is_hot(self.active_phone[0]):
            return False
        if self.battery_low(self.active_phone[0]):
            state = self.discovery.state_of(self.active_phone[0])
            logger.info(f"Smartphone {self.active_phone_label} is low on battery ({state.battery}%, its minimum is {state.min_battery}%)")
            return False
        now = time.monotonic() if now is None else now
        if not force and now - self.standby_confirmed_at < self.standby_confirm_interval:
            return True
        self.standby_confirmed_at = now
        try:
            status = self.client(*self.active_phone).get_status()
        except Exception as e:
            # Unreachable over HTTP: the tunnel probes decide whether the phone is lost
            logger.debug(f"Standby confirmation from {self.active_phone_label} failed: {e}")
            return True
        return status.get("standby") == pairing.STANDBY_HOT and not self.reports_low_battery(status)

    def switch_target(self, now: float) -> tuple[str, str, bool] | None:
        """
        A better phone to carry the outage: (phone_id, ip, offers hot standby), or None.
        Worth a switch (which resets every connection: new cellular public IP) only when
          - the active phone is low on battery, for a phone plugged in or with enough battery
            (any phone not low if the active one isn't allowed to carry an outage like that);
          - the active phone doesn't offer hot standby, for one that does (and isn't low).
        The new phone must have announced the same thing for [switch_stable_after].
        """
        active_id = self.active_phone[0]
        if self.discovery.state_of(active_id) is None:
            # Not heard: the tunnel probes decide whether it's lost
            return None
        active_low, active_hot = self.battery_low(active_id), self.is_hot(active_id)
        must_leave = self.must_not_carry(active_id)
        if not active_low and active_hot:
            return None
        available = {pid for pid, _ in self.discovery.get_available_phones()}
        self.switch_backoff = {pid: b for pid, b in self.switch_backoff.items() if pid in available}
        for phone_id, ip in self.ranked_phones():
            state = self.discovery.state_of(phone_id)
            if phone_id == active_id or state is None or now - state.since < self.switch_stable_after:
                continue
            if phone_id in self.switch_backoff and now < self.switch_backoff[phone_id][0]:
                continue
            hot = self.is_hot(phone_id)
            if must_leave and not state.battery_low:
                return phone_id, ip, hot
            if active_low and (state.plugged or state.battery >= state.min_battery + self.switch_battery_margin):
                return phone_id, ip, hot
            if not active_hot and hot and not self.battery_low(phone_id):
                return phone_id, ip, True
        if active_low and not must_leave and not self.low_battery_reported:
            self.low_battery_reported = True
            logger.warning(f"Smartphone {self.active_phone_label} is low on battery, and no other smartphone can take over yet")
        return None

    def maybe_switch(self, now: float | None = None) -> bool:
        """
        During an outage: moves the failover to a better phone (see switch_target). A phone
        low on battery whose owner doesn't allow it to carry an outage like that is released
        even when no other phone can take over. True if the active phone changed or was released.
        """
        now = time.monotonic() if now is None else now
        if not self.active_phone or now < self.switch_check_at:
            return False
        target = self.switch_target(now)
        must_leave = self.must_not_carry(self.active_phone[0])
        if target is None and not must_leave:
            return False
        # Moving to hot standby: the new phone's signed status must confirm it offers it
        to_hot = target is not None and target[2] and not self.is_hot(self.active_phone[0])
        if not to_hot:
            # Only a low battery justifies this one: the active phone itself must confirm it
            try:
                status = self.client(*self.active_phone).get_status()
            except Exception as e:
                logger.debug(f"Battery confirmation from {self.active_phone_label} failed: {e}")
                status = None
            confirmed = status is not None and self.reports_low_battery(status)
            if confirmed and target is None:
                confirmed = status.get("last_resort") is False
            if not confirmed:
                self.switch_check_at = now + self.standby_confirm_interval
                return False
        if target is None:
            logger.warning(
                f"Smartphone {self.active_phone_label} is below its minimum battery and may not carry an outage like that: "
                "releasing it (no other smartphone can take over)"
            )
            self.deactivate()
            return True
        phone_id, ip, _ = target
        return self.switch_to(phone_id, ip, to_hot, now)

    def switch_to(self, phone_id: str, ip: str, hot: bool = False, now: float | None = None) -> bool:
        """
        Planned handover, make-before-break: start the new phone's relay (cellular is the slow
        part) while the current one keeps carrying, swap wg0, check the new tunnel, then
        release the old phone. If anything fails, the current phone keeps (or gets back) the
        tunnel and the new one is retried later (backoff).
        """
        now = time.monotonic() if now is None else now
        old_phone, old_config = self.active_phone, self.active_config
        label = self.label(phone_id, ip)
        logger.info(f"Moving failover from smartphone {self.active_phone_label} to {label}...")
        try:
            config = start_phone_relay(self.client(phone_id, ip), standby=hot)
        except (StandbyDeclined, PhoneRequestError) as e:
            logger.warning(f"Smartphone {label} can't take over: {e}")
            self._switch_failed(phone_id, now)
            return False
        except Exception as e:
            logger.warning(f"Smartphone {label} could not start its relay: {e}")
            self.release_phone(phone_id, ip)
            self._switch_failed(phone_id, now)
            return False

        try:
            bring_up_tunnel(self.routing, config)
            ok = any(
                check_tunnel_canary(self.wg_iface, self.canary_ip, timeout=self.ping_timeout)
                for _ in range(self.switch_probe_attempts)
            )
        except Exception as e:
            logger.warning(f"Tunnel to smartphone {label} failed: {e}")
            ok = False
        if not ok:
            logger.warning(f"Smartphone {label} did not answer through the tunnel: back to {self.active_phone_label}")
            try:
                # Its relay still runs with the previous key
                bring_up_tunnel(self.routing, old_config)
            except Exception as e:
                # The tunnel probes will hand the failover over
                logger.error(f"Could not restore the tunnel to smartphone {self.active_phone_label}: {e}")
            self.release_phone(phone_id, ip)
            self._switch_failed(phone_id, now)
            return False

        self.release_phone(*old_phone)
        self._set_active(phone_id, ip, config)
        self.switch_backoff.pop(phone_id, None)
        logger.info(f"Failover moved to smartphone {label}")
        return True

    def _switch_failed(self, phone_id: str, now: float):
        _, delay = self.switch_backoff.get(phone_id, (0.0, self.standby_retry_min))
        self.switch_backoff[phone_id] = (now + delay, min(delay * 2, self.standby_retry_max))
        logger.info(f"Next attempt with smartphone {phone_id} in {delay:.0f}s at the earliest")

    def tunnel_lost(self) -> bool:
        """
        Probes the canary IP through the tunnel itself; only the phone's relay answers it.
        Returns True once [tunnel_fail_threshold] consecutive probes have failed.
        """
        if check_tunnel_canary(self.wg_iface, self.canary_ip, timeout=self.ping_timeout):
            self.tunnel_failures = 0
            return False
        self.tunnel_failures += 1
        logger.warning(
            f"Tunnel probe to smartphone {self.active_phone_label} via {self.wg_iface} failed "
            f"({self.tunnel_failures}/{self.tunnel_fail_threshold})"
        )
        return self.tunnel_failures >= self.tunnel_fail_threshold

    def hand_over(self) -> bool:
        """Moves failover off the lost phone, preferring another one; retrying the same phone re-keys a restarted relay."""
        lost_id = self.active_phone[0] if self.active_phone else None
        logger.error(f"!!! Smartphone {self.active_phone_label} lost: handing failover over to another smartphone... !!!")
        self.deactivate()
        return self.activate(avoid=lost_id)


class FailoverLoop:
    """
    One health check per step(). The gateway is in one of three states:
      - idle: no tunnel; WAN 1 failing [fail_threshold] times starts failover (cold standby),
        and a phone offering hot standby gets a tunnel right away;
      - hot standby: tunnel up, the router still uses WAN 1 (but sees WAN 2 online);
      - carrying: the router uses WAN 2 (the canary from the primary interface answers).
    After an outage, a phone still offering hot standby keeps its tunnel; others are released.
    While carrying, the failover moves to a better phone when one is worth it (see
    FailoverCoordinator.switch_target).
    """

    def __init__(self, coordinator: "FailoverCoordinator", args: argparse.Namespace):
        self.coordinator = coordinator
        self.args = args
        self.failed_probes = 0
        self.success_probes = 0
        # Last reported state while a tunnel is up ("wan2" / "waiting" / "standby"), to log changes only
        self.active_state: str | None = None

    def step(self):
        if self.coordinator.active:
            self._step_active()
        else:
            self._step_idle()

    def _step_idle(self):
        args, coordinator = self.args, self.coordinator
        if check_primary_wan(args.primary_iface, args.targets, timeout=args.ping_timeout):
            self.failed_probes = 0
            coordinator.try_standby()
            return

        self.failed_probes += 1
        self.success_probes = 0
        logger.warning(f"WAN 1 probe ({args.primary_iface}): FAILED ({self.failed_probes}/{args.fail_threshold})")
        if self.failed_probes >= args.fail_threshold:
            logger.error("!!! WAN 1 OUTAGE DETECTED !!! Activating cellular failover...")
            if coordinator.activate():
                self.failed_probes = 0
                self.active_state = None
                logger.info(">>> Cellular WAN 2 Failover 100% OPERATIONAL. Router routes traffic via smartphone. <<<")

    def _step_active(self):
        args, coordinator = self.args, self.coordinator
        if coordinator.tunnel_lost():
            self.active_state = None
            self.success_probes = 0
            # The phone carrying the tunnel stopped answering through it
            if check_primary_wan(args.primary_iface, args.targets, timeout=args.ping_timeout):
                # No outage: drop it (a phone offering hot standby gets a new tunnel on the next check)
                logger.info("Tunnel lost but WAN 1 is reachable: releasing the smartphone")
                coordinator.deactivate()
                self.failed_probes = 0
            elif coordinator.hand_over():
                logger.info(f">>> Failover handed over to smartphone {coordinator.active_phone_label}. <<<")
            else:
                # Still in an outage: retry activation on the next failed WAN 1 probe
                logger.error("No smartphone could take over the failover; retrying on next probe")
                self.failed_probes = max(args.fail_threshold - 1, 0)
            return
        if coordinator.tunnel_failures:
            # Tunnel probe failing: wait for the next probes before deciding
            return

        # If the router routes LAN default traffic through WAN 2, the non-routable canary IP
        # (198.18.0.1) reaches eth1 -> wg0 and is answered by the phone's relay
        if check_tunnel_canary(args.primary_iface, args.canary_ip, timeout=args.ping_timeout):
            coordinator.carrying = True
            self.success_probes = 0
            if coordinator.maybe_switch():
                self.active_state = None
                if coordinator.active:
                    logger.info(f">>> Failover moved to smartphone {coordinator.active_phone_label}. <<<")
                else:
                    # Back to idle: retried on the next failed WAN 1 probe if a phone shows up
                    self.failed_probes = max(args.fail_threshold - 1, 0)
                return
            self._report(
                "wan2",
                f"WAN 2 active: LAN traffic routed via backup cellular ({args.failover_iface}). "
                f"Standby for primary WAN recovery...",
            )
            return

        if not coordinator.carrying:
            # Hot standby while the router uses WAN 1
            if coordinator.standby_still_offered():
                self._report("standby", f"Hot standby via smartphone {coordinator.active_phone_label}: WAN 2 ready")
            else:
                logger.info(f"Hot standby via smartphone {coordinator.active_phone_label} ended: releasing it")
                self.active_state = None
                coordinator.deactivate()
            return

        # Canary timed out: outbound traffic no longer goes out WAN 2. Is WAN 1 healthy?
        if not check_primary_wan(args.primary_iface, args.targets, timeout=args.ping_timeout):
            self.success_probes = 0
            self._report("waiting", f"Router is not using WAN 2, but primary WAN ({args.primary_iface}) is still unreachable", logging.WARNING)
            return

        self.active_state = None
        self.success_probes += 1
        logger.info(f"WAN 1 probe ({args.primary_iface}): Direct via Primary ({self.success_probes}/{args.restore_threshold})")
        if self.success_probes < args.restore_threshold:
            return
        self.success_probes = 0
        if coordinator.standby_still_offered(force=True):
            logger.info(f">>> WAN 1 recovery confirmed! Back to hot standby via smartphone {coordinator.active_phone_label}. <<<")
            coordinator.carrying = False
            return
        logger.info(">>> WAN 1 recovery confirmed! Tearing down cellular failover... <<<")
        # Routing/NAT off first (router falls back to WAN 1 instantly), then wg0,
        # then the phone releases its cellular radio
        coordinator.deactivate()
        logger.info("Failback to WAN 1 completed successfully.")

    def _report(self, state: str, message: str, level: int = logging.INFO):
        if self.active_state != state:
            logger.log(level, message)
            self.active_state = state


def check_tunnel_canary(iface: str, canary_ip: str = "198.18.0.1", timeout: int = 2) -> bool:
    """
    Sends an ICMP Echo Request to a non-routable canary IP (RFC 2544 benchmark range).
    Since the IP is non-routable on the internet, public ISPs drop it.
    However, the Android WireGuard relay synthesizes ICMP Echo Replies for any packet
    entering the tunnel. Thus, this returns True IF AND ONLY IF the router is currently
    routing outbound LAN traffic through the backup WAN 2 gateway.
    """
    cmd = ["ping", "-I", iface, "-c", "1", "-W", str(timeout), canary_ip]
    try:
        res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return res.returncode == 0
    except Exception as e:
        logger.debug(f"Canary probe error via {iface} to {canary_ip}: {e}")
        return False


def check_primary_wan(iface: str, targets: list[str], timeout: int = 2) -> bool:
    """Sends ICMP ping probes to specified targets via primary interface eth0."""
    for target in targets:
        cmd = ["ping", "-I", iface, "-c", "1", "-W", str(timeout), target]
        try:
            res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if res.returncode == 0:
                return True
        except Exception as e:
            logger.debug(f"Ping error {target} via {iface}: {e}")
    return False


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Cellular WAN Failover Daemon (Linux Gateway <-> Android)")
    parser.add_argument("--primary-iface", default=os.environ.get("PRIMARY_IFACE", "eth0"), help="Primary WAN 1 interface connected to LAN (default: eth0)")
    parser.add_argument("--failover-iface", default=os.environ.get("FAILOVER_IFACE", "eth1"), help="Failover WAN 2 interface connected to router (default: eth1)")
    parser.add_argument("--failover-gateway-ip", "--failover-gateway", dest="failover_gateway", default=os.environ.get("FAILOVER_GATEWAY_IP", "192.168.100.1"), help="IP address of failover gateway on failover interface (default: 192.168.100.1)")
    parser.add_argument("--wg-iface", default=os.environ.get("WG_IFACE", "wg0"), help="WireGuard interface name (default: wg0)")
    parser.add_argument("--table-id", type=int, default=int(os.environ.get("ROUTING_TABLE_ID", "100")), help="Policy routing table ID for failover traffic (default: 100)")
    parser.add_argument("--clamp-mss", action=argparse.BooleanOptionalAction, default=os.environ.get("CLAMP_MSS", "true").lower() in ("true", "1", "yes"), help="Enable/disable TCP MSS clamping for WireGuard (default: enabled)")
    parser.add_argument("--config-dir", default=os.environ.get("CONFIG_DIR", "/config"), help="Directory holding the gateway ID and paired phones (default: /config)")
    parser.add_argument("--phone", help="Paired phone (name or ID) for --status-only / --start-now")
    parser.add_argument("--http-port", type=int, default=int(os.environ.get("HTTP_PORT", "8989")), help="Smartphone HTTP API port (default: 8989)")
    parser.add_argument("--discovery-port", type=int, default=int(os.environ.get("DISCOVERY_PORT", "8990")), help="UDP discovery port (default: 8990)")
    parser.add_argument("--targets", nargs="+", default=os.environ.get("PING_TARGETS", "1.1.1.1 8.8.8.8").split(), help="Ping target addresses")
    parser.add_argument("--ping-timeout", type=int, default=int(os.environ.get("PING_TIMEOUT", "2")), help="Timeout in seconds for ICMP ping probes (default: 2)")
    parser.add_argument("--canary-ip", default=os.environ.get("CANARY_IP", "198.18.0.1"), help="Non-routable RFC 2544 canary IP synthesized only by tunnel (default: 198.18.0.1)")
    parser.add_argument("--fail-threshold", type=int, default=int(os.environ.get("FAIL_THRESHOLD", "3")), help="Consecutive failures before failover")
    parser.add_argument("--restore-threshold", type=int, default=int(os.environ.get("RESTORE_THRESHOLD", "5")), help="Consecutive successes before failback")
    parser.add_argument("--tunnel-fail-threshold", type=int, default=int(os.environ.get("TUNNEL_FAIL_THRESHOLD", "3")), help="Consecutive failed tunnel probes before the phone is considered lost and failover is handed over")
    parser.add_argument("--hot-standby", action=argparse.BooleanOptionalAction, default=os.environ.get("HOT_STANDBY", "true").lower() in ("true", "1", "yes"), help="Keep the tunnel up at all times with phones that offer hot standby (default: enabled)")
    parser.add_argument("--check-interval", type=int, default=int(os.environ.get("CHECK_INTERVAL", "5")), help="Health check interval in seconds")
    parser.add_argument("--discover-only", action="store_true", help="Print discovered smartphone IP and exit")
    parser.add_argument("--status-only", action="store_true", help="Print smartphone status and exit")
    parser.add_argument("--test-route", action="store_true", help="Test if current outbound traffic from primary interface is hairpinned through WAN 2")
    parser.add_argument("--start-now", action="store_true", help="Force immediate failover activation and exit")
    parser.add_argument("--stop-now", action="store_true", help="Force immediate failover teardown and exit")
    parser.add_argument("--pair", action="store_true", help="Show a pairing QR code and wait for a smartphone to pair")
    parser.add_argument("--pair-timeout", type=int, default=300, help="Seconds to wait for the smartphone during --pair (default: 300)")
    parser.add_argument("--list-phones", action="store_true", help="List paired smartphones and whether they are online")
    parser.add_argument("--unpair", metavar="PHONE", help="Remove a paired smartphone (name or ID)")
    return parser


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    return build_parser().parse_args(argv)


def resolve_phone(store: "pairing.PairingStore", selector: str | None) -> "pairing.PairedPhone | None":
    """The paired phone matching [selector] (exits with an error if none or ambiguous); None without selector."""
    if not selector:
        return None
    matches = store.find(selector)
    if len(matches) != 1:
        problem = "No paired smartphone" if not matches else "Several paired smartphones"
        print(f"Error: {problem} named '{selector}'. See 'wanctl phones'.", file=sys.stderr)
        sys.exit(1)
    return matches[0]


def print_pairing_qr(link: str):
    try:
        # The link travels through stdin, not argv, so it never shows up in the process list
        subprocess.run(["qrencode", "-t", "ANSIUTF8", "-m", "2"], input=link, text=True, check=True)
    except (FileNotFoundError, subprocess.CalledProcessError) as e:
        print(f"(Cannot draw the QR code: {e}; paste the link below instead)")


def run_pairing(store: "pairing.PairingStore", args: argparse.Namespace) -> int:
    """
    Shows a one-time pairing QR code, then waits for the phone that scanned it: once it
    broadcasts on its Wi-Fi with the new key, the gateway completes the pairing over HTTP.
    That exchange proves to the phone that the gateway is on that Wi-Fi network. Nothing
    is saved on either side before it succeeds.
    """
    gateway_id = store.gateway_id()
    gateway_name = pairing.default_gateway_name()
    phone_id, key = pairing.new_id(), pairing.new_key()
    link = pairing.build_pair_link(gateway_id, gateway_name, phone_id, key)

    print(f"Pairing a smartphone with gateway '{gateway_name}'.")
    print("Connect the phone to the Wi-Fi network this gateway is on, then scan this QR code")
    print("with the Cellular WAN Failover app or the camera app:\n")
    print_pairing_qr(link)
    print(f"\nOr paste this link in the app:\n{link}\n")
    print(f"Pairing code: {pairing.pairing_code(gateway_id, phone_id, key)}  (check that the phone shows the same code)\n")
    print(f"Waiting for the phone (up to {args.pair_timeout // 60} min, Ctrl+C to cancel)...", flush=True)

    discovery = PhoneDiscovery(
        gateway_id=gateway_id,
        key_for=lambda pid: key if pid == phone_id else None,
        port=args.discovery_port,
    )
    discovery.start()
    deadline = time.monotonic() + args.pair_timeout
    last_error = None
    try:
        while time.monotonic() < deadline:
            try:
                _, ip = discovery.wait_for_phone(timeout=max(deadline - time.monotonic(), 0.1), phone_id=phone_id)
            except TimeoutError:
                break
            client = AndroidFailoverClient(phone_ip=ip, phone_id=phone_id, key=key, gateway_id=gateway_id, http_port=args.http_port)
            try:
                result = client.complete_pairing()
                if not isinstance(result, dict):
                    raise ValueError("unexpected answer to the pairing completion")
            except Exception as e:
                if str(e) != last_error:
                    logger.warning(f"Completing the pairing with {ip} failed: {e} (retrying)")
                    last_error = str(e)
                time.sleep(3)
                continue

            name = pairing.sanitize_label(result.get("name")) or "Android phone"
            ssid = pairing.sanitize_label(result.get("ssid"))
            replaces = None
            old = result.get("replaces")
            if isinstance(old, dict) and isinstance(old.get("phone_id"), str):
                previous = store.get(old["phone_id"])
                # Only the phone holding the old key can retire its old pairing
                if previous and pairing.verify(previous.key, old.get("sig"), pairing.replace_fields(gateway_id, previous.id, phone_id)):
                    replaces = previous.id
            store.add(pairing.PairedPhone(id=phone_id, name=name, key=key, ssid=ssid, paired_at=time.time()), replaces=replaces)
            print(f"\n✓ Paired with '{name}'" + (f" on Wi-Fi '{ssid}'" if ssid else "") + (" (replaces its previous pairing)" if replaces else ""))
            return 0
    finally:
        discovery.stop()

    print("\nPairing timed out: the phone did not show up on this network. Nothing was saved.", file=sys.stderr)
    return 1


def manual_stop(routing: "RoutingManager", discovery: "PhoneDiscovery", coordinator: "FailoverCoordinator", listen: float = 6.0):
    """`wanctl stop`: tears down the gateway side, then puts every paired phone heard back to standby."""
    # Local teardown first: it must work even when no phone can be reached
    routing.disable_routing_and_nat()
    routing.teardown_wireguard_interface()
    # Listen one broadcast interval so every phone (only one carries the failover) is known
    time.sleep(listen)
    phones = discovery.get_available_phones()
    if not phones:
        logger.warning("No paired smartphone heard: if a relay is still running, stop it in the app.")
    for phone_id, ip in phones:
        logger.info(f"Manual deactivation on {coordinator.label(phone_id, ip)}...")
        coordinator.release_phone(phone_id, ip)
    logger.info("Cellular failover stopped.")


def release_before_unpair(store: "pairing.PairingStore", phone: "pairing.PairedPhone", args: argparse.Namespace, wait: float = 6.0):
    """Best effort: stops a relay the phone may be running for this gateway, while the key still works."""
    gateway_id = store.gateway_id()
    discovery = PhoneDiscovery(gateway_id=gateway_id, key_for=lambda pid: phone.key if pid == phone.id else None, port=args.discovery_port)
    discovery.start()
    try:
        _, ip = discovery.wait_for_phone(timeout=wait, phone_id=phone.id)
    except TimeoutError:
        print(f"'{phone.label}' is not on the network: if its relay is still running, stop it in the app.")
        return
    finally:
        discovery.stop()
    client = AndroidFailoverClient(phone_ip=ip, phone_id=phone.id, key=phone.key, gateway_id=gateway_id, http_port=args.http_port)
    try:
        client.stop_failover(timeout=3.0, attempts=1)
        print(f"Put '{phone.label}' back to standby.")
    except PhoneRequestError as e:
        if e.status != 409:
            print(f"Could not put '{phone.label}' back to standby: {e}")
        # 409: its relay is serving another gateway, nothing to stop for us
    except Exception as e:
        print(f"Could not put '{phone.label}' back to standby: {e}")


def list_phones(store: "pairing.PairingStore", discovery: "PhoneDiscovery"):
    phones = store.phones()
    if not phones:
        print("No paired smartphones. Pair one with 'wanctl pair'.")
        return
    # Phones broadcast every 5s
    time.sleep(6)
    print(f"{'NAME':<28} {'WI-FI':<20} {'PAIRED':<17} {'STATUS':<22} {'STANDBY':<8} {'BATTERY':<16} {'MINIMUM':<20} ID")
    for p in phones:
        paired = time.strftime("%Y-%m-%d %H:%M", time.localtime(p.paired_at)) if p.paired_at else "?"
        state = discovery.state_of(p.id)
        status = f"online ({state.ip})" if state else "not seen"
        standby = state.standby if state else "-"
        battery = format_battery(state.battery, state.plugged) if state else "-"
        minimum = f"{state.min_battery}%{' (last resort)' if state.last_resort else ''}" if state else "-"
        print(f"{p.label:<28} {p.ssid:<20} {paired:<17} {status:<22} {standby:<8} {battery:<16} {minimum:<20} {p.id}")


def main():
    args = parse_args()
    store = pairing.PairingStore(args.config_dir)

    if args.pair:
        try:
            sys.exit(run_pairing(store, args))
        except KeyboardInterrupt:
            print("\nPairing cancelled. Nothing was saved.")
            sys.exit(130)

    if args.unpair:
        phone = resolve_phone(store, args.unpair)
        # Once removed, this gateway can no longer sign the request that stops the phone's relay
        release_before_unpair(store, phone, args)
        store.remove(phone.id)
        print(f"Removed '{phone.label}' ({phone.id}). Remove the gateway in the phone's app too.")
        return

    def key_for(phone_id: str) -> bytes | None:
        phone = store.get(phone_id)
        return phone.key if phone else None

    discovery = PhoneDiscovery(gateway_id=store.gateway_id(), key_for=key_for, port=args.discovery_port)
    discovery.start()

    routing = RoutingManager(
        failover_iface=args.failover_iface,
        wg_iface=args.wg_iface,
        table_id=args.table_id,
        clamp_mss=args.clamp_mss,
    )

    coordinator = FailoverCoordinator(
        discovery=discovery,
        routing=routing,
        store=store,
        http_port=args.http_port,
        wg_iface=args.wg_iface,
        canary_ip=args.canary_ip,
        ping_timeout=args.ping_timeout,
        tunnel_fail_threshold=args.tunnel_fail_threshold,
        hot_standby=args.hot_standby,
    )

    selected = resolve_phone(store, args.phone)

    def wait_for_phone() -> tuple[str, str]:
        try:
            return discovery.wait_for_phone(timeout=15.0, phone_id=selected.id if selected else None)
        except TimeoutError as e:
            print(f"Error: {e}", file=sys.stderr)
            sys.exit(1)

    # CLI one-shot actions
    if args.list_phones:
        list_phones(store, discovery)
        discovery.stop()
        return

    if args.discover_only:
        phone_id, ip = wait_for_phone()
        print(f"Smartphone discovered: {coordinator.label(phone_id, ip)}")
        discovery.stop()
        return

    if args.status_only:
        phone_id, ip = wait_for_phone()
        print(f"Querying {coordinator.label(phone_id, ip)}...")
        status = coordinator.client(phone_id, ip).get_status()
        print(json.dumps(status, indent=2))
        discovery.stop()
        return

    if args.test_route:
        wan_ok = check_primary_wan(args.primary_iface, args.targets, timeout=args.ping_timeout)
        canary_ok = check_tunnel_canary(args.primary_iface, args.canary_ip, timeout=args.ping_timeout)
        print("=== WAN Egress Route Verification ===")
        print(f"  - Primary interface  : {args.primary_iface}")
        print(f"  - Failover interface : {args.failover_iface} (Gateway: {args.failover_gateway})")
        print(f"  - Probe targets      : {', '.join(args.targets)}")
        print(f"  - Canary target      : {args.canary_ip} (RFC 2544 benchmark)")
        print(f"  - Internet reachable : {'YES' if wan_ok else 'NO'}")
        print(f"  - Tunnel canary      : {'RESPONDED (via WAN 2 tunnel)' if canary_ok else 'TIMEOUT / UNREACHABLE'}")
        if canary_ok:
            print("  - Active egress path : WAN 2 (Hairpinned/routed via mobile cellular relay)")
        elif wan_ok:
            print("  - Active egress path : WAN 1 (Direct via primary WAN)")
        else:
            print("  - Active egress path : UNKNOWN / OFFLINE (Both primary and canary unreachable)")
        discovery.stop()
        return

    if args.start_now:
        wait_for_phone()  # waits for a first smartphone
        activated = coordinator.activate(prefer=selected.id if selected else None)
        discovery.stop()
        if not activated:
            sys.exit(1)
        logger.info("Cellular failover manually activated successfully.")
        return

    if args.stop_now:
        manual_stop(routing, discovery, coordinator)
        discovery.stop()
        return

    # Main monitoring loop
    loop = FailoverLoop(coordinator, args)
    running = True

    def sig_handler(signum, frame):
        nonlocal running
        logger.info(f"Shutdown signal received ({signum}). Cleaning up...")
        running = False

    signal.signal(signal.SIGINT, sig_handler)
    signal.signal(signal.SIGTERM, sig_handler)

    logger.info("====================================================================")
    logger.info("Starting WAN Failover Watchdog")
    logger.info(f"  - Primary interface (WAN 1)  : {args.primary_iface}")
    logger.info(f"  - Failover interface (WAN 2) : {args.failover_iface} ({args.failover_gateway})")
    logger.info(f"  - Policy routing table ID    : {args.table_id}")
    logger.info(f"  - TCP MSS clamping           : {'enabled' if args.clamp_mss else 'disabled'}")
    logger.info(f"  - WireGuard interface        : {args.wg_iface}")
    logger.info(f"  - ICMP probe targets         : {', '.join(args.targets)} (timeout: {args.ping_timeout}s)")
    logger.info(f"  - Non-routable canary IP     : {args.canary_ip}")
    logger.info(f"  - Fail / restore thresholds  : {args.fail_threshold} failures / {args.restore_threshold} successes")
    logger.info(f"  - Lost phone after           : {args.tunnel_fail_threshold} failed tunnel probes")
    logger.info(f"  - Hot standby                : {'enabled (phones that offer it)' if args.hot_standby else 'disabled'}")
    logger.info(f"  - Health check interval      : {args.check_interval}s")
    logger.info(f"  - Paired smartphones         : {', '.join(p.label for p in store.phones()) or 'none (run wanctl pair)'}")
    logger.info("====================================================================")

    # Smartphone check at startup
    logger.info("Initial discovery for paired smartphones on local Wi-Fi...")
    try:
        phone_id, ip = discovery.wait_for_phone(timeout=10.0)
        logger.info(f"Smartphone ready: {coordinator.label(phone_id, ip)}")
    except TimeoutError:
        logger.warning("No paired smartphone detected yet (discovery continues in background)")

    try:
        while running:
            loop.step()
            time.sleep(args.check_interval)

    finally:
        if coordinator.active:
            logger.info("Service stopping: cleaning up routing and stopping smartphone relay...")
            coordinator.deactivate()
        discovery.stop()
        logger.info("Watchdog stopped.")


if __name__ == "__main__":
    main()
