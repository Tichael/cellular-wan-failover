FROM alpine:latest

# Install required packages for routing, DHCP, WireGuard, and watchdog
RUN apk add --no-cache \
    dnsmasq \
    iptables \
    iproute2 \
    wireguard-tools \
    python3 \
    iputils \
    bash

# Default environment configuration
ENV PRIMARY_IFACE=eth0 \
    FAILOVER_IFACE=eth1 \
    FAILOVER_GATEWAY_IP=192.168.100.1 \
    FAILOVER_CIDR=24 \
    FAILOVER_AUTO_CONFIGURE_IFACE=true \
    FAILOVER_DHCP_ENABLED=true \
    FAILOVER_DHCP_RANGE_START="" \
    FAILOVER_DHCP_RANGE_END="" \
    FAILOVER_DHCP_LEASE_TIME=12h \
    FAILOVER_DNS_SERVERS=1.1.1.1,1.0.0.1 \
    ROUTING_TABLE_ID=100 \
    WG_IFACE=wg0 \
    CLAMP_MSS=true \
    PHONE_IP="" \
    HTTP_PORT=8989 \
    DISCOVERY_PORT=8990 \
    PING_TARGETS="1.1.1.1 8.8.8.8" \
    PING_TIMEOUT=2 \
    CANARY_IP=198.18.0.1 \
    FAIL_THRESHOLD=3 \
    RESTORE_THRESHOLD=5 \
    CHECK_INTERVAL=5

WORKDIR /app

# Copy scripts
COPY watchdog.py /app/watchdog.py
COPY entrypoint.sh /app/entrypoint.sh
COPY wanctl /usr/local/bin/wanctl

RUN chmod +x /app/entrypoint.sh /app/watchdog.py /usr/local/bin/wanctl

ENTRYPOINT ["/app/entrypoint.sh"]