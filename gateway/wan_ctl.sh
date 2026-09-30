#!/usr/bin/env bash
#
# Command and test utility script for Cellular WAN Failover Gateway
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER_NAME="${CONTAINER_NAME:-wan-failover-gateway}"
PRIMARY_IFACE="${PRIMARY_IFACE:-eth0}"

ACTION="${1:-help}"
PHONE_IP="${2:-${PHONE_IP:-}}"

run_gateway() {
    if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        docker exec -it "$CONTAINER_NAME" python3 /app/watchdog.py "$@"
    elif command -v python3 >/dev/null 2>&1 && [ -f "$SCRIPT_DIR/watchdog.py" ]; then
        python3 "$SCRIPT_DIR/watchdog.py" "$@"
    else
        echo "Error: Gateway container '$CONTAINER_NAME' is not running." >&2
        echo "Start it with: docker compose up -d" >&2
        exit 1
    fi
}

EXTRA_ARGS=()
if [ -n "$PHONE_IP" ]; then
    EXTRA_ARGS+=(--phone-ip "$PHONE_IP")
fi

case "$ACTION" in
    discover)
        echo "=== Searching for smartphone via UDP broadcast ==="
        run_gateway --discover-only
        ;;

    status)
        echo "=== Querying smartphone service status ==="
        run_gateway --status-only "${EXTRA_ARGS[@]}"
        ;;

    start)
        echo "=== Activating cellular failover ==="
        run_gateway --start-now "${EXTRA_ARGS[@]}"
        ;;

    stop)
        echo "=== Stopping cellular failover ==="
        run_gateway --stop-now "${EXTRA_ARGS[@]}"
        ;;

    test-probe)
        echo "=== Testing primary WAN probe via $PRIMARY_IFACE ==="
        ping -I "$PRIMARY_IFACE" -c 3 -W 2 1.1.1.1 || echo "Ping failed via $PRIMARY_IFACE"
        ;;

    test-route)
        echo "=== Testing WAN egress path (Canary probe) ==="
        run_gateway --test-route
        ;;

    *)
        echo "Usage: $0 <action> [PHONE_IP]"
        echo ""
        echo "Available actions:"
        echo "  discover           : Listen to network and display smartphone IP"
        echo "  status [IP]        : Query Android service status (/v1/status)"
        echo "  start [IP]         : Force immediate activation of WireGuard failover"
        echo "  stop [IP]          : Stop failover and restore normal state"
        echo "  test-probe         : Test ping probe via primary interface ($PRIMARY_IFACE)"
        echo "  test-route         : Verify if egress path is direct (WAN 1) or hairpinned (WAN 2)"
        exit 1
        ;;
esac
