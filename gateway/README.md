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
By default, **the gateway container automatically manages the failover interface IP** (`FAILOVER_AUTO_CONFIGURE_IFACE=true`).
- The interface will be brought up and assigned `${FAILOVER_GATEWAY_IP}/${FAILOVER_CIDR}` (default: `192.168.100.1/24`).
- **Fail-fast behavior**: If the interface already has an existing IPv4 address assigned on the host, the container will exit with an error to prevent silent conflicts.
- If you prefer managing the interface manually on the host (e.g. via `NetworkManager` or `systemd-networkd`), set `FAILOVER_AUTO_CONFIGURE_IFACE=false` in your `.env` file.

Manual host configuration (optional, only needed if `FAILOVER_AUTO_CONFIGURE_IFACE=false`):
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

> **Upgrading:** the gateway and the Android app must be updated together. Since pairing was introduced, every request between them is signed ([PROTOCOL.md](../PROTOCOL.md)): after upgrading, pair each phone with `wanctl pair` (unpaired phones are ignored, and `PHONE_IP` is no longer supported). Keep the `/config` volume: it holds the gateway ID and the paired phones.

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
      - FAILOVER_AUTO_CONFIGURE_IFACE=true
      # Internal DHCP server (dnsmasq)
      - FAILOVER_DHCP_ENABLED=true
      # - FAILOVER_DHCP_RANGE_START=192.168.100.10  # Optional (auto-derived from FAILOVER_GATEWAY_IP if omitted)
      # - FAILOVER_DHCP_RANGE_END=192.168.100.20    # Optional (auto-derived from FAILOVER_GATEWAY_IP if omitted)
      - FAILOVER_DHCP_LEASE_TIME=12h
      - FAILOVER_DNS_SERVERS=1.1.1.1,1.0.0.1
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
      - HOT_STANDBY=true   # Always-on tunnel with phones that offer it (see "Hot standby")
      # Name shown on phones when pairing (default: host name)
      # - GATEWAY_NAME=Home gateway
    volumes:
      - /lib/modules:/lib/modules:ro
      - gateway-config:/config   # Gateway ID and paired phones

volumes:
  gateway-config:
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
  -v gateway-config:/config \
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
   - Listens for UDP broadcast beacons sent every 5 seconds by the Android app on port `8990`, and only counts a beacon that carries a valid signature from a phone paired with this gateway (`wanctl pair`).
   - Tracks **every** paired phone (e.g. both adults' phones in a family). A phone is available while its beacons keep arriving (last 15 seconds); only one carries the failover at a time.
   - Handles dynamic smartphone Wi-Fi IP changes automatically with no static DHCP reservation needed.
4. **Failover Trigger (after 3 consecutive failures)** :
   - Generates a fresh WireGuard key pair locally (`wg genkey`). The private key never leaves the gateway.
   - Reads the phone's WireGuard public key and port from `GET http://<phone_ip>:8989/v1/status`. Like every request to the phone, it uses a single-use challenge and is signed with the phone's pairing secret. The answer must be signed by the phone, so another device on the LAN can't substitute its own key ([PROTOCOL.md](../PROTOCOL.md)).
   - Calls `POST http://<phone_ip>:8989/v1/failover/start` with `{"gateway_public_key": "<base64>"}` to activate cellular data and the WireGuard relay on the phone.
   - Builds `/etc/wireguard/wg0.conf` itself from validated fields only (keys, IPv4 addresses, port; mode `0600`). Nothing received from the phone is written verbatim, so the phone cannot inject `PostUp`-style commands.
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
   - The best available phone is used (see **Choosing a phone** below). If it fails to activate (or answers `409`: it is relaying for another gateway), the next one is tried.
5. **Lost Phone Handover** :
   - While failover is active, the watchdog also pings the canary IP through `wg0` itself. Only the phone's relay answers it, so this checks the tunnel, the relay and the phone's cellular link end to end.
   - After 3 consecutive failed probes (`TUNNEL_FAIL_THRESHOLD`), the phone is considered lost (app crashed, empty battery, out of Wi-Fi range...). If WAN 1 is reachable again, failover simply ends; otherwise it is handed over to another available phone, or restarted on the same one if it is the only phone left (a fresh key is generated either way).
6. **Tunnel Canary Verification & Failback Recovery** :
   - While failover is active, the watchdog probes a non-routable benchmark IP (`198.18.0.1`, RFC 2544 / RFC 6890) via `eth0`.
   - **Why this is completely router-agnostic and avoids fragile iptables manipulation**:
     - `198.18.0.1` is reserved for benchmarking and is not routable on the public internet (ISPs drop it).
     - However, the smartphone's userspace WireGuard relay synthesizes ICMP Echo Replies for all ICMP requests entering the tunnel.
     - **If the router routes traffic via WAN 2**: `198.18.0.1` is forwarded through WAN 2 -> `wg0` -> phone replies -> canary probe succeeds. The watchdog knows LAN traffic is still hairpinned through cellular backup and remains in failover standby.
     - **If the router switches back to WAN 1**: `198.18.0.1` is sent out WAN 1 and dropped by the ISP -> canary probe times out! The watchdog now tests public targets (`1.1.1.1`, `8.8.8.8`) via `eth0`.
   - After 5 consecutive direct successes on WAN 1, the Pi cleanly tears down iptables NAT rules, drops `wg0`, and signals the smartphone (`POST /v1/failover/stop`) to return cellular to low-power standby. A phone in hot standby keeps its tunnel instead.
7. **Hot Standby** (`HOT_STANDBY=true`, default) :
   - Phones announce in their signed beacons whether they offer hot standby (app setting *Standby Mode*, e.g. only while plugged in).
   - While WAN 1 works and a phone offers it, the watchdog activates failover right away (same steps as above), so the router sees WAN 2 online at all times. Before that, the phone's signed `/v1/status` must confirm `"standby": "hot"`, and it is confirmed again every 60 s: a replayed beacon can't keep the tunnel up.
   - The tunnel is never restarted when an outage starts: the router simply switches to WAN 2 (seen by the canary from `eth0`).
   - A phone that stops offering it is released right away, except while the router uses WAN 2: then it carries the outage until WAN 1 is back.
   - Failed activations are retried after 30 s, then up to every 5 minutes.
   - A phone on battery below its minimum battery gets no hot standby tunnel (an existing one is released, and another hot phone takes over).
8. **Choosing a phone** :
   - Phones announce their battery level, whether they are plugged in, and their battery settings (app: *Minimum Battery*, default 20%, and *Use below the minimum if no other phone can*, default on), in the same signed beacons.
   - When several phones are available, the gateway ranks them: battery not low first (plugged in, or at least the phone's minimum), then phones offering hot standby (their owners chose them as the backup), then plugged in, then the highest battery level. A phone low on battery is only used when no other phone can be, and only if its owner allows it (otherwise the phone also refuses to start).
   - **During an outage, the failover moves to a better phone** when:
     - the active phone runs low on battery (confirmed by its signed `/v1/status`) and another phone is plugged in or has at least its own minimum + 10%. If the active phone may not be used below its minimum, any phone that isn't low takes over, and with none the phone is released anyway;
     - the active phone doesn't offer hot standby and a phone that does (and isn't low) becomes available, e.g. a phone set to *Always on* comes home. After the outage, that phone keeps the hot standby tunnel.
   - The new phone must have announced the same state for 60 s first. The move is make-before-break: the new phone starts its cellular relay while the current one keeps carrying traffic, then `wg0` switches over and the new tunnel is checked before the old phone is released. Every phone change gives the network a new cellular public IP, so open connections reset.
   - If the new phone fails to start or doesn't answer through the tunnel, the current phone keeps (or gets back) the tunnel, and that phone is retried after 30 s, then up to every 5 minutes.

---

## 5. CLI Utility (`wanctl`)

The image includes the `wanctl` CLI for manual testing and operational control. Run it from the host with `docker exec` (no files needed on the host):

```bash
# Pair a smartphone: shows a QR code to scan in the app (phone on the gateway's Wi-Fi)
docker exec -it wan-failover-gateway wanctl pair

# List paired smartphones (name, Wi-Fi, online status, standby, battery, minimum) / remove one
docker exec -it wan-failover-gateway wanctl phones
docker exec -it wan-failover-gateway wanctl unpair "<phone name>"

# Discover a paired smartphone on local Wi-Fi
docker exec -it wan-failover-gateway wanctl discover

# Check Android gateway status and byte counters (optionally for one phone: status "<phone name>")
docker exec -it wan-failover-gateway wanctl status

# Manually trigger WireGuard failover
docker exec -it wan-failover-gateway wanctl start

# Test primary WAN 1 probe via eth0
docker exec -it wan-failover-gateway wanctl test-probe

# Verify if egress path is direct (WAN 1) or hairpinned (WAN 2)
docker exec -it wan-failover-gateway wanctl test-route

# Stop failover and return to standby
docker exec -it wan-failover-gateway wanctl stop

# Show all actions
docker exec -it wan-failover-gateway wanctl help
```

Optional shortcut on the host: `alias wanctl='docker exec -it wan-failover-gateway wanctl'`. For debugging, `docker exec -it wan-failover-gateway bash` opens a shell where `wanctl`, `wg`, `ip` and `iptables` are available.
