package com.hyproj.blitzlink.bukkit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.hyproj.blitzlink.lite.LiteTunnel;

/**
 * The BlitzLink host tunnel for Bukkit. Native BlitzLink starts its {@code blitzlink-tunnel host} sidecar here; Lite runs
 * the same protocol inside the server ({@link LiteTunnel}, libwebrtc via webrtc-java) with the same arguments and JSON
 * events (docs/en/PROTOCOL.md), so no executable is unpacked.
 */
final class BukkitTunnel implements AutoCloseable {
    private final LiteTunnel tunnel;

    private BukkitTunnel(LiteTunnel tunnel) {
        this.tunnel = tunnel;
    }

    static BukkitTunnel start(Path dataDir, List<String> args, Logger log, Consumer<JsonObject> onEvent, Runnable onExit) throws IOException {
        return new BukkitTunnel(LiteTunnel.start("host", args, dataDir, onEvent, onExit, msg -> log.info("[tunnel] " + msg)));
    }

    void send(String type, JsonObject body) {
        tunnel.send(type, body);
    }

    boolean isAlive() {
        return tunnel.isAlive();
    }

    @Override
    public void close() {
        tunnel.close();
    }
}
