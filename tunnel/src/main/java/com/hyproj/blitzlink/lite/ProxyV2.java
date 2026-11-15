package com.hyproj.blitzlink.lite;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * HAProxy PROXY protocol v2 header ({@code --proxy-protocol}, Bukkit hosts with Paper's {@code proxies.proxy-protocol}),
 * sent before any byte when dialing the target so the server logs and bans the player's real address.
 * Same bytes as native {@code proxy.rs}.
 */
final class ProxyV2 {
    private static final byte[] SIGNATURE = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    private ProxyV2() {}

    /** {@code src} (the player) → {@code dst} (the server we dial); LOCAL when the source is unknown. */
    static byte[] header(InetSocketAddress src, InetSocketAddress dst) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        b.writeBytes(SIGNATURE);
        if (src == null || src.getAddress() == null) {
            b.writeBytes(new byte[] {0x20, 0x00, 0x00, 0x00});
            return b.toByteArray();
        }
        InetAddress s = src.getAddress();
        InetAddress d = dst.getAddress();
        if (s instanceof Inet4Address && d instanceof Inet4Address) {
            b.writeBytes(new byte[] {0x21, 0x11, 0x00, 12});
            b.writeBytes(s.getAddress());
            b.writeBytes(d.getAddress());
        } else {
            b.writeBytes(new byte[] {0x21, 0x21, 0x00, 36});
            b.writeBytes(v6(s));
            b.writeBytes(v6(d));
        }
        b.write(src.getPort() >> 8);
        b.write(src.getPort());
        b.write(dst.getPort() >> 8);
        b.write(dst.getPort());
        return b.toByteArray();
    }

    private static byte[] v6(InetAddress a) {
        if (a instanceof Inet6Address) return a.getAddress();
        byte[] v4 = a.getAddress();
        byte[] m = new byte[16];
        m[10] = (byte) 0xFF;
        m[11] = (byte) 0xFF;
        System.arraycopy(v4, 0, m, 12, 4);
        return m;
    }

}
