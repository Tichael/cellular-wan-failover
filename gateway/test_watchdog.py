import http.server
import json
import os
import subprocess
import threading
import time
import urllib.error
from unittest.mock import MagicMock, patch

import pairing
import pytest
from watchdog import (
    AndroidFailoverClient,
    FailoverCoordinator,
    FailoverLoop,
    PhoneAuthError,
    PhoneDiscovery,
    PhoneRequestError,
    RoutingManager,
    StandbyDeclined,
    PhoneState,
    bring_up_tunnel,
    start_phone_relay,
    manual_stop,
    build_wg_config,
    check_primary_wan,
    check_tunnel_canary,
    generate_wg_keypair,
    parse_args,
    validate_ipv4,
    validate_port,
    validate_wg_key,
)

KEY_A = "YNqHbfBQKaGvzefSSMvi/A7nLC4sQ+iAo56KNNQAo2E="
KEY_B = "nGrA9Rfrm/XXy1OI0Nn2D9VoFFdbg+QnqWJyolQaDHw="
KEY_C = "8BWqdg4V2GV7RVyiUb6Q4GPqHHQlOfzCoA1bBoGwn2g="


GW_ID = "Z2F0ZXdheS1pZC0wMDAwMQ"
PHONE_KEY = bytes(range(32))


class FakePhone:
    """Minimal phone-side API: issues challenges, checks request signatures, signs answers."""

    def __init__(self, phone_id="cGhvbmUtaWQtMDAwMDAwMQ", key=PHONE_KEY, sign_key=None):
        self.gateway_id = GW_ID
        self.phone_id = phone_id
        self.key = key
        # Key used to sign answers (a different one simulates an impostor)
        self.sign_key = sign_key or key
        self.challenges = set()
        self.requests = []
        self.routes = {}
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _answer(self, status, payload, nonce=None, signed=True):
                body = json.dumps(payload).encode()
                self.send_response(status)
                if signed and nonce is not None:
                    fields = pairing.response_fields(fake.gateway_id, fake.phone_id, nonce, status, body)
                    self.send_header(pairing.HEADER_SIGNATURE, pairing.sign(fake.sign_key, fields))
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _handle(self, method):
                if self.path == "/v1/challenge":
                    challenge = pairing.new_id()
                    fake.challenges.add(challenge)
                    return self._answer(200, {"challenge": challenge}, signed=False)
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                challenge = self.headers.get(pairing.HEADER_CHALLENGE)
                nonce = self.headers.get(pairing.HEADER_NONCE)
                fields = pairing.request_fields(
                    self.headers.get(pairing.HEADER_GATEWAY), self.headers.get(pairing.HEADER_PHONE), method, self.path, challenge, nonce, body
                )
                valid = challenge in fake.challenges and pairing.verify(fake.key, self.headers.get(pairing.HEADER_SIGNATURE), fields)
                fake.challenges.discard(challenge)
                if not valid:
                    return self._answer(401, {"result": "error"}, signed=False)
                fake.requests.append((method, self.path, body))
                route = fake.routes.get(self.path, (200, {"result": "ok"}))
                status, payload = route() if callable(route) else route
                self._answer(status, payload, nonce)

            def do_GET(self):
                self._handle("GET")

            def do_POST(self):
                self._handle("POST")

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()

    def client(self, key=None):
        return AndroidFailoverClient(
            phone_ip="127.0.0.1", phone_id=self.phone_id, key=key or self.key, gateway_id=self.gateway_id, http_port=self.port, timeout=2.0
        )

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def phone():
    fake = FakePhone()
    yield fake
    fake.close()


class TestAndroidFailoverClient:
    def test_base_url(self):
        client = AndroidFailoverClient(phone_ip="192.168.1.50", phone_id="p", key=PHONE_KEY, gateway_id=GW_ID, http_port=8989)
        assert client.base_url == "http://192.168.1.50:8989"

    def test_signed_status_round_trip(self, phone):
        phone.routes["/v1/status"] = (200, {"status": "idle", "cellular_ready": True})
        status = phone.client().get_status()
        assert status == {"status": "idle", "cellular_ready": True}
        assert phone.requests == [("GET", "/v1/status", b"")]

    def test_start_failover_sends_signed_key(self, phone):
        phone.routes["/v1/failover/start"] = (200, {"result": "active"})
        assert phone.client().start_failover(KEY_A) == {"result": "active"}
        method, path, body = phone.requests[0]
        assert (method, path) == ("POST", "/v1/failover/start")
        assert json.loads(body) == {"gateway_public_key": KEY_A}

    def test_stop_failover(self, phone):
        phone.routes["/v1/failover/stop"] = (200, {"result": "idle"})
        assert phone.client().stop_failover() == {"result": "idle"}

    def test_each_request_uses_a_new_challenge(self, phone):
        client = phone.client()
        client.get_status()
        client.get_status()
        assert len(phone.requests) == 2
        assert phone.challenges == set()

    def test_rejected_signature_raises_auth_error(self, phone):
        with pytest.raises(PhoneAuthError, match="rejected our signature"):
            phone.client(key=bytes(32)).get_status()
        assert phone.requests == []

    def test_answer_signed_by_impostor_is_rejected(self):
        # e.g. another LAN device answering in place of the phone with its own WireGuard key
        impostor = FakePhone(sign_key=bytes(32))
        impostor.routes["/v1/status"] = (200, {"wireguard": {"public_key": KEY_C}})
        try:
            with pytest.raises(PhoneAuthError, match="not signed by the paired phone"):
                impostor.client().get_status()
        finally:
            impostor.close()

    def test_signed_refusal_raises_request_error(self, phone):
        phone.routes["/v1/failover/start"] = (409, {"result": "busy", "message": "Serving another gateway"})
        with pytest.raises(PhoneRequestError, match="Serving another gateway") as err:
            phone.client().start_failover(KEY_A)
        assert err.value.status == 409

    @patch("time.sleep")
    def test_retry_on_failure_and_recover(self, mock_sleep, phone):
        real_send = AndroidFailoverClient._send_once
        calls = []

        def flaky(self, *args):
            calls.append(1)
            if len(calls) == 1:
                raise urllib.error.URLError("Connection refused")
            return real_send(self, *args)

        with patch.object(AndroidFailoverClient, "_send_once", flaky):
            assert phone.client().get_status() == {"result": "ok"}
        assert len(calls) == 2
        assert mock_sleep.call_count == 1

    @patch("time.sleep")
    @patch("urllib.request.urlopen")
    def test_max_retries_exceeded_raises(self, mock_urlopen, mock_sleep):
        mock_urlopen.side_effect = urllib.error.URLError("Host unreachable")
        client = AndroidFailoverClient(phone_ip="10.20.0.5", phone_id="p", key=PHONE_KEY, gateway_id=GW_ID)
        with pytest.raises(urllib.error.URLError, match="Host unreachable"):
            client.get_status()
        assert mock_urlopen.call_count == 3


def discovery_packet(entries) -> bytes:
    return json.dumps({"service": "wan-failover", "v": 1, "pairings": entries}).encode()


def signed_entry(phone_id, ip, key=PHONE_KEY, gateway_id=GW_ID, standby="cold", battery=85, plugged=False, min_battery=20, last_resort=True):
    sig = pairing.sign(key, pairing.discovery_fields(gateway_id, phone_id, ip, standby, battery, plugged, min_battery, last_resort))
    return {
        "gw": gateway_id, "pid": phone_id, "standby": standby, "battery": battery, "plugged": plugged,
        "min_battery": min_battery, "last_resort": last_resort, "sig": sig,
    }


class TestPhoneDiscovery:
    def make(self, keys=None):
        keys = keys if keys is not None else {"phoneA": PHONE_KEY, "phoneB": PHONE_KEY}
        return PhoneDiscovery(gateway_id=GW_ID, key_for=keys.get, port=18990, max_age=15.0)

    def test_start_and_stop(self):
        discovery = self.make()
        discovery.start()
        assert discovery._running is True
        discovery.stop()
        assert discovery._running is False

    def test_accepts_signed_broadcast(self):
        discovery = self.make()
        assert discovery.handle_packet("10.20.0.10", discovery_packet([signed_entry("phoneA", "10.20.0.10")]), now=100.0) == ["phoneA"]
        assert discovery.get_available_phones(now=101.0) == [("phoneA", "10.20.0.10")]

    def test_records_signed_hot_standby(self):
        discovery = self.make()
        discovery.handle_packet("10.20.0.10", discovery_packet([signed_entry("phoneA", "10.20.0.10", standby="hot")]), now=100.0)
        assert discovery.standby_of("phoneA", now=101.0) == "hot"
        # A silent phone offers nothing
        assert discovery.standby_of("phoneA", now=200.0) == "cold"

    def test_standby_switches_back_to_cold(self):
        discovery = self.make()
        discovery.handle_packet("10.20.0.10", discovery_packet([signed_entry("phoneA", "10.20.0.10", standby="hot")]), now=100.0)
        discovery.handle_packet("10.20.0.10", discovery_packet([signed_entry("phoneA", "10.20.0.10", standby="cold")]), now=105.0)
        assert discovery.standby_of("phoneA", now=106.0) == "cold"

    @pytest.mark.parametrize("standby", [None, "", "lukewarm", 1])
    def test_ignores_entry_without_valid_standby(self, standby):
        discovery = self.make()
        entry = signed_entry("phoneA", "10.20.0.10")
        entry["standby"] = standby
        assert discovery.handle_packet("10.20.0.10", discovery_packet([entry]), now=100.0) == []

    def test_standby_is_covered_by_the_signature(self):
        # Flipping "cold" to "hot" breaks the signature: the entry is ignored
        discovery = self.make()
        entry = signed_entry("phoneA", "10.20.0.10", standby="cold") | {"standby": "hot"}
        assert discovery.handle_packet("10.20.0.10", discovery_packet([entry]), now=100.0) == []
        assert discovery.standby_of("phoneA", now=101.0) == "cold"

    def test_records_signed_battery(self):
        discovery = self.make()
        entry = signed_entry("phoneA", "10.20.0.10", battery=42, plugged=True, min_battery=30, last_resort=False)
        discovery.handle_packet("10.20.0.10", discovery_packet([entry]), now=100.0)
        state = discovery.state_of("phoneA", now=101.0)
        assert (state.battery, state.plugged, state.min_battery, state.last_resort) == (42, True, 30, False)
        assert discovery.state_of("phoneA", now=200.0) is None

    @pytest.mark.parametrize("field, value", [
        ("battery", None), ("battery", -1), ("battery", 101), ("battery", "85"), ("battery", True), ("battery", 85.0),
        ("plugged", None), ("plugged", "true"), ("plugged", 1),
        ("min_battery", None), ("min_battery", 101), ("min_battery", "20"),
        ("last_resort", None), ("last_resort", 0),
    ])
    def test_ignores_entry_without_valid_battery(self, field, value):
        discovery = self.make()
        entry = signed_entry("phoneA", "10.20.0.10") | {field: value}
        assert discovery.handle_packet("10.20.0.10", discovery_packet([entry]), now=100.0) == []

    def test_battery_is_covered_by_the_signature(self):
        discovery = self.make()
        for tampered in ({"battery": 100}, {"plugged": True}, {"min_battery": 5}, {"last_resort": False}):
            entry = signed_entry("phoneA", "10.20.0.10", battery=10) | tampered
            assert discovery.handle_packet("10.20.0.10", discovery_packet([entry]), now=100.0) == []

    def test_since_tracks_how_long_the_announcement_is_steady(self):
        discovery = self.make()
        discovery.record_phone("phoneA", "10.20.0.10", now=100.0, battery=80)
        # Battery and power changes don't count as a new announcement
        discovery.record_phone("phoneA", "10.20.0.10", now=105.0, battery=79, plugged=True)
        assert discovery.state_of("phoneA", now=106.0).since == 100.0
        discovery.record_phone("phoneA", "10.20.0.10", now=110.0, standby="hot")
        assert discovery.state_of("phoneA", now=111.0).since == 110.0
        discovery.record_phone("phoneA", "10.20.0.42", now=115.0, standby="hot")
        assert discovery.state_of("phoneA", now=116.0).since == 115.0
        # Silent for a while, then back
        discovery.record_phone("phoneA", "10.20.0.42", now=200.0, standby="hot")
        assert discovery.state_of("phoneA", now=201.0).since == 200.0

    def test_picks_own_entry_among_other_gateways(self):
        discovery = self.make()
        packet = discovery_packet([
            signed_entry("phoneX", "10.20.0.10", gateway_id="b3RoZXItZ2F0ZXdheQ"),
            signed_entry("phoneA", "10.20.0.10"),
        ])
        assert discovery.handle_packet("10.20.0.10", packet, now=100.0) == ["phoneA"]

    @pytest.mark.parametrize("packet", [
        b"not json",
        b"[]",
        json.dumps({"service": "other", "pairings": []}).encode(),
        json.dumps({"service": "wan-failover", "pairings": "x"}).encode(),
        json.dumps({"service": "wan-failover", "device": "Pixel"}).encode(),
    ])
    def test_ignores_malformed_packets(self, packet):
        discovery = self.make()
        assert discovery.handle_packet("10.20.0.10", packet, now=100.0) == []
        assert discovery.get_available_phones(now=100.0) == []

    def test_ignores_unknown_phone(self):
        discovery = self.make(keys={})
        assert discovery.handle_packet("10.20.0.10", discovery_packet([signed_entry("phoneA", "10.20.0.10")])) == []

    def test_ignores_bad_signature(self):
        discovery = self.make()
        entry = signed_entry("phoneA", "10.20.0.10", key=bytes(32))
        assert discovery.handle_packet("10.20.0.10", discovery_packet([entry])) == []

    def test_ignores_broadcast_replayed_from_another_address(self):
        discovery = self.make()
        packet = discovery_packet([signed_entry("phoneA", "10.20.0.10")])
        assert discovery.handle_packet("10.20.0.66", packet) == []

    def test_tracks_multiple_phones_most_recent_first(self):
        discovery = self.make()
        discovery.record_phone("phoneA", "10.20.0.10", now=100.0)
        discovery.record_phone("phoneB", "10.20.0.11", now=103.0)
        assert discovery.get_available_phones(now=104.0) == [("phoneB", "10.20.0.11"), ("phoneA", "10.20.0.10")]

    def test_silent_phone_becomes_unavailable(self):
        discovery = self.make()
        discovery.record_phone("phoneA", "10.20.0.10", now=100.0)
        discovery.record_phone("phoneB", "10.20.0.11", now=110.0)
        # Phone A stopped broadcasting (battery, crash, out of range)
        assert discovery.get_available_phones(now=120.0) == [("phoneB", "10.20.0.11")]

    def test_phone_ip_change_is_followed(self):
        discovery = self.make()
        discovery.record_phone("phoneA", "10.20.0.10", now=100.0)
        discovery.record_phone("phoneA", "10.20.0.42", now=105.0)
        assert discovery.get_available_phones(now=106.0) == [("phoneA", "10.20.0.42")]

    def test_wait_for_specific_phone(self):
        discovery = self.make()
        discovery.record_phone("phoneA", "10.20.0.10")
        discovery.record_phone("phoneB", "10.20.0.11")
        assert discovery.wait_for_phone(timeout=1.0, phone_id="phoneA") == ("phoneA", "10.20.0.10")
        with pytest.raises(TimeoutError):
            discovery.wait_for_phone(timeout=0.1, phone_id="phoneC")


class TestNetworkProbes:
    @patch("subprocess.run")
    def test_canary_probe_success(self, mock_run):
        mock_run.return_value = MagicMock(returncode=0)
        assert check_tunnel_canary(iface="eth0", canary_ip="198.18.0.1", timeout=2) is True
        mock_run.assert_called_once_with(
            ["ping", "-I", "eth0", "-c", "1", "-W", "2", "198.18.0.1"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    @patch("subprocess.run")
    def test_canary_probe_failure(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1)
        assert check_tunnel_canary(iface="eth0", canary_ip="198.18.0.1", timeout=2) is False

    @patch("subprocess.run")
    def test_ping_check_first_success(self, mock_run):
        mock_run.return_value = MagicMock(returncode=0)
        assert check_primary_wan(iface="eth0", targets=["1.1.1.1", "8.8.8.8"], timeout=2) is True
        assert mock_run.call_count == 1

    @patch("subprocess.run")
    def test_ping_check_fallback_success(self, mock_run):
        mock_run.side_effect = [MagicMock(returncode=1), MagicMock(returncode=0)]
        assert check_primary_wan(iface="eth0", targets=["1.1.1.1", "8.8.8.8"], timeout=2) is True
        assert mock_run.call_count == 2

    @patch("subprocess.run")
    def test_ping_check_all_failed(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1)
        assert check_primary_wan(iface="eth0", targets=["1.1.1.1", "8.8.8.8"], timeout=2) is False
        assert mock_run.call_count == 2


class TestRoutingManager:
    @patch("subprocess.run")
    def test_setup_wireguard_interface_writes_private_file(self, mock_run, tmp_path):
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
        conf_dir = tmp_path / "wireguard"
        conf_path = conf_dir / "wg0.conf"
        # Pre-existing world-readable file must be tightened
        conf_dir.mkdir()
        conf_path.write_text("old")
        os.chmod(conf_path, 0o644)

        manager = RoutingManager(wg_iface="wg0")
        manager.setup_wireguard_interface("[Interface]\n", conf_dir=str(conf_dir))

        assert conf_path.read_text() == "[Interface]\n"
        assert (conf_path.stat().st_mode & 0o777) == 0o600
        cmds = [call[0][0] for call in mock_run.call_args_list]
        assert ["wg-quick", "up", str(conf_path)] in cmds

    @patch("subprocess.run")
    def test_enable_routing_and_nat_with_clamp_mss(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1, stdout="")
        manager = RoutingManager(failover_iface="eth1", wg_iface="wg0", table_id=105, clamp_mss=True)
        manager.enable_routing_and_nat()

        cmds = [call[0][0] for call in mock_run.call_args_list]
        # Check table_id used in route and rule
        assert ["ip", "route", "replace", "default", "dev", "wg0", "table", "105"] in cmds
        assert ["ip", "rule", "add", "iif", "eth1", "table", "105"] in cmds
        # Check TCP MSS rule check and add
        assert ["iptables", "-t", "mangle", "-C", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"] in cmds
        assert ["iptables", "-t", "mangle", "-A", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"] in cmds

    @patch("subprocess.run")
    def test_enable_routing_and_nat_without_clamp_mss(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1, stdout="")
        manager = RoutingManager(failover_iface="eth1", wg_iface="wg0", table_id=105, clamp_mss=False)
        manager.enable_routing_and_nat()

        cmds = [call[0][0] for call in mock_run.call_args_list]
        # Ensure TCPMSS is not called
        for cmd in cmds:
            assert "TCPMSS" not in cmd

    @patch("subprocess.run")
    def test_disable_routing_and_nat_with_clamp_mss(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1, stdout="")
        manager = RoutingManager(failover_iface="eth1", wg_iface="wg0", table_id=105, clamp_mss=True)
        manager.disable_routing_and_nat()

        cmds = [call[0][0] for call in mock_run.call_args_list]
        assert ["iptables", "-t", "mangle", "-D", "FORWARD", "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"] in cmds
        assert ["ip", "route", "flush", "table", "105"] in cmds


class TestWireGuardConfig:
    def test_validate_wg_key_canonical(self):
        assert validate_wg_key(KEY_A) == KEY_A

    @pytest.mark.parametrize("bad", [
        None,
        42,
        "",
        "not base64!!",
        "AAAA",  # too short
        KEY_A + "\nPostUp = touch /tmp/pwned",
        KEY_A[:-1] + "\nPostUp = id",
    ])
    def test_validate_wg_key_rejects(self, bad):
        with pytest.raises(ValueError):
            validate_wg_key(bad)

    @pytest.mark.parametrize("bad", ["8.8.8.8", "10.100.0.2\nPostUp = id", "fe80::1", "10.100.0", None])
    def test_validate_ipv4_private_rejects(self, bad):
        with pytest.raises(ValueError):
            validate_ipv4(bad, require_private=True)

    @pytest.mark.parametrize("bad", [0, 65536, True, "51820", None, 51820.0])
    def test_validate_port_rejects(self, bad):
        with pytest.raises(ValueError):
            validate_port(bad)

    def test_build_wg_config_exact(self):
        cfg = build_wg_config(KEY_A, KEY_B, "192.168.1.42", 51820, "10.100.0.2")
        assert cfg == (
            "[Interface]\n"
            f"PrivateKey = {KEY_A}\n"
            "Address = 10.100.0.2/24\n"
            "Table = off\n"
            "\n"
            "[Peer]\n"
            f"PublicKey = {KEY_B}\n"
            "Endpoint = 192.168.1.42:51820\n"
            "AllowedIPs = 0.0.0.0/0\n"
            "PersistentKeepalive = 25\n"
        )

    def test_build_wg_config_rejects_injection(self):
        with pytest.raises(ValueError):
            build_wg_config(KEY_A, KEY_B + "\nPostUp = id", "192.168.1.42", 51820, "10.100.0.2")

    @patch("subprocess.run")
    def test_generate_wg_keypair(self, mock_run):
        mock_run.side_effect = [
            MagicMock(stdout=KEY_A + "\n"),
            MagicMock(stdout=KEY_B + "\n"),
        ]
        assert generate_wg_keypair() == (KEY_A, KEY_B)
        assert mock_run.call_args_list[0][0][0] == ["wg", "genkey"]
        assert mock_run.call_args_list[1][0][0] == ["wg", "pubkey"]
        assert mock_run.call_args_list[1][1]["input"] == KEY_A

    @patch("watchdog.generate_wg_keypair", return_value=(KEY_A, KEY_B))
    def test_start_phone_relay_flow(self, _mock_keys):
        client = MagicMock()
        client.phone_ip = "192.168.1.42"
        client.get_status.return_value = {
            "status": "idle",
            "wifi_ip": "6.6.6.6",  # must be ignored in favour of client.phone_ip
            "wireguard": {"port": 51820, "public_key": KEY_C, "tunnel_ip": "10.100.0.1", "peer_ip": "10.100.0.2"},
        }
        client.start_failover.return_value = {"result": "active"}

        cfg = start_phone_relay(client)

        client.start_failover.assert_called_once_with(KEY_B)
        assert f"PrivateKey = {KEY_A}" in cfg
        assert f"PublicKey = {KEY_C}" in cfg
        assert "Endpoint = 192.168.1.42:51820" in cfg

    def test_bring_up_tunnel(self):
        routing = MagicMock()
        bring_up_tunnel(routing, "config")
        routing.setup_wireguard_interface.assert_called_once_with("config")
        routing.enable_routing_and_nat.assert_called_once()

    @patch("watchdog.generate_wg_keypair", return_value=(KEY_A, KEY_B))
    def test_start_phone_relay_rejects_bad_status_before_waking_phone(self, _mock_keys):
        client = MagicMock()
        client.phone_ip = "192.168.1.42"
        client.get_status.return_value = {
            "wireguard": {"port": 51820, "public_key": "x\nPostUp = id", "peer_ip": "10.100.0.2"},
        }

        with pytest.raises(ValueError):
            start_phone_relay(client)
        client.start_failover.assert_not_called()


@patch("watchdog.generate_wg_keypair", return_value=(KEY_A, KEY_B))
@pytest.mark.parametrize("standby", [None, "cold"])
def test_standby_activation_requires_signed_hot_status(_mock_keys, standby):
    client = MagicMock()
    client.phone_ip = "192.168.1.42"
    client.get_status.return_value = {
        "wireguard": {"port": 51820, "public_key": KEY_C, "peer_ip": "10.100.0.2"},
        **({"standby": standby} if standby else {}),
    }
    with pytest.raises(StandbyDeclined):
        start_phone_relay(client, standby=True)
    client.start_failover.assert_not_called()


@patch("watchdog.generate_wg_keypair", return_value=(KEY_A, KEY_B))
def test_standby_activation_with_hot_status(_mock_keys):
    client = MagicMock()
    client.phone_ip = "192.168.1.42"
    client.get_status.return_value = {
        "wireguard": {"port": 51820, "public_key": KEY_C, "peer_ip": "10.100.0.2"},
        "standby": "hot",
    }
    assert "PublicKey" in start_phone_relay(client, standby=True)
    client.start_failover.assert_called_once_with(KEY_B)


class FakeDiscovery:
    def __init__(self, phones, hot=(), battery=None, plugged=(), min_battery=None, strict=()):
        self.gateway_id = GW_ID
        self.phones = list(phones)
        # IDs of the phones announcing hot standby
        self.hot = set(hot)
        # Battery % per phone (default 100), IDs of the phones plugged in
        self.battery = dict(battery or {})
        self.plugged = set(plugged)
        # Minimum battery % per phone (default 20), IDs of the phones not allowed below it (no last resort)
        self.min_battery = dict(min_battery or {})
        self.strict = set(strict)
        # When each phone's announcement last changed (default: long ago)
        self.since = {}

    def get_available_phones(self):
        return list(self.phones)

    def state_of(self, phone_id, now=None):
        ip = dict(self.phones).get(phone_id)
        if ip is None:
            return None
        return PhoneState(
            ip=ip, last_seen=0.0, standby=self.standby_of(phone_id), battery=self.battery.get(phone_id, 100),
            plugged=phone_id in self.plugged, min_battery=self.min_battery.get(phone_id, 20),
            last_resort=phone_id not in self.strict, since=self.since.get(phone_id, float("-inf")),
        )

    def standby_of(self, phone_id):
        return pairing.STANDBY_HOT if phone_id in self.hot and phone_id in dict(self.phones) else pairing.STANDBY_COLD


def fake_store(tmp_path, phone_ids):
    store = pairing.PairingStore(str(tmp_path))
    for pid in phone_ids:
        store.add(pairing.PairedPhone(id=pid, name=f"Pixel {pid}", key=PHONE_KEY, ssid="Home", paired_at=0))
    return store


class TestFailoverCoordinator:
    @pytest.fixture(autouse=True)
    def _tmp(self, tmp_path):
        self.tmp_path = tmp_path

    def make(self, phones, failing_phones=(), refusing_phones=()):
        clients = {}

        def factory(phone_ip, phone_id, key, gateway_id, http_port):
            client = clients.setdefault(phone_id, MagicMock(name=phone_id))
            client.phone_ip = phone_ip
            client.phone_id = phone_id
            return client

        def fake_activate(client, standby=False):
            if client.phone_id in failing_phones:
                raise ConnectionError(f"{client.phone_id} unreachable")
            if client.phone_id in refusing_phones:
                raise PhoneRequestError(409, "Serving another gateway")
            return f"config {client.phone_id}"

        routing = MagicMock()
        coordinator = FailoverCoordinator(
            discovery=FakeDiscovery(phones),
            routing=routing,
            store=fake_store(self.tmp_path, [pid for pid, _ in phones]),
            tunnel_fail_threshold=3,
            client_factory=factory,
        )
        return coordinator, routing, clients, fake_activate

    A = ("phoneA", "10.20.0.10")
    B = ("phoneB", "10.20.0.11")

    def test_activate_uses_first_available_phone(self):
        coordinator, _, clients, fake_activate = self.make([self.A, self.B])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.A
        assert coordinator.active_phone_label == "Pixel phoneA (10.20.0.10)"

    def test_activate_prefers_requested_phone(self):
        coordinator, _, _, fake_activate = self.make([self.A, self.B])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            assert coordinator.activate(prefer="phoneB") is True
        assert coordinator.active_phone == self.B

    def test_activate_falls_back_to_next_phone(self):
        coordinator, routing, clients, fake_activate = self.make([self.A, self.B], failing_phones={"phoneA"})
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.B
        # Partial state from the failed attempt was cleaned up
        routing.teardown_wireguard_interface.assert_called()
        clients["phoneA"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)

    def test_busy_phone_is_skipped_without_stopping_it(self):
        # Phone A serves another gateway: asking it to stop would be refused anyway
        coordinator, _, clients, fake_activate = self.make([self.A, self.B], refusing_phones={"phoneA"})
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.B
        clients["phoneA"].stop_failover.assert_not_called()

    def test_unpaired_phone_is_skipped(self):
        coordinator, _, _, fake_activate = self.make([self.A, self.B])
        coordinator.store.remove("phoneA")
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.B

    def test_client_uses_paired_key(self):
        coordinator = FailoverCoordinator(
            discovery=FakeDiscovery([self.A]), routing=MagicMock(), store=fake_store(self.tmp_path, ["phoneA"])
        )
        client = coordinator.client(*self.A)
        assert (client.phone_ip, client.phone_id, client.key, client.gateway_id) == ("10.20.0.10", "phoneA", PHONE_KEY, GW_ID)

    def test_manual_stop_without_phones_still_tears_down_locally(self):
        coordinator, routing, _, _ = self.make([])
        manual_stop(routing, coordinator.discovery, coordinator, listen=0)
        routing.disable_routing_and_nat.assert_called_once()
        routing.teardown_wireguard_interface.assert_called_once()

    def test_manual_stop_releases_every_phone_heard(self):
        coordinator, routing, clients, _ = self.make([self.A, self.B])
        manual_stop(routing, coordinator.discovery, coordinator, listen=0)
        routing.teardown_wireguard_interface.assert_called_once()
        clients["phoneA"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)
        clients["phoneB"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)

    def test_activate_without_phones(self):
        coordinator, _, _, fake_activate = self.make([])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            assert coordinator.activate() is False
        assert coordinator.active is False

    @patch("watchdog.check_tunnel_canary", return_value=False)
    def test_tunnel_lost_after_threshold(self, _canary):
        coordinator, _, _, fake_activate = self.make([self.A])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            coordinator.activate()
        assert coordinator.tunnel_lost() is False
        assert coordinator.tunnel_lost() is False
        assert coordinator.tunnel_lost() is True

    def test_tunnel_success_resets_failures(self):
        coordinator, _, _, fake_activate = self.make([self.A])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            coordinator.activate()
        with patch("watchdog.check_tunnel_canary", side_effect=[False, False, True, False, False]):
            assert [coordinator.tunnel_lost() for _ in range(5)] == [False, False, False, False, False]

    def test_hand_over_prefers_another_phone(self):
        coordinator, _, clients, fake_activate = self.make([self.A, self.B])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            coordinator.activate()
            assert coordinator.active_phone == self.A
            assert coordinator.hand_over() is True
        assert coordinator.active_phone == self.B
        # Lost phone was asked (best effort) to release cellular
        clients["phoneA"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)

    def test_hand_over_retries_same_phone_when_alone(self):
        # e.g. the app restarted: the same phone comes back with a fresh relay
        coordinator, _, _, fake_activate = self.make([self.A])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            coordinator.activate()
            assert coordinator.hand_over() is True
        assert coordinator.active_phone == self.A

    def test_hand_over_fails_when_no_phone_left(self):
        coordinator, routing, _, fake_activate = self.make([self.A])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            coordinator.activate()
            coordinator.discovery.phones = []
            assert coordinator.hand_over() is False
        assert coordinator.active is False
        routing.disable_routing_and_nat.assert_called()

    def test_release_tolerates_unreachable_phone(self):
        coordinator, _, clients, fake_activate = self.make([self.A])
        with patch("watchdog.start_phone_relay", side_effect=fake_activate):
            coordinator.activate()
        clients["phoneA"].stop_failover.side_effect = ConnectionError("gone")
        coordinator.deactivate()
        assert coordinator.active is False


class TestArgParsing:
    def test_default_arguments(self):
        args = parse_args([])
        assert args.primary_iface == "eth0"
        assert args.failover_iface == "eth1"
        assert args.failover_gateway == "192.168.100.1"
        assert args.wg_iface == "wg0"
        assert args.table_id == 100
        assert args.clamp_mss is True
        assert args.ping_timeout == 2
        assert args.http_port == 8989
        assert args.discovery_port == 8990
        assert args.targets == ["1.1.1.1", "8.8.8.8"]
        assert args.canary_ip == "198.18.0.1"
        assert args.fail_threshold == 3
        assert args.restore_threshold == 5
        assert args.check_interval == 5
        assert args.tunnel_fail_threshold == 3
        assert args.config_dir == "/config"
        assert args.phone is None

    def test_custom_arguments(self):
        args = parse_args([
            "--primary-iface", "enp1s0",
            "--failover-iface", "enp2s0",
            "--failover-gateway-ip", "192.168.200.1",
            "--table-id", "200",
            "--no-clamp-mss",
            "--ping-timeout", "4",
            "--phone", "Pixel",
            "--config-dir", "/tmp/cfg",
            "--http-port", "9090",
            "--discovery-port", "9091",
            "--fail-threshold", "2",
            "--restore-threshold", "4",
            "--check-interval", "3",
            "--targets", "9.9.9.9",
        ])
        assert args.primary_iface == "enp1s0"
        assert args.failover_iface == "enp2s0"
        assert args.failover_gateway == "192.168.200.1"
        assert args.table_id == 200
        assert args.clamp_mss is False
        assert args.ping_timeout == 4
        assert args.phone == "Pixel"
        assert args.config_dir == "/tmp/cfg"
        assert args.http_port == 9090
        assert args.discovery_port == 9091
        assert args.fail_threshold == 2
        assert args.restore_threshold == 4
        assert args.check_interval == 3
        assert args.targets == ["9.9.9.9"]


class LoopHarness:
    """Runs FailoverLoop against fake probes, phones and discovery."""

    A = ("phoneA", "10.20.0.10")
    B = ("phoneB", "10.20.0.11")

    @pytest.fixture(autouse=True)
    def _setup(self, tmp_path):
        self.tmp_path = tmp_path
        # Probe results, changed by the tests
        self.wan1_ok = True
        self.router_on_wan2 = False
        self.tunnel_ok = True
        # Configs whose tunnel never answers (a new phone's relay that doesn't work)
        self.dead_configs = set()
        self.activations = []
        patches = [
            patch("watchdog.check_primary_wan", side_effect=lambda *a, **k: self.wan1_ok),
            patch("watchdog.check_tunnel_canary", side_effect=self._canary),
            patch("watchdog.start_phone_relay", side_effect=self._activate),
        ]
        for p in patches:
            p.start()
        yield
        for p in patches:
            p.stop()

    def _canary(self, iface, canary_ip, timeout=2):
        # Through wg0: the phone answers; from the primary interface: only if the router uses WAN 2
        if iface != "wg0":
            return self.router_on_wan2
        current = self.routing.setup_wireguard_interface.call_args
        return self.tunnel_ok and not (current and current[0][0] in self.dead_configs)

    def _activate(self, client, standby=False):
        if client.phone_id in self.failing:
            raise ConnectionError("unreachable")
        if standby and client.get_status().get("standby") != "hot":
            raise StandbyDeclined("not hot")
        self.activations.append((client.phone_id, standby))
        return f"config {client.phone_id}"

    def make(self, phones, hot=(), hot_standby=True, failing=(), battery=None, plugged=(), min_battery=None, strict=()):
        self.failing = set(failing)
        self.clients = {}

        def factory(phone_ip, phone_id, key, gateway_id, http_port):
            client = self.clients.get(phone_id)
            if client is None:
                client = self.clients[phone_id] = MagicMock(name=phone_id)
                # The signed status agrees with the announcement unless a test says otherwise
                client.get_status.side_effect = lambda pid=phone_id: self.status_of(pid)
            client.phone_ip, client.phone_id = phone_ip, phone_id
            return client

        self.discovery = FakeDiscovery(phones, hot=hot, battery=battery, plugged=plugged, min_battery=min_battery, strict=strict)
        self.routing = MagicMock()
        self.coordinator = FailoverCoordinator(
            discovery=self.discovery,
            routing=self.routing,
            # Every test phone is paired; [phones] are the ones on the network
            store=fake_store(self.tmp_path, ["phoneA", "phoneB", "phoneC"]),
            tunnel_fail_threshold=3,
            client_factory=factory,
            hot_standby=hot_standby,
        )
        args = parse_args([])
        args.fail_threshold, args.restore_threshold = 3, 5
        self.loop = FailoverLoop(self.coordinator, args)
        return self.coordinator

    def status_of(self, phone_id):
        state = self.discovery.state_of(phone_id)
        return {
            "standby": self.discovery.standby_of(phone_id), "battery": state.battery, "plugged": state.plugged,
            "min_battery": state.min_battery, "last_resort": state.last_resort,
        }

    def steps(self, n=1):
        for _ in range(n):
            self.loop.step()



class TestHotStandby(LoopHarness):
    """FailoverLoop state machine: idle / hot standby / carrying (see FailoverLoop)."""

    def test_hot_phone_gets_a_tunnel_without_outage(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        assert coordinator.active_phone == self.A
        assert coordinator.carrying is False
        assert self.activations == [("phoneA", True)]

    def test_cold_phone_waits_for_an_outage(self):
        coordinator = self.make([self.A])
        self.steps(5)
        assert coordinator.active is False
        self.wan1_ok = False
        self.steps(3)
        assert coordinator.active_phone == self.A
        assert coordinator.carrying is True

    def test_gateway_can_disable_hot_standby(self):
        coordinator = self.make([self.A], hot={"phoneA"}, hot_standby=False)
        self.steps(5)
        assert coordinator.active is False

    def test_standby_prefers_hot_phone(self):
        coordinator = self.make([self.A, self.B], hot={"phoneB"})
        self.steps()
        assert coordinator.active_phone == self.B

    def test_outage_prefers_hot_phone(self):
        # WAN 1 down from the start: the outage path activates, not hot standby
        coordinator = self.make([self.A, self.B], hot={"phoneB"})
        self.wan1_ok = False
        self.steps(3)
        assert coordinator.active_phone == self.B
        assert coordinator.carrying is True

    def test_standby_starts_carrying_when_router_switches(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        self.wan1_ok, self.router_on_wan2 = False, True
        self.steps()
        assert coordinator.carrying is True
        # No new activation: re-keying the relay would cut the traffic it carries
        assert self.activations == [("phoneA", True)]

    def test_unplugged_phone_is_released_in_standby(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        self.discovery.hot.clear()
        self.steps()
        assert coordinator.active is False
        self.clients["phoneA"].stop_failover.assert_called_once()

    def test_unplugged_phone_keeps_carrying_during_outage(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        self.wan1_ok, self.router_on_wan2 = False, True
        self.steps()
        self.discovery.hot.clear()
        self.steps(10)
        assert coordinator.active_phone == self.A
        self.clients["phoneA"].stop_failover.assert_not_called()

    def test_failback_returns_hot_phone_to_standby(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        self.wan1_ok, self.router_on_wan2 = False, True
        self.steps()
        self.wan1_ok, self.router_on_wan2 = True, False
        self.steps(5)
        assert coordinator.active_phone == self.A
        assert coordinator.carrying is False
        self.routing.disable_routing_and_nat.assert_not_called()

    def test_failback_releases_phone_that_went_cold(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        self.wan1_ok, self.router_on_wan2 = False, True
        self.steps()
        self.discovery.hot.clear()
        self.wan1_ok, self.router_on_wan2 = True, False
        self.steps(4)
        assert coordinator.active is True
        self.steps()
        assert coordinator.active is False
        self.clients["phoneA"].stop_failover.assert_called_once()

    def test_signed_status_overrides_replayed_hot_broadcast(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        # Discovery still says hot (e.g. a replayed broadcast), the phone itself says cold
        self.clients["phoneA"].get_status.side_effect = None
        self.clients["phoneA"].get_status.return_value = {"standby": "cold"}
        coordinator.standby_confirmed_at -= coordinator.standby_confirm_interval
        self.steps()
        assert coordinator.active is False

    def test_standby_is_confirmed_only_periodically(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        calls = self.clients["phoneA"].get_status.call_count
        self.steps(5)
        assert self.clients["phoneA"].get_status.call_count == calls
        assert coordinator.active is True

    def test_released_phone_is_replaced_by_another_hot_phone(self):
        coordinator = self.make([self.A, self.B], hot={"phoneA", "phoneB"})
        self.steps()
        assert coordinator.active_phone == self.A
        self.discovery.hot.discard("phoneA")
        self.steps(2)
        assert coordinator.active_phone == self.B

    def test_lost_standby_tunnel_is_rebuilt(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        self.tunnel_ok = False
        self.steps(3)
        assert coordinator.active is False
        self.tunnel_ok = True
        self.steps()
        assert coordinator.active_phone == self.A
        assert self.activations == [("phoneA", True), ("phoneA", True)]

    def test_failing_standby_backs_off(self):
        coordinator = self.make([self.A], hot={"phoneA"}, failing={"phoneA"})
        assert coordinator.try_standby(now=1000.0) is False
        assert coordinator.try_standby(now=1010.0) is False
        assert coordinator.standby_retry_at == 1030.0
        # Retries after 30 s, then 60 s, ... up to 5 min
        coordinator.try_standby(now=1030.0)
        assert coordinator.standby_retry_at == 1090.0
        for _ in range(10):
            coordinator.try_standby(now=coordinator.standby_retry_at)
        assert coordinator.standby_retry_delay == 300.0
        self.failing.clear()
        assert coordinator.try_standby(now=coordinator.standby_retry_at) is True
        assert coordinator.standby_retry_delay == 30.0

    def test_phone_leaving_and_coming_back(self):
        coordinator = self.make([self.A], hot={"phoneA"})
        self.steps()
        # Out of Wi-Fi range: no more beacons, the tunnel stops answering
        self.discovery.phones, self.tunnel_ok = [], False
        self.steps(3)
        assert coordinator.active is False
        self.steps(10)
        assert self.activations == [("phoneA", True)]
        # Back home
        self.discovery.phones, self.tunnel_ok = [self.A], True
        self.steps()
        assert coordinator.active_phone == self.A
        assert self.activations == [("phoneA", True), ("phoneA", True)]

    def test_phone_coming_back_skips_backoff_from_before_it_left(self):
        coordinator = self.make([self.A], hot={"phoneA"}, failing={"phoneA"})
        # Still heard for a few seconds after leaving: the attempt fails and backs off
        assert coordinator.try_standby(now=1000.0) is False
        self.discovery.phones = []
        assert coordinator.try_standby(now=1005.0) is False
        # Back (with a working relay) before the backoff ends: retried right away
        self.discovery.phones = [self.A]
        self.failing.clear()
        assert coordinator.try_standby(now=1010.0) is True


class TestPhoneSelection:
    """Which phone carries the failover: hot standby, battery not low, plugged in, battery level."""

    A = ("phoneA", "10.20.0.10")
    B = ("phoneB", "10.20.0.11")
    C = ("phoneC", "10.20.0.12")

    @pytest.fixture(autouse=True)
    def _tmp(self, tmp_path):
        self.tmp_path = tmp_path

    def activate(self, phones, standby=False, failing=(), **discovery):
        coordinator = FailoverCoordinator(
            discovery=FakeDiscovery(phones, **discovery),
            routing=MagicMock(),
            store=fake_store(self.tmp_path, [pid for pid, _ in phones]),
            client_factory=lambda phone_ip, phone_id, **_: MagicMock(phone_ip=phone_ip, phone_id=phone_id),
        )
        tried = []

        def relay(client, standby=False):
            tried.append(client.phone_id)
            if client.phone_id in failing:
                raise ConnectionError("unreachable")
            return f"config {client.phone_id}"

        with patch("watchdog.start_phone_relay", side_effect=relay):
            coordinator.activate(standby=standby)
        return coordinator, tried

    def test_plugged_phone_first(self):
        coordinator, _ = self.activate([self.A, self.B], plugged={"phoneB"}, battery={"phoneA": 100, "phoneB": 30})
        assert coordinator.active_phone == self.B

    def test_highest_battery_first(self):
        coordinator, _ = self.activate([self.A, self.B, self.C], battery={"phoneA": 40, "phoneB": 90, "phoneC": 60})
        assert coordinator.active_phone == self.B

    def test_hot_phone_before_plugged_phone(self):
        # The hot phone's owner chose it as the backup (and a cold one would be switched away from)
        coordinator, _ = self.activate([self.A, self.B], hot={"phoneA"}, plugged={"phoneB"}, battery={"phoneA": 50})
        assert coordinator.active_phone == self.A

    def test_low_battery_phone_is_last(self):
        coordinator, tried = self.activate(
            [self.A, self.B], hot={"phoneA"}, battery={"phoneA": 10, "phoneB": 25}, failing={"phoneB"}
        )
        assert tried == ["phoneB", "phoneA"]
        # Better a phone low on battery than no internet at all
        assert coordinator.active_phone == self.A

    def test_plugged_phone_is_never_low(self):
        coordinator, _ = self.activate([self.A, self.B], plugged={"phoneA"}, battery={"phoneA": 5, "phoneB": 15})
        assert coordinator.active_phone == self.A

    def test_each_phone_has_its_own_minimum(self):
        # 35% is low for phone A (minimum 40), 30% is fine for phone B (minimum 10)
        coordinator, _ = self.activate([self.A, self.B], battery={"phoneA": 35, "phoneB": 30}, min_battery={"phoneA": 40, "phoneB": 10})
        assert coordinator.active_phone == self.B

    def test_low_phone_without_last_resort_is_never_used(self):
        coordinator, tried = self.activate([self.A], battery={"phoneA": 10}, strict={"phoneA"})
        assert tried == []
        assert coordinator.active is False

    def test_strict_phone_is_used_above_its_minimum_or_plugged_in(self):
        coordinator, _ = self.activate([self.A], battery={"phoneA": 10}, strict={"phoneA"}, plugged={"phoneA"})
        assert coordinator.active_phone == self.A

    def test_no_hot_standby_on_low_battery(self):
        coordinator, tried = self.activate([self.A], standby=True, hot={"phoneA"}, battery={"phoneA": 19})
        assert tried == []
        assert coordinator.active is False

    def test_hot_standby_on_the_best_hot_phone(self):
        coordinator, _ = self.activate(
            [self.A, self.B, self.C], standby=True, hot={"phoneA", "phoneB"}, battery={"phoneA": 15, "phoneB": 50, "phoneC": 100}
        )
        assert coordinator.active_phone == self.B


class TestPlannedSwitch(LoopHarness):
    """During an outage, the failover moves to a better phone (see FailoverCoordinator.switch_target)."""

    def carrying_on(self, phone, phones, **make):
        """An outage carried by [phone]; the other [phones] join right after it started."""
        coordinator = self.make([phone], **make)
        self.wan1_ok = False
        self.steps(3)
        assert coordinator.active_phone == phone and coordinator.carrying
        self.router_on_wan2 = True
        self.discovery.phones = list(phones)
        return coordinator

    def test_moves_to_hot_phone_that_joins(self):
        coordinator = self.carrying_on(self.A, phones=[self.A])
        # A phone set to always on comes home
        self.discovery.phones.append(self.B)
        self.discovery.hot.add("phoneB")
        self.steps()
        assert coordinator.active_phone == self.B
        assert coordinator.carrying is True
        assert self.activations == [("phoneA", False), ("phoneB", True)]
        # Make-before-break: the new tunnel was up before the old phone was released
        assert self.routing.setup_wireguard_interface.call_args[0][0] == "config phoneB"
        self.clients["phoneA"].stop_failover.assert_called_once()
        self.clients["phoneB"].stop_failover.assert_not_called()
        self.routing.disable_routing_and_nat.assert_not_called()
        # After the outage, it stays as hot standby
        self.wan1_ok, self.router_on_wan2 = True, False
        self.steps(5)
        assert coordinator.active_phone == self.B and coordinator.carrying is False

    def test_waits_until_hot_phone_is_steady(self):
        coordinator = self.carrying_on(self.A, phones=[self.A])
        self.discovery.phones.append(self.B)
        self.discovery.hot.add("phoneB")
        self.discovery.since["phoneB"] = time.monotonic()
        self.steps(3)
        assert coordinator.active_phone == self.A
        self.discovery.since["phoneB"] -= coordinator.switch_stable_after
        self.steps()
        assert coordinator.active_phone == self.B

    def test_hot_phone_low_on_battery_is_not_worth_a_switch(self):
        coordinator = self.carrying_on(self.A, phones=[self.A])
        self.discovery.phones.append(self.B)
        self.discovery.hot.add("phoneB")
        self.discovery.battery["phoneB"] = 15
        self.steps(5)
        assert coordinator.active_phone == self.A

    def test_no_switch_away_from_hot_phone(self):
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B], hot={"phoneA", "phoneB"}, plugged={"phoneB"})
        self.steps(5)
        assert coordinator.active_phone == self.A

    def test_no_switch_in_hot_standby(self):
        # Not carrying: nothing to move
        coordinator = self.make([self.A, self.B], hot={"phoneA"})
        self.steps()
        self.discovery.hot.add("phoneB")
        self.steps(5)
        assert coordinator.active_phone == self.A

    def test_moves_off_low_battery_phone(self):
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B], battery={"phoneB": 50})
        self.discovery.battery["phoneA"] = 19
        self.steps()
        assert coordinator.active_phone == self.B
        self.clients["phoneA"].stop_failover.assert_called_once()

    def test_low_battery_phone_moves_to_plugged_phone_with_little_battery(self):
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B], battery={"phoneB": 10}, plugged={"phoneB"})
        self.discovery.battery["phoneA"] = 15
        self.steps()
        assert coordinator.active_phone == self.B

    def test_low_battery_needs_a_clearly_better_phone(self):
        # 25% is above the minimum, but would soon be low too
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B], battery={"phoneB": 25})
        self.discovery.battery["phoneA"] = 19
        self.steps(5)
        assert coordinator.active_phone == self.A
        self.discovery.battery["phoneB"] = 30
        self.steps()
        assert coordinator.active_phone == self.B

    def test_low_battery_must_be_confirmed_by_the_phone(self):
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B])
        # A replayed beacon says low, the signed status doesn't
        self.discovery.battery["phoneA"] = 5
        self.clients["phoneA"].get_status.side_effect = None
        self.clients["phoneA"].get_status.return_value = {"standby": "cold", "battery": 80, "plugged": False, "min_battery": 20, "last_resort": True}
        self.steps()
        assert coordinator.active_phone == self.A
        # Not asked again on every check
        calls = self.clients["phoneA"].get_status.call_count
        self.steps(5)
        assert self.clients["phoneA"].get_status.call_count == calls

    def test_failed_start_keeps_current_phone_and_backs_off(self):
        coordinator = self.carrying_on(self.A, phones=[self.A])
        self.discovery.phones.append(self.B)
        self.discovery.hot.add("phoneB")
        self.failing.add("phoneB")
        self.steps()
        assert coordinator.active_phone == self.A
        # The current tunnel was never touched
        assert self.routing.setup_wireguard_interface.call_count == 1
        self.clients["phoneA"].stop_failover.assert_not_called()
        retry_at, _ = coordinator.switch_backoff["phoneB"]
        self.steps(5)
        assert self.activations == [("phoneA", False)]
        self.failing.clear()
        assert coordinator.maybe_switch(now=retry_at) is True
        assert coordinator.active_phone == self.B

    def test_dead_new_tunnel_goes_back_to_current_phone(self):
        coordinator = self.carrying_on(self.A, phones=[self.A])
        self.discovery.phones.append(self.B)
        self.discovery.hot.add("phoneB")
        self.dead_configs.add("config phoneB")
        self.steps()
        assert coordinator.active_phone == self.A
        assert coordinator.active_config == "config phoneA"
        # wg0 rebuilt with the current phone's config (its relay still has that key)
        assert self.routing.setup_wireguard_interface.call_args[0][0] == "config phoneA"
        self.clients["phoneB"].stop_failover.assert_called_once()
        self.clients["phoneA"].stop_failover.assert_not_called()
        assert "phoneB" in coordinator.switch_backoff
        self.steps(5)
        assert coordinator.active_phone == self.A

    def test_backoff_doubles_then_is_forgotten_when_phone_leaves(self):
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B], hot={"phoneB"}, failing={"phoneB"})
        assert coordinator.maybe_switch(now=1000.0) is False
        assert coordinator.switch_backoff["phoneB"] == (1030.0, 60.0)
        assert coordinator.maybe_switch(now=1030.0) is False
        assert coordinator.switch_backoff["phoneB"] == (1090.0, 120.0)
        self.discovery.phones.remove(self.B)
        assert coordinator.maybe_switch(now=1031.0) is False
        assert "phoneB" not in coordinator.switch_backoff

    def test_hot_standby_on_low_battery_is_handed_to_another_hot_phone(self):
        coordinator = self.make([self.A, self.B], hot={"phoneA", "phoneB"}, battery={"phoneB": 60})
        self.steps()
        assert coordinator.active_phone == self.A
        self.discovery.battery["phoneA"] = 10
        self.steps(2)
        assert coordinator.active_phone == self.B
        self.clients["phoneA"].stop_failover.assert_called_once()

    def test_low_battery_margin_uses_the_new_phones_own_minimum(self):
        # Phone B on battery at 25%: enough with a minimum of 10 (10 + 10), not with 20
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B], battery={"phoneB": 25}, min_battery={"phoneB": 10})
        self.discovery.battery["phoneA"] = 19
        self.steps()
        assert coordinator.active_phone == self.B

    def test_last_resort_phone_keeps_carrying_when_alone(self):
        coordinator = self.carrying_on(self.A, phones=[self.A])
        self.discovery.battery["phoneA"] = 10
        self.steps(5)
        assert coordinator.active_phone == self.A

    def test_strict_phone_moves_to_any_phone_not_low(self):
        # Below the usual margin, but the active phone may not carry on below its minimum
        coordinator = self.carrying_on(self.A, phones=[self.A, self.B], strict={"phoneA"}, battery={"phoneB": 22})
        self.discovery.battery["phoneA"] = 15
        self.steps()
        assert coordinator.active_phone == self.B
        self.clients["phoneA"].stop_failover.assert_called_once()

    def test_strict_phone_is_released_when_alone(self):
        coordinator = self.carrying_on(self.A, phones=[self.A], strict={"phoneA"})
        self.discovery.battery["phoneA"] = 15
        self.steps()
        assert coordinator.active is False
        self.clients["phoneA"].stop_failover.assert_called_once()
        self.routing.disable_routing_and_nat.assert_called()
        # Still an outage: no phone may carry it, so it isn't restarted
        self.router_on_wan2 = False
        self.steps(5)
        assert coordinator.active is False
        # Plugged in again: it takes the outage back
        self.discovery.plugged.add("phoneA")
        self.steps()
        assert coordinator.active_phone == self.A

    def test_strict_release_needs_the_phones_confirmation(self):
        coordinator = self.carrying_on(self.A, phones=[self.A], strict={"phoneA"})
        self.discovery.battery["phoneA"] = 15
        # Its signed status says it may still carry the outage (the beacon was stale or replayed)
        self.clients["phoneA"].get_status.side_effect = None
        self.clients["phoneA"].get_status.return_value = {"standby": "cold", "battery": 15, "plugged": False, "min_battery": 20, "last_resort": True}
        self.steps()
        assert coordinator.active_phone == self.A
