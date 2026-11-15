package com.hyproj.blitzlink.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.platform.Platform;

/** {@code config/blitzlink/config.json}. Defaults for relay/stun come from the bundled {@code blitzlink/net.json} (data/net.json). */
public final class BlitzConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static BlitzConfig instance;

    /** Custom signalling relay base URL (advanced settings). Empty = the one built into this jar (data/net.json). */
    public String relay = "";
    /** Custom STUN servers ({@code stun:host:port}). Empty = data/net.json. */
    public List<String> stun = new ArrayList<>();
    /** Dedicated servers register with the relay on start (prints a MCJE- code in the console). */
    public boolean enabledOnDedicated = true;
    /** List the server in the relay's public list. */
    public boolean publicListing = false;
    /** Ask the router (UPnP) to forward the game port (TCP+UDP). */
    public boolean upnp = true;
    /** Host-embedded TURN on the game's TCP port for clients on UDP-blocked networks. */
    public boolean turn = true;
    /** Mods a joining client must have even when BlitzLink would call them optional. */
    public List<String> requiredClientMods = new ArrayList<>();
    /** Mods that only run on the server (never asked of clients). */
    public List<String> serverOnlyMods = new ArrayList<>();
    /** Name shown in the public list (empty = server MOTD's first line / world name). */
    public String listingName = "";

    public static BlitzConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static Path dir() {
        return Platform.get().configDir().resolve("blitzlink");
    }

    private static BlitzConfig load() {
        Path file = dir().resolve("config.json");
        BlitzConfig cfg = new BlitzConfig();
        try {
            if (Files.exists(file)) {
                BlitzConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BlitzConfig.class);
                if (read != null) cfg = read;
            }
        } catch (Exception e) {
            BlitzLink.LOGGER.warn("BlitzLink: {} is broken, using defaults ({})", file, e.toString());
        }
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("config.json"), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BlitzLink.LOGGER.warn("BlitzLink: could not write config ({})", e.toString());
        }
    }

    /** Relay base URL: {@code -Dblitzlink.relay} (dev builds only) → custom (advanced settings) → net.json. */
    public String relayUrl() {
        String dev = devOverride("blitzlink.relay");
        if (dev != null) return stripSlash(dev);
        if (hasCustomRelay()) return stripSlash(relay.strip());
        return stripSlash(Net.DEFAULTS.relay);
    }

    public boolean hasCustomRelay() {
        return relay != null && !relay.isBlank();
    }

    /**
     * False when no usable relay is set: the jar was built from the repository's placeholder data/net.json (forks, local
     * builds) and the player has not set a custom relay. The sidecar then refuses to start ({@code notConfigured}).
     */
    public boolean relayConfigured() {
        String r = relayUrl();
        return !r.isBlank() && !isPlaceholder(r);
    }

    /** STUN servers to use; placeholder entries are dropped (never ask a host that doesn't exist). */
    public List<String> stunServers() {
        String dev = devOverride("blitzlink.stun");
        List<String> list;
        if (dev != null) list = List.of(dev.split(","));
        else if (stun != null && !stun.isEmpty()) list = stun;
        else list = Net.DEFAULTS.stun;
        return list.stream().map(String::strip).filter(s -> !s.isEmpty() && !isPlaceholder(s)).toList();
    }

    /** The relay built into this jar (data/net.json), or null when it is the placeholder. Shown in settings. */
    public static String builtInRelay() {
        return isPlaceholder(Net.DEFAULTS.relay) ? null : stripSlash(Net.DEFAULTS.relay);
    }

    public static List<String> builtInStun() {
        return Net.DEFAULTS.stun.stream().filter(s -> !isPlaceholder(s)).toList();
    }

    /**
     * The placeholder rule shared with the sidecar ({@code blitzlink_core::net::is_placeholder}): the host is
     * {@code example.com/.org/.net} or a subdomain of one, or ends in {@code .example}.
     */
    public static boolean isPlaceholder(String urlOrStun) {
        String host = hostOf(urlOrStun).toLowerCase(java.util.Locale.ROOT);
        if (host.isEmpty()) return true;
        for (String d : new String[] {"example.com", "example.org", "example.net"}) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return host.equals("example") || host.endsWith(".example");
    }

    private static String hostOf(String s) {
        String t = s.strip();
        int scheme = t.indexOf("://");
        if (scheme >= 0) t = t.substring(scheme + 3);
        else if (t.startsWith("stun:") || t.startsWith("turn:")) t = t.substring(5);
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        int at = t.lastIndexOf('@');
        if (at >= 0) t = t.substring(at + 1);
        if (t.startsWith("[")) {
            int end = t.indexOf(']');
            return end > 0 ? t.substring(1, end) : t;
        }
        int colon = t.lastIndexOf(':');
        return colon >= 0 ? t.substring(0, colon) : t;
    }

    /** Listened to only in development runs, never in a shipped jar. */
    private static String devOverride(String key) {
        if (!Boolean.getBoolean("blitzlink.dev")) return null;
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The network built into this jar (data/net.json — the sidecar embeds the same file). The repository holds a
     * placeholder; official builds replace it in CI from the BLITZLINK_NET_JSON variable. Extra fields (`_comment`) are
     * ignored.
     */
    public static final class Net {
        // Without a bundled net.json the build has no network of its own (same as the placeholder).
        public String relay = "https://blitzlink.example.com/relay/mcje";
        public List<String> stun = List.of();

        static final Net DEFAULTS = read();

        private static Net read() {
            try (InputStream in = BlitzConfig.class.getResourceAsStream("/blitzlink/net.json")) {
                if (in != null) {
                    JsonObject o = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    Net n = new Net();
                    if (o.has("relay")) n.relay = o.get("relay").getAsString();
                    if (o.has("stun")) {
                        List<String> s = new ArrayList<>();
                        o.getAsJsonArray("stun").forEach(e -> s.add(e.getAsString()));
                        n.stun = List.copyOf(s);
                    }
                    return n;
                }
            } catch (Exception e) {
                BlitzLink.LOGGER.warn("BlitzLink: bundled net.json unreadable ({})", e.toString());
            }
            return new Net();
        }
    }
}
