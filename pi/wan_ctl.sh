#!/usr/bin/env bash
#
# Command and test utility script for Raspberry Pi WAN Failover
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HTTP_PORT="${HTTP_PORT:-8989}"
DISCOVERY_PORT="${DISCOVERY_PORT:-8990}"
PRIMARY_IFACE="${PRIMARY_IFACE:-eth0}"
FAILOVER_IFACE="${FAILOVER_IFACE:-eth1}"

ACTION="${1:-help}"
PHONE_IP="${2:-${PHONE_IP:-}}"

case "$ACTION" in
    discover)
        echo "=== Searching for smartphone via UDP broadcast on port $DISCOVERY_PORT ==="
        python3 "$SCRIPT_DIR/watchdog.py" --discover-only --discovery-port "$DISCOVERY_PORT"
        ;;

    status)
        if [ -z "$PHONE_IP" ]; then
            echo "Attempting smartphone auto-discovery..."
            python3 "$SCRIPT_DIR/watchdog.py" --status-only --http-port "$HTTP_PORT" --discovery-port "$DISCOVERY_PORT"
        else
            echo "=== Smartphone status on $PHONE_IP:$HTTP_PORT ==="
            curl -s -f "http://$PHONE_IP:$HTTP_PORT/v1/status" | jq . || curl -s "http://$PHONE_IP:$HTTP_PORT/v1/status"
        fi
        ;;

    start|stop)
        # If Docker container is running, execute command inside container
        if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q "wan-failover-gateway"; then
            echo "=== Executing action $ACTION inside wan-failover-gateway container ==="
            if [ -z "$PHONE_IP" ]; then
                docker exec -it wan-failover-gateway python3 /app/watchdog.py --${ACTION}-now --http-port "$HTTP_PORT" --discovery-port "$DISCOVERY_PORT" --primary-iface "$PRIMARY_IFACE" --failover-iface "$FAILOVER_IFACE"
            else
                docker exec -it wan-failover-gateway python3 /app/watchdog.py --${ACTION}-now --phone-ip "$PHONE_IP" --http-port "$HTTP_PORT" --primary-iface "$PRIMARY_IFACE" --failover-iface "$FAILOVER_IFACE"
            fi
        else
            # Direct execution on host (requires wireguard-tools)
            if ! command -v wg-quick >/dev/null 2>&1; then
                echo "Error: 'wg-quick' is not installed on host."
                echo "-> To test with Docker (recommended): docker compose up -d --build"
                echo "-> Or to test directly on host: sudo apt install -y wireguard-tools"
                exit 1
            fi
            SUDO_CMD=""
            if [ "$(id -u)" -ne 0 ]; then
                SUDO_CMD="sudo"
            fi
            if [ -z "$PHONE_IP" ]; then
                $SUDO_CMD python3 "$SCRIPT_DIR/watchdog.py" --${ACTION}-now --http-port "$HTTP_PORT" --discovery-port "$DISCOVERY_PORT" --primary-iface "$PRIMARY_IFACE" --failover-iface "$FAILOVER_IFACE"
            else
                $SUDO_CMD python3 "$SCRIPT_DIR/watchdog.py" --${ACTION}-now --phone-ip "$PHONE_IP" --http-port "$HTTP_PORT" --primary-iface "$PRIMARY_IFACE" --failover-iface "$FAILOVER_IFACE"
            fi
        fi
        ;;

    test-probe)
        echo "=== Testing WAN 1 probe via $PRIMARY_IFACE to 1.1.1.1 ==="
        ping -I "$PRIMARY_IFACE" -c 3 -W 2 1.1.1.1 || echo "Ping failed via $PRIMARY_IFACE"
        ;;

    test-route)
        echo "=== Testing WAN egress path via $PRIMARY_IFACE (Canary probe) ==="
        if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q "wan-failover-gateway"; then
            docker exec -it wan-failover-gateway python3 /app/watchdog.py --test-route --primary-iface "$PRIMARY_IFACE" --failover-iface "$FAILOVER_IFACE"
        else
            python3 "$SCRIPT_DIR/watchdog.py" --test-route --primary-iface "$PRIMARY_IFACE" --failover-iface "$FAILOVER_IFACE"
        fi
        ;;

    docker-build)
        echo "=== Building Docker image mobile-wan-gateway ==="
        docker build -t mobile-wan-gateway:latest "$SCRIPT_DIR"
        ;;

    docker-run)
        echo "=== Running standalone container via docker run ==="
        docker run -d \
            --name wan-failover-gateway \
            --restart unless-stopped \
            --network host \
            --cap-add NET_ADMIN \
            --cap-add NET_RAW \
            -v "$SCRIPT_DIR/dnsmasq.conf:/etc/dnsmasq.conf:ro" \
            -v /lib/modules:/lib/modules:ro \
            mobile-wan-gateway:latest
        ;;

    *)
        echo "Usage: $0 <action> [PHONE_IP]"
        echo ""
        echo "Available actions:"
        echo "  discover           : Listen to network and display smartphone IP"
        echo "  status [IP]        : Query Android service status (/v1/status)"
        echo "  start [IP]         : Force immediate activation of WireGuard failover"
        echo "  stop [IP]          : Stop failover and restore normal state"
        echo "  test-probe         : Test ping probe via primary interface eth0"
        echo "  test-route         : Verify if egress path is direct (WAN 1) or hairpinned (WAN 2)"
        echo "  docker-build       : Build local Docker image"
        echo "  docker-run         : Run container in background with docker run"
        exit 1
        ;;
esac
