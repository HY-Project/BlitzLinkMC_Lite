package com.hyproj.blitzlink.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.config.BlitzConfig;
import com.hyproj.blitzlink.mods.ServerInfo;
import com.hyproj.blitzlink.tunnel.TunnelProcess;

/**
 * One {@code blitzlink-tunnel join} session: relay exchange (SDP only) → WebRTC straight to the host → the host's
 * ServerInfo (mod list) over the P2P meta channel → a 127.0.0.1 port Minecraft connects to. The session stays up while
 * the player reviews the mod list and for the whole game session; it is closed when the player leaves.
 */
public final class RelayJoin implements AutoCloseable {
    public enum Phase { RELAY, CONNECTING, TURN, OPEN, FAILED, CLOSED }

    private static RelayJoin active;

    public final String code;
    private final TunnelProcess tunnel;
    private volatile Phase phase = Phase.RELAY;
    private volatile String error;
    private volatile ServerInfo info;
    private volatile String fingerprint;
    private volatile int listenPort;

    private RelayJoin(String code) throws IOException {
        this.code = code;
        BlitzConfig cfg = BlitzConfig.get();
        List<String> args = new ArrayList<>(List.of("--code", code, "--relay", cfg.relayUrl()));
        for (String s : cfg.stunServers()) {
            args.add("--stun");
            args.add(s);
        }
        if (Boolean.getBoolean("blitzlink.dev") && Boolean.getBoolean("blitzlink.turnOnly")) args.add("--turn-only");
        this.tunnel = TunnelProcess.start("join", args, BlitzConfig.dir(), this::onEvent, this::onExit);
    }

    /** Starts a new join (closing any previous one). Never null: a sidecar that cannot start yields a FAILED session. */
    public static synchronized RelayJoin start(String code) {
        closeActive();
        if (!BlitzConfig.get().relayConfigured()) {
            active = failed(code, "notConfigured");
            return active;
        }
        try {
            active = new RelayJoin(code);
        } catch (IOException e) {
            BlitzLink.LOGGER.error("BlitzLink: could not start the tunnel: {}", e.getMessage());
            active = failed(code, e.getMessage());
        }
        return active;
    }

    public static synchronized RelayJoin active() {
        return active;
    }

    public static synchronized void closeActive() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    private static RelayJoin failed(String code, String reason) {
        RelayJoin j = new RelayJoin(code, reason);
        return j;
    }

    /** A session that never started (no sidecar for this platform, …). */
    private RelayJoin(String code, String reason) {
        this.code = code;
        this.tunnel = null;
        this.phase = Phase.FAILED;
        this.error = reason == null || reason.isBlank() ? "sidecarFailed" : reason;
    }

    private void onEvent(JsonObject e) {
        switch (TunnelProcess.str(e, "t", "")) {
            case "status" -> {
                String via = TunnelProcess.str(e, "via", "udp");
                // `state` (relay|connecting|turn|open) from the sidecar; `phase` (signaling|connecting+via|open) per the doc.
                switch (TunnelProcess.str(e, "state", TunnelProcess.str(e, "phase", ""))) {
                    case "relay", "signaling" -> phase = Phase.RELAY;
                    case "connecting" -> phase = "turn".equals(via) ? Phase.TURN : Phase.CONNECTING;
                    case "turn" -> phase = Phase.TURN;
                    case "open" -> phase = Phase.OPEN;
                    default -> {
                    }
                }
            }
            case "meta" -> {
                if (e.has("info") && e.get("info").isJsonObject()) info = ServerInfo.fromJson(e.getAsJsonObject("info"));
                fingerprint = TunnelProcess.str(e, "fingerprint", fingerprint);
                phase = Phase.OPEN;
            }
            case "listen" -> listenPort = TunnelProcess.num(e, "port", 0);
            case "error" -> {
                error = TunnelProcess.str(e, "reason", "unknown");
                phase = Phase.FAILED;
            }
            default -> BlitzLink.LOGGER.debug("BlitzLink join event {}", e);
        }
    }

    private void onExit() {
        if (phase != Phase.FAILED && phase != Phase.CLOSED) {
            error = error == null ? "sidecarExited" : error;
            phase = Phase.FAILED;
        }
    }

    public Phase phase() {
        return phase;
    }

    public String error() {
        return error;
    }

    public ServerInfo info() {
        return info;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public int listenPort() {
        return listenPort;
    }

    /** Ready for the mod check: P2P open and the host's ServerInfo arrived. */
    public boolean ready() {
        return info != null && phase == Phase.OPEN;
    }

    @Override
    public void close() {
        if (tunnel != null) tunnel.close();
        if (phase != Phase.FAILED) phase = Phase.CLOSED;
    }
}
