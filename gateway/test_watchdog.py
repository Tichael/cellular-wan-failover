import http.server
import json
import os
import subprocess
import threading
import urllib.error
from unittest.mock import MagicMock, patch

import pairing
import pytest
from watchdog import (
    AndroidFailoverClient,
    FailoverCoordinator,
    PhoneAuthError,
    PhoneDiscovery,
    PhoneRequestError,
    RoutingManager,
    activate_failover,
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


def signed_entry(phone_id, ip, key=PHONE_KEY, gateway_id=GW_ID):
    return {"gw": gateway_id, "pid": phone_id, "sig": pairing.sign(key, pairing.discovery_fields(gateway_id, phone_id, ip))}


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
    def test_activate_failover_flow(self, _mock_keys):
        client = MagicMock()
        client.phone_ip = "192.168.1.42"
        client.get_status.return_value = {
            "status": "idle",
            "wifi_ip": "6.6.6.6",  # must be ignored in favour of client.phone_ip
            "wireguard": {"port": 51820, "public_key": KEY_C, "tunnel_ip": "10.100.0.1", "peer_ip": "10.100.0.2"},
        }
        client.start_failover.return_value = {"result": "active"}
        routing = MagicMock()

        activate_failover(client, routing)

        client.start_failover.assert_called_once_with(KEY_B)
        cfg = routing.setup_wireguard_interface.call_args[0][0]
        assert f"PrivateKey = {KEY_A}" in cfg
        assert f"PublicKey = {KEY_C}" in cfg
        assert "Endpoint = 192.168.1.42:51820" in cfg
        routing.enable_routing_and_nat.assert_called_once()

    @patch("watchdog.generate_wg_keypair", return_value=(KEY_A, KEY_B))
    def test_activate_failover_rejects_bad_status_before_waking_phone(self, _mock_keys):
        client = MagicMock()
        client.phone_ip = "192.168.1.42"
        client.get_status.return_value = {
            "wireguard": {"port": 51820, "public_key": "x\nPostUp = id", "peer_ip": "10.100.0.2"},
        }
        routing = MagicMock()

        with pytest.raises(ValueError):
            activate_failover(client, routing)
        client.start_failover.assert_not_called()
        routing.setup_wireguard_interface.assert_not_called()


class FakeDiscovery:
    def __init__(self, phones):
        self.gateway_id = GW_ID
        self.phones = list(phones)

    def get_available_phones(self):
        return list(self.phones)


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

        def fake_activate(client, routing):
            if client.phone_id in failing_phones:
                raise ConnectionError(f"{client.phone_id} unreachable")
            if client.phone_id in refusing_phones:
                raise PhoneRequestError(409, "Serving another gateway")
            return {"result": "active"}

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
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.A
        assert coordinator.active_phone_label == "Pixel phoneA (10.20.0.10)"

    def test_activate_prefers_requested_phone(self):
        coordinator, _, _, fake_activate = self.make([self.A, self.B])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate(prefer="phoneB") is True
        assert coordinator.active_phone == self.B

    def test_activate_falls_back_to_next_phone(self):
        coordinator, routing, clients, fake_activate = self.make([self.A, self.B], failing_phones={"phoneA"})
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.B
        # Partial state from the failed attempt was cleaned up
        routing.teardown_wireguard_interface.assert_called()
        clients["phoneA"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)

    def test_busy_phone_is_skipped_without_stopping_it(self):
        # Phone A serves another gateway: asking it to stop would be refused anyway
        coordinator, _, clients, fake_activate = self.make([self.A, self.B], refusing_phones={"phoneA"})
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.B
        clients["phoneA"].stop_failover.assert_not_called()

    def test_unpaired_phone_is_skipped(self):
        coordinator, _, _, fake_activate = self.make([self.A, self.B])
        coordinator.store.remove("phoneA")
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone == self.B

    def test_client_uses_paired_key(self):
        coordinator = FailoverCoordinator(
            discovery=FakeDiscovery([self.A]), routing=MagicMock(), store=fake_store(self.tmp_path, ["phoneA"])
        )
        client = coordinator.client(*self.A)
        assert (client.phone_ip, client.phone_id, client.key, client.gateway_id) == ("10.20.0.10", "phoneA", PHONE_KEY, GW_ID)

    def test_activate_without_phones(self):
        coordinator, _, _, fake_activate = self.make([])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is False
        assert coordinator.active is False

    @patch("watchdog.check_tunnel_canary", return_value=False)
    def test_tunnel_lost_after_threshold(self, _canary):
        coordinator, _, _, fake_activate = self.make([self.A])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
        assert coordinator.tunnel_lost() is False
        assert coordinator.tunnel_lost() is False
        assert coordinator.tunnel_lost() is True

    def test_tunnel_success_resets_failures(self):
        coordinator, _, _, fake_activate = self.make([self.A])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
        with patch("watchdog.check_tunnel_canary", side_effect=[False, False, True, False, False]):
            assert [coordinator.tunnel_lost() for _ in range(5)] == [False, False, False, False, False]

    def test_hand_over_prefers_another_phone(self):
        coordinator, _, clients, fake_activate = self.make([self.A, self.B])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
            assert coordinator.active_phone == self.A
            assert coordinator.hand_over() is True
        assert coordinator.active_phone == self.B
        # Lost phone was asked (best effort) to release cellular
        clients["phoneA"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)

    def test_hand_over_retries_same_phone_when_alone(self):
        # e.g. the app restarted: the same phone comes back with a fresh relay
        coordinator, _, _, fake_activate = self.make([self.A])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
            assert coordinator.hand_over() is True
        assert coordinator.active_phone == self.A

    def test_hand_over_fails_when_no_phone_left(self):
        coordinator, routing, _, fake_activate = self.make([self.A])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
            coordinator.discovery.phones = []
            assert coordinator.hand_over() is False
        assert coordinator.active is False
        routing.disable_routing_and_nat.assert_called()

    def test_release_tolerates_unreachable_phone(self):
        coordinator, _, clients, fake_activate = self.make([self.A])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
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
