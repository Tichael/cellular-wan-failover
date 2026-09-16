FROM alpine:latest

# Install required packages for routing, DHCP, WireGuard, and watchdog
RUN apk add --no-cache \
    dnsmasq \
    iptables \
    iproute2 \
    wireguard-tools \
    python3 \
    iputils \
    bash \
    curl \
    ca-certificates

WORKDIR /app

# Copy configuration files and scripts
COPY dnsmasq.conf /etc/dnsmasq.conf
COPY watchdog.py /app/watchdog.py
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh /app/watchdog.py

ENTRYPOINT ["/app/entrypoint.sh"]