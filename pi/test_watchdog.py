import json
import os
import subprocess
import urllib.error
from unittest.mock import MagicMock, patch

import pytest
from watchdog import (
    AndroidFailoverClient,
    PhoneDiscovery,
    RoutingManager,
    check_primary_wan,
    check_tunnel_canary,
    parse_args,
)


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
        result = client.start_failover()

        assert result["status"] == "ACTIVE"
        assert result["active"] is True

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

    @patch("urllib.request.urlopen")
    def test_get_wireguard_config_success(self, mock_urlopen):
        mock_response = MagicMock()
        mock_response.read.return_value = b"[Interface]\nPrivateKey = privkey\nAddress = 10.100.0.2/24\n"
        mock_response.__enter__.return_value = mock_response
        mock_urlopen.return_value = mock_response

        client = AndroidFailoverClient(phone_ip="10.20.0.5")
        config = client.get_wireguard_config()

        assert "[Interface]" in config
        assert "PrivateKey = privkey" in config

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
    @patch("builtins.open")
    @patch("os.makedirs")
    @patch("os.chmod")
    def test_setup_wireguard_interface(self, mock_chmod, mock_makedirs, mock_open, mock_run):
        mock_file = MagicMock()
        mock_open.return_value.__enter__.return_value = mock_file
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")

        manager = RoutingManager(wg_iface="wg0")
        raw_config = "[Interface]\nPrivateKey = foo\nDNS = 1.1.1.1\nAddress = 10.100.0.2/24\n[Peer]\nPublicKey = bar\n"
        manager.setup_wireguard_interface(raw_config)

        # Ensure DNS was stripped and Table = off was added
        written_content = mock_file.write.call_args[0][0]
        assert "DNS" not in written_content
        assert "Table = off" in written_content


class TestArgParsing:
    def test_default_arguments(self):
        args = parse_args([])
        assert args.primary_iface == "eth0"
        assert args.failover_iface == "eth1"
        assert args.failover_gateway == "192.168.100.1"
        assert args.wg_iface == "wg0"
        assert args.http_port == 8989
        assert args.discovery_port == 8990
        assert args.targets == ["1.1.1.1", "8.8.8.8"]
        assert args.canary_ip == "198.18.0.1"
        assert args.fail_threshold == 3
        assert args.restore_threshold == 5
        assert args.check_interval == 5

    def test_custom_arguments(self):
        args = parse_args([
            "--primary-iface", "enp1s0",
            "--failover-iface", "enp2s0",
            "--failover-gateway", "192.168.200.1",
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
        assert args.phone_ip == "10.0.0.123"
        assert args.http_port == 9090
        assert args.discovery_port == 9091
        assert args.fail_threshold == 2
        assert args.restore_threshold == 4
        assert args.check_interval == 3
        assert args.targets == ["9.9.9.9"]
