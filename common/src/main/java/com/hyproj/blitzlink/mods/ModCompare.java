package com.hyproj.blitzlink.mods;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the host's mod list (ServerInfo) with this client's. A red row (MISSING/VERSION) disables 「참가」 until the
 * player installs the right mod; a loader or protocol mismatch blocks the whole join.
 */
public final class ModCompare {
    public enum Status {
        /** Required and present with the same version. */
        OK,
        /** Required, not installed here. */
        MISSING,
        /** Required, installed with another version. */
        VERSION,
        /** Server-only or optional on the server — not needed here. */
        NOT_NEEDED,
        /** Both-sided mod with content that only this client has (the server may refuse it). */
        CLIENT_EXTRA
    }

    public enum Block {
        NONE,
        LOADER,
        PROTOCOL
    }

    public record Row(String id, String name, String serverVersion, String clientVersion, Status status) {
        public boolean bad() {
            return status == Status.MISSING || status == Status.VERSION;
        }
    }

    public record Result(Block block, List<Row> server, List<Row> clientOnly, List<Row> plugins) {
        public long badCount() {
            return server.stream().filter(Row::bad).count();
        }

        public boolean canJoin() {
            return block == Block.NONE && badCount() == 0;
        }
    }

    private ModCompare() {}

    /**
     * Whether a client can join at all. Vanilla and Bukkit-family servers (Spigot, Paper, forks) speak plain vanilla
     * protocol, so any client may join; a NeoForge or Fabric server needs a client on the same loader.
     */
    public static Block block(String serverLoader, int serverProtocol, String localLoader, int localProtocol) {
        if (serverProtocol != 0 && localProtocol != 0 && serverProtocol != localProtocol) return Block.PROTOCOL;
        if (serverLoader == null || serverLoader.isBlank() || "vanilla".equals(serverLoader) || "bukkit".equals(serverLoader)) return Block.NONE;
        return serverLoader.equals(localLoader) ? Block.NONE : Block.LOADER;
    }

    public static Result compare(ServerInfo host, String localLoader, int localProtocol, List<ModEntry> local) {
        Block block = block(host.loader(), host.protocol(), localLoader, localProtocol);
        List<Row> plugins = new ArrayList<>();
        Map<String, ModEntry> mine = new HashMap<>();
        for (ModEntry m : local) mine.put(m.id(), m);

        List<Row> server = new ArrayList<>();
        for (ModEntry m : host.mods()) {
            if (m.side() == ModEntry.Side.PLUGIN) {
                plugins.add(new Row(m.id(), m.name(), m.version(), null, Status.NOT_NEEDED));
                continue;
            }
            if (m.side() == ModEntry.Side.BUILTIN || m.side() == ModEntry.Side.CLIENT) continue;
            ModEntry here = mine.get(m.id());
            String hv = here == null ? null : here.version();
            Status st;
            if (m.side() == ModEntry.Side.REQUIRED) {
                if (here == null) st = Status.MISSING;
                else if (!m.version().equals(hv)) st = Status.VERSION;
                else st = Status.OK;
            } else if (here != null && m.version().equals(hv)) {
                st = Status.OK;
            } else {
                st = Status.NOT_NEEDED;
            }
            server.add(new Row(m.id(), m.name(), m.version(), hv, st));
        }
        // Red first, then OK, then not needed — so the player sees what to install without scrolling.
        server.sort(Comparator.comparingInt((Row r) -> switch (r.status()) {
            case MISSING, VERSION -> 0;
            case OK -> 1;
            default -> 2;
        }).thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));

        List<Row> clientOnly = new ArrayList<>();
        java.util.Set<String> hostIds = new java.util.HashSet<>();
        host.mods().forEach(m -> hostIds.add(m.id()));
        boolean moddedHost = "neoforge".equals(host.loader()) || "fabric".equals(host.loader());
        for (ModEntry m : moddedHost ? local : List.<ModEntry>of()) {
            if (hostIds.contains(m.id()) || m.side() != ModEntry.Side.REQUIRED) continue;
            clientOnly.add(new Row(m.id(), m.name(), null, m.version(), Status.CLIENT_EXTRA));
        }
        clientOnly.sort(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        plugins.sort(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        return new Result(block, server, clientOnly, plugins);
    }
}
