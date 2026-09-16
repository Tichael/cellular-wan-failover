#!/usr/bin/env bash
set -e

echo "[entrypoint] Enabling IPv4 forwarding..."
sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true

echo "[entrypoint] Starting dnsmasq DHCP server on eth1..."
dnsmasq -k -C /etc/dnsmasq.conf &
DNSMASQ_PID=$!
echo "[entrypoint] dnsmasq running (PID $DNSMASQ_PID)"

cleanup() {
    echo "[entrypoint] Termination signal received. Stopping processes cleanly..."
    if [ -n "${WATCHDOG_PID:-}" ]; then
        kill -TERM "$WATCHDOG_PID" 2>/dev/null || true
        wait "$WATCHDOG_PID" 2>/dev/null || true
    fi
    if [ -n "${DNSMASQ_PID:-}" ]; then
        kill -TERM "$DNSMASQ_PID" 2>/dev/null || true
        wait "$DNSMASQ_PID" 2>/dev/null || true
    fi
    echo "[entrypoint] Shutdown complete."
    exit 0
}

trap cleanup SIGINT SIGTERM

echo "[entrypoint] Starting watchdog daemon..."
python3 /app/watchdog.py "$@" &
WATCHDOG_PID=$!

# Wait for watchdog process
wait "$WATCHDOG_PID"
