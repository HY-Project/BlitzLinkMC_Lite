# BlitzLink server guide (HY Project)

BlitzLink connects Minecraft servers with a single **join code `MCJE-XXXX`**. The hyproj.com relay (`blitzlink-relay`) only
handles one WebRTC offer/answer (SDP) pair and the public list; **game data flows directly between players and the server
over a WebRTC DataChannel** (see `docs/en/RELAY.md`).

```
[MC client] ─TCP→ 127.0.0.1:any ─ [tunnel join] ══ WebRTC (UDP, direct) ══ [tunnel host] ─TCP→ 127.0.0.1:game port ─ [server]
                                          └─ UDP blocked: the server's embedded TURN (TCP) ─┘
```

## What goes where

| Artifact | Used on |
|---|---|
| `blitzlink-lite-<version>+mc26.3.jar` | **One file for NeoForge and Fabric** — clients and dedicated servers (`mods/`; Fabric also needs Fabric API) |
| `blitzlink-lite-bukkit-26.3-*.jar` | Spigot · Paper · Purpur and other Bukkit-family servers (`plugins/`) |

BlitzLink Lite has no executable (sidecar): the tunnel runs inside the server's JVM and WebRTC comes from webrtc-java
(libwebrtc), which ships in the jar and is unpacked on first use into `config/blitzlink/natives/` (`plugins/BlitzLink/natives/`
for the plugin). Supported: Windows, Linux and macOS on x64 and arm64. Headless Linux servers (Docker images without X11/GLib)
work as they are: Lite stands in for the screen-capture libraries libwebrtc links against.

## Dedicated servers (NeoForge / Fabric)

1. Drop the jar in `mods/` and start the server. The console prints `BlitzLink: join code MCJE-…`; `/blitzlink code` shows it again.
2. Settings live in `config/blitzlink/config.json`:
   - `enabledOnDedicated` — register with the relay on start
   - `publicListing` — show in the public list (`/blitzlink public on|off`)
   - `upnp` — ask the router to forward the game port (TCP). Lite opens no UDP port: libwebrtc uses a new UDP port per connection, so hole punching and TURN cover UDP
   - `turn` — embedded TURN on the game's TCP port (told apart from Minecraft connections by the first byte)
   - `requiredClientMods` / `serverOnlyMods` — override which mods clients must have / which are server-only
3. The code is kept in `config/blitzlink/code.txt`, so it survives restarts. `/blitzlink regen` requests a new one.
4. Tunnelled players show up with their real IP in logs and bans (the tunnel reports each player's address).

## Bukkit-family servers (Spigot / Paper)

1. Drop `blitzlink-lite-bukkit` into `plugins/`. Settings: `plugins/BlitzLink/config.yml`.
2. Bukkit servers speak vanilla protocol, so **vanilla, NeoForge and Fabric clients can all join**. The plugin list appears on
   the client's server card under "Server plugins" (nothing to install).
3. The embedded TURN can't share the game port here, so it opens its own TCP port (`turn-port`, default game port + 1,
   `-1` turns it off). Forward it too if the server is behind NAT.
4. Real IPs: turn on `proxy-protocol: true` **together with** Paper's `proxies.proxy-protocol: true`
   (`config/paper-global.yml`). Turning on only one of them makes every connection fail.

## Client — "Open Server"

- Title screen → Multiplayer → BlitzLink → "Open Server":
  - **Pick a world to open** — an existing singleplayer world is opened and published through BlitzLink right away.
  - **Import Server ZIP** — drop a ZIP of a server folder (or a world) on the window, or put it in `<game dir>/blitzlink-import/`.
    The world with the shallowest `level.dat` becomes a new save; Bukkit-style `<world>_nether` / `<world>_the_end` are merged in.
    Mods in the ZIP's `mods/` that this client doesn't have are shown in red (an integrated server runs with the client's mods).
- In game, Esc → **Open BlitzLink Server** ("Manage BlitzLink Server" while hosting) — where "Open to LAN" used to be.

## Joining and the mod check

After a code is entered, the client connects straight to the server, receives its mod list (never through the relay) and
compares it with its own.

| Shown | Meaning |
|---|---|
| Red background + ✕ "Install" / "Version differs" | Install the mod or match its version to enable "Join" |
| ✓ | Present with the same version |
| Dim "Server only" | Not needed on the client |
| Whole server row red + ✕ | Can't join — other game version, or another loader (NeoForge ↔ Fabric) |

Server badges: game version + `VANILLA` / `NEOFORGE` / `FABRIC` / `BUKKIT` (covers Spigot, Paper and every compatible server).

## Custom relay

Official builds carry the hyproj.com relay. Builds from the repository's placeholder `data/net.json` (forks, local builds)
have none and say "No relay server is configured" until one is set (see `docs/en/RELAY.md`, "Network config").

- **Players:** BlitzLink → **Settings** — *Relay URL* (e.g. `https://relay.example.org/relay/mcje`) and *STUN servers*
  (`stun:host:port`, comma separated). **Test** checks `GET <relay>/health` → `ok`. Empty fields use the built-in network.
- **NeoForge / Fabric servers:** `relay` and `stun` in `config/blitzlink/config.json`.
- **Bukkit servers:** `relay:` and `stun:` in `plugins/BlitzLink/config.yml`.

Hosts and players must use the same relay to find each other; the public list comes from that relay too.

## Logs

All log output is English (the usual mod convention). UI text comes from `assets/blitzlink/lang/{ko_kr,en_us}.json`.
