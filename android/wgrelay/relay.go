package main

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

var (
	relayMutex            sync.Mutex
	isRunningState        atomic.Bool
	currentNetHandle      atomic.Uint64
	totalBytesTransmitted atomic.Uint64
	currentDevice         *device.Device
	currentTun            *NetTun
	currentStack          *stack.Stack
)

func isCellularReady() bool {
	return isRunningState.Load() && currentNetHandle.Load() != 0
}

func IsRunning() bool {
	return isRunningState.Load()
}

func GetBytesTransmitted() int64 {
	return int64(totalBytesTransmitted.Load())
}

func StartRelay(port int, privKeyBase64, peerPubKeyBase64 string, netHandle uint64) error {
	relayMutex.Lock()
	defer relayMutex.Unlock()

	if isRunningState.Load() {
		StopRelayLocked()
	}

	privKeyBytes, err := base64.StdEncoding.DecodeString(privKeyBase64)
	if err != nil {
		return fmt.Errorf("invalid private key base64: %w", err)
	}
	peerPubKeyBytes, err := base64.StdEncoding.DecodeString(peerPubKeyBase64)
	if err != nil {
		return fmt.Errorf("invalid peer public key base64: %w", err)
	}

	privKeyHex := hex.EncodeToString(privKeyBytes)
	peerPubKeyHex := hex.EncodeToString(peerPubKeyBytes)

	// Create in-memory gVisor TCP/IP network stack
	opts := stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
			icmp.NewProtocol4,
			icmp.NewProtocol6,
		},
		HandleLocal: true,
	}
	s := stack.New(opts)
	sackOpt := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sackOpt)

	ep := channel.New(2048, 1420, "")
	tcpipErr := s.CreateNIC(1, ep)
	if tcpipErr != nil {
		s.Close()
		return fmt.Errorf("CreateNIC: %v", tcpipErr)
	}

	// Assign tunnel IP 10.100.0.1/24 to NIC 1
	tcpipErr = s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFromSlice(net.ParseIP("10.100.0.1").To4()).WithPrefix(),
	}, stack.AddressProperties{})
	if tcpipErr != nil {
		s.Close()
		return fmt.Errorf("AddProtocolAddress: %v", tcpipErr)
	}

	s.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: 1})
	s.SetSpoofing(1, true)

	currentNetHandle.Store(netHandle)

	// TCP Transparent Forwarder
	fwdTCP := tcp.NewForwarder(s, 0, 10000, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		srcIP := id.RemoteAddress.As4()
		srcPort := id.RemotePort
		var destAddr string
		if origDstIP, origDstPort, ok := LookupNat(6, srcIP, srcPort); ok {
			destAddr = net.JoinHostPort(net.IP(origDstIP[:]).String(), strconv.Itoa(int(origDstPort)))
		} else {
			destAddr = net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
		}
		androidLog(fmt.Sprintf("TCP request from %s to %s", id.RemoteAddress, destAddr))

		var wq waiter.Queue
		clientEP, epErr := r.CreateEndpoint(&wq)
		if epErr != nil {
			androidLog(fmt.Sprintf("TCP CreateEndpoint error: %v", epErr))
			r.Complete(true)
			return
		}
		r.Complete(false)

		clientConn := gonet.NewTCPConn(&wq, clientEP)
		go handleTCPProxy(clientConn, destAddr, netHandle)
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, fwdTCP.HandlePacket)

	// UDP Transparent Forwarder
	fwdUDP := udp.NewForwarder(s, func(r *udp.ForwarderRequest) {
		id := r.ID()
		srcIP := id.RemoteAddress.As4()
		srcPort := id.RemotePort
		var destAddr string
		if origDstIP, origDstPort, ok := LookupNat(17, srcIP, srcPort); ok {
			destAddr = net.JoinHostPort(net.IP(origDstIP[:]).String(), strconv.Itoa(int(origDstPort)))
		} else {
			destAddr = net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
		}
		androidLog(fmt.Sprintf("UDP request from %s to %s", id.RemoteAddress, destAddr))

		var wq waiter.Queue
		clientEP, epErr := r.CreateEndpoint(&wq)
		if epErr != nil {
			androidLog(fmt.Sprintf("UDP CreateEndpoint error: %v", epErr))
			return
		}

		clientConn := gonet.NewUDPConn(&wq, clientEP)
		go handleUDPProxy(clientConn, destAddr, netHandle)
	})
	s.SetTransportProtocolHandler(udp.ProtocolNumber, fwdUDP.HandlePacket)

	// WireGuard device
	netTun := NewNetTun(ep, 1420)
	logger := device.NewLogger(device.LogLevelVerbose, "[wgrelay] ")
	bind := conn.NewDefaultBind()
	dev := device.NewDevice(netTun, bind, logger)

	uapiConfig := fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\nreplace_peers=true\npublic_key=%s\nallowed_ip=10.100.0.2/32\n",
		privKeyHex,
		port,
		peerPubKeyHex,
	)

	err = dev.IpcSetOperation(bufio.NewReader(strings.NewReader(uapiConfig)))
	if err != nil {
		dev.Close()
		netTun.Close()
		s.Close()
		return fmt.Errorf("IpcSetOperation: %w", err)
	}

	err = dev.Up()
	if err != nil {
		dev.Close()
		netTun.Close()
		s.Close()
		return fmt.Errorf("device.Up: %w", err)
	}

	currentDevice = dev
	currentTun = netTun
	currentStack = s
	isRunningState.Store(true)

	androidLog(fmt.Sprintf("Relay started on :%d, netHandle=%d", port, netHandle))
	return nil
}

func handleTCPProxy(clientConn net.Conn, destAddr string, netHandle uint64) {
	defer clientConn.Close()

	androidLog(fmt.Sprintf("TCP dialing %s (netHandle=%d)...", destAddr, netHandle))
	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			return bindFdToNetwork(c, netHandle)
		},
	}

	remoteConn, err := dialer.Dial("tcp", destAddr)
	if err != nil {
		androidLog(fmt.Sprintf("TCP dial %s failed: %v", destAddr, err))
		return
	}
	defer remoteConn.Close()
	androidLog(fmt.Sprintf("TCP connected to %s", destAddr))

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		n, _ := io.Copy(remoteConn, clientConn)
		totalBytesTransmitted.Add(uint64(n))
		if tc, ok := remoteConn.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		n, _ := io.Copy(clientConn, remoteConn)
		totalBytesTransmitted.Add(uint64(n))
		if tc, ok := clientConn.(*gonet.TCPConn); ok {
			tc.CloseRead()
		}
	}()
	wg.Wait()
	androidLog(fmt.Sprintf("TCP session %s closed", destAddr))
}

func handleUDPProxy(clientConn net.Conn, destAddr string, netHandle uint64) {
	defer clientConn.Close()

	androidLog(fmt.Sprintf("UDP dialing %s (netHandle=%d)...", destAddr, netHandle))
	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			return bindFdToNetwork(c, netHandle)
		},
	}

	remoteConn, err := dialer.Dial("udp", destAddr)
	if err != nil {
		androidLog(fmt.Sprintf("UDP dial %s failed: %v", destAddr, err))
		return
	}
	defer remoteConn.Close()
	androidLog(fmt.Sprintf("UDP connected to %s", destAddr))

	done := make(chan struct{}, 2)
	buf1 := make([]byte, 65535)
	buf2 := make([]byte, 65535)

	go func() {
		for {
			clientConn.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, rErr := clientConn.Read(buf1)
			if rErr != nil {
				break
			}
			totalBytesTransmitted.Add(uint64(n))
			remoteConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			_, wErr := remoteConn.Write(buf1[:n])
			if wErr != nil {
				break
			}
		}
		done <- struct{}{}
	}()

	go func() {
		for {
			remoteConn.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, rErr := remoteConn.Read(buf2)
			if rErr != nil {
				break
			}
			totalBytesTransmitted.Add(uint64(n))
			clientConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			_, wErr := clientConn.Write(buf2[:n])
			if wErr != nil {
				break
			}
		}
		done <- struct{}{}
	}()

	<-done
	androidLog(fmt.Sprintf("UDP session %s closed", destAddr))
}

func StopRelay() {
	relayMutex.Lock()
	defer relayMutex.Unlock()
	StopRelayLocked()
}

func StopRelayLocked() {
	if !isRunningState.CompareAndSwap(true, false) {
		return
	}

	currentNetHandle.Store(0)

	if currentDevice != nil {
		currentDevice.Close()
		currentDevice = nil
	}
	if currentTun != nil {
		currentTun.Close()
		currentTun = nil
	}
	if currentStack != nil {
		currentStack.Close()
		currentStack = nil
	}
}

func main() {}
