package com.hyproj.blitzlink.lite;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TURN-over-TCP framing and long-term credentials — {@code blitzlink_core::turn_codec}. Native hosts and Lite hosts hand
 * out the same kind of time-limited credentials ({@code <expiry unix s>:<tag>}, password = base64(HMAC-SHA1(secret, user))).
 */
public final class TurnCodec {
    public static final String REALM = "blitzlink";

    public static final int ALLOCATE = 0x003;
    public static final int REFRESH = 0x004;
    public static final int SEND = 0x006;
    public static final int DATA = 0x007;
    public static final int CREATE_PERMISSION = 0x008;
    public static final int CHANNEL_BIND = 0x009;

    public static final int CLASS_REQUEST = 0x0000;
    public static final int CLASS_INDICATION = 0x0010;
    public static final int CLASS_SUCCESS = 0x0100;
    public static final int CLASS_ERROR = 0x0110;

    public static final int A_USERNAME = 0x0006;
    public static final int A_INTEGRITY = 0x0008;
    public static final int A_ERROR = 0x0009;
    public static final int A_CHANNEL = 0x000C;
    public static final int A_LIFETIME = 0x000D;
    public static final int A_PEER = 0x0012;
    public static final int A_DATA = 0x0013;
    public static final int A_REALM = 0x0014;
    public static final int A_NONCE = 0x0015;
    public static final int A_RELAYED = 0x0016;
    public static final int A_TRANSPORT = 0x0019;
    public static final int A_SOFTWARE = 0x8022;

    public static final byte[] TRANSPORT_UDP = {17, 0, 0, 0};

    private TurnCodec() {}

    public static byte[] longTermKey(String user, String pass) {
        try {
            return MessageDigest.getInstance("MD5").digest((user + ":" + REALM + ":" + pass).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmacSha1(byte[] key, byte[] data, int len) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            mac.update(data, 0, len);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String password(byte[] secret, String user) {
        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(hmacSha1(secret, u, u.length));
    }

    /** {user, password} that expire at {@code expiryUnix}. */
    public static String[] credentials(byte[] secret, long expiryUnix, String tag) {
        String user = expiryUnix + ":" + tag;
        return new String[] {user, password(secret, user)};
    }

    /** The long-term key when {@code user} was made with {@code secret} (and, if checked, has not expired); else null. */
    public static byte[] keyFor(byte[] secret, String user, long nowUnix, boolean checkExpiry) {
        int colon = user.indexOf(':');
        String head = colon < 0 ? user : user.substring(0, colon);
        long expiry;
        try {
            expiry = Long.parseLong(head);
        } catch (NumberFormatException e) {
            return null;
        }
        if (checkExpiry && expiry < nowUnix) return null;
        return longTermKey(user, password(secret, user));
    }

    /** One parsed STUN message (attributes in order). */
    public static final class Msg {
        public final int method;
        public final int cls;
        public final byte[] tx;
        private final byte[] raw;
        private final List<int[]> attrs = new ArrayList<>(); // {type, offset of the header, value length}

        private Msg(int method, int cls, byte[] tx, byte[] raw) {
            this.method = method;
            this.cls = cls;
            this.tx = tx;
            this.raw = raw;
        }

        public static Msg parse(byte[] raw) {
            if (raw.length < 20 || (raw[0] & 0xC0) != 0) return null;
            ByteBuffer b = ByteBuffer.wrap(raw);
            int ty = b.getShort() & 0xFFFF;
            int len = b.getShort() & 0xFFFF;
            if (b.getInt() != Stun.MAGIC || len % 4 != 0 || 20 + len != raw.length) return null;
            Msg m = new Msg((ty & 0x000F) | ((ty & 0x00E0) >> 1) | ((ty & 0x3E00) >> 2), ty & 0x0110, Arrays.copyOfRange(raw, 8, 20), raw);
            int at = 20;
            while (at + 4 <= 20 + len) {
                int aty = ((raw[at] & 0xFF) << 8) | (raw[at + 1] & 0xFF);
                int alen = ((raw[at + 2] & 0xFF) << 8) | (raw[at + 3] & 0xFF);
                if (at + 4 + alen > raw.length) return null;
                m.attrs.add(new int[] {aty, at, alen});
                at += 4 + (alen + 3) / 4 * 4;
            }
            return m;
        }

        public byte[] get(int type) {
            for (int[] a : attrs) {
                if (a[0] == type) return Arrays.copyOfRange(raw, a[1] + 4, a[1] + 4 + a[2]);
            }
            return null;
        }

        public List<byte[]> all(int type) {
            List<byte[]> out = new ArrayList<>();
            for (int[] a : attrs) {
                if (a[0] == type) out.add(Arrays.copyOfRange(raw, a[1] + 4, a[1] + 4 + a[2]));
            }
            return out;
        }

        public String text(int type) {
            byte[] v = get(type);
            return v == null ? null : new String(v, StandardCharsets.UTF_8);
        }

        public InetSocketAddress xaddr(byte[] v) {
            return v == null ? null : Stun.readAddress(v, tx, true);
        }

        public Integer errorCode() {
            byte[] v = get(A_ERROR);
            return v == null || v.length < 4 ? null : (v[2] & 0x07) * 100 + (v[3] & 0xFF);
        }

        public Integer lifetime() {
            byte[] v = get(A_LIFETIME);
            return v == null || v.length != 4 ? null : ByteBuffer.wrap(v).getInt();
        }

        /** MESSAGE-INTEGRITY checks out with this key. */
        public boolean integrityOk(byte[] key) {
            for (int[] a : attrs) {
                if (a[0] != A_INTEGRITY) continue;
                if (a[2] != 20) return false;
                int at = a[1];
                byte[] head = Arrays.copyOf(raw, at);
                int fakeLen = at + 24 - 20;
                head[2] = (byte) (fakeLen >> 8);
                head[3] = (byte) fakeLen;
                return MessageDigest.isEqual(hmacSha1(key, head, head.length), Arrays.copyOfRange(raw, at + 4, at + 24));
            }
            return false;
        }
    }

    /** Builds one STUN message. */
    public static final class Build {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        private final byte[] tx;

        public Build(int method, int cls, byte[] tx) {
            this.tx = tx;
            int ty = (method & 0x000F) | ((method & 0x0070) << 1) | ((method & 0x0F80) << 2) | cls;
            b.write(ty >> 8);
            b.write(ty);
            b.write(0);
            b.write(0);
            b.writeBytes(ByteBuffer.allocate(4).putInt(Stun.MAGIC).array());
            b.writeBytes(tx);
        }

        public Build attr(int type, byte[] v) {
            b.write(type >> 8);
            b.write(type);
            b.write(v.length >> 8);
            b.write(v.length);
            b.writeBytes(v);
            for (int i = 0; i < (4 - v.length % 4) % 4; i++) b.write(0);
            return this;
        }

        public Build addr(int type, InetSocketAddress a) {
            return attr(type, Stun.xorAddress(a, tx));
        }

        public Build u32(int type, int v) {
            return attr(type, ByteBuffer.allocate(4).putInt(v).array());
        }

        private static byte[] withLen(byte[] m, int extra) {
            int len = m.length - 20 + extra;
            m[2] = (byte) (len >> 8);
            m[3] = (byte) len;
            return m;
        }

        /** MESSAGE-INTEGRITY with {@code key} when given, then FINGERPRINT. */
        public byte[] finish(byte[] key) {
            if (key != null) {
                byte[] m = withLen(b.toByteArray(), 24);
                attr(A_INTEGRITY, hmacSha1(key, m, m.length));
            }
            byte[] m = withLen(b.toByteArray(), 8);
            int crc = Stun.crc32(m, m.length) ^ Stun.FINGERPRINT_XOR;
            attr(Stun.FINGERPRINT, ByteBuffer.allocate(4).putInt(crc).array());
            return withLen(b.toByteArray(), 0);
        }
    }

    public static byte[] error(int method, byte[] tx, int code, String reason, java.util.function.UnaryOperator<Build> extra) {
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        byte[] v = new byte[4 + r.length];
        v[2] = (byte) (code / 100);
        v[3] = (byte) (code % 100);
        System.arraycopy(r, 0, v, 4, r.length);
        return extra.apply(new Build(method, CLASS_ERROR, tx).attr(A_ERROR, v)).finish(null);
    }

    /** ChannelData, padded to 4 bytes over TCP (RFC 8656 12.5). */
    public static byte[] channelData(int ch, byte[] data, int off, int len) {
        byte[] out = new byte[4 + (len + 3) / 4 * 4];
        out[0] = (byte) (ch >> 8);
        out[1] = (byte) ch;
        out[2] = (byte) (len >> 8);
        out[3] = (byte) len;
        System.arraycopy(data, off, out, 4, len);
        return out;
    }

    /** Channel number of a ChannelData frame, or -1. */
    public static int channelOf(byte[] frame) {
        if (frame.length < 4 || (frame[0] & 0xC0) != 0x40) return -1;
        return ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
    }

    public static int channelDataLength(byte[] frame) {
        return ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
    }

    /** Length of the frame at the front of {@code buf[0..n)} (a STUN message or ChannelData), or -1 if too short. */
    public static int frameLen(byte[] buf, int n) {
        if (n < 4) return -1;
        int len = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        return (buf[0] & 0xC0) == 0x40 ? 4 + (len + 3) / 4 * 4 : 20 + len;
    }
}
