# Raspberry Pi WAN Failover Gateway (UniFi WAN 2 <-> Android WireGuard)

This component turns a Raspberry Pi running Debian 12 into an autonomous backup gateway for an **UniFi Cloud Gateway (UCG-Fiber)** console, relaying traffic over the 4G/5G mobile connection of a wireless Android smartphone.

---

## 1. Network Topology

```text
[ UniFi Console (UCG-Fiber) ]
  ├── WAN 1 Port (Fiber) ───────> Fiber Internet (Primary)
  └── WAN 2 Port (Failover) ────> Direct Ethernet Cable ───> [ eth1: 192.168.100.1/24 ]
                                                                       │
                                                              [ Raspberry Pi (Debian 12) ]
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

## 2. Raspberry Pi Host Prerequisites

### Configuration of `eth1` (USB-Ethernet Adapter)
Interface `eth1` must have a static IP with no default gateway. Using `NetworkManager`:
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

### Method 1: Launch with `docker-compose` (Recommended)
```bash
cd /path/to/pi

# Build the image and start in the background
docker compose up -d --build

# Follow logs in real time
docker compose logs -f
```

### Method 2: Direct Execution with `docker run`
```bash
# 1. Build local image
docker build -t mobile-wan-gateway:latest .

# 2. Run container
docker run -d \
  --name wan-failover-gateway \
  --restart unless-stopped \
  --network host \
  --cap-add NET_ADMIN \
  --cap-add NET_RAW \
  -v "$(pwd)/dnsmasq.conf:/etc/dnsmasq.conf:ro" \
  -v /lib/modules:/lib/modules:ro \
  mobile-wan-gateway:latest
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
   - UniFi detects WAN 2 online and routes local network traffic through it.
5. **Failback Recovery (after 5 consecutive successes on `eth0`)** :
   - Immediately tears down iptables and Table 100 rules (UniFi switches back to WAN 1 instantly).
   - Tears down `wg0`.
   - Calls `POST http://<phone_ip>:8989/v1/failover/stop` to release mobile radio and conserve phone battery.

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

# Stop failover and return to standby
./wan_ctl.sh stop
```
