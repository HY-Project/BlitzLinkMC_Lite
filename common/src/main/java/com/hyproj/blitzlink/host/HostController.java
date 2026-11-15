package com.hyproj.blitzlink.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.config.BlitzConfig;
import com.hyproj.blitzlink.mods.ModEntry;
import com.hyproj.blitzlink.mods.ServerInfo;
import com.hyproj.blitzlink.net.PeerAddresses;
import com.hyproj.blitzlink.net.TurnMux;
import com.hyproj.blitzlink.tunnel.TunnelProcess;
import net.minecraft.server.MinecraftServer;

/**
 * Runs {@code blitzlink-tunnel host} next to a Minecraft server — the dedicated server (on start, when enabled in config)
 * or the integrated one (「서버 열기」). The sidecar registers with the relay, answers offers and pipes each WebRTC
 * {@code mc-N} channel to {@code 127.0.0.1:<port>}.
 */
public final class HostController {
    public enum State { OFF, STARTING, ONLINE, RECONNECTING, FAILED }

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blitzlink-host-timer");
        t.setDaemon(true);
        return t;
    });

    private static volatile HostController current;

    private final MinecraftServer server;
    private final int port;
    private final TunnelProcess tunnel;
    private final ScheduledFuture<?> ticker;
    private volatile State state = State.STARTING;
    private volatile String code;
    private volatile String fingerprint;
    private volatile String error;
    private volatile String publicAddress;
    private volatile String upnp = "off";
    private volatile boolean isPublic;
    private String lastListing = "";

    private HostController(MinecraftServer server, int port, boolean isPublic) throws IOException {
        this.server = server;
        this.port = port;
        this.isPublic = isPublic;
        BlitzConfig cfg = BlitzConfig.get();
        Path dir = BlitzConfig.dir();
        Files.createDirectories(dir);
        List<String> args = new ArrayList<>(List.of(
                "--target", "127.0.0.1:" + port,
                "--port", Integer.toString(port),
                "--relay", cfg.relayUrl(),
                "--key-file", dir.resolve("host.key").toString(),
                "--code-file", dir.resolve("code.txt").toString()));
        for (String s : cfg.stunServers()) {
            args.add("--stun");
            args.add(s);
        }
        if (isPublic) args.add("--public");
        if (cfg.upnp) args.add("--upnp");
        if (!cfg.turn) args.add("--no-turn");
        this.tunnel = TunnelProcess.start("host", args, dir, this::onEvent, this::onExit);
        TurnMux.setPeerReporter((localPort, addr) -> {
            JsonObject o = new JsonObject();
            o.addProperty("port", localPort);
            o.addProperty("addr", addr);
            tunnel.send("turnPeer", o);
        });
        sendInfo(true);
        this.ticker = TIMER.scheduleAtFixedRate(() -> sendInfo(false), 5, 5, TimeUnit.SECONDS);
    }

    // ---- lifecycle ----

    /** Dedicated servers: register with the relay right after start when {@code enabledOnDedicated}. */
    public static void onServerStarted(MinecraftServer server) {
        if (!server.isDedicatedServer() || !BlitzConfig.get().enabledOnDedicated) return;
        start(server, server.getPort(), BlitzConfig.get().publicListing);
    }

    public static void onServerStopping(MinecraftServer server) {
        HostController c = current;
        if (c != null && c.server == server) c.stop();
    }

    /** Starts hosting (idempotent per server). Returns the controller or null when the sidecar could not start. */
    public static synchronized HostController start(MinecraftServer server, int port, boolean isPublic) {
        if (current != null && current.server == server && current.tunnel.isAlive()) return current;
        if (!BlitzConfig.get().relayConfigured()) {
            BlitzLink.LOGGER.warn("BlitzLink: no relay configured (this build has the placeholder network config); "
                    + "set \"relay\" in config/blitzlink/config.json or the mod's advanced settings");
            return null;
        }
        if (current != null) current.stop();
        try {
            current = new HostController(server, port, isPublic);
            BlitzLink.LOGGER.info("BlitzLink: registering {}:{} with the relay {}", "127.0.0.1", port, BlitzConfig.get().relayUrl());
            return current;
        } catch (IOException e) {
            BlitzLink.LOGGER.error("BlitzLink: could not start the tunnel: {}", e.getMessage());
            return null;
        }
    }

    public static HostController current() {
        return current;
    }

    public synchronized void stop() {
        ticker.cancel(false);
        tunnel.close();
        TurnMux.setTurnPort(0);
        TurnMux.setPeerReporter(null);
        PeerAddresses.clear();
        state = State.OFF;
        if (current == this) current = null;
    }

    public void setPublic(boolean value) {
        isPublic = value;
        JsonObject o = new JsonObject();
        o.addProperty("public", value);
        tunnel.send("public", o);
    }

    // ---- what the sidecar tells us ----

    private void onEvent(JsonObject e) {
        switch (TunnelProcess.str(e, "t", "")) {
            case "hosted" -> {
                code = TunnelProcess.str(e, "code", null);
                fingerprint = TunnelProcess.str(e, "fingerprint", null);
                state = State.ONLINE;
                error = null;
                BlitzLink.LOGGER.info("BlitzLink: join code {} (identity {})", code, fingerprint);
            }
            case "status" -> {
                // The sidecar reports `state` (online|reconnecting); docs/en/PROTOCOL.md also names a `relay` field
                // (connecting|online|retrying|failed). Accept both.
                switch (TunnelProcess.str(e, "state", TunnelProcess.str(e, "relay", ""))) {
                    case "online" -> state = State.ONLINE;
                    case "retrying", "reconnecting" -> state = State.RECONNECTING;
                    case "failed" -> state = State.FAILED;
                    default -> {
                    }
                }
                if (e.has("code") && !e.get("code").isJsonNull()) code = TunnelProcess.str(e, "code", code);
                if (e.has("reason") && !e.get("reason").isJsonNull()) error = TunnelProcess.str(e, "reason", error);
                publicAddress = TunnelProcess.str(e, "publicAddr", null);
                upnp = TunnelProcess.str(e, "upnp", "off");
                if (e.has("public")) isPublic = e.get("public").getAsBoolean();
            }
            case "turnPort" -> TurnMux.setTurnPort(TunnelProcess.num(e, "port", 0));
            case "peerClosed" -> PeerAddresses.remove(TunnelProcess.num(e, "localPort", 0));
            case "peer" -> PeerAddresses.put(TunnelProcess.num(e, "localPort", 0), TunnelProcess.str(e, "addr", null));
            case "error" -> {
                error = TunnelProcess.str(e, "reason", "unknown");
                BlitzLink.LOGGER.warn("BlitzLink: relay error {}", error);
                if ("relay.badSignature".equals(error) || "relay.version".equals(error)) state = State.FAILED;
            }
            default -> BlitzLink.LOGGER.debug("BlitzLink host event {}", e);
        }
    }

    private void onExit() {
        state = State.FAILED;
        if (error == null) error = "sidecarExited";
        BlitzLink.LOGGER.warn("BlitzLink: the tunnel exited");
    }

    /** Sends the relay listing (when it changed) and the P2P ServerInfo (mod list) to the sidecar. */
    private void sendInfo(boolean force) {
        try {
            ServerInfo info = serverInfo();
            JsonObject listing = new JsonObject();
            listing.addProperty("name", info.name());
            listing.addProperty("motd", info.motd());
            listing.addProperty("mcVersion", info.mcVersion());
            listing.addProperty("protocol", info.protocol());
            listing.addProperty("loader", info.loader());
            listing.addProperty("loaderVersion", info.loaderVersion());
            listing.addProperty("modCount", (int) info.mods().stream().filter(m -> m.side() != ModEntry.Side.BUILTIN).count());
            listing.addProperty("modsHash", info.modsHash());
            listing.addProperty("players", info.players());
            listing.addProperty("maxPlayers", info.maxPlayers());
            listing.addProperty("onlineMode", info.onlineMode());
            String key = listing.toString();
            if (!force && key.equals(lastListing)) return;
            lastListing = key;
            tunnel.send("listing", listing); // Listing fields sit next to "t" (docs/en/PROTOCOL.md)
            // The P2P info carries player counts too, so it is refreshed together with the listing.
            JsonObject m = new JsonObject();
            m.add("info", info.toJson());
            tunnel.send("meta", m);
        } catch (RuntimeException e) {
            BlitzLink.LOGGER.debug("BlitzLink: listing update failed: {}", e.toString());
        }
    }

    private ServerInfo serverInfo() {
        String motd = server.getMotd() == null ? "" : server.getMotd();
        String name = BlitzConfig.get().listingName;
        if (name == null || name.isBlank()) {
            name = server.isDedicatedServer() ? motd.lines().findFirst().orElse("Minecraft Server") : server.getWorldData().getLevelName();
        }
        return ServerInfo.local(name, motd, server.getPlayerCount(), server.getMaxPlayers(), server.usesAuthentication());
    }

    // ---- read by UI / commands ----

    public State state() {
        return state;
    }

    public String code() {
        return code;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String error() {
        return error;
    }

    public String publicAddress() {
        return publicAddress;
    }

    /** "off", "searching", "ok <externalIP>" or "failed". */
    public String upnp() {
        return upnp;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public int port() {
        return port;
    }

    public MinecraftServer server() {
        return server;
    }

    /** Asks the relay for a fresh code (deletes the remembered one and restarts the sidecar). */
    public static void regenerate() {
        HostController c = current;
        if (c == null) return;
        try {
            Files.deleteIfExists(BlitzConfig.dir().resolve("code.txt"));
        } catch (IOException ignored) {
        }
        MinecraftServer s = c.server;
        int p = c.port;
        boolean pub = c.isPublic;
        c.stop();
        start(s, p, pub);
    }
}
