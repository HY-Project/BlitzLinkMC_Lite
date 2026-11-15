package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Network addresses — {@code blitzlink_core::net} in Java. Defaults come from {@code blitzlink/net.json} in the jar (the
 * same {@code data/net.json} native BlitzLink embeds; CI swaps the repository placeholder for the official one).
 */
public final class NetConfig {
    public record Defaults(String relay, List<String> stun) {}

    private NetConfig() {}

    public static Defaults defaults() {
        try (InputStream in = NetConfig.class.getResourceAsStream("/blitzlink/net.json")) {
            if (in == null) return new Defaults("https://blitzlink.example.com/relay/mcje", List.of());
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> stun = new ArrayList<>();
            if (o.has("stun") && o.get("stun").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("stun")) stun.add(e.getAsString());
            }
            return new Defaults(o.get("relay").getAsString(), stun);
        } catch (IOException | RuntimeException e) {
            return new Defaults("https://blitzlink.example.com/relay/mcje", List.of());
        }
    }

    /** {@code is_placeholder}: hosts under example.com/.org/.net or *.example. */
    public static boolean isPlaceholder(String url) {
        String host = url;
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        if (host.startsWith("stun:")) host = host.substring(5);
        else if (host.startsWith("turn:")) host = host.substring(5);
        int cut = indexOfAny(host, "/?");
        if (cut >= 0) host = host.substring(0, cut);
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && !host.endsWith("]")) host = host.substring(0, colon);
        host = host.toLowerCase(Locale.ROOT);
        for (String d : new String[] {"example.com", "example.org", "example.net"}) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return host.endsWith(".example");
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    /** {@code ws_url}: relay base URL → its WebSocket endpoint ({@code …/ws}). */
    public static String wsUrl(String base) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.startsWith("https://")) b = "wss://" + b.substring(8);
        else if (b.startsWith("http://")) b = "ws://" + b.substring(7);
        else if (!b.startsWith("ws://") && !b.startsWith("wss://")) b = "wss://" + b;
        return b.endsWith("/ws") ? b : b + "/ws";
    }

    /** {@code stun:host:port} · {@code host:port} · {@code host} → {@code host:port} (default 3478); off/empty → null. */
    public static String stunHost(String url) {
        String s = url == null ? "" : url.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("off") || s.equalsIgnoreCase("none")) return null;
        if (s.startsWith("stun:")) s = s.substring(5);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        return withPort(s, 3478);
    }

    /** {@code turn:host:port?transport=tcp} → {@code host:port}; non-TCP → null (this TURN is TCP only). */
    public static String turnHost(String url) {
        String s = url == null ? "" : url.trim();
        if (!s.startsWith("turn:")) return null;
        s = s.substring(5);
        String addr = s;
        String query = "";
        int q = s.indexOf('?');
        if (q >= 0) {
            addr = s.substring(0, q);
            query = s.substring(q + 1);
        }
        if (!query.isEmpty()) {
            boolean tcp = false;
            for (String kv : query.split("&")) tcp |= kv.equalsIgnoreCase("transport=tcp");
            if (!tcp) return null;
        }
        return addr.isEmpty() ? null : withPort(addr, 3478);
    }

    static String withPort(String s, int port) {
        if (s.startsWith("[")) return s.contains("]:") ? s : s + ":" + port;
        long colons = s.chars().filter(c -> c == ':').count();
        if (colons == 1) return s;
        if (colons > 1) return "[" + s + "]:" + port;
        return s + ":" + port;
    }

    /** {@code host:port} / {@code [v6]:port} → (host, port). */
    public static String[] splitHostPort(String hp) {
        int colon = hp.lastIndexOf(':');
        String host = hp.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return new String[] {host, hp.substring(colon + 1)};
    }
}
