#!/usr/bin/env python3
"""
Cellular WAN Failover Watchdog (WireGuard + Policy Routing)
Monitors WAN 1 (Fiber/Cable) connectivity via eth0 and automatically fails over
to the Android cellular relay via WireGuard when an outage is detected.
"""

import argparse
import base64
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
        self._phones: dict[str, dict[str, object]] = {}
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
            key = self.key_for(phone_id) if isinstance(phone_id, str) else None
            if key and pairing.verify(key, entry.get("sig"), pairing.discovery_fields(self.gateway_id, phone_id, ip)):
                self.record_phone(phone_id, ip, now)
                proven.append(phone_id)
        return proven

    def record_phone(self, phone_id: str, ip: str, now: float | None = None):
        now = time.monotonic() if now is None else now
        with self._lock:
            previous = self._phones.get(phone_id)
            if previous is None or previous["ip"] != ip or now - previous["last_seen"] > self.max_age:
                logger.info(f"[Discovery] Paired smartphone {phone_id} detected at {ip}")
            self._phones[phone_id] = {"ip": ip, "last_seen": now}

    def get_available_phones(self, now: float | None = None) -> list[tuple[str, str]]:
        """(phone_id, ip) of the phones heard within max_age, most recently seen first."""
        now = time.monotonic() if now is None else now
        with self._lock:
            fresh = [(pid, p) for pid, p in self._phones.items() if now - p["last_seen"] <= self.max_age]
        fresh.sort(key=lambda item: item[1]["last_seen"], reverse=True)
        return [(pid, p["ip"]) for pid, p in fresh]

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


def activate_failover(client: "AndroidFailoverClient", routing: "RoutingManager") -> dict:
    """Generates a fresh gateway key, starts the phone relay, and brings up wg0 with routing/NAT."""
    private_key, public_key = generate_wg_keypair()

    status = client.get_status()
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

    routing.setup_wireguard_interface(config)
    routing.enable_routing_and_nat()
    return res


class FailoverCoordinator:
    """
    Owns the active failover: which smartphone carries it, whether its tunnel still
    answers, and handing it over to another phone when it is lost (app crash, empty
    battery, out of Wi-Fi range...). Several phones may be available (e.g. a family's).
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
        # (phone_id, ip) of the phone carrying the failover
        self.active_phone: tuple[str, str] | None = None
        self.tunnel_failures = 0

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

    def activate(self, avoid: str | None = None, prefer: str | None = None) -> bool:
        """
        Starts failover on the first available phone that succeeds. [avoid] (a phone just lost)
        is tried last, [prefer] first (phone IDs).
        """
        candidates = self.discovery.get_available_phones()
        candidates.sort(key=lambda c: 0 if c[0] == prefer else 2 if c[0] == avoid else 1)
        if not candidates:
            logger.error("Cannot activate failover: no paired smartphone available")
            return False

        for phone_id, ip in candidates:
            label = self.label(phone_id, ip)
            try:
                activate_failover(self.client(phone_id, ip), self.routing)
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
            self.active_phone = (phone_id, ip)
            self.tunnel_failures = 0
            logger.info(f"Failover active via smartphone {label}")
            return True
        return False

    def deactivate(self):
        self._teardown_local()
        if self.active_phone:
            self.release_phone(*self.active_phone)
        self.active_phone = None
        self.tunnel_failures = 0

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
    online = dict(discovery.get_available_phones())
    print(f"{'NAME':<28} {'WI-FI':<20} {'PAIRED':<17} {'STATUS':<22} ID")
    for p in phones:
        paired = time.strftime("%Y-%m-%d %H:%M", time.localtime(p.paired_at)) if p.paired_at else "?"
        status = f"online ({online[p.id]})" if p.id in online else "not seen"
        print(f"{p.label:<28} {p.ssid:<20} {paired:<17} {status:<22} {p.id}")


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
    failed_probes = 0
    # Last reported egress state while failover is active ("wan2" / "waiting"), to log changes only
    active_state: str | None = None
    success_probes = 0
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
            if coordinator.active:
                if coordinator.tunnel_lost():
                    active_state = None
                    # The phone carrying the failover stopped answering through the tunnel
                    if check_primary_wan(args.primary_iface, args.targets, timeout=args.ping_timeout):
                        logger.info("Tunnel lost but WAN 1 is reachable: ending cellular failover")
                        coordinator.deactivate()
                        failed_probes = 0
                    elif coordinator.hand_over():
                        logger.info(f">>> Failover handed over to smartphone {coordinator.active_phone_label}. <<<")
                    else:
                        # Still in an outage: retry activation on the next failed WAN 1 probe
                        logger.error("No smartphone could take over the failover; retrying on next probe")
                        failed_probes = max(args.fail_threshold - 1, 0)
                    success_probes = 0
                elif coordinator.tunnel_failures == 0:
                    # While failover is active, test the non-routable canary IP (198.18.0.1).
                    # If the router is still routing LAN default traffic through WAN 2,
                    # the canary probe reaches eth1 -> wg0 and is answered by the phone's relay.
                    is_wan2_active = check_tunnel_canary(args.primary_iface, args.canary_ip, timeout=args.ping_timeout)
                    if is_wan2_active:
                        success_probes = 0
                        if active_state != "wan2":
                            logger.info(
                                f"WAN 2 active: LAN traffic routed via backup cellular ({args.failover_iface}). "
                                f"Standby for primary WAN recovery..."
                            )
                            active_state = "wan2"
                    else:
                        # Canary timed out! Outbound traffic is no longer going out WAN 2.
                        # Verify if primary WAN 1 is healthy and passing traffic to public targets.
                        wan1_ok = check_primary_wan(args.primary_iface, args.targets, timeout=args.ping_timeout)
                        if wan1_ok:
                            active_state = None
                            success_probes += 1
                            logger.info(
                                f"WAN 1 probe ({args.primary_iface}): Direct via Primary "
                                f"({success_probes}/{args.restore_threshold})"
                            )
                            if success_probes >= args.restore_threshold:
                                logger.info(">>> WAN 1 recovery confirmed! Tearing down cellular failover... <<<")
                                # Routing/NAT off first (router falls back to WAN 1 instantly), then wg0,
                                # then the phone releases its cellular radio
                                coordinator.deactivate()
                                success_probes = 0
                                logger.info("Failback to WAN 1 completed successfully.")
                        else:
                            success_probes = 0
                            if active_state != "waiting":
                                logger.warning(
                                    f"Router is not using WAN 2, but primary WAN ({args.primary_iface}) is still unreachable"
                                )
                                active_state = "waiting"
                # else: tunnel probe failing, wait for the next probes before deciding
            else:
                wan_ok = check_primary_wan(args.primary_iface, args.targets, timeout=args.ping_timeout)
                if not wan_ok:
                    failed_probes += 1
                    success_probes = 0
                    logger.warning(f"WAN 1 probe ({args.primary_iface}): FAILED ({failed_probes}/{args.fail_threshold})")

                    if failed_probes >= args.fail_threshold:
                        logger.error("!!! WAN 1 OUTAGE DETECTED !!! Activating cellular failover...")
                        if coordinator.activate():
                            failed_probes = 0
                            active_state = None
                            logger.info(">>> Cellular WAN 2 Failover 100% OPERATIONAL. Router routes traffic via smartphone. <<<")
                else:
                    failed_probes = 0

            time.sleep(args.check_interval)

    finally:
        if coordinator.active:
            logger.info("Service stopping: cleaning up routing and stopping smartphone relay...")
            coordinator.deactivate()
        discovery.stop()
        logger.info("Watchdog stopped.")


if __name__ == "__main__":
    main()
