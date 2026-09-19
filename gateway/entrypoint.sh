#!/usr/bin/env bash
set -e

# Validate required environment variables (defaults are defined in Dockerfile)
if [ -z "$FAILOVER_IFACE" ]; then
    echo "[entrypoint] ERROR: FAILOVER_IFACE is not set or empty." >&2
    exit 1
fi

if [ -z "$FAILOVER_GATEWAY_IP" ]; then
    echo "[entrypoint] ERROR: FAILOVER_GATEWAY_IP is not set or empty." >&2
    exit 1
fi

if [ -z "$AUTO_CONFIGURE_IFACE" ]; then
    echo "[entrypoint] ERROR: AUTO_CONFIGURE_IFACE is not set or empty (must be 'true' or 'false')." >&2
    exit 1
fi

if [ "$AUTO_CONFIGURE_IFACE" = "true" ] && [ -z "$FAILOVER_CIDR" ]; then
    echo "[entrypoint] ERROR: FAILOVER_CIDR is not set or empty (required when AUTO_CONFIGURE_IFACE=true)." >&2
    exit 1
fi

if [ -z "$DHCP_ENABLED" ]; then
    echo "[entrypoint] ERROR: DHCP_ENABLED is not set or empty (must be 'true' or 'false')." >&2
    exit 1
fi

if [ "$DHCP_ENABLED" = "true" ]; then
    if [ -z "$FAILOVER_NETMASK" ]; then
        echo "[entrypoint] ERROR: FAILOVER_NETMASK is not set or empty (required when DHCP_ENABLED=true)." >&2
        exit 1
    fi
    if [ -z "$DHCP_LEASE_TIME" ]; then
        echo "[entrypoint] ERROR: DHCP_LEASE_TIME is not set or empty (required when DHCP_ENABLED=true)." >&2
        exit 1
    fi
    if [ -z "$DNS_SERVERS" ]; then
        echo "[entrypoint] ERROR: DNS_SERVERS is not set or empty (required when DHCP_ENABLED=true)." >&2
        exit 1
    fi
fi

CONFIGURED_BY_CONTAINER=false

# 1. Interface existence check
if ! ip link show dev "$FAILOVER_IFACE" >/dev/null 2>&1; then
    echo "[entrypoint] ERROR: Failover interface '$FAILOVER_IFACE' does not exist."
    echo "[entrypoint] Please check your hardware connections or set FAILOVER_IFACE to the correct interface name."
    exit 1
fi

# 2. Interface IP configuration
if [ "$AUTO_CONFIGURE_IFACE" = "true" ]; then
    echo "[entrypoint] Checking status of failover interface '$FAILOVER_IFACE'..."
    EXISTING_IPS=$(ip -4 -o addr show dev "$FAILOVER_IFACE" 2>/dev/null | awk '{print $4}' | paste -sd ", " - || true)
    if [ -n "$EXISTING_IPS" ]; then
        echo "[entrypoint] ERROR: Interface '$FAILOVER_IFACE' is already configured with IPv4 address(es): $EXISTING_IPS"
        echo "[entrypoint] AUTO_CONFIGURE_IFACE is enabled, but interface is not clean."
        echo "[entrypoint] To fix: remove host IP configuration (e.g. 'sudo ip addr flush dev $FAILOVER_IFACE'),"
        echo "[entrypoint] or set AUTO_CONFIGURE_IFACE=false in your environment if the host manages this interface."
        exit 1
    fi

    echo "[entrypoint] Bringing up $FAILOVER_IFACE and assigning ${FAILOVER_GATEWAY_IP}/${FAILOVER_CIDR}..."
    ip link set dev "$FAILOVER_IFACE" up
    ip addr add "${FAILOVER_GATEWAY_IP}/${FAILOVER_CIDR}" dev "$FAILOVER_IFACE"
    CONFIGURED_BY_CONTAINER=true

    echo "[entrypoint] Configuring sysctl reverse path filter and forwarding on $FAILOVER_IFACE..."
    sysctl -w "net.ipv4.conf.${FAILOVER_IFACE}.rp_filter=2" >/dev/null 2>&1 || true
    sysctl -w "net.ipv4.conf.${FAILOVER_IFACE}.forwarding=1" >/dev/null 2>&1 || true
else
    echo "[entrypoint] AUTO_CONFIGURE_IFACE=false: leaving interface '$FAILOVER_IFACE' management to host."
fi

# 3. Global IPv4 forwarding
echo "[entrypoint] Enabling IPv4 forwarding..."
sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true

# 4. Dynamic internal dnsmasq generation
DNSMASQ_PID=""
if [ "$DHCP_ENABLED" = "true" ]; then
    if [ -z "$DHCP_RANGE_START" ] || [ -z "$DHCP_RANGE_END" ]; then
        PREFIX="${FAILOVER_GATEWAY_IP%.*}"
        DHCP_RANGE_START="${DHCP_RANGE_START:-${PREFIX}.10}"
        DHCP_RANGE_END="${DHCP_RANGE_END:-${PREFIX}.20}"
    fi

    echo "[entrypoint] Generating internal dnsmasq configuration..."
    cat <<EOF > /etc/dnsmasq.conf
interface=${FAILOVER_IFACE}
bind-interfaces
listen-address=${FAILOVER_GATEWAY_IP}
port=0
dhcp-range=${DHCP_RANGE_START},${DHCP_RANGE_END},${FAILOVER_NETMASK},${DHCP_LEASE_TIME}
dhcp-option=3,${FAILOVER_GATEWAY_IP}
dhcp-option=6,${DNS_SERVERS}
log-dhcp
EOF

    echo "[entrypoint] Starting dnsmasq DHCP server on $FAILOVER_IFACE (${FAILOVER_GATEWAY_IP})..."
    dnsmasq -k -C /etc/dnsmasq.conf &
    DNSMASQ_PID=$!
    echo "[entrypoint] dnsmasq running (PID $DNSMASQ_PID)"
else
    echo "[entrypoint] DHCP server disabled (DHCP_ENABLED=false)."
fi

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
    if [ "$CONFIGURED_BY_CONTAINER" = "true" ]; then
        echo "[entrypoint] De-configuring IP from $FAILOVER_IFACE..."
        ip addr del "${FAILOVER_GATEWAY_IP}/${FAILOVER_CIDR}" dev "$FAILOVER_IFACE" 2>/dev/null || true
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
