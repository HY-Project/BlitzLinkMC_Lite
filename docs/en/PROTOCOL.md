# BlitzLink sidecar control protocol (`blitzlink-tunnel`)

> **BlitzLink Lite:** this is the same protocol as native BlitzLink. Lite has no sidecar executable: `LiteTunnel` in `tunnel/` (Java, webrtc-java) takes the same arguments and produces the same JSON lines inside the game's JVM (method calls and callbacks instead of stdin/stdout and exit codes). Relay messages, P2P channels, identity and TURN are byte-for-byte the same, so native and Lite peers mix freely (`tunnel/src/test/java/com/hyproj/blitzlink/lite/InteropTest.java`). The Rust paths and sidecar builds below belong to the native repository (https://github.com/HY-Project/BlitzLinkMC).

The mod or plugin (Java) extracts `natives/<os>-<arch>/blitzlink-tunnel(.exe)` from its jar, starts it, and controls it with **JSON lines
on stdin/stdout** (one object per line, tagged by `t`). The Rust source of truth is `native/blitzlink-tunnel/src/control.rs`
(Java → sidecar) and every `emit(...)` call (sidecar → Java). Relay messages and the P2P flow are described in [RELAY.md](RELAY.md).
한국어: [../ko/PROTOCOL.md](../ko/PROTOCOL.md)

## Launching

```
blitzlink-tunnel host --target 127.0.0.1:<mcPort> --port <mcPort> --relay <url> --stun <a,b> --key-file <path> --code-file <path>
                      [--public] [--upnp] [--no-turn] [--turn-listen <addr:port>] [--proxy-protocol] --json
blitzlink-tunnel join --code MCJE-XXXX --relay <url> --stun <a,b> --json [--turn-only]
```

| Argument | Meaning |
|---|---|
| `--target` | (host) Minecraft server to dial for every tunneled connection, usually `127.0.0.1:<mcPort>`. |
| `--port` | (host) The Minecraft server's TCP port. The WebRTC UDP socket tries the same number first (falls back to a random port, see `status.udpPort`). Without `--turn-listen` the embedded TURN is advertised on this port (`turn:<publicIP>:<port>?transport=tcp`). |
| `--relay` | Relay URL (official `https://hyproj.com/relay/mcje`; for development `http://127.0.0.1:1947`; or a custom relay from the mod's advanced settings). Defaults to `relay` in the `data/net.json` embedded at build time. If that is the repository placeholder (`*.example.com`), the sidecar exits with `notConfigured` unless `--relay` is given (see RELAY.md, "Network config"). |
| `--stun` | STUN servers, **one comma-separated argument** (`stun:a:3478,stun:b:3478`); repeating the flag also works. Defaults to `stun` in `data/net.json`; placeholder (`*.example.com`) entries are skipped. `off` disables STUN. |
| `--key-file` | (host) Ed25519 seed (one line, 64 hex chars); created if missing. Default `blitzlink-host.key`. |
| `--code-file` | (host) The last join code. Default `blitzlink-code.txt`. |
| `--public` | (host) List on the public server list from the start (change it later with `public`). |
| `--upnp` | (host) Map TCP `--port` (plus the `--turn-listen` port) and UDP `udpPort` on the router via UPnP-IGD; removed on exit. |
| `--no-turn` | (host) Do not run the embedded TURN server (no `turnPort` line). |
| `--turn-listen` | (host, **Bukkit**) Bind the embedded TURN directly on this TCP address (e.g. `0.0.0.0:25566`) instead of loopback, and advertise that port in `answer.turn`. `turnPort` is still reported. Without it, TURN listens on loopback and Java pipes game-port connections to it. Port 0 picks a free port (reported in `turnPort`). |
| `--proxy-protocol` | (host, **Bukkit**) When dialing `--target`, send a HAProxy PROXY protocol v2 header with the player's real address before any byte (Paper `proxies.proxy-protocol: true`). If the address is unknown a `LOCAL` header is sent. `peer` is still emitted. |
| `--turn-only` | (join, **development only**) Skip the direct UDP attempt and use the host's TURN only. |
| `--json` | JSON lines on stdout. **Java always passes it.** Without it stdout carries human-readable `[t] k=v …` lines. |

- **stdout**: sidecar → Java, one JSON object per line.
- **stderr**: human-readable log in English (`[blitzlink] …`); Java forwards it into the Minecraft log.
- **stdin**: Java → sidecar, one JSON object per line. **Closing stdin is the same as `quit`**, so the sidecar never outlives the game.
- **Exit code**: 0 when ended by `quit` or (join) after `closed`; 1 on an error (an `error{reason}` line is printed right before exiting).

## Java → sidecar (stdin)

| Message | Mode | Meaning |
|---|---|---|
| `{"t":"listing","listing":{"name":…,"motd":…,"mcVersion":…,"protocol":…,"loader":…,"loaderVersion":…,"modCount":…,"modsHash":…,"players":…,"maxPlayers":…,"onlineMode":…}}` | host | Summary for the public list and join screen. `loader` is `vanilla` · `neoforge` · `fabric` · `bukkit` (anything else becomes `vanilla`); `loaderVersion` e.g. `21.3.4` or `Paper 26.3-251`. Missing fields take defaults; the flattened form (fields next to `t`) is also accepted. Send once right after launch and whenever the player count changes. The sidecar forwards only changes to the relay, every 5 s, and waits up to 5 s for the first `listing` before registering. |
| `{"t":"meta","info":{…}}` | host | Server info for joiners (`ServerInfo{mcVersion, protocol, loader, loaderVersion, mods:[{id,name,version,side,required}]}`, `side` may be `plugin`). Relayed **verbatim** over the P2P `meta` channel (≤ 256 KB). Sending it again pushes the update to connected joiners. |
| `{"t":"public","value":true}` | host | Add to / remove from the public list. `{"t":"public","public":true}` is accepted too. |
| `{"t":"turnPeer","port":51234,"addr":"203.0.113.5:40000"}` | host | *Optional.* When Java pipes a TURN connection (first byte 0x00–0x03) from the game port to `turnPort`: `port` is the **local port of Java's loopback socket**, `addr` the external remote address. With it, `peer.addr` (and the PROXY header) of TURN players carries their real IP instead of loopback. Send right after opening the loopback connection. |
| `{"t":"quit"}` | both | Clean up (the peer is told the connection is closing) and exit 0. |

Unknown or unparsable lines are logged to stderr and ignored.

## Host → Java (stdout)

| Message | When |
|---|---|
| `{"t":"turnPort","port":N}` | Right after start (unless `--no-turn`). Loopback TCP port of the embedded TURN; with `--turn-listen`, the bound port. Java pipes game-port connections whose first byte is 0x00–0x03 to `127.0.0.1:N` (not needed with `--turn-listen`). |
| `{"t":"hosted","code":"MCJE-7K3Q","fingerprint":"ABCD-EFGH-IJKL-MNOP"}` | Every time the host registers with the relay (reconnects included; the code normally stays the same). Also written to `--code-file`. |
| `{"t":"status","state":"online"\|"reconnecting","publicAddr"?:"ip:port","upnp"?:bool, …}` | Whenever anything changes (compared every 0.5 s). `publicAddr` once STUN answered; `upnp` only with `--upnp` once the result is known (`true` = mapped). Extra fields: `code`, `reason` (while reconnecting), `public`, `udpPort`, `peers` (joiners with `meta` open), `tunnels` (open `mc-N`), `turnAllocs`. |
| `{"t":"peer","localPort":N,"addr":"ip:port"}` | A tunnel connected to `--target`, emitted **before any byte is written on that socket**. `localPort` is that socket's local port: the MC server sees the connection coming from `127.0.0.1:N`. `addr` is the player's real address, `ip:port` or `[v6]:port`. For direct connections it is the remote side of the selected ICE pair. For TURN it is the TURN TCP peer: the player itself with `--turn-listen`, otherwise the `turnPeer` address or loopback. It is `"unknown"` if no address is known. |
| `{"t":"peerClosed","localPort":N}` | That tunnel's socket to the MC server closed (either side). |
| `{"t":"error","reason":…}` | `badSignature` / `version` from the relay are fatal for hosting: the sidecar keeps running but gets no code. Also printed right before exiting with 1. |

The host never drops a joiner whose `meta` channel is open. It only drops one when the peer closes or ICE has been down for more than 10 s.

## Join → Java (stdout)

| Message | When |
|---|---|
| `{"t":"status","state":"relay","code":"MCJE-7K3Q"}` | Start: gathering candidates and exchanging SDP through the relay. |
| `{"t":"status","state":"connecting"}` | The direct (UDP, hole punching) attempt started. |
| `{"t":"status","state":"turn"}` | The TURN attempt started (UDP not open within 2.5 s, failed, or `--turn-only`). |
| `{"t":"status","state":"open","via":"udp"\|"turn"[,"rttMs":N]}` | The P2P connection is open. Repeated every 15 s with `rttMs` (meta ping round trip). |
| `{"t":"meta","info":{…},"fingerprint":…,"listing":{…},"via":…,"rttMs":N\|null}` | Once the channel is open **and the host identity is verified**: `info` is exactly what the host's Java sent; `fingerprint` was checked against the relay and the host's signature; `listing` is the relay's sanitized summary. Sent again when the host updates its info (`rttMs: null`). |
| `{"t":"listen","port":N}` | Right after the first `meta`. Point Minecraft at `127.0.0.1:N`. Every TCP connection opens one `mc-N` channel (status ping and login can use separate connections). |
| `{"t":"closed"}` | The P2P session ended after it was open (host quit, or the connection dropped). The sidecar then exits 0. |
| `{"t":"error","reason":…}` | Right before exiting with 1. |

The join session stays alive until `quit` or stdin EOF, so the user can sit on the mod-list screen as long as they like before connecting.

### Error `reason` values

| Value | Meaning |
|---|---|
| `noSuchCode` | No such code (also for input that is not a code). |
| `hostTimeout` | The host did not answer in time. |
| `refused` | The host refused (full or shutting down). |
| `rateLimited` · `full` | The relay is limiting. |
| `version` | Relay protocol mismatch, so the mod needs an update. |
| `badSignature` · `badMessage` | (host) Signature or message rejected by the relay. |
| `relayUnreachable` | Could not reach the relay. |
| `badPacket` | The relay or host sent something unparsable. |
| `rtcFailed` | Neither direct nor TURN opened within 14 s (or the connection dropped before identity was verified). |
| `badIdentity` | The host's signature is wrong or its fingerprint differs from the relay's: possible man-in-the-middle, so show the red warning. |
| `timeout` | The connection opened but the identity probe got no answer. |
| `notConfigured` | (both) No relay configured: this build has the placeholder `data/net.json` and no `--relay` was given. Printed at start, exit 1. |

## Embedded TURN: what Java must do (host)

Players on networks that block UDP use TURN (RFC 8656, over TCP) through the host.

- **Mod servers (NeoForge/Fabric), default.** TURN arrives on the **game port**. Java looks at the first byte at the very front of the MC server's
  TCP pipeline:
  - If it is `0x00`–`0x03` (STUN header), pipe **every byte of that connection (including the first)** both ways to `127.0.0.1:<turnPort>`.
    Optionally send `turnPeer` right after opening the loopback connection.
  - Otherwise, remove the handler and treat it as a normal MC connection. An MC handshake starts with a varint length (≥ 0x07) or the legacy
    ping `0xFE`, so the ranges never overlap.
- **Bukkit**: start the sidecar with `--turn-listen 0.0.0.0:<port>` (that TCP port must be reachable from outside, as UPnP does) and
  `--proxy-protocol` with Paper's `proxies.proxy-protocol: true`. No first-byte split is needed.

The TURN server only forwards to this host's own WebRTC UDP socket, so it is not an open relay. The host creates the credentials for each
offer and the relay hands them out (HMAC; the client must Allocate within 300 s).

## P2P channels (reference: between sidecars)

- `meta` (reliable, ordered; created by the joiner in its SDP). JSON text:
  - the joiner sends `probe{nonce}` and the host answers `info{key, sig, info}`, where `sig` = Ed25519(`blitzlink/server-identity/v1` ‖ nonce);
  - info updates arrive as `info{info}`;
  - liveness is `ping{n}` / `pong{n}`.

  Source: `native/blitzlink-tunnel/src/meta.rs`.
- `mc-N` (reliable, ordered; opened by the joiner for every local TCP connection). It carries the byte stream in 16 KB messages.
  - When more than 1 MB is buffered, the TCP reader pauses; it resumes below 256 KB.
  - When one side's TCP closes, the channel is closed after the backlog is sent. When the channel closes, the other side's TCP is closed.
