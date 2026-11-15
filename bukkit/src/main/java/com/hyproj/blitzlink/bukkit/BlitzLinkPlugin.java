package com.hyproj.blitzlink.bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * BlitzLink on Spigot / Paper / forks: registers this server with the hyproj.com relay through the
 * {@code blitzlink-tunnel} sidecar, prints the MCJE- join code, and advertises the plugin list (side "plugin") over the
 * P2P meta channel the way the mod advertises its mod list. Bukkit-family servers speak vanilla protocol, so every
 * client — vanilla, NeoForge or Fabric — can join.
 */
public final class BlitzLinkPlugin extends JavaPlugin {
    private BukkitTunnel tunnel;
    private BukkitTask ticker;
    private volatile String code;
    private volatile String fingerprint;
    private volatile String state = "starting";
    private volatile String error;
    private String lastListing = "";
    private boolean isPublic;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("enabled", true)) {
            getLogger().info("Disabled in config.yml; not registering with the relay.");
            return;
        }
        isPublic = getConfig().getBoolean("public", false);
        startTunnel();
    }

    @Override
    public void onDisable() {
        stopTunnel();
    }

    private void startTunnel() {
        if (isPlaceholder(relayUrl())) {
            getLogger().warning("No relay configured: this build has the placeholder network config. "
                    + "Set `relay:` in plugins/BlitzLink/config.yml to your blitzlink-relay URL.");
            state = "notConfigured";
            return;
        }
        Path dir = getDataFolder().toPath();
        int port = Bukkit.getPort();
        List<String> args = new ArrayList<>(List.of(
                "--target", "127.0.0.1:" + port,
                "--port", Integer.toString(port),
                "--relay", relayUrl(),
                "--key-file", dir.resolve("host.key").toString(),
                "--code-file", dir.resolve("code.txt").toString()));
        for (String s : stun()) {
            args.add("--stun");
            args.add(s);
        }
        if (isPublic) args.add("--public");
        if (getConfig().getBoolean("upnp", true)) args.add("--upnp");
        // A Bukkit plugin can't see the game port's first byte, so the embedded TURN listens on its own TCP port.
        int turnPort = getConfig().getInt("turn-port", 0);
        if (turnPort < 0) {
            args.add("--no-turn");
        } else {
            args.add("--turn-listen");
            args.add("0.0.0.0:" + (turnPort == 0 ? port + 1 : turnPort));
        }
        if (getConfig().getBoolean("proxy-protocol", false)) args.add("--proxy-protocol");
        try {
            Files.createDirectories(dir);
            tunnel = BukkitTunnel.start(dir, args, getLogger(), this::onEvent, () -> {
                state = "failed";
                getLogger().warning("The tunnel exited.");
            });
        } catch (IOException e) {
            getLogger().severe("Could not start the tunnel: " + e.getMessage());
            return;
        }
        getLogger().info("Registering 127.0.0.1:" + port + " with the relay " + relayUrl());
        sendInfo(true);
        ticker = Bukkit.getScheduler().runTaskTimer(this, () -> sendInfo(false), 100L, 100L);
    }

    private void stopTunnel() {
        if (ticker != null) ticker.cancel();
        if (tunnel != null) tunnel.close();
        ticker = null;
        tunnel = null;
        code = null;
        lastListing = "";
    }

    private void onEvent(JsonObject e) {
        String t = e.has("t") ? e.get("t").getAsString() : "";
        switch (t) {
            case "hosted" -> {
                code = str(e, "code");
                fingerprint = str(e, "fingerprint");
                state = "online";
                error = null;
                getLogger().info("Join code " + code + " (identity " + fingerprint + ")");
            }
            case "status" -> {
                String relay = str(e, "state") != null ? str(e, "state") : str(e, "relay");
                if (relay != null) state = relay;
                String reason = str(e, "reason");
                if (reason != null) error = reason;
            }
            case "error" -> {
                error = str(e, "reason");
                getLogger().warning("Relay error: " + error);
            }
            default -> {
            }
        }
    }

    // ---- listing + P2P info ----

    private void sendInfo(boolean force) {
        if (tunnel == null) return;
        String motd = Bukkit.getMotd() == null ? "" : Bukkit.getMotd();
        String name = getConfig().getString("listing-name", "");
        if (name == null || name.isBlank()) name = motd.replaceAll("§.", "").lines().findFirst().orElse(Bukkit.getServer().getName());
        String mcVersion = Bukkit.getBukkitVersion().split("-")[0];
        int protocol = protocolVersion();
        String loaderVersion = Bukkit.getName() + " " + Bukkit.getVersion();
        if (loaderVersion.length() > 24) loaderVersion = loaderVersion.substring(0, 24);
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();

        JsonObject listing = new JsonObject();
        listing.addProperty("name", name);
        listing.addProperty("motd", motd);
        listing.addProperty("mcVersion", mcVersion);
        listing.addProperty("protocol", protocol);
        listing.addProperty("loader", "bukkit");
        listing.addProperty("loaderVersion", loaderVersion);
        listing.addProperty("modCount", plugins.length);
        listing.addProperty("modsHash", pluginsHash(plugins));
        listing.addProperty("players", Bukkit.getOnlinePlayers().size());
        listing.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        listing.addProperty("onlineMode", Bukkit.getOnlineMode());
        String key = listing.toString();
        if (!force && key.equals(lastListing)) return;
        lastListing = key;
        tunnel.send("listing", listing); // Listing fields sit next to "t" (docs/en/PROTOCOL.md)

        JsonObject info = listing.deepCopy();
        info.addProperty("v", 1);
        info.remove("modCount");
        info.remove("modsHash");
        JsonArray mods = new JsonArray();
        for (Plugin p : plugins) {
            JsonObject m = new JsonObject();
            m.addProperty("id", p.getName().toLowerCase(Locale.ROOT));
            m.addProperty("name", p.getName());
            m.addProperty("version", p.getDescription().getVersion());
            m.addProperty("side", "plugin");
            mods.add(m);
        }
        info.add("mods", mods);
        JsonObject meta = new JsonObject();
        meta.add("info", info);
        tunnel.send("meta", meta);
    }

    /** Paper exposes the protocol number; plain Spigot doesn't (0 = unknown, the client then lets the game check). */
    private static int protocolVersion() {
        try {
            Object unsafe = Bukkit.class.getMethod("getUnsafe").invoke(null);
            return (int) unsafe.getClass().getMethod("getProtocolVersion").invoke(unsafe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static String pluginsHash(Plugin[] plugins) {
        long h = 1125899906842597L;
        List<Plugin> sorted = new ArrayList<>(List.of(plugins));
        sorted.sort(Comparator.comparing(Plugin::getName));
        for (Plugin p : sorted) for (char c : (p.getName() + "@" + p.getDescription().getVersion() + ";").toCharArray()) h = 31 * h + c;
        return String.format(Locale.ROOT, "%016x", h);
    }

    // ---- config ----

    private String relayUrl() {
        String r = getConfig().getString("relay", "");
        if (r == null || r.isBlank()) r = net("relay", "https://blitzlink.example.com/relay/mcje");
        return r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
    }

    private List<String> stun() {
        List<String> s = getConfig().getStringList("stun");
        if (!s.isEmpty()) return s.stream().filter(x -> !isPlaceholder(x)).toList();
        try (InputStream in = getResource("blitzlink/net.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> out = new ArrayList<>();
                o.getAsJsonArray("stun").forEach(e -> out.add(e.getAsString()));
                return out.stream().filter(x -> !isPlaceholder(x)).toList();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return List.of();
    }

    /** Same rule as the sidecar (blitzlink_core::net::is_placeholder): example.com/.org/.net hosts or *.example. */
    static boolean isPlaceholder(String urlOrStun) {
        String t = urlOrStun.strip();
        int scheme = t.indexOf("://");
        if (scheme >= 0) t = t.substring(scheme + 3);
        else if (t.startsWith("stun:") || t.startsWith("turn:")) t = t.substring(5);
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        if (!t.endsWith("]")) {
            int colon = t.lastIndexOf(':');
            if (colon >= 0) t = t.substring(0, colon);
        }
        String host = t.toLowerCase(Locale.ROOT);
        for (String d : new String[] {"example.com", "example.org", "example.net"}) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return host.endsWith(".example") || host.isEmpty();
    }

    private String net(String key, String fallback) {
        try (InputStream in = getResource("blitzlink/net.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has(key)) return o.get(key).getAsString();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return fallback;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    // ---- /blitzlink ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "code" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "code" -> sender.sendMessage(code == null
                    ? "[BlitzLink] Registering with the relay (" + state + (error == null ? "" : ", " + error) + ")"
                    : "[BlitzLink] Join code: " + code + "  (identity " + fingerprint + ")");
            case "public" -> {
                if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) return false;
                isPublic = args[1].equalsIgnoreCase("on");
                JsonObject o = new JsonObject();
                o.addProperty("public", isPublic);
                if (tunnel != null) tunnel.send("public", o);
                sender.sendMessage(isPublic ? "[BlitzLink] Listed publicly" : "[BlitzLink] Removed from the public list");
            }
            case "regen" -> {
                try {
                    Files.deleteIfExists(getDataFolder().toPath().resolve("code.txt"));
                } catch (IOException ignored) {
                }
                stopTunnel();
                startTunnel();
                sender.sendMessage("[BlitzLink] Requesting a new join code");
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
