package main

import (
	"errors"
	"testing"
)

func TestParseLastHandshakeKeepsLatestPeer(t *testing.T) {
	state := "private_key=00\nlisten_port=51820\npublic_key=aa\nlast_handshake_time_sec=1700000000\nlast_handshake_time_nsec=5\n" +
		"public_key=bb\nlast_handshake_time_sec=1700000100\nlast_handshake_time_nsec=0\n"
	if got := parseLastHandshake(state); got != 1700000100 {
		t.Fatalf("parseLastHandshake = %d, want 1700000100", got)
	}
}

func TestParseLastHandshakeWithoutHandshake(t *testing.T) {
	if got := parseLastHandshake("public_key=aa\nlast_handshake_time_sec=0\n"); got != 0 {
		t.Fatalf("parseLastHandshake = %d, want 0", got)
	}
	if got := parseLastHandshake(""); got != 0 {
		t.Fatalf("parseLastHandshake(empty) = %d, want 0", got)
	}
}

func TestDialCellularRefusesWithoutCellularNetwork(t *testing.T) {
	currentNetHandle.Store(0)
	if _, err := dialCellular("udp", "127.0.0.1:9"); !errors.Is(err, errNoCellular) {
		t.Fatalf("dialCellular without cellular network: err = %v, want errNoCellular", err)
	}
}

func TestSetCellularNetworkIgnoredWhileStopped(t *testing.T) {
	isRunningState.Store(false)
	currentNetHandle.Store(0)
	if SetCellularNetwork(42) {
		t.Fatal("SetCellularNetwork succeeded while the relay is stopped")
	}
	if currentNetHandle.Load() != 0 {
		t.Fatal("SetCellularNetwork changed the network while the relay is stopped")
	}
}
