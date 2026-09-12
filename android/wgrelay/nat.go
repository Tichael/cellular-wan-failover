package main

import (
	"encoding/binary"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
)

type NatKey struct {
	Proto   uint8
	SrcIP   [4]byte
	SrcPort uint16
}

type NatVal struct {
	OrigDstIP   [4]byte
	OrigDstPort uint16
	LastSeen    time.Time
}

var natTable sync.Map

func RecordNat(proto uint8, srcIP [4]byte, srcPort uint16, dstIP [4]byte, dstPort uint16) {
	key := NatKey{
		Proto:   proto,
		SrcIP:   srcIP,
		SrcPort: srcPort,
	}
	val := NatVal{
		OrigDstIP:   dstIP,
		OrigDstPort: dstPort,
		LastSeen:    time.Now(),
	}
	natTable.Store(key, val)
}

func LookupNat(proto uint8, srcIP [4]byte, srcPort uint16) (dstIP [4]byte, dstPort uint16, ok bool) {
	key := NatKey{
		Proto:   proto,
		SrcIP:   srcIP,
		SrcPort: srcPort,
	}
	v, exists := natTable.Load(key)
	if !exists {
		return [4]byte{}, 0, false
	}
	nv := v.(NatVal)
	return nv.OrigDstIP, nv.OrigDstPort, true
}

func updateIPv4Checksum(pkt []byte, ihl int) {
	pkt[10] = 0
	pkt[11] = 0
	csum := checksum.Checksum(pkt[:ihl], 0)
	binary.BigEndian.PutUint16(pkt[10:12], ^csum)
}

func updateTCPChecksum(pkt []byte, ihl int) {
	src := tcpip.AddrFromSlice(pkt[12:16])
	dst := tcpip.AddrFromSlice(pkt[16:20])
	tcpData := pkt[ihl:]
	pkt[ihl+16] = 0
	pkt[ihl+17] = 0
	hdr := header.TCP(tcpData)
	dataOffset := int(hdr.DataOffset())
	if dataOffset < header.TCPMinimumSize || dataOffset > len(tcpData) {
		return
	}
	payloadCsum := checksum.Checksum(tcpData[dataOffset:], 0)
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, src, dst, uint16(len(tcpData)))
	xsum = checksum.Combine(xsum, payloadCsum)
	csum := hdr.CalculateChecksum(xsum)
	binary.BigEndian.PutUint16(pkt[ihl+16:ihl+18], ^csum)
}

func updateUDPChecksum(pkt []byte, ihl int) {
	src := tcpip.AddrFromSlice(pkt[12:16])
	dst := tcpip.AddrFromSlice(pkt[16:20])
	udpData := pkt[ihl:]
	pkt[ihl+6] = 0
	pkt[ihl+7] = 0
	hdr := header.UDP(udpData)
	if len(udpData) < header.UDPMinimumSize {
		return
	}
	payloadCsum := checksum.Checksum(udpData[header.UDPMinimumSize:], 0)
	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, src, dst, uint16(len(udpData)))
	xsum = checksum.Combine(xsum, payloadCsum)
	csum := hdr.CalculateChecksum(xsum)
	csum = ^csum
	if csum == 0 {
		csum = 0xffff
	}
	binary.BigEndian.PutUint16(pkt[ihl+6:ihl+8], csum)
}
