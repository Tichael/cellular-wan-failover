import argparse
import json
import os
import stat
import urllib.parse
from unittest.mock import patch

import pairing
import pytest
import watchdog
from test_watchdog import GW_ID, FakePhone

# Shared with the Android tests (AuthProtocolTest.kt): both sides must produce these exact signatures
VECTOR_KEY = bytes(range(32))
VECTOR_PHONE = "cGhvbmUtaWQtMDAwMDAwMQ"
VECTOR_CHALLENGE = "Y2hhbGxlbmdlLTAwMDAwMQ"
VECTOR_NONCE = "bm9uY2UtMDAwMDAwMDAwMQ"
VECTOR_BODY = b'{"gateway_public_key":"YNqHbfBQKaGvzefSSMvi/A7nLC4sQ+iAo56KNNQAo2E="}'


class TestVectors:
    def test_key_encoding(self):
        assert pairing.b64url_encode(VECTOR_KEY) == "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    def test_request_signature(self):
        fields = pairing.request_fields(GW_ID, VECTOR_PHONE, "POST", "/v1/failover/start", VECTOR_CHALLENGE, VECTOR_NONCE, VECTOR_BODY)
        assert pairing.sign(VECTOR_KEY, fields) == "vCQOwRK_Zpwbi3KbuZh57IVEsh4O1iC1McF5n34KcPM"

    def test_response_signature(self):
        fields = pairing.response_fields(GW_ID, VECTOR_PHONE, VECTOR_NONCE, 200, b'{"result":"active"}')
        assert pairing.sign(VECTOR_KEY, fields) == "Nhk2S2diibbLHvgOw8ZO5f05LA3RO9iKbalJsf2pxwk"

    @pytest.mark.parametrize("standby, battery, plugged, min_battery, last_resort, signature", [
        ("cold", 85, False, 20, True, "zEd_0eG__PAEoFGscOeRZ-X6Og2iszvkY_J4Wrsh5dA"),
        ("hot", 100, True, 30, False, "Rr4JEhSfOsl8tjJVxJPNsWcoPAYSCDVduOrnZZe8VC4"),
    ])
    def test_discovery_signature(self, standby, battery, plugged, min_battery, last_resort, signature):
        fields = pairing.discovery_fields(GW_ID, VECTOR_PHONE, "192.168.1.23", standby, battery, plugged, min_battery, last_resort)
        assert pairing.sign(VECTOR_KEY, fields) == signature

    def test_replace_signature(self):
        fields = pairing.replace_fields(GW_ID, "b2xkLXBob25lLWlkLTAwMQ", VECTOR_PHONE)
        assert pairing.sign(VECTOR_KEY, fields) == "6EiGtiiw2MujSygFh6qP7hbD9_lR1eCe2KntmXjJWtk"

    def test_pairing_code(self):
        assert pairing.pairing_code(GW_ID, VECTOR_PHONE, VECTOR_KEY) == "77FB-236A"

    def test_pair_link(self):
        link = pairing.build_pair_link(GW_ID, "Pi Home & Co", VECTOR_PHONE, VECTOR_KEY)
        assert link == (
            "wanfailover://pair?v=1&gw=Z2F0ZXdheS1pZC0wMDAwMQ&gn=Pi%20Home%20%26%20Co"
            "&pid=cGhvbmUtaWQtMDAwMDAwMQ&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        )


class TestSigning:
    def test_verify_round_trip(self):
        fields = pairing.discovery_fields(GW_ID, VECTOR_PHONE, "10.0.0.2", "cold", 85, False, 20, True)
        assert pairing.verify(VECTOR_KEY, pairing.sign(VECTOR_KEY, fields), fields)

    @pytest.mark.parametrize("signature", [None, "", "x", 42, "zEd_0eG__PAEoFGscOeRZ-X6Og2iszvkY_J4Wrsh5dB"])
    def test_verify_rejects(self, signature):
        assert not pairing.verify(VECTOR_KEY, signature, pairing.discovery_fields(GW_ID, VECTOR_PHONE, "192.168.1.23", "cold", 85, False, 20, True))

    def test_fields_cannot_be_shifted(self):
        # A newline inside a field could make two different messages sign the same bytes
        with pytest.raises(ValueError):
            pairing.sign(VECTOR_KEY, ["wf1-disc", "a\nb", "c"])
        assert not pairing.verify(VECTOR_KEY, "x", ["wf1-disc", "a\nb"])

    @pytest.mark.parametrize("value", ["", "a+b", "a/b", "abc=", "AAECAw==", None, "A"])
    def test_b64url_decode_is_strict(self, value):
        with pytest.raises(ValueError):
            pairing.b64url_decode(value)

    def test_new_id_and_key(self):
        assert len(pairing.b64url_decode(pairing.new_id())) == 16
        assert len(pairing.new_key()) == 32
        assert pairing.new_id() != pairing.new_id()

    def test_sanitize_label_strips_terminal_escapes(self):
        assert pairing.sanitize_label("Mom's \x1b[31mPixel\x07\n") == "Mom's [31mPixel"
        assert pairing.sanitize_label("é" * 100) == "é" * 64
        assert pairing.sanitize_label(None) == ""


def phone_record(pid, name="Pixel", key=VECTOR_KEY):
    return pairing.PairedPhone(id=pid, name=name, key=key, ssid="Home", paired_at=1.5)


class TestPairingStore:
    def test_gateway_id_is_created_once(self, tmp_path):
        store = pairing.PairingStore(str(tmp_path / "cfg"))
        gateway_id = store.gateway_id()
        assert len(pairing.b64url_decode(gateway_id)) == 16
        assert pairing.PairingStore(str(tmp_path / "cfg")).gateway_id() == gateway_id

    def test_file_is_private(self, tmp_path):
        store = pairing.PairingStore(str(tmp_path))
        store.add(phone_record("p1"))
        assert stat.S_IMODE(os.stat(store.path).st_mode) == 0o600

    def test_add_get_remove(self, tmp_path):
        store = pairing.PairingStore(str(tmp_path))
        store.add(phone_record("p1", "Pixel A"))
        store.add(phone_record("p2", "Pixel B"))
        assert store.get("p1") == phone_record("p1", "Pixel A")
        assert [p.id for p in store.phones()] == ["p1", "p2"]
        assert store.remove("p1") is True
        assert store.remove("p1") is False
        assert [p.id for p in store.phones()] == ["p2"]

    def test_add_replaces_previous_pairing(self, tmp_path):
        store = pairing.PairingStore(str(tmp_path))
        store.add(phone_record("old"))
        store.add(phone_record("other"))
        store.add(phone_record("new"), replaces="old")
        assert [p.id for p in store.phones()] == ["other", "new"]

    def test_find_by_name_or_id(self, tmp_path):
        store = pairing.PairingStore(str(tmp_path))
        store.add(phone_record("p1", "Mom's Pixel"))
        store.add(phone_record("p2", "Pixel"))
        assert [p.id for p in store.find("mom's pixel")] == ["p1"]
        assert [p.id for p in store.find("p2")] == ["p2"]
        assert store.find("nope") == []

    def test_reader_sees_changes_from_another_process(self, tmp_path):
        watchdog_view = pairing.PairingStore(str(tmp_path))
        assert watchdog_view.get("p1") is None
        pairing.PairingStore(str(tmp_path)).add(phone_record("p1"))
        assert watchdog_view.get("p1") is not None
        pairing.PairingStore(str(tmp_path)).remove("p1")
        assert watchdog_view.get("p1") is None

    def test_gateway_id_survives_phone_changes(self, tmp_path):
        store = pairing.PairingStore(str(tmp_path))
        gateway_id = store.gateway_id()
        store.add(phone_record("p1"))
        store.remove("p1")
        assert json.load(open(store.path))["gateway_id"] == gateway_id


class StubDiscovery:
    """Stands in for the UDP listener: the phone 'broadcasts' from 127.0.0.1 right away."""

    def __init__(self, gateway_id, key_for, port):
        self.gateway_id = gateway_id

    def start(self):
        pass

    def stop(self):
        pass

    def wait_for_phone(self, timeout, phone_id):
        return phone_id, "127.0.0.1"


@pytest.fixture
def phone():
    fake = FakePhone()
    yield fake
    fake.close()


def pair(store, phone, answer):
    """Runs `wanctl pair` against [phone], which 'scans' the QR code and answers [answer] (or answer())."""
    real_link = pairing.build_pair_link

    def scan(gateway_id, gateway_name, phone_id, key):
        phone.gateway_id, phone.phone_id, phone.key, phone.sign_key = gateway_id, phone_id, key, key
        return real_link(gateway_id, gateway_name, phone_id, key)

    phone.routes["/v1/pair/complete"] = answer
    args = argparse.Namespace(discovery_port=0, http_port=phone.port, pair_timeout=5)
    with patch("watchdog.PhoneDiscovery", StubDiscovery), \
            patch("watchdog.pairing.build_pair_link", side_effect=scan), \
            patch("watchdog.print_pairing_qr"):
        return watchdog.run_pairing(store, args)


class TestRunPairing:
    def test_pairs_phone_with_reported_name(self, tmp_path, phone, capsys):
        store = pairing.PairingStore(str(tmp_path))
        assert pair(store, phone, (200, {"name": "Mom's Pixel", "ssid": "Home", "replaces": None})) == 0

        saved = store.get(phone.phone_id)
        assert (saved.name, saved.ssid, saved.key) == ("Mom's Pixel", "Home", phone.key)
        out = capsys.readouterr().out
        assert "Paired with 'Mom's Pixel' on Wi-Fi 'Home'" in out
        link = next(line for line in out.splitlines() if line.startswith("wanfailover://"))
        query = urllib.parse.parse_qs(urllib.parse.urlparse(link).query)
        assert query["gw"] == [store.gateway_id()]
        assert query["pid"] == [phone.phone_id]
        assert pairing.b64url_decode(query["k"][0]) == phone.key
        assert f"Pairing code: {pairing.pairing_code(store.gateway_id(), phone.phone_id, phone.key)}" in out

    def test_phone_name_is_sanitized(self, tmp_path, phone):
        store = pairing.PairingStore(str(tmp_path))
        assert pair(store, phone, (200, {"name": "\x1b[2JPixel", "ssid": "Home"})) == 0
        assert store.get(phone.phone_id).name == "[2JPixel"

    def test_replaces_previous_pairing_with_proof(self, tmp_path, phone, capsys):
        store = pairing.PairingStore(str(tmp_path))
        old_key = bytes([7]) * 32
        store.add(phone_record("old-pid", key=old_key))

        def answer():
            proof = pairing.sign(old_key, pairing.replace_fields(phone.gateway_id, "old-pid", phone.phone_id))
            return 200, {"name": "Pixel", "ssid": "Home", "replaces": {"phone_id": "old-pid", "sig": proof}}

        assert pair(store, phone, answer) == 0
        assert [p.id for p in store.phones()] == [phone.phone_id]
        assert "replaces its previous pairing" in capsys.readouterr().out

    def test_cannot_replace_another_phone_without_its_key(self, tmp_path, phone):
        store = pairing.PairingStore(str(tmp_path))
        store.add(phone_record("victim-pid", "Dad's Pixel"))
        answer = (200, {"name": "Pixel", "ssid": "Home", "replaces": {"phone_id": "victim-pid", "sig": "forged"}})
        assert pair(store, phone, answer) == 0
        assert store.get("victim-pid") is not None

    def test_times_out_without_saving(self, tmp_path):
        store = pairing.PairingStore(str(tmp_path))

        class SilentDiscovery(StubDiscovery):
            def wait_for_phone(self, timeout, phone_id):
                raise TimeoutError()

        args = argparse.Namespace(discovery_port=0, http_port=1, pair_timeout=1)
        with patch("watchdog.PhoneDiscovery", SilentDiscovery), patch("watchdog.print_pairing_qr"):
            assert watchdog.run_pairing(store, args) == 1
        assert store.phones() == []

    @patch("time.sleep")
    def test_retries_until_phone_accepts(self, _sleep, tmp_path, phone):
        store = pairing.PairingStore(str(tmp_path))
        answers = iter([(500, {"message": "not ready"}), (200, {"name": "Pixel", "ssid": "Home"})])
        assert pair(store, phone, lambda: next(answers)) == 0
        assert store.get(phone.phone_id) is not None


class TestReleaseBeforeUnpair:
    def release(self, store, phone, discovery=StubDiscovery):
        args = argparse.Namespace(discovery_port=0, http_port=phone.port if phone else 1)
        record = store.phones()[0]
        with patch("watchdog.PhoneDiscovery", discovery):
            watchdog.release_before_unpair(store, record, args, wait=0.1)

    def setup_phone(self, tmp_path, phone):
        store = pairing.PairingStore(str(tmp_path))
        phone.gateway_id = store.gateway_id()
        store.add(phone_record(phone.phone_id, "Pixel", key=phone.key))
        return store

    def test_stops_the_relay_while_the_key_still_works(self, tmp_path, phone, capsys):
        store = self.setup_phone(tmp_path, phone)
        phone.routes["/v1/failover/stop"] = (200, {"result": "idle"})
        self.release(store, phone)
        assert [(m, p) for m, p, _ in phone.requests] == [("POST", "/v1/failover/stop")]
        assert "back to standby" in capsys.readouterr().out

    def test_relay_serving_another_gateway_is_left_alone(self, tmp_path, phone, capsys):
        store = self.setup_phone(tmp_path, phone)
        phone.routes["/v1/failover/stop"] = (409, {"result": "busy", "message": "Relay in use by another gateway"})
        self.release(store, phone)
        assert "Could not" not in capsys.readouterr().out

    def test_phone_not_on_the_network(self, tmp_path, phone, capsys):
        store = self.setup_phone(tmp_path, phone)

        class SilentDiscovery(StubDiscovery):
            def wait_for_phone(self, timeout, phone_id):
                raise TimeoutError()

        self.release(store, phone, SilentDiscovery)
        assert phone.requests == []
        assert "stop it in the app" in capsys.readouterr().out
