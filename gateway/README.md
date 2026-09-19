# Cellular WAN Failover Gateway (UniFi WAN 2 <-> Android WireGuard)

This component turns a Linux machine (Raspberry Pi, Mini PC, or Debian/Ubuntu server) into an autonomous backup gateway for an **UniFi Cloud Gateway (UCG-Fiber)** console, relaying traffic over the 4G/5G mobile connection of a wireless Android smartphone.

---

## 1. Network Topology

```text
[ UniFi Console (UCG-Fiber) ]
  ├── WAN 1 Port (Fiber) ───────> Fiber Internet (Primary)
  └── WAN 2 Port (Failover) ────> Direct Ethernet Cable ───> [ eth1: 192.168.100.1/24 ]
                                                                       │
                                                              [ Linux Gateway Host ]
                                                                       │
  ┌───────────────────────────────────────────────────────────────────┘
  │
  ├── [ eth0: 10.20.0.X/24 ] ───> Local LAN (UniFi Switch / AP)
  │                                    │ (Local Wi-Fi)
  │                                    ▼
  └── [ wg0: 10.100.0.2/24 ] ───> [ Android Smartphone ]
        (WireGuard Tunnel)             ├── Wi-Fi: 10.20.0.Y (HTTP API 8989 / WG 51820 / UDP 8990)
                                       └── Mobile Data (LTE/5G) ───> Cellular Internet
```

---

## 2. Host Prerequisites

### Configuration of `FAILOVER_IFACE` (e.g. `eth1`)
By default, **the gateway container automatically manages the failover interface IP** (`AUTO_CONFIGURE_IFACE=true`).
- The interface will be brought up and assigned `${FAILOVER_GATEWAY_IP}/${FAILOVER_CIDR}` (default: `192.168.100.1/24`).
- **Fail-fast behavior**: If the interface already has an existing IPv4 address assigned on the host, the container will exit with an error to prevent silent conflicts.
- If you prefer managing the interface manually on the host (e.g. via `NetworkManager` or `systemd-networkd`), set `AUTO_CONFIGURE_IFACE=false` in your `.env` file.

Manual host configuration (optional, only needed if `AUTO_CONFIGURE_IFACE=false`):
```bash
sudo nmcli con add type ethernet ifname eth1 con-name "WAN2-Link" \
  ipv4.method manual ipv4.addresses 192.168.100.1/24 \
  ipv4.never-default yes ipv6.method disabled
sudo nmcli con up "WAN2-Link"
```

### Kernel Modules (WireGuard & iptables)
Debian 12 natively includes WireGuard in its Linux kernel (6.1+). Ensure required kernel modules are loaded:
```bash
sudo modprobe wireguard
sudo modprobe iptable_nat
```

---

## 3. Docker Container Deployment

The gateway runs as a self-contained, lightweight Alpine Docker container.

### Configuration (`.env`)
Copy the provided `.env.example` to `.env` and customize parameters if needed:
```bash
cp .env.example .env
```

### Method 1: Launch with `docker compose` (Recommended)

#### Option A: Clone repository or copy `gateway/`
```bash
cd /path/to/gateway

# 1. Customize configuration if needed
cp .env.example .env

# 2. Start container in background (use --build to build locally)
docker compose up -d

# 3. Follow logs in real time
docker compose logs -f
```

#### Option B: Standalone `compose.yaml` (No Git clone required)
Save the following as `compose.yaml` in your homelab directory:

```yaml
services:
  wan-failover-gateway:
    image: ghcr.io/tichael/cellular-wan-gateway:latest
    container_name: wan-failover-gateway
    restart: unless-stopped
    network_mode: host
    cap_add:
      - NET_ADMIN
      - NET_RAW
    environment:
      # Network interfaces
      - PRIMARY_IFACE=eth0
      - FAILOVER_IFACE=eth1
      # Failover gateway IP & automatic interface configuration
      - FAILOVER_GATEWAY_IP=192.168.100.1
      - FAILOVER_CIDR=24
      - FAILOVER_NETMASK=255.255.255.0
      - AUTO_CONFIGURE_IFACE=true
      # Internal DHCP server (dnsmasq)
      - DHCP_ENABLED=true
      - DHCP_RANGE_START=192.168.100.10
      - DHCP_RANGE_END=192.168.100.20
      - DHCP_LEASE_TIME=12h
      - DNS_SERVERS=9.9.9.10,149.112.112.10
      # Routing table & WireGuard
      - ROUTING_TABLE_ID=100
      - WG_IFACE=wg0
      - CLAMP_MSS=true
      # Health checks & thresholds
      - PING_TARGETS=1.1.1.1 8.8.8.8
      - PING_TIMEOUT=2
      - CANARY_IP=198.18.0.1
      - FAIL_THRESHOLD=3
      - RESTORE_THRESHOLD=5
      - CHECK_INTERVAL=5
    volumes:
      - /lib/modules:/lib/modules:ro
```

Run:
```bash
docker compose up -d
```

### Method 2: Direct Execution with `docker run`
```bash
# 1. Pull prebuilt image
docker pull ghcr.io/tichael/cellular-wan-gateway:latest

# 2. Run container (uses default environment or pass custom -e flags / --env-file .env)
docker run -d \
  --name wan-failover-gateway \
  --restart unless-stopped \
  --network host \
  --cap-add NET_ADMIN \
  --cap-add NET_RAW \
  -v /lib/modules:/lib/modules:ro \
  ghcr.io/tichael/cellular-wan-gateway:latest
```

---

## 4. Watchdog Operation (`watchdog.py`)

1. **DHCP Server (`dnsmasq`)** :
   - Listens strictly on `eth1` (`192.168.100.1`).
   - Leases `192.168.100.10` with default gateway `192.168.100.1` to UniFi WAN 2.
   - `port=0`: DNS server disabled to prevent any port 53 conflict with local DNS servers (e.g. AdGuard Home).
2. **WAN 1 Monitoring** :
   - Continuously pings `1.1.1.1` and `8.8.8.8` via `eth0` (`ping -I eth0`).
3. **Smartphone Auto-Discovery** :
   - Listens for UDP broadcast beacons sent every 5 seconds by the Android app on port `8990`.
   - Handles dynamic smartphone Wi-Fi IP changes automatically with no static DHCP reservation needed.
4. **Failover Trigger (after 3 consecutive failures)** :
   - Calls `POST http://<phone_ip>:8989/v1/failover/start` to activate cellular data and the WireGuard relay on the phone.
   - Retrieves configuration via `GET http://<phone_ip>:8989/v1/wireguard/config`.
   - Brings up WireGuard interface `wg0`.
   - Applies an isolated policy routing table (**Table 100**) so only UniFi WAN 2 traffic is redirected:
     ```bash
     ip route replace default dev wg0 table 100
     ip rule add iif eth1 table 100
     ```
   - Injects transit and NAT iptables rules:
     ```bash
     iptables -A FORWARD -i eth1 -o wg0 -j ACCEPT
     iptables -A FORWARD -i wg0 -o eth1 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
     iptables -t nat -A POSTROUTING -o wg0 -j MASQUERADE
     ```
   - Router detects WAN 2 online and routes local network traffic through it.
5. **Tunnel Canary Verification & Failback Recovery** :
   - While failover is active, the watchdog probes a non-routable benchmark IP (`198.18.0.1`, RFC 2544 / RFC 6890) via `eth0`.
   - **Why this is completely router-agnostic and avoids fragile iptables manipulation**:
     - `198.18.0.1` is reserved for benchmarking and is not routable on the public internet (ISPs drop it).
     - However, the smartphone's userspace WireGuard relay synthesizes ICMP Echo Replies for all ICMP requests entering the tunnel.
     - **If the router routes traffic via WAN 2**: `198.18.0.1` is forwarded through WAN 2 -> `wg0` -> phone replies -> canary probe succeeds. The watchdog knows LAN traffic is still hairpinned through cellular backup and remains in failover standby.
     - **If the router switches back to WAN 1**: `198.18.0.1` is sent out WAN 1 and dropped by the ISP -> canary probe times out! The watchdog now tests public targets (`1.1.1.1`, `8.8.8.8`) via `eth0`.
   - After 5 consecutive direct successes on WAN 1, the Pi cleanly tears down iptables NAT rules, drops `wg0`, and signals the smartphone (`POST /v1/failover/stop`) to return cellular to low-power standby.

---

## 5. CLI Utility (`wan_ctl.sh`)

A helper script is provided for manual testing and operational control:

```bash
chmod +x wan_ctl.sh

# Discover smartphone on local Wi-Fi
./wan_ctl.sh discover

# Check Android gateway status and byte counters
./wan_ctl.sh status

# Manually trigger WireGuard failover
./wan_ctl.sh start

# Test primary WAN 1 probe via eth0
./wan_ctl.sh test-probe

# Verify if egress path is direct (WAN 1) or hairpinned (WAN 2)
./wan_ctl.sh test-route

# Stop failover and return to standby
./wan_ctl.sh stop
```
