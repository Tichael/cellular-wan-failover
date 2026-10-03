import json
import os
import subprocess
import urllib.error
from unittest.mock import MagicMock, patch

import pytest
from watchdog import (
    AndroidFailoverClient,
    FailoverCoordinator,
    PhoneDiscovery,
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


class TestAndroidFailoverClient:
    def test_base_url(self):
        client = AndroidFailoverClient(phone_ip="192.168.1.50", http_port=8989)
        assert client.base_url == "http://192.168.1.50:8989"

    @patch("urllib.request.urlopen")
    def test_get_status_success(self, mock_urlopen):
        mock_response = MagicMock()
        mock_response.read.return_value = b'{"status": "STANDBY", "cellularConnected": true}'
        mock_response.__enter__.return_value = mock_response
        mock_urlopen.return_value = mock_response

        client = AndroidFailoverClient(phone_ip="10.20.0.5")
        status = client.get_status()

        assert status["status"] == "STANDBY"
        assert status["cellularConnected"] is True
        assert mock_urlopen.call_count == 1

    @patch("urllib.request.urlopen")
    def test_start_failover_success(self, mock_urlopen):
        mock_response = MagicMock()
        mock_response.read.return_value = b'{"status": "ACTIVE", "active": true}'
        mock_response.__enter__.return_value = mock_response
        mock_urlopen.return_value = mock_response

        client = AndroidFailoverClient(phone_ip="10.20.0.5")
        result = client.start_failover(KEY_A)

        assert result["status"] == "ACTIVE"
        assert result["active"] is True

        req = mock_urlopen.call_args[0][0]
        assert req.get_method() == "POST"
        assert req.get_header("Content-type") == "application/json"
        assert json.loads(req.data) == {"gateway_public_key": KEY_A}

    @patch("urllib.request.urlopen")
    def test_stop_failover_success(self, mock_urlopen):
        mock_response = MagicMock()
        mock_response.read.return_value = b'{"status": "STANDBY", "active": false}'
        mock_response.__enter__.return_value = mock_response
        mock_urlopen.return_value = mock_response

        client = AndroidFailoverClient(phone_ip="10.20.0.5")
        result = client.stop_failover()

        assert result["status"] == "STANDBY"
        assert result["active"] is False

    @patch("time.sleep")
    @patch("urllib.request.urlopen")
    def test_retry_on_failure_and_recover(self, mock_urlopen, mock_sleep):
        mock_success = MagicMock()
        mock_success.read.return_value = b'{"status": "ACTIVE"}'
        mock_success.__enter__.return_value = mock_success

        mock_urlopen.side_effect = [
            urllib.error.URLError("Connection refused"),
            mock_success,
        ]

        client = AndroidFailoverClient(phone_ip="10.20.0.5")
        status = client.get_status()

        assert status["status"] == "ACTIVE"
        assert mock_urlopen.call_count == 2
        assert mock_sleep.call_count == 1

    @patch("time.sleep")
    @patch("urllib.request.urlopen")
    def test_max_retries_exceeded_raises(self, mock_urlopen, mock_sleep):
        mock_urlopen.side_effect = urllib.error.URLError("Host unreachable")

        client = AndroidFailoverClient(phone_ip="10.20.0.5")
        with pytest.raises(urllib.error.URLError, match="Host unreachable"):
            client.get_status()


class TestPhoneDiscovery:
    def test_initial_ip(self):
        discovery = PhoneDiscovery(port=8990, initial_ip="10.20.0.99")
        assert discovery.get_phone_ip() == "10.20.0.99"
        assert discovery.phone_ip == "10.20.0.99"

    def test_start_and_stop(self):
        discovery = PhoneDiscovery(port=18990)
        discovery.start()
        assert discovery._running is True
        discovery.stop()
        assert discovery._running is False

    def test_tracks_multiple_phones_most_recent_first(self):
        discovery = PhoneDiscovery(port=18990, max_age=15.0)
        discovery.record_phone("10.20.0.10", {"device": "Pixel A"}, now=100.0)
        discovery.record_phone("10.20.0.11", {"device": "Pixel B"}, now=103.0)
        assert discovery.get_available_phones(now=104.0) == ["10.20.0.11", "10.20.0.10"]
        assert discovery.get_phone_ip() == "10.20.0.11"

    def test_silent_phone_becomes_unavailable(self):
        discovery = PhoneDiscovery(port=18990, max_age=15.0)
        discovery.record_phone("10.20.0.10", {"device": "Pixel A"}, now=100.0)
        discovery.record_phone("10.20.0.11", {"device": "Pixel B"}, now=110.0)
        # Phone A stopped broadcasting (battery, crash, out of range)
        assert discovery.get_available_phones(now=120.0) == ["10.20.0.11"]

    def test_static_phone_always_available(self):
        discovery = PhoneDiscovery(port=18990, initial_ip="10.20.0.99", max_age=15.0)
        assert discovery.get_available_phones(now=1000.0) == ["10.20.0.99"]
        discovery.record_phone("10.20.0.10", {"device": "Pixel A"}, now=1000.0)
        assert discovery.get_available_phones(now=1001.0) == ["10.20.0.10", "10.20.0.99"]


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
        self.phones = list(phones)

    def get_available_phones(self):
        return list(self.phones)


class TestFailoverCoordinator:
    def make(self, phones, failing_phones=()):
        clients = {}

        def factory(phone_ip, http_port):
            client = clients.setdefault(phone_ip, MagicMock(name=phone_ip))
            client.phone_ip = phone_ip
            return client

        def fake_activate(client, routing):
            if client.phone_ip in failing_phones:
                raise ConnectionError(f"{client.phone_ip} unreachable")
            return {"result": "active"}

        routing = MagicMock()
        coordinator = FailoverCoordinator(
            discovery=FakeDiscovery(phones), routing=routing, tunnel_fail_threshold=3, client_factory=factory
        )
        return coordinator, routing, clients, fake_activate

    def test_activate_uses_first_available_phone(self):
        coordinator, _, _, fake_activate = self.make(["10.20.0.10", "10.20.0.11"])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone_ip == "10.20.0.10"

    def test_activate_falls_back_to_next_phone(self):
        coordinator, routing, clients, fake_activate = self.make(["10.20.0.10", "10.20.0.11"], failing_phones={"10.20.0.10"})
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is True
        assert coordinator.active_phone_ip == "10.20.0.11"
        # Partial state from the failed attempt was cleaned up
        routing.teardown_wireguard_interface.assert_called()
        clients["10.20.0.10"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)

    def test_activate_without_phones(self):
        coordinator, _, _, fake_activate = self.make([])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            assert coordinator.activate() is False
        assert coordinator.active is False

    @patch("watchdog.check_tunnel_canary", return_value=False)
    def test_tunnel_lost_after_threshold(self, _canary):
        coordinator, _, _, fake_activate = self.make(["10.20.0.10"])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
        assert coordinator.tunnel_lost() is False
        assert coordinator.tunnel_lost() is False
        assert coordinator.tunnel_lost() is True

    def test_tunnel_success_resets_failures(self):
        coordinator, _, _, fake_activate = self.make(["10.20.0.10"])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
        with patch("watchdog.check_tunnel_canary", side_effect=[False, False, True, False, False]):
            assert [coordinator.tunnel_lost() for _ in range(5)] == [False, False, False, False, False]

    def test_hand_over_prefers_another_phone(self):
        coordinator, _, clients, fake_activate = self.make(["10.20.0.10", "10.20.0.11"])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
            assert coordinator.active_phone_ip == "10.20.0.10"
            assert coordinator.hand_over() is True
        assert coordinator.active_phone_ip == "10.20.0.11"
        # Lost phone was asked (best effort) to release cellular
        clients["10.20.0.10"].stop_failover.assert_called_once_with(timeout=3.0, attempts=1)

    def test_hand_over_retries_same_phone_when_alone(self):
        # e.g. the app restarted: the same phone comes back with a fresh relay
        coordinator, _, _, fake_activate = self.make(["10.20.0.10"])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
            assert coordinator.hand_over() is True
        assert coordinator.active_phone_ip == "10.20.0.10"

    def test_hand_over_fails_when_no_phone_left(self):
        coordinator, routing, _, fake_activate = self.make(["10.20.0.10"])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
            coordinator.discovery.phones = []
            assert coordinator.hand_over() is False
        assert coordinator.active is False
        routing.disable_routing_and_nat.assert_called()

    def test_release_tolerates_unreachable_phone(self):
        coordinator, _, clients, fake_activate = self.make(["10.20.0.10"])
        with patch("watchdog.activate_failover", side_effect=fake_activate):
            coordinator.activate()
        clients["10.20.0.10"].stop_failover.side_effect = ConnectionError("gone")
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

    def test_custom_arguments(self):
        args = parse_args([
            "--primary-iface", "enp1s0",
            "--failover-iface", "enp2s0",
            "--failover-gateway-ip", "192.168.200.1",
            "--table-id", "200",
            "--no-clamp-mss",
            "--ping-timeout", "4",
            "--phone-ip", "10.0.0.123",
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
        assert args.phone_ip == "10.0.0.123"
        assert args.http_port == 9090
        assert args.discovery_port == 9091
        assert args.fail_threshold == 2
        assert args.restore_threshold == 4
        assert args.check_interval == 3
        assert args.targets == ["9.9.9.9"]
