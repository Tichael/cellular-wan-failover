//go:build !android

package main

import (
	"log"
	"syscall"
)

func bindFdToNetwork(c syscall.RawConn, netHandle uint64) error {
	return nil
}

func androidLog(msg string) {
	log.Println("[wgrelay]", msg)
}
