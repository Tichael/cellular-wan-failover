package main

import (
	"errors"
	"net"
	"testing"

	"golang.zx2c4.com/wireguard/conn"
)

func TestNewSingleAddrBindRejectsNonSpecificIPv4(t *testing.T) {
	for _, addr := range []string{"", "0.0.0.0", "::", "fe80::1", "not-an-ip"} {
		if _, err := NewSingleAddrBind(addr, 0); err == nil {
			t.Errorf("expected error for bind address %q", addr)
		}
	}
}

func TestSingleAddrBindListensOnlyOnGivenAddress(t *testing.T) {
	b, err := NewSingleAddrBind("127.0.0.1", 0)
	if err != nil {
		t.Fatal(err)
	}
	fns, port, err := b.Open(0)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()

	if _, _, err := b.Open(0); !errors.Is(err, conn.ErrBindAlreadyOpen) {
		t.Fatalf("second Open: got %v, want ErrBindAlreadyOpen", err)
	}

	local := b.udp.LocalAddr().(*net.UDPAddr)
	if !local.IP.Equal(net.IPv4(127, 0, 0, 1)) {
		t.Fatalf("bound to %v, want 127.0.0.1", local.IP)
	}

	// Peer sends a packet to the bind
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	if _, err := peer.WriteToUDP([]byte("hello"), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)}); err != nil {
		t.Fatal(err)
	}

	packets := [][]byte{make([]byte, 1500)}
	sizes := make([]int, 1)
	eps := make([]conn.Endpoint, 1)
	n, err := fns[0](packets, sizes, eps)
	if err != nil || n != 1 {
		t.Fatalf("receive: n=%d err=%v", n, err)
	}
	if got := string(packets[0][:sizes[0]]); got != "hello" {
		t.Fatalf("received %q, want %q", got, "hello")
	}
	if eps[0].DstToString() != peer.LocalAddr().String() {
		t.Fatalf("endpoint %s, want %s", eps[0].DstToString(), peer.LocalAddr())
	}

	// Reply goes back to the peer through Send
	if err := b.Send([][]byte{[]byte("world")}, eps[0]); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 16)
	rn, _, err := peer.ReadFromUDP(buf)
	if err != nil || string(buf[:rn]) != "world" {
		t.Fatalf("peer read %q err=%v", buf[:rn], err)
	}

	// Receive functions must report net.ErrClosed after Close
	b.Close()
	if _, err := fns[0](packets, sizes, eps); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("receive after Close: got %v, want net.ErrClosed", err)
	}
}
