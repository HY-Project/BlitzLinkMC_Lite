package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * STUN (RFC 5389) — {@code blitzlink_core::stun}: Binding request/response, XOR addresses and FINGERPRINT, plus the
 * public-address query the host uses for its status line and its TURN URLs. (ICE itself is libwebrtc's.)
 */
public final class Stun {
    public static final int MAGIC = 0x2112A442;
    public static final int XOR_MAPPED_ADDRESS = 0x0020;
    static final int MAPPED_ADDRESS = 0x0001;
    public static final int FINGERPRINT = 0x8028;
    public static final int FINGERPRINT_XOR = 0x5354554E;
    static final int BINDING_REQUEST = 0x0001;
    static final int BINDING_SUCCESS = 0x0101;

    private Stun() {}

    public static byte[] bindingRequest(byte[] tx) {
        ByteBuffer b = ByteBuffer.allocate(20);
        b.putShort((short) BINDING_REQUEST).putShort((short) 0).putInt(MAGIC).put(tx);
        return b.array();
    }

    /** The mapped address from a Binding success with transaction id {@code want}, or null. */
    public static InetSocketAddress parseBindingResponse(byte[] buf, int len, byte[] want) {
        if (len < 20 || (buf[0] & 0xC0) != 0) return null;
        ByteBuffer b = ByteBuffer.wrap(buf, 0, len);
        int ty = b.getShort() & 0xFFFF;
        int alen = b.getShort() & 0xFFFF;
        if (b.getInt() != MAGIC || alen % 4 != 0 || 20 + alen != len || ty != BINDING_SUCCESS) return null;
        byte[] tx = Arrays.copyOfRange(buf, 8, 20);
        if (!Arrays.equals(tx, want)) return null;
        InetSocketAddress plain = null;
        int at = 20;
        while (at + 4 <= 20 + alen) {
            int aty = ((buf[at] & 0xFF) << 8) | (buf[at + 1] & 0xFF);
            int l = ((buf[at + 2] & 0xFF) << 8) | (buf[at + 3] & 0xFF);
            if (at + 4 + l > len) return null;
            byte[] v = Arrays.copyOfRange(buf, at + 4, at + 4 + l);
            if (aty == XOR_MAPPED_ADDRESS) return readAddress(v, tx, true);
            if (aty == MAPPED_ADDRESS) plain = readAddress(v, tx, false);
            at += 4 + (l + 3) / 4 * 4;
        }
        return plain;
    }

    public static byte[] xorAddress(InetSocketAddress addr, byte[] tx) {
        byte[] ip = addr.getAddress().getAddress();
        int xport = addr.getPort() ^ (MAGIC >>> 16);
        ByteBuffer b = ByteBuffer.allocate(4 + ip.length);
        b.put((byte) 0).put((byte) (ip.length == 4 ? 1 : 2)).putShort((short) xport);
        byte[] key = v6Key(tx);
        for (int i = 0; i < ip.length; i++) b.put((byte) (ip[i] ^ key[i]));
        return b.array();
    }

    private static byte[] v6Key(byte[] tx) {
        byte[] key = new byte[16];
        ByteBuffer.wrap(key).putInt(MAGIC).put(tx);
        return key;
    }

    public static InetSocketAddress readAddress(byte[] v, byte[] tx, boolean xor) {
        if (v.length < 8) return null;
        int port = (((v[2] & 0xFF) << 8) | (v[3] & 0xFF)) ^ (xor ? MAGIC >>> 16 : 0);
        byte[] ip;
        if (v[1] == 1 && v.length == 8) ip = Arrays.copyOfRange(v, 4, 8);
        else if (v[1] == 2 && v.length == 20) ip = Arrays.copyOfRange(v, 4, 20);
        else return null;
        if (xor) {
            byte[] key = v6Key(tx);
            for (int i = 0; i < ip.length; i++) ip[i] ^= key[i];
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public static int crc32(byte[] data, int len) {
        CRC32 c = new CRC32();
        c.update(data, 0, len);
        return (int) c.getValue();
    }

    /** {@code stun:host:port} list → IPv4 addresses (names that do not resolve are skipped). */
    public static List<InetSocketAddress> resolve(List<String> urls) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String u : urls) {
            String hp = NetConfig.stunHost(u);
            if (hp == null) continue;
            String[] parts = NetConfig.splitHostPort(hp);
            try {
                for (InetAddress a : InetAddress.getAllByName(parts[0])) {
                    if (a instanceof Inet4Address) out.add(new InetSocketAddress(a, Integer.parseInt(parts[1])));
                }
            } catch (UnknownHostException | NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Asks the servers (twice each, {@code waitMs} per try) for this socket's public address; null when none answer. */
    public static InetSocketAddress query(DatagramSocket sock, List<InetSocketAddress> servers, int waitMs) {
        byte[] buf = new byte[1500];
        for (InetSocketAddress server : servers) {
            for (int i = 0; i < 2; i++) {
                byte[] tx = Wire.random(12);
                try {
                    byte[] req = bindingRequest(tx);
                    sock.send(new DatagramPacket(req, req.length, server));
                    long until = System.currentTimeMillis() + waitMs;
                    while (true) {
                        long left = until - System.currentTimeMillis();
                        if (left <= 0) break;
                        sock.setSoTimeout((int) left);
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        InetSocketAddress a = parseBindingResponse(p.getData(), p.getLength(), tx);
                        if (a != null) return a;
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    break;
                }
            }
        }
        return null;
    }

    /** Public address via a throwaway UDP socket (the IP is what matters; the port is that socket's mapping). */
    public static InetSocketAddress publicAddress(List<InetSocketAddress> servers, int waitMs) {
        if (servers.isEmpty()) return null;
        try (DatagramSocket s = new DatagramSocket(new InetSocketAddress("0.0.0.0", 0))) {
            return query(s, servers, waitMs);
        } catch (IOException e) {
            return null;
        }
    }
}
