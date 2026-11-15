package com.hyproj.blitzlink.lite;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Small shared helpers: hex, randomness, join codes and address formatting. Mirrors {@code blitzlink_core::relay}
 * (codes) and the Rust {@code SocketAddr} text form, so Lite and native agree byte for byte.
 */
public final class Wire {
    /** Relay protocol version ({@code RELAY_PROTOCOL}). */
    public static final int RELAY_PROTOCOL = 1;
    public static final int MAX_MESSAGE = 16 * 1024;
    public static final String CODE_PREFIX = "MCJE";
    /** 30 letters without 0·1·O·I·L·U. */
    public static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int CODE_LEN = 4;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private Wire() {}

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static String hex(byte[] b) {
        return HEX.formatHex(b);
    }

    /** Lower- or upper-case hex → bytes, or null. */
    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        try {
            return HEX.parseHex(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code normalize_code}: case, spaces and hyphens ignored, prefix optional; null when it is not a code. */
    public static String normalizeCode(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        input.codePoints().filter(c -> !Character.isWhitespace(c) && c != '-').forEach(sb::appendCodePoint);
        String s = sb.toString().toUpperCase(Locale.ROOT);
        String body = s.startsWith(CODE_PREFIX) ? s.substring(CODE_PREFIX.length()) : s;
        if (body.length() != CODE_LEN) return null;
        for (int i = 0; i < body.length(); i++) {
            if (CODE_ALPHABET.indexOf(body.charAt(i)) < 0) return null;
        }
        return CODE_PREFIX + "-" + body;
    }

    /** Rust's {@code SocketAddr} display: {@code 1.2.3.4:5} or {@code [v6]:5}. */
    public static String addr(InetSocketAddress a) {
        if (a == null) return "unknown";
        InetAddress ip = a.getAddress();
        String host = ip == null ? a.getHostString() : ip.getHostAddress();
        int pct = host.indexOf('%');
        if (pct >= 0) host = host.substring(0, pct);
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + a.getPort();
    }

    /** {@code ip:port} / {@code [v6]:port} with a literal IP (no DNS) → address, or null. */
    public static InetSocketAddress parseAddr(String s) {
        if (s == null) return null;
        s = s.trim();
        int colon = s.lastIndexOf(':');
        if (colon <= 0) return null;
        String host = s.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        int port;
        try {
            port = Integer.parseInt(s.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 0 || port > 65535 || !isIpLiteral(host)) return null;
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    static boolean isIpLiteral(String host) {
        if (host.isEmpty()) return false;
        if (host.contains(":")) return host.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.');
        String[] p = host.split("\\.", -1);
        if (p.length != 4) return false;
        for (String x : p) {
            if (x.isEmpty() || x.length() > 3 || !x.chars().allMatch(Character::isDigit) || Integer.parseInt(x) > 255) return false;
        }
        return true;
    }
}
