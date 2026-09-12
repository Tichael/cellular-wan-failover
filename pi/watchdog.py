#!/usr/bin/env python3
"""
Raspberry Pi WAN Failover Watchdog (WireGuard + Policy Routing)
Monitors WAN 1 (Fiber/Cable) connectivity via eth0 and automatically fails over
to the Android cellular relay via WireGuard when an outage is detected.
"""

import argparse
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

    def _request(self, path: str, method: str = "GET", data: bytes | None = None, timeout: float | None = None) -> str:
        url = f"{self.base_url}{path}"
        req = urllib.request.Request(url, data=data, headers={"User-Agent": "RPi-Watchdog/1.0"}, method=method)
        t = timeout or self.timeout
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=t) as resp:
                    return resp.read().decode("utf-8")
            except (urllib.error.URLError, ConnectionError, OSError) as e:
                if attempt == 2:
                    raise
                time.sleep(0.5)
        return ""

    def get_status(self) -> dict:
        return json.loads(self._request("/v1/status"))

    def get_wireguard_config(self) -> str:
        return self._request("/v1/wireguard/config")

    def start_failover(self) -> dict:
        return json.loads(self._request("/v1/failover/start", method="POST", data=b"", timeout=15.0))

    def stop_failover(self) -> dict:
        return json.loads(self._request("/v1/failover/stop", method="POST", data=b"", timeout=10.0))


class PhoneDiscovery:
    """Background UDP listener to discover and keep track of smartphone IP."""

    def __init__(self, port: int = 8990, initial_ip: str | None = None):
        self.port = port
        self.phone_ip = initial_ip
        self.device_info = {}
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
                            discovered_ip = addr[0]
                            with self._lock:
                                if self.phone_ip != discovered_ip:
                                    device_name = payload.get("device", "Android Device")
                                    logger.info(f"[Discovery] Smartphone detected: {device_name} ({discovered_ip})")
                                self.phone_ip = discovered_ip
                                self.device_info = payload
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

    def __init__(self, primary_iface: str = "eth0", failover_iface: str = "eth1", wg_iface: str = "wg0", table_id: int = 100):
        self.primary_iface = primary_iface
        self.failover_iface = failover_iface
        self.wg_iface = wg_iface
        self.table_id = str(table_id)

    def _run(self, cmd: list[str], check: bool = False) -> subprocess.CompletedProcess:
        try:
            return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=check)
        except subprocess.CalledProcessError as e:
            logger.error(f"Command error {' '.join(cmd)}: {e.stderr.strip()}")
            raise

    def setup_wireguard_interface(self, config_text: str):
        """Adapts and writes WireGuard config received from Android, then brings up interface."""
        # Adjust config to prevent wg-quick from overriding default routing table
        # and avoid resolvconf conflicts in a minimal container.
        lines = []
        for line in config_text.splitlines():
            stripped = line.strip()
            # Strip DNS to avoid altering container/host resolver
            if stripped.startswith("DNS"):
                continue
            lines.append(line)

        # Add Table = off under [Interface] to delegate routing to isolated table
        adapted_lines = []
        under_interface = False
        table_added = False
        for line in lines:
            adapted_lines.append(line)
            if line.strip().lower() == "[interface]":
                under_interface = True
            elif line.strip().startswith("[") and under_interface:
                if not table_added:
                    adapted_lines.insert(len(adapted_lines) - 1, f"Table = off")
                    table_added = True
                under_interface = False
        if under_interface and not table_added:
            adapted_lines.append(f"Table = off")

        conf_path = f"/etc/wireguard/{self.wg_iface}.conf"
        os.makedirs("/etc/wireguard", exist_ok=True)
        with open(conf_path, "w") as f:
            f.write("\n".join(adapted_lines) + "\n")
        os.chmod(conf_path, 0o600)

        # Preemptive teardown if wg0 was already active
        self._run(["wg-quick", "down", self.wg_iface])

        logger.info(f"Bringing up WireGuard ({self.wg_iface})...")
        self._run(["wg-quick", "up", self.wg_iface], check=True)
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

        logger.info(f"Transit and NAT configured: {self.failover_iface} -> {self.wg_iface} -> Cellular.")

    def disable_routing_and_nat(self):
        """Removes iptables rules and dedicated routing rule."""
        logger.info(f"Cleaning up iptables rules and removing dedicated route (Table {self.table_id})...")

        # 1. Remove iptables forwarding & NAT rules
        self._run(["iptables", "-D", "FORWARD", "-i", self.failover_iface, "-o", self.wg_iface, "-j", "ACCEPT"])
        self._run(["iptables", "-D", "FORWARD", "-i", self.wg_iface, "-o", self.failover_iface, "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT"])
        self._run(["iptables", "-t", "nat", "-D", "POSTROUTING", "-o", self.wg_iface, "-j", "MASQUERADE"])

        # 2. Remove ip rule
        while True:
            res = self._run(["ip", "rule", "del", "iif", self.failover_iface, "table", self.table_id])
            if res.returncode != 0:
                break

        # 3. Flush routing table
        self._run(["ip", "route", "flush", "table", self.table_id])
        logger.info("Network cleanup complete.")


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


def main():
    parser = argparse.ArgumentParser(description="Cellular WAN Failover Daemon (Raspberry Pi <-> Android)")
    parser.add_argument("--primary-iface", default=os.environ.get("PRIMARY_IFACE", "eth0"), help="Primary WAN 1 interface connected to LAN (default: eth0)")
    parser.add_argument("--failover-iface", default=os.environ.get("FAILOVER_IFACE", "eth1"), help="Failover WAN 2 interface connected to router (default: eth1)")
    parser.add_argument("--failover-gateway", default=os.environ.get("FAILOVER_GATEWAY", "192.168.100.1"), help="IP address of failover gateway on failover interface (default: 192.168.100.1)")
    parser.add_argument("--wg-iface", default=os.environ.get("WG_IFACE", "wg0"), help="WireGuard interface name (default: wg0)")
    parser.add_argument("--phone-ip", default=os.environ.get("PHONE_IP"), help="Static Wi-Fi IP of smartphone (optional if using UDP discovery)")
    parser.add_argument("--http-port", type=int, default=int(os.environ.get("HTTP_PORT", 8989)), help="Smartphone HTTP API port (default: 8989)")
    parser.add_argument("--discovery-port", type=int, default=int(os.environ.get("DISCOVERY_PORT", 8990)), help="UDP discovery port (default: 8990)")
    parser.add_argument("--targets", nargs="+", default=os.environ.get("PING_TARGETS", "1.1.1.1 8.8.8.8").split(), help="Ping target addresses")
    parser.add_argument("--canary-ip", default=os.environ.get("CANARY_IP", "198.18.0.1"), help="Non-routable RFC 2544 canary IP synthesized only by tunnel (default: 198.18.0.1)")
    parser.add_argument("--fail-threshold", type=int, default=int(os.environ.get("FAIL_THRESHOLD", 3)), help="Consecutive failures before failover")
    parser.add_argument("--restore-threshold", type=int, default=int(os.environ.get("RESTORE_THRESHOLD", 5)), help="Consecutive successes before failback")
    parser.add_argument("--check-interval", type=int, default=int(os.environ.get("CHECK_INTERVAL", 5)), help="Health check interval in seconds")
    parser.add_argument("--discover-only", action="store_true", help="Print discovered smartphone IP and exit")
    parser.add_argument("--status-only", action="store_true", help="Print smartphone status and exit")
    parser.add_argument("--test-route", action="store_true", help="Test if current outbound traffic from primary interface is hairpinned through WAN 2")
    parser.add_argument("--start-now", action="store_true", help="Force immediate failover activation and exit")
    parser.add_argument("--stop-now", action="store_true", help="Force immediate failover teardown and exit")
    args = parser.parse_args()

    discovery = PhoneDiscovery(port=args.discovery_port, initial_ip=args.phone_ip)
    discovery.start()

    routing = RoutingManager(
        primary_iface=args.primary_iface,
        failover_iface=args.failover_iface,
        wg_iface=args.wg_iface,
        table_id=100
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
        wan_ok = check_primary_wan(args.primary_iface, args.targets)
        canary_ok = check_tunnel_canary(args.primary_iface, args.canary_ip)
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
        client = get_client()
        cfg = client.get_wireguard_config()
        res = client.start_failover()
        logger.info(f"Smartphone wake response: {res}")
        routing.setup_wireguard_interface(cfg)
        routing.enable_routing_and_nat()
        logger.info("Cellular failover manually activated successfully.")
        discovery.stop()
        return

    if args.stop_now:
        client = get_client()
        logger.info(f"Manual deactivation on {client.phone_ip}...")
        routing.disable_routing_and_nat()
        routing.teardown_wireguard_interface()
        client.stop_failover()
        logger.info("Cellular failover stopped.")
        discovery.stop()
        return

    # Main monitoring loop
    failover_active = False
    failed_probes = 0
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
    logger.info(f"  - WireGuard interface        : {args.wg_iface}")
    logger.info(f"  - ICMP probe targets         : {', '.join(args.targets)}")
    logger.info(f"  - Non-routable canary IP     : {args.canary_ip}")
    logger.info(f"  - Fail / restore thresholds  : {args.fail_threshold} failures / {args.restore_threshold} successes")
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
            if failover_active:
                # While failover is active, test the non-routable canary IP (198.18.0.1).
                # If the router is still routing LAN default traffic through WAN 2,
                # the canary probe reaches eth1 -> wg0 and is answered by the phone's relay.
                is_wan2_active = check_tunnel_canary(args.primary_iface, args.canary_ip)
                if is_wan2_active:
                    success_probes = 0
                    logger.info(
                        f"WAN 2 active: LAN traffic routed via backup cellular ({args.failover_iface}). "
                        f"Standby for primary WAN recovery..."
                    )
                else:
                    # Canary timed out! Outbound traffic is no longer going out WAN 2.
                    # Verify if primary WAN 1 is healthy and passing traffic to public targets.
                    wan1_ok = check_primary_wan(args.primary_iface, args.targets)
                    if wan1_ok:
                        success_probes += 1
                        logger.info(
                            f"WAN 1 probe ({args.primary_iface}): Direct via Primary "
                            f"({success_probes}/{args.restore_threshold})"
                        )
                        if success_probes >= args.restore_threshold:
                            logger.info(">>> WAN 1 recovery confirmed! Tearing down cellular failover... <<<")
                            current_phone_ip = discovery.get_phone_ip()

                            # 1. Immediate NAT transit deactivation (router falls back to WAN 1 instantly)
                            routing.disable_routing_and_nat()

                            # 2. Tear down WireGuard tunnel
                            routing.teardown_wireguard_interface()

                            # 3. Release mobile cellular radio on smartphone
                            if current_phone_ip:
                                try:
                                    client = AndroidFailoverClient(phone_ip=current_phone_ip, http_port=args.http_port)
                                    res = client.stop_failover()
                                    logger.info(f"Smartphone returned to standby: {res}")
                                except Exception as e:
                                    logger.warning(f"Error while putting smartphone to standby: {e}")

                            failover_active = False
                            success_probes = 0
                            logger.info("Failback to WAN 1 completed successfully.")
                    else:
                        success_probes = 0
                        logger.warning(
                            f"WAN probe failed: router left WAN 2, but primary WAN ({args.primary_iface}) still unreachable"
                        )
            else:
                wan_ok = check_primary_wan(args.primary_iface, args.targets)
                if not wan_ok:
                    failed_probes += 1
                    success_probes = 0
                    logger.warning(f"WAN 1 probe ({args.primary_iface}): FAILED ({failed_probes}/{args.fail_threshold})")

                    if failed_probes >= args.fail_threshold:
                        logger.error("!!! WAN 1 OUTAGE DETECTED !!! Activating cellular failover...")
                        current_phone_ip = discovery.get_phone_ip()
                        if not current_phone_ip:
                            logger.error("Cannot activate failover: no smartphone reachable!")
                        else:
                            client = AndroidFailoverClient(phone_ip=current_phone_ip, http_port=args.http_port)
                            try:
                                # 1. Fetch WireGuard configuration
                                wg_cfg = client.get_wireguard_config()

                                # 2. Command smartphone to activate cellular and WireGuard
                                res = client.start_failover()
                                logger.info(f"Smartphone wake response: {res}")

                                # 3. Bring up local WireGuard interface wg0
                                routing.setup_wireguard_interface(wg_cfg)

                                # 4. Activate dedicated routing eth1 -> wg0 and MASQUERADE
                                routing.enable_routing_and_nat()

                                failover_active = True
                                failed_probes = 0
                                logger.info(">>> Cellular WAN 2 Failover 100% OPERATIONAL. Router routes traffic via smartphone. <<<")
                            except Exception as e:
                                logger.error(f"Error during failover activation: {e}")
                else:
                    failed_probes = 0

            time.sleep(args.check_interval)

    finally:
        if failover_active:
            logger.info("Service stopping: cleaning up routing and stopping smartphone relay...")
            routing.disable_routing_and_nat()
            routing.teardown_wireguard_interface()
            current_phone_ip = discovery.get_phone_ip()
            if current_phone_ip:
                try:
                    client = AndroidFailoverClient(phone_ip=current_phone_ip, http_port=args.http_port)
                    client.stop_failover()
                except Exception:
                    pass
        discovery.stop()
        logger.info("Watchdog stopped.")


if __name__ == "__main__":
    main()
