# BlitzLink official relay — blitzlink-relay

> **BlitzLink Lite:** this is the same protocol as native BlitzLink. Lite has no sidecar executable: `LiteTunnel` in `tunnel/` (Java, webrtc-java) takes the same arguments and produces the same JSON lines inside the game's JVM (method calls and callbacks instead of stdin/stdout and exit codes). Relay messages, P2P channels, identity and TURN are byte-for-byte the same, so native and Lite peers mix freely (`tunnel/src/test/java/com/hyproj/blitzlink/lite/InteropTest.java`). The Rust paths and sidecar builds below belong to the native repository (https://github.com/HY-Project/BlitzLinkMC).

The single source of truth for message shapes is
`native/blitzlink-core/src/relay.rs`, shared by the relay (`blitzlink-relay`) and the sidecar (`blitzlink-tunnel`). Sidecar control is in
[PROTOCOL.md](PROTOCOL.md). 한국어: [../ko/RELAY.md](../ko/RELAY.md)

## What it does

Home-hosted MC servers usually sit behind NAT. The relay only **introduces** the two sides: one SDP offer/answer pair and a public list.

```
[MC client] ─TCP→ 127.0.0.1:any ─ [tunnel join] ══ WebRTC DataChannel (direct, UDP) ══ [tunnel host] ─TCP→ 127.0.0.1:mcPort ─ [MC server]
                                       │  └─ (UDP blocked) TURN over TCP → host MC port (mod's first-byte split) ┘
                                       └──wss── [blitzlink-relay @hyproj.com/relay/mcje] ──wss── ┘   (SDP only · public list · STUN shared on :3478)
```

- **Minecraft data never passes through the relay.** The relay hands over one SDP pair and closes the client socket; hyproj only sees a few
  KB of signaling.
- Both sides run **full ICE** (str0m 0.25, not ICE-lite): one UDP socket with host candidates (LAN, loopback) and a server-reflexive
  candidate (public address from STUN), all in the SDP. Both sides send connectivity checks at each other, which punches the NAT. The
  host's UDP port uses the MC port number (random if taken); `--upnp` also maps it on the router.
- Clients whose UDP is blocked use the host's **embedded TURN** (on the MC TCP port; the mod splits by first byte and hands it to the
  sidecar; Bukkit hosts bind it directly on a public port with `--turn-listen`). The host puts credentials in its answer; the relay only
  sanitizes them (`TurnInfo::sanitized`). **There is no TURN on hyproj.**
- STUN uses the existing STUN service at `hyproj.com:3478` (official `data/net.json`, see below). This relay can serve STUN itself (`--stun`) but it is
  off on hyproj.

| Route | Purpose |
|---|---|
| `GET /ws` | WebSocket. The relay sends `hello{relay, nonce}` first. Hosts send `host`, clients send `join`. |
| `GET /servers` | Public list (`ServerList` JSON) — public hosts only, most players first, max 500. Names and MOTDs are censored at STRICT. `Cache-Control: max-age=5`. |
| `GET /health` | `ok` |
| UDP 3478 (optional) | STUN Binding (RFC 5389) — `blitzlink_core::stun`. Off on hyproj (`--no-stun`). |

## Flow

1. **Host registers** — `tunnel host` connects to `/ws`, receives `hello{nonce}` and sends `host{relay, key, sig, listing, public, code?}`.
   `sig` is an Ed25519 signature over the nonce with a context different from the client identity proof (`blitzlink/relay-host/v1`). The
   relay verifies it and assigns a **join code** (`hosted{code, fingerprint}`).
   - Code: `MCJE-` + 4 characters (30 letters without 0·1·O·I·L·U → 810,000 codes). Case, spaces and hyphens are ignored and the prefix is
     optional (`7k3q` → `MCJE-7K3Q`).
   - The host writes the code to `--code-file` and asks for it again next time; the relay also reserves it for that key for **24 hours**.
   - If the same key connects again it takes over the old registration.
   - The host sends `update{listing, public?}` every 5 s when something changed and pings every 20 s. After 75 s of silence the relay
     drops it. On disconnect it retries with a 2 s → 60 s backoff.
   - `listing` is the MC summary (`name·motd·mcVersion·protocol·loader·loaderVersion·modCount·modsHash·players·maxPlayers·onlineMode`).
     **The mod list itself is not sent to the relay** — it goes over the P2P `meta` channel.
2. **Client joins** — `tunnel join --code MCJE-7K3Q` gathers candidates and sends its offer (channel `meta`) as `join{relay, code, sdp}`.
   The relay forwards `offer{session, sdp}` to the host and returns the host's `answer{session, sdp, turn?}` to the client as
   `answer{sdp, fingerprint, listing, turn?}`. No answer within 8 s → `error{hostTimeout}`.
3. **Race** — if the first (UDP) connection has not opened within 2.5 s or fails, a second connection
   that uses only the answer's `turn` starts in parallel (TURN Allocate → one relayed candidate → a new offer through `join`). The first one
   to open wins. Nothing open within 14 s → `rtcFailed`.
4. **Then directly with the host** — on `meta`: `probe{nonce}` → `info{key, sig, info}`. The client verifies the signature
   (`blitzlink/server-identity/v1`) and that the fingerprint equals the one the relay handed over (otherwise `badIdentity`). After that,
   one `mc-N` channel per local TCP connection.

## Limits

- Message 16 KB · SDP 12 KB, first message within 10 s.
- `Origin` — pages outside the allowlist (`--allow-origin`, default `https://hyproj.com`, `https://www.hyproj.com`, `http://localhost:*`,
  `http://127.0.0.1:*`) get 403 on `/ws`. The sidecar sends no `Origin` and always passes (this only stops other web pages from using the relay).
- Per-IP WebSocket token bucket (30 tokens, one every 2 s) — HTTP 429 when empty.
- 5000 hosts total · 8 per IP (`--max-hosts`, `--max-hosts-per-ip`).
- Hosts must sign correctly. Answers to other hosts' sessions are ignored.
- Public list text is trimmed (length, control characters, loader name, hash format) and censored at STRICT (word lists in
  `native/blitzlink-relay/data/chat-filter/`).
- Logs (English) contain codes, fingerprints and IPs only — never SDP.

## Running locally (development)

```
cd native
cargo run -p blitzlink-relay -- --listen 127.0.0.1:1947 --stun 127.0.0.1:3478
cargo run -p blitzlink-tunnel -- host --target 127.0.0.1:25565 --port 25565 --relay http://127.0.0.1:1947 --stun stun:127.0.0.1:3478 --public
cargo run -p blitzlink-tunnel -- join --code MCJE-XXXX --relay http://127.0.0.1:1947 --stun stun:127.0.0.1:3478
```

`blitzlink-tunnel/tests/e2e.rs` (part of `cargo test --workspace`) does this automatically: relay + host + join + an echo "MC server" that
emulates the first-byte split, with a 1 MB round trip and close propagation over both direct UDP and `--turn-only`.

Development builds of the mod switch relays with `-Dblitzlink.relay=http://127.0.0.1:1947` (not available in release builds).

## Deployment proposal — hyproj.com (**only after the user approves**)

Changes to the hyproj.com server deploy immediately, so the following is a proposal. Applying it (image,
compose, nginx, firewall) happens only after the user approves.

1. **Image** — the server is ARM, so linux/arm64. The build context is the `BlitzLink_26.3` root (`blitzlink-core` embeds `data/net.json`).
   ```
   docker buildx build --platform linux/arm64 -f native/blitzlink-relay/Dockerfile -t blitzlink-relay:26.3.0 .
   ```
2. **Compose service** (added to the hyproj.com compose file):
   ```yaml
   blitzlink-relay:
     image: blitzlink-relay:26.3.0
     restart: unless-stopped
     command: ["--listen", "0.0.0.0:1947", "--no-stun", "--trust-proxy"]
     expose: ["1947"]          # only nginx reaches it — not exposed publicly (--trust-proxy trusts X-Real-IP)
   ```
   STUN reuses the STUN service already running on UDP 3478, so no new port is opened. To run a separate one: `--stun 0.0.0.0:3479` +
   `ports: ["3479:3479/udp"]` + change `stun` in `BLITZLINK_NET_JSON` to `stun:hyproj.com:3479`.
3. **nginx** (same server block):
   ```nginx
   location /relay/mcje/ {
       proxy_pass http://blitzlink-relay:1947/;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_set_header X-Real-IP $remote_addr;
       proxy_read_timeout 120s;   # hosts ping every 20 s
   }
   ```
4. **Firewall** — nothing new (TCP goes through nginx on 443; STUN 3478/udp is already open).
5. **Check** — `curl https://hyproj.com/relay/mcje/health` → `ok`, `curl https://hyproj.com/relay/mcje/servers` → `{"relay":1,"servers":[…]}`,
   a `MCJE-…` join code on a dedicated server console, and joining with that code from another PC.

## Network config (`data/net.json`)

The repository's `data/net.json` is a **placeholder** (`https://blitzlink.example.com/relay/mcje`, `stun:blitzlink.example.com:3478`).
The sidecar embeds this file at build time and the mod reads the same file.

- **Official builds**: GitHub Actions (`.github/workflows/natives.yml`, step "Network config") overwrites `data/net.json` with the repository
  variable `BLITZLINK_NET_JSON` before building. For HY-Project/BlitzLinkMC the value is
  `{"relay":"https://hyproj.com/relay/mcje","stun":["stun:hyproj.com:3478"]}`. The mod's jar build has to apply the same variable.
- **Forks and local builds** keep the placeholder. The sidecar then refuses to start unless `--relay` is given (`error{reason:"notConfigured"}`),
  and it never queries placeholder STUN servers. To run your own network, deploy `blitzlink-relay`, then either put its address into
  `data/net.json` or set `BLITZLINK_NET_JSON` in your fork.
- **Players** can point the mod at a custom relay in its advanced settings. The mod passes `--relay` / `--stun` to the sidecar, which overrides
  `data/net.json`.

## Sidecar binaries

All four platforms are built by GitHub Actions (`.github/workflows/natives.yml`, public repository `HY-Project/BlitzLinkMC`) and
merged into one artifact `natives` (`natives/<platform>/blitzlink-tunnel[.exe]` + `SHA256SUMS`), which the single universal mod jar
bundles. Windows x64 can also be built locally.

| Platform | Target | Runner → artifact |
|---|---|---|
| Windows x64 | `x86_64-pc-windows-msvc` | `windows-2025-vs2026` → `natives-windows-x64` (locally: `cargo build --release -p blitzlink-tunnel --target x86_64-pc-windows-msvc` → `native/target/x86_64-pc-windows-msvc/release/blitzlink-tunnel.exe`) |
| Windows arm64 | `aarch64-pc-windows-msvc` | `windows-11-vs2026-arm` → `natives-windows-arm64` |
| Linux x64 | `x86_64-unknown-linux-gnu` | `ubuntu-26.04` → `natives-linux-x64` |
| Linux arm64 | `aarch64-unknown-linux-gnu` | `ubuntu-26.04-arm` → `natives-linux-arm64` |

The Gradle build packs `natives/{windows-x64,windows-arm64,linux-x64,linux-arm64}/` and the SHA-256 list into the one mod jar.

## Not yet

- Distinguishing official signed servers (release).
- IPv6 candidates (currently one IPv4 socket).
- Multiple LAN interfaces — the destination of a received packet is inferred as the single outbound LAN address.
