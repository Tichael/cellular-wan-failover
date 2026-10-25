# Gateway ↔ Phone Pairing & Authentication Protocol

The gateway and every phone it pairs with share a secret. Every control message between them is signed with it, so devices on the LAN that aren't paired can't start or stop failover, impersonate the phone, or swap the WireGuard keys. After pairing, the secret is never sent over the network.

Implementations: [`gateway/pairing.py`](gateway/pairing.py) + [`gateway/watchdog.py`](gateway/watchdog.py) (gateway), [`android/.../auth/`](android/app/src/main/java/com/example/cellularwanfailover/auth/) + [`ControlHttpServer.kt`](android/app/src/main/java/com/example/cellularwanfailover/http/ControlHttpServer.kt) (phone). Both test suites check the same test vectors (`gateway/test_pairing.py`, `AuthProtocolTest.kt`).

## Threat model

Anything else on the Wi-Fi network can be hostile: a guest, a compromised IoT device, or an evil twin of the home SSID (the phone checks the SSID only, not the BSSID, so it keeps working on mesh networks). Such a device must not be able to:

- trigger failover (spending the phone's mobile data) or stop it;
- answer in the phone's place and give the gateway its own WireGuard key, which would route the house's traffic through it;
- replay messages it captured earlier.

The WireGuard tunnel already encrypts traffic end to end once the keys are right. This protocol makes sure each side gets the other's real key.

## Identifiers and encoding

| Value | Size | Created by |
|---|---|---|
| `gateway_id` | 16 random bytes | the gateway, once (`/config/pairing.json`) |
| `phone_id` | 16 random bytes | the gateway, once per pairing |
| secret `k` | 32 random bytes | the gateway, once per pairing |
| challenge, nonce | 16 random bytes | phone (challenge) / gateway (nonce), once per request |

All binary values are written as base64url without padding. Signatures are `base64url(HMAC-SHA256(k, message))`. A message is a list of fields joined by `\n`, and its first field names the message type. No field may contain `\n`.

| Message | Fields |
|---|---|
| Request | `wf1-req`, gateway_id, phone_id, METHOD, path, challenge, nonce, hex(sha256(body)) |
| Response | `wf1-resp`, gateway_id, phone_id, nonce, HTTP status, hex(sha256(body)) |
| Discovery | `wf1-disc`, gateway_id, phone_id, phone Wi-Fi IPv4, standby (`hot` or `cold`), battery (decimal 0–100), power (`plugged` or `unplugged`), minimum battery (decimal 0–100), `last-resort` or `no-last-resort` |
| Replace | `wf1-replace`, gateway_id, old phone_id, new phone_id |
| Pairing code | `wf1-code`, gateway_id, phone_id |

## Pairing

```
wanctl pair                                    Phone (app)
───────────                                    ───────────
new phone_id + secret, shown as QR code:
wanfailover://pair?v=1&gw=…&gn=<name>&pid=…&k=…
and the pairing code
                                               scan (in-app scanner or camera app) or paste
                                               must be on Wi-Fi; records its SSID
                                               shows the pairing code (same as wanctl pair)
                                               user confirms → pairing PENDING (in memory)
                                               starts services on that Wi-Fi, broadcasts a
                                               signed discovery entry for the pending pairing
receives the broadcast, checks the signature
GET  /v1/challenge                    ───────▶
POST /v1/pair/complete (signed)       ───────▶  valid signature, same Wi-Fi?
                                               → save pairing (secret into Android Keystore),
                                                 trust this SSID
                                      ◀───────  200 {name, ssid, replaces?} (signed)
check the signature, save the phone
in /config/pairing.json under its name
```

- Neither side saves anything until this exchange succeeds. Each side gives up after about 5 minutes, and the phone also gives up if it leaves that Wi-Fi network. `wanctl pair` retries the completion until the phone accepts it, and a repeated completion gets the same answer.
- **The pairing is tied to the Wi-Fi network:** the phone accepts requests for a pairing only while it is on the SSID that pairing was made on. The phone's trusted networks are exactly the SSIDs of its pairings. If the SSID changes, the phone has to be paired again.
- **Pairing the same gateway again** replaces the phone's earlier pairing with it. Then `replaces` = `{phone_id: old, sig: Replace message signed with the OLD secret}`, so the gateway removes the old entry only if the phone proves it held that pairing.
- **Pairing code:** `wanctl pair` and the phone's confirmation screen both show a short code: the first 4 bytes of the Pairing code signature as `XXXX-XXXX` hex. If they match, the phone is pairing with the QR code this gateway displayed, not a link from somewhere else.
- If the pending pairing was dropped on the phone (timeout, Wi-Fi change, user) by the time a completion arrives, the phone answers `409` (signed) and the gateway saves nothing.
- The phone name comes from Android's device name and is only used for display. The gateway strips control characters from it before printing it.
- A pairing link can open the app from any QR code or web page. That's why the app always asks for confirmation, shows the gateway name and the Wi-Fi network, and warns when the pairing replaces an existing one.

## Discovery (UDP broadcast, port 8990, every 5 s)

```json
{"service": "wan-failover", "v": 1, "pairings": [{"gw": "<gateway_id>", "pid": "<phone_id>", "standby": "hot", "battery": 85, "plugged": true, "min_battery": 20, "last_resort": true, "sig": "<Discovery signature>"}]}
```

`standby` says whether the phone offers hot standby (an always-on tunnel; see the README); the pending pairing is always `cold`. `battery` is the battery state of charge in percent (integer 0–100) and `plugged` whether the phone is plugged in (JSON boolean; signed as `plugged`/`unplugged`). `min_battery` (integer 0–100) and `last_resort` (JSON boolean; signed as `last-resort`/`no-last-resort`) are the phone's own settings: below its minimum on battery, the gateway uses another phone if it can, and uses this one only if `last_resort` is true. An entry without valid values for all of these is ignored.

The gateway uses these values to choose a phone, but only as hints: before starting or keeping a hot standby tunnel, it confirms `standby` with the signed `/v1/status`, and before moving the failover off a phone (or releasing it) because its battery is low, it confirms that with the phone's signed `/v1/status` too. A replayed `hot` beacon therefore can't keep the tunnel up for more than about a minute, and a replayed `cold` or low-battery one only makes the gateway release the phone (in standby) or ask it again.

The packet contains one entry per gateway paired on the current Wi-Fi network, plus the pending pairing if there is one. A gateway uses only its own entry, and only if the signature matches the packet's source address. Discovery only tells the gateway where the phone is. A replayed packet from another address is rejected, and a forged source address only points the gateway at the real phone. The signed HTTP exchange below is what actually protects the phone.

## Control requests (HTTP, port 8989, phone's Wi-Fi address only)

1. `GET /v1/challenge` → `{"challenge": "…"}`. This request is unauthenticated. The phone keeps at most 4 challenges per requesting IP address (256 in total), each valid for 30 s and usable once (even by a request that fails the checks). A device that floods the phone with challenge requests only pushes out its own challenges, not the gateway's.
2. The gateway sends the request with these headers: `X-WF-Gateway`, `X-WF-Phone`, `X-WF-Challenge`, `X-WF-Nonce` (new random value) and `X-WF-Signature` (Request message).
3. The phone checks: the headers are well formed → the challenge is valid and unused (it is consumed now) → the pairing exists (pending pairings only for `/v1/pair/complete`) → the phone is on that pairing's Wi-Fi → the signature is valid. Any failure returns `401` without a signature.
4. The phone signs its answer for the gateway's nonce (`X-WF-Signature`, Response message). The gateway ignores any answer whose signature isn't valid. This is what stops an impostor from supplying the WireGuard key in `/v1/status`.

The phone's challenge and the gateway's nonce make both directions fresh without relying on clocks. This matters because a Raspberry Pi without a real-time clock can't sync its time during an internet outage.

| Route | Purpose |
|---|---|
| `GET /v1/status` | State, standby (`hot`/`cold`), `battery` (%), `plugged`, `min_battery`, `last_resort`, and the phone's WireGuard public key and port |
| `POST /v1/failover/start` `{"gateway_public_key"}` | Start cellular + relay. `409` if another gateway is using the relay (`busy`), or the battery is below the phone's minimum and `last_resort` is off (`declined`) |
| `POST /v1/failover/stop` | Stop it. `409` if another gateway started it |
| `POST /v1/pair/complete` | Complete a pending pairing (see above). `409` if it was dropped on the phone |

## Storage

- **Gateway:** `/config/pairing.json` (mode `0600`, Docker volume `gateway-config`) holds the gateway ID and each phone's ID, name, SSID and secret. `wanctl pair`/`unpair` write it, using a lock and atomic replacement. `wanctl unpair` first asks the phone (if it's on the network) to stop any relay it runs for this gateway, because the gateway can't sign that request once the pairing is gone. The watchdog notices changes without restarting.
- **Phone:** the secrets are imported into the Android Keystore as sign-only HMAC keys and can't be read back. The list of pairings (`gateway_pairings.xml`) is excluded from backups and device transfers. A pairing whose Keystore key is missing is dropped.
