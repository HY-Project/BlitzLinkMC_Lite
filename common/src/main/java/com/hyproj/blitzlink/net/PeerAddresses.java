package com.hyproj.blitzlink.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tunnelled players reach the server from 127.0.0.1 (the host sidecar dials the game port). The sidecar reports
 * {@code peer{localPort, addr}} for each such socket so logs, bans and {@code /ban-ip} see the player's real address.
 */
public final class PeerAddresses {
    private static final Map<Integer, InetSocketAddress> BY_LOCAL_PORT = new ConcurrentHashMap<>();

    private PeerAddresses() {}

    public static void put(int localPort, String addr) {
        if (localPort <= 0 || addr == null) return;
        InetSocketAddress a = parse(addr);
        if (a != null) BY_LOCAL_PORT.put(localPort, a);
    }

    public static void remove(int localPort) {
        BY_LOCAL_PORT.remove(localPort);
    }

    public static void clear() {
        BY_LOCAL_PORT.clear();
    }

    /** The real address for a loopback connection made by the sidecar, or the address unchanged. */
    public static SocketAddress resolve(SocketAddress seen) {
        if (seen instanceof InetSocketAddress in && in.getAddress() != null && in.getAddress().isLoopbackAddress()) {
            InetSocketAddress real = BY_LOCAL_PORT.get(in.getPort());
            if (real != null) return real;
        }
        return seen;
    }

    /** A loopback connection while BlitzLink is hosting — it may be one the sidecar made. */
    public static boolean isTunnelCandidate(SocketAddress seen) {
        return com.hyproj.blitzlink.host.HostController.current() != null
                && seen instanceof InetSocketAddress in && in.getAddress() != null && in.getAddress().isLoopbackAddress();
    }

    /**
     * Like {@link #resolve}, but while hosting waits up to 300 ms for the sidecar's {@code peer} line, which is read on
     * another thread and can trail the TCP accept (docs/en/PROTOCOL.md).
     */
    public static SocketAddress resolveWaiting(SocketAddress seen) {
        SocketAddress real = resolve(seen);
        if (real != seen || !isTunnelCandidate(seen)) return real;
        long end = System.nanoTime() + 300_000_000L;
        while (System.nanoTime() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            real = resolve(seen);
            if (real != seen) return real;
        }
        return seen;
    }

    /** {@code 1.2.3.4:5678} or {@code [::1]:5678}; literal IPs only (no DNS lookups). */
    static InetSocketAddress parse(String s) {
        try {
            int colon = s.lastIndexOf(':');
            if (colon <= 0) return null;
            String host = s.substring(0, colon);
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            if (!host.matches("[0-9a-fA-F:.]+")) return null;
            int port = Integer.parseInt(s.substring(colon + 1));
            return new InetSocketAddress(InetAddress.ofLiteral(host), port);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
