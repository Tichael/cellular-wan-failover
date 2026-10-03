package main

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"syscall"

	"golang.zx2c4.com/wireguard/conn"
)

// SingleAddrBind is a minimal conn.Bind that listens on one IPv4 address only
// (the phone's Wi-Fi address), unlike conn.NewDefaultBind which listens on every
// interface, including cellular where the phone may have a public IPv6 address.
//
// The socket is also bound to the Wi-Fi Android network (netHandle). Android routes
// by network mark, not by source address: without it, replies to the gateway follow
// the default network, which becomes cellular as soon as Wi-Fi loses internet access
// (i.e. during the very outage the relay exists for).
type SingleAddrBind struct {
	mu        sync.Mutex
	addr      netip.Addr
	netHandle uint64
	udp       *net.UDPConn
}

var _ conn.Bind = (*SingleAddrBind)(nil)

func NewSingleAddrBind(bindAddr string, netHandle uint64) (*SingleAddrBind, error) {
	addr, err := netip.ParseAddr(bindAddr)
	if err != nil {
		return nil, fmt.Errorf("invalid bind address %q: %w", bindAddr, err)
	}
	addr = addr.Unmap()
	if !addr.Is4() || addr.IsUnspecified() {
		return nil, fmt.Errorf("bind address must be a specific IPv4 address, got %q", bindAddr)
	}
	return &SingleAddrBind{addr: addr, netHandle: netHandle}, nil
}

func (b *SingleAddrBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.udp != nil {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			return bindFdToNetwork(c, b.netHandle)
		},
	}
	pc, err := lc.ListenPacket(context.Background(), "udp4", netip.AddrPortFrom(b.addr, port).String())
	if err != nil {
		return nil, 0, err
	}
	udp := pc.(*net.UDPConn)
	b.udp = udp
	actualPort := uint16(udp.LocalAddr().(*net.UDPAddr).Port)

	receive := func(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
		n, src, err := udp.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		eps[0] = &conn.StdNetEndpoint{AddrPort: netip.AddrPortFrom(src.Addr().Unmap(), src.Port())}
		return 1, nil
	}
	return []conn.ReceiveFunc{receive}, actualPort, nil
}

func (b *SingleAddrBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.udp == nil {
		return nil
	}
	err := b.udp.Close()
	b.udp = nil
	return err
}

func (b *SingleAddrBind) SetMark(mark uint32) error {
	return nil
}

func (b *SingleAddrBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	b.mu.Lock()
	udp := b.udp
	b.mu.Unlock()

	if udp == nil {
		return net.ErrClosed
	}
	dst, ok := ep.(*conn.StdNetEndpoint)
	if !ok {
		return conn.ErrWrongEndpointType
	}
	for _, buf := range bufs {
		if _, err := udp.WriteToUDPAddrPort(buf, dst.AddrPort); err != nil {
			return err
		}
	}
	return nil
}

func (b *SingleAddrBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	ap, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return &conn.StdNetEndpoint{AddrPort: ap}, nil
}

func (b *SingleAddrBind) BatchSize() int {
	return 1
}
