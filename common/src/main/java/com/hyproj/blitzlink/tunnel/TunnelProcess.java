package com.hyproj.blitzlink.tunnel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.lite.LiteTunnel;

/**
 * One running tunnel (host or join). In native BlitzLink this is the {@code blitzlink-tunnel} sidecar process; in
 * BlitzLink Lite it is {@link LiteTunnel}, the same protocol running inside the game (no executable), so the rest of the
 * mod is shared unchanged: the same arguments in, the same JSON events out (docs/en/PROTOCOL.md).
 * Events are delivered on the tunnel's event thread — callers hop to the game thread themselves.
 */
public final class TunnelProcess implements AutoCloseable {
    private final LiteTunnel tunnel;

    private TunnelProcess(LiteTunnel tunnel) {
        this.tunnel = tunnel;
    }

    /** Starts {@code mode} ("host"/"join") with the sidecar's arguments; libwebrtc is unpacked under {@code workDir/natives}. */
    public static TunnelProcess start(String mode, List<String> args, Path workDir, Consumer<JsonObject> onEvent, Runnable onExit) throws IOException {
        String label = "tunnel-" + mode;
        BlitzLink.LOGGER.debug("BlitzLink Lite: starting {} {}", mode, String.join(" ", args));
        return new TunnelProcess(LiteTunnel.start(mode, args, workDir, onEvent, onExit, msg -> BlitzLink.LOGGER.info("[{}] {}", label, msg)));
    }

    public void send(String type, JsonObject body) {
        tunnel.send(type, body);
    }

    public boolean isAlive() {
        return tunnel.isAlive();
    }

    @Override
    public void close() {
        tunnel.close();
    }

    public static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    public static int num(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }
}
