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

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger("wan-watchdog")


class AndroidFailoverClient:
    """HTTP client to communicate with the Android app local API (port 8989)."""

    def __init__(self, phone_ip: str, http_port: int = 8989, timeout: float = 5.0):
        self.phone_ip = phone_ip
        self.http_port = http_port
        self.timeout = timeout

    @property
    def base_url(self) -> str:
        return f"http://{self.phone_ip}:{self.http_port}"

    def _request(self, path: str, method: str = "GET", data: bytes | None = None, timeout: float | None = None, attempts: int = 3) -> str:
        url = f"{self.base_url}{path}"
        headers = {"User-Agent": "Cellular-WAN-Gateway/1.0"}
        if data:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        t = timeout or self.timeout
        for attempt in range(attempts):
            try:
                with urllib.request.urlopen(req, timeout=t) as resp:
                    return resp.read().decode("utf-8")
            except (urllib.error.URLError, ConnectionError, OSError) as e:
                if attempt == attempts - 1:
                    raise
                time.sleep(0.5)
        return ""

    def get_status(self) -> dict:
        return json.loads(self._request("/v1/status"))

    def start_failover(self, gateway_public_key: str) -> dict:
        body = json.dumps({"gateway_public_key": gateway_public_key}).encode("utf-8")
        return json.loads(self._request("/v1/failover/start", method="POST", data=body, timeout=15.0))

    def stop_failover(self, timeout: float = 10.0, attempts: int = 3) -> dict:
        return json.loads(self._request("/v1/failover/stop", method="POST", data=b"", timeout=timeout, attempts=attempts))


class PhoneDiscovery:
    """Background UDP listener that keeps track of every smartphone broadcasting on the LAN."""

    def __init__(self, port: int = 8990, initial_ip: str | None = None, max_age: float = 15.0):
        self.port = port
        self.static_ip = initial_ip
        # A phone is available while its broadcasts (every 5s) keep arriving
        self.max_age = max_age
        # Most recently seen phone (used by the one-shot CLI actions)
        self.phone_ip = initial_ip
        self.device_info: dict[str, object] = {}
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

    def get_phone_ip(self) -> str | None:
        with self._lock:
            return self.phone_ip

    def record_phone(self, ip: str, payload: dict, now: float | None = None):
        now = time.monotonic() if now is None else now
        with self._lock:
            previous = self._phones.get(ip)
            if previous is None or now - previous["last_seen"] > self.max_age:
                device_name = payload.get("device", "Android Device")
                logger.info(f"[Discovery] Smartphone detected: {device_name} ({ip})")
            self._phones[ip] = {"info": payload, "last_seen": now}
            self.phone_ip = ip
            self.device_info = payload

    def get_available_phones(self, now: float | None = None) -> list[str]:
        """Phones heard within max_age, most recently seen first. A static PHONE_IP is always included."""
        now = time.monotonic() if now is None else now
        with self._lock:
            fresh = [ip for ip, p in self._phones.items() if now - p["last_seen"] <= self.max_age]
            fresh.sort(key=lambda ip: self._phones[ip]["last_seen"], reverse=True)
        if self.static_ip and self.static_ip not in fresh:
            fresh.append(self.static_ip)
        return fresh

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
                    data, addr = sock.recvfrom(2048)
                    try:
                        payload = json.loads(data.decode("utf-8"))
                        if payload.get("service") == "wan-failover" or "device" in payload:
                            self.record_phone(addr[0], payload)
                    except Exception as parse_err:
                        logger.debug(f"[Discovery] Error parsing UDP packet from {addr}: {parse_err}")
            except Exception:
                pass
        sock.close()

    def wait_for_phone(self, timeout: float = 30.0) -> str:
        start = time.time()
        while time.time() - start < timeout:
            ip = self.get_phone_ip()
            if ip:
                return ip
            time.sleep(0.5)
        raise TimeoutError(f"No smartphone detected on UDP port {self.port} after {timeout}s")


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
        http_port: int = 8989,
        wg_iface: str = "wg0",
        canary_ip: str = "198.18.0.1",
        ping_timeout: int = 2,
        tunnel_fail_threshold: int = 3,
        client_factory=None,
    ):
        self.discovery = discovery
        self.routing = routing
        self.http_port = http_port
        self.wg_iface = wg_iface
        self.canary_ip = canary_ip
        self.ping_timeout = ping_timeout
        self.tunnel_fail_threshold = tunnel_fail_threshold
        self.client_factory = client_factory or AndroidFailoverClient
        self.active_phone_ip: str | None = None
        self.tunnel_failures = 0

    @property
    def active(self) -> bool:
        return self.active_phone_ip is not None

    def _client(self, ip: str) -> "AndroidFailoverClient":
        return self.client_factory(phone_ip=ip, http_port=self.http_port)

    def _release_phone(self, ip: str):
        """Best effort: the phone may be gone, so don't wait on it."""
        try:
            res = self._client(ip).stop_failover(timeout=3.0, attempts=1)
            logger.info(f"Smartphone {ip} returned to standby: {res}")
        except Exception as e:
            logger.warning(f"Could not put smartphone {ip} back to standby: {e}")

    def _teardown_local(self):
        self.routing.disable_routing_and_nat()
        self.routing.teardown_wireguard_interface()

    def activate(self, avoid: str | None = None) -> bool:
        """Starts failover on the first available phone that succeeds. [avoid] (a phone just lost) is tried last."""
        candidates = self.discovery.get_available_phones()
        if avoid in candidates:
            candidates = [ip for ip in candidates if ip != avoid] + [avoid]
        if not candidates:
            logger.error("Cannot activate failover: no smartphone available")
            return False

        for ip in candidates:
            try:
                activate_failover(self._client(ip), self.routing)
            except Exception as e:
                logger.error(f"Failover activation via smartphone {ip} failed: {e}")
                self._teardown_local()
                self._release_phone(ip)
                continue
            self.active_phone_ip = ip
            self.tunnel_failures = 0
            logger.info(f"Failover active via smartphone {ip}")
            return True
        return False

    def deactivate(self):
        self._teardown_local()
        if self.active_phone_ip:
            self._release_phone(self.active_phone_ip)
        self.active_phone_ip = None
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
            f"Tunnel probe to smartphone {self.active_phone_ip} via {self.wg_iface} failed "
            f"({self.tunnel_failures}/{self.tunnel_fail_threshold})"
        )
        return self.tunnel_failures >= self.tunnel_fail_threshold

    def hand_over(self) -> bool:
        """Moves failover off the lost phone, preferring another one; retrying the same phone re-keys a restarted relay."""
        lost = self.active_phone_ip
        logger.error(f"!!! Smartphone {lost} lost: handing failover over to another smartphone... !!!")
        self.deactivate()
        return self.activate(avoid=lost)


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
    parser.add_argument("--phone-ip", default=os.environ.get("PHONE_IP"), help="Static Wi-Fi IP of smartphone (optional if using UDP discovery)")
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
    return parser


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    return build_parser().parse_args(argv)


def main():
    args = parse_args()

    discovery = PhoneDiscovery(port=args.discovery_port, initial_ip=args.phone_ip)
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
        http_port=args.http_port,
        wg_iface=args.wg_iface,
        canary_ip=args.canary_ip,
        ping_timeout=args.ping_timeout,
        tunnel_fail_threshold=args.tunnel_fail_threshold,
    )

    def get_client() -> AndroidFailoverClient:
        ip = discovery.get_phone_ip()
        if not ip:
            logger.info("Waiting for smartphone discovery...")
            ip = discovery.wait_for_phone(timeout=15.0)
        return AndroidFailoverClient(phone_ip=ip, http_port=args.http_port)

    # CLI one-shot actions
    if args.discover_only:
        try:
            ip = discovery.wait_for_phone(timeout=30.0)
            print(f"Smartphone discovered: IP={ip}, Info={discovery.device_info}")
        except TimeoutError as e:
            print(f"Error: {e}")
            sys.exit(1)
        finally:
            discovery.stop()
        return

    if args.status_only:
        client = get_client()
        print(f"Connecting to {client.base_url}...")
        status = client.get_status()
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
        get_client()  # waits for a first smartphone
        activated = coordinator.activate()
        discovery.stop()
        if not activated:
            sys.exit(1)
        logger.info("Cellular failover manually activated successfully.")
        return

    if args.stop_now:
        get_client()  # waits for a first smartphone
        # Listen one more broadcast interval so every phone (only one carries the failover) is known
        time.sleep(6)
        routing.disable_routing_and_nat()
        routing.teardown_wireguard_interface()
        for ip in discovery.get_available_phones():
            logger.info(f"Manual deactivation on {ip}...")
            coordinator._release_phone(ip)
        logger.info("Cellular failover stopped.")
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
    logger.info("====================================================================")

    # Smartphone check at startup
    if not args.phone_ip:
        logger.info("Initial discovery for smartphone on local Wi-Fi...")
        try:
            detected_ip = discovery.wait_for_phone(timeout=10.0)
            logger.info(f"Smartphone ready: {detected_ip}")
        except TimeoutError:
            logger.warning("Smartphone not detected yet (discovery continues in background)")

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
                        logger.info(f">>> Failover handed over to smartphone {coordinator.active_phone_ip}. <<<")
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
