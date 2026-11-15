package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The host's embedded TURN server (a small RFC 8656 subset, TCP only) — the Lite counterpart of native
 * {@code turn_server.rs}, wire-compatible with both libwebrtc's TURN client (Lite joiners) and native joiners.
 *
 * <p>Players whose network blocks UDP reach it through the Minecraft TCP port (the mod splits connections by their first
 * byte and pipes STUN-looking ones here). It is <b>not an open relay</b>: every allocation's relayed socket only sends to
 * this host's own WebRTC sockets ({@code resolvePeer} maps a peer address the client names to one of the host's own
 * candidates, or rejects it). Credentials are the host's time-limited ones from the answer; expiry is checked at Allocate.
 */
final class TurnServer implements AutoCloseable {
    private static final int DEFAULT_LIFETIME = 600;
    private static final int MAX_LIFETIME = 3600;
    private static final long PERMISSION_MS = 300_000;
    private static final long CHANNEL_MS = 600_000;
    private static final long FIRST_ALLOCATE_MS = 10_000;
    private static final int MAX_FRAME = 4096;
    private static final int MAX_PEERS = 8;

    private final byte[] secret = Wire.random(32);
    private final Log log;
    private final int max;
    private final Function<InetSocketAddress, InetSocketAddress> resolvePeer;
    private final InetAddress lan;
    private final AtomicInteger active = new AtomicInteger();
    /** Relayed socket port → the TCP peer of that allocation (the mod's loopback pipe, or the player itself). */
    private final Map<Integer, InetSocketAddress> sources = new ConcurrentHashMap<>();
    private volatile ServerSocket listener;

    TurnServer(Log log, int max, InetAddress lan, Function<InetSocketAddress, InetSocketAddress> resolvePeer) {
        this.log = log;
        this.max = max;
        this.lan = lan;
        this.resolvePeer = resolvePeer;
    }

    /** Binds and starts accepting; returns the bound port. */
    int listen(InetSocketAddress bind) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        try {
            ss.bind(bind);
        } catch (IOException e) {
            ss.close();
            throw new IOException("TURN " + Wire.addr(bind) + ": cannot bind (" + e.getMessage() + ")", e);
        }
        listener = ss;
        Thread.ofVirtual().name("blitzlink-turn-accept").start(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    Thread.ofVirtual().name("blitzlink-turn").start(() -> serve(s));
                } catch (IOException e) {
                    if (ss.isClosed()) return;
                }
            }
        });
        return ss.getLocalPort();
    }

    /** {user, password} for one offer, valid for Allocate until {@code ttlSeconds} from now. */
    String[] credentials(long ttlSeconds) {
        return TurnCodec.credentials(secret, System.currentTimeMillis() / 1000 + ttlSeconds, Wire.hex(Wire.random(4)));
    }

    byte[] keyFor(String user, boolean checkExpiry) {
        return TurnCodec.keyFor(secret, user, System.currentTimeMillis() / 1000, checkExpiry);
    }

    /** When a host socket hears from {@code port} on one of our relayed sockets: the TCP peer of that allocation. */
    InetSocketAddress tcpPeerOfRelayPort(int port) {
        return sources.get(port);
    }

    int active() {
        return active.get();
    }

    @Override
    public void close() {
        ServerSocket ss = listener;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---- one TCP connection ----

    private static final class Alloc {
        String user;
        byte[] key;
        long expires;
        // Written by the TCP reader, read by the relayed socket's reader.
        final Map<InetAddress, Long> perms = new ConcurrentHashMap<>();
        final Map<Integer, InetSocketAddress> channels = new ConcurrentHashMap<>();
        final Map<Integer, Long> channelExpiry = new ConcurrentHashMap<>();
        /** Actual destination (one of our candidates) → the peer address the client used for it. */
        final Map<InetSocketAddress, InetSocketAddress> named = new ConcurrentHashMap<>();
        DatagramSocket relay;

        boolean permitted(InetAddress ip, long now) {
            Long t = perms.get(ip);
            return t != null && t > now;
        }

        Integer channelFor(InetSocketAddress peer, long now) {
            for (Map.Entry<Integer, InetSocketAddress> e : channels.entrySet()) {
                Long t = channelExpiry.get(e.getKey());
                if (e.getValue().equals(peer) && t != null && t > now) return e.getKey();
            }
            return null;
        }
    }

    private void serve(Socket s) {
        if (active.incrementAndGet() > max) {
            active.decrementAndGet();
            closeQuietly(s);
            return;
        }
        InetSocketAddress tcpPeer = (InetSocketAddress) s.getRemoteSocketAddress();
        String nonce = Wire.hex(Wire.random(12));
        long opened = System.currentTimeMillis();
        Alloc[] holder = new Alloc[1];
        Object writeLock = new Object();
        try (s) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(1000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] buf = new byte[MAX_FRAME * 2];
            int n = 0;
            while (true) {
                long now = System.currentTimeMillis();
                Alloc a = holder[0];
                if (a == null && now - opened > FIRST_ALLOCATE_MS) return;
                if (a != null && a.expires <= now) return;
                int r;
                try {
                    r = in.read(buf, n, buf.length - n);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (r < 0) return;
                n += r;
                while (true) {
                    int len = TurnCodec.frameLen(buf, n);
                    if (len < 0) break;
                    if (len > MAX_FRAME) return;
                    if (n < len) break;
                    byte[] frame = Arrays.copyOf(buf, len);
                    System.arraycopy(buf, len, buf, 0, n - len);
                    n -= len;
                    byte[] reply = onFrame(frame, s, tcpPeer, nonce, holder, out, writeLock);
                    if (reply == CLOSE) return;
                    if (reply != null) write(out, writeLock, reply);
                }
            }
        } catch (IOException ignored) {
        } finally {
            active.decrementAndGet();
            Alloc a = holder[0];
            if (a != null && a.relay != null) {
                sources.remove(a.relay.getLocalPort());
                a.relay.close();
            }
        }
    }

    private static final byte[] CLOSE = new byte[0];

    private static void write(OutputStream out, Object lock, byte[] b) throws IOException {
        synchronized (lock) {
            out.write(b);
            out.flush();
        }
    }

    private byte[] onFrame(byte[] frame, Socket s, InetSocketAddress tcpPeer, String nonce, Alloc[] holder, OutputStream out,
            Object writeLock) throws IOException {
        long now = System.currentTimeMillis();
        int ch = TurnCodec.channelOf(frame);
        if (ch >= 0) {
            Alloc a = holder[0];
            if (a == null) return null;
            InetSocketAddress to = a.channels.get(ch);
            Long t = a.channelExpiry.get(ch);
            if (to == null || t == null || t <= now) return null;
            relayOut(a, to, frame, 4, TurnCodec.channelDataLength(frame));
            return null;
        }
        TurnCodec.Msg m = TurnCodec.Msg.parse(frame);
        if (m == null) return null;
        if (m.cls == TurnCodec.CLASS_INDICATION) {
            Alloc a = holder[0];
            if (m.method == TurnCodec.SEND && a != null) {
                InetSocketAddress to = m.xaddr(m.get(TurnCodec.A_PEER));
                byte[] data = m.get(TurnCodec.A_DATA);
                if (to != null && data != null && a.permitted(to.getAddress(), now)) relayOut(a, to, data, 0, data.length);
            }
            return null;
        }
        if (m.cls != TurnCodec.CLASS_REQUEST) return null;

        byte[] nonceBytes = nonce.getBytes(StandardCharsets.US_ASCII);
        byte[] realm = TurnCodec.REALM.getBytes(StandardCharsets.US_ASCII);
        byte[] unauthorized = TurnCodec.error(m.method, m.tx, 401, "Unauthorized", b -> b.attr(TurnCodec.A_REALM, realm).attr(TurnCodec.A_NONCE, nonceBytes));
        String user = m.text(TurnCodec.A_USERNAME);
        if (user == null) return unauthorized;
        if (!nonce.equals(m.text(TurnCodec.A_NONCE))) {
            return TurnCodec.error(m.method, m.tx, 438, "Stale Nonce", b -> b.attr(TurnCodec.A_REALM, realm).attr(TurnCodec.A_NONCE, nonceBytes));
        }
        Alloc a = holder[0];
        byte[] key;
        if (a != null) {
            if (!a.user.equals(user)) return unauthorized;
            key = a.key;
        } else {
            key = keyFor(user, m.method == TurnCodec.ALLOCATE);
            if (key == null) return unauthorized;
        }
        if (!m.integrityOk(key)) return unauthorized;

        switch (m.method) {
            case TurnCodec.ALLOCATE -> {
                if (a != null) return TurnCodec.error(TurnCodec.ALLOCATE, m.tx, 437, "Allocation Mismatch", b -> b);
                byte[] tr = m.get(TurnCodec.A_TRANSPORT);
                if (tr == null || tr.length == 0 || tr[0] != 17) {
                    return TurnCodec.error(TurnCodec.ALLOCATE, m.tx, 442, "Unsupported Transport Protocol", b -> b);
                }
                Integer req = m.lifetime();
                int secs = Math.clamp(req == null ? DEFAULT_LIFETIME : req, DEFAULT_LIFETIME, MAX_LIFETIME);
                Alloc na = new Alloc();
                na.user = user;
                na.key = key;
                na.expires = now + secs * 1000L;
                try {
                    na.relay = new DatagramSocket(new InetSocketAddress("0.0.0.0", 0));
                } catch (SocketException e) {
                    return TurnCodec.error(TurnCodec.ALLOCATE, m.tx, 508, "Insufficient Capacity", b -> b);
                }
                holder[0] = na;
                sources.put(na.relay.getLocalPort(), tcpPeer);
                Thread.ofVirtual().name("blitzlink-turn-relay").start(() -> relayIn(na, out, writeLock));
                InetAddress local = s.getLocalAddress();
                InetAddress shown = local.isLoopbackAddress() && lan != null ? lan : local;
                InetSocketAddress relayed = new InetSocketAddress(shown, na.relay.getLocalPort());
                log.info("TURN: allocation for " + Wire.addr(tcpPeer) + " (client behind blocked UDP)");
                return new TurnCodec.Build(TurnCodec.ALLOCATE, TurnCodec.CLASS_SUCCESS, m.tx)
                        .addr(TurnCodec.A_RELAYED, relayed)
                        .addr(Stun.XOR_MAPPED_ADDRESS, tcpPeer)
                        .u32(TurnCodec.A_LIFETIME, secs)
                        .attr(TurnCodec.A_SOFTWARE, "BlitzLink Lite".getBytes(StandardCharsets.US_ASCII))
                        .finish(key);
            }
            case TurnCodec.REFRESH -> {
                if (a == null) return null;
                Integer req = m.lifetime();
                int secs = Math.min(req == null ? DEFAULT_LIFETIME : req, MAX_LIFETIME);
                byte[] reply = new TurnCodec.Build(TurnCodec.REFRESH, TurnCodec.CLASS_SUCCESS, m.tx).u32(TurnCodec.A_LIFETIME, secs).finish(key);
                if (secs == 0) {
                    write(out, writeLock, reply);
                    return CLOSE;
                }
                a.expires = now + secs * 1000L;
                return reply;
            }
            case TurnCodec.CREATE_PERMISSION -> {
                if (a == null) return null;
                var peers = m.all(TurnCodec.A_PEER).stream().map(m::xaddr).filter(java.util.Objects::nonNull).toList();
                if (peers.isEmpty()) return TurnCodec.error(TurnCodec.CREATE_PERMISSION, m.tx, 400, "Bad Request", b -> b);
                for (InetSocketAddress p : peers) a.perms.put(p.getAddress(), now + PERMISSION_MS);
                return new TurnCodec.Build(TurnCodec.CREATE_PERMISSION, TurnCodec.CLASS_SUCCESS, m.tx).finish(key);
            }
            case TurnCodec.CHANNEL_BIND -> {
                if (a == null) return null;
                byte[] cv = m.get(TurnCodec.A_CHANNEL);
                InetSocketAddress to = m.xaddr(m.get(TurnCodec.A_PEER));
                if (cv == null || cv.length != 4 || to == null) return TurnCodec.error(TurnCodec.CHANNEL_BIND, m.tx, 400, "Bad Request", b -> b);
                int c = ((cv[0] & 0xFF) << 8) | (cv[1] & 0xFF);
                boolean clash = false;
                for (Map.Entry<Integer, InetSocketAddress> e : a.channels.entrySet()) {
                    Long t = a.channelExpiry.get(e.getKey());
                    if (t != null && t > now && ((e.getKey() == c) != e.getValue().equals(to))) clash = true;
                }
                if (c < 0x4000 || c > 0x4FFF || clash) return TurnCodec.error(TurnCodec.CHANNEL_BIND, m.tx, 400, "Bad Request", b -> b);
                a.channels.put(c, to);
                a.channelExpiry.put(c, now + CHANNEL_MS);
                a.perms.put(to.getAddress(), now + PERMISSION_MS);
                return new TurnCodec.Build(TurnCodec.CHANNEL_BIND, TurnCodec.CLASS_SUCCESS, m.tx).finish(key);
            }
            default -> {
                return TurnCodec.error(m.method, m.tx, 400, "Bad Request", b -> b);
            }
        }
    }

    /** Client → peer: only to this host's own WebRTC sockets. */
    private void relayOut(Alloc a, InetSocketAddress to, byte[] data, int off, int len) {
        InetSocketAddress dest = resolvePeer.apply(to);
        if (dest == null) return;
        if (!a.named.containsKey(dest)) {
            if (a.named.size() >= MAX_PEERS) return;
            a.named.put(dest, to);
        }
        try {
            a.relay.send(new DatagramPacket(data, off, len, dest));
        } catch (IOException ignored) {
        }
    }

    /** Peer → client, as ChannelData when the client bound a channel to it, else a Data indication. */
    private void relayIn(Alloc a, OutputStream out, Object writeLock) {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!a.relay.isClosed()) {
            try {
                p.setLength(buf.length);
                a.relay.receive(p);
            } catch (IOException e) {
                return;
            }
            InetSocketAddress src = (InetSocketAddress) p.getSocketAddress();
            InetSocketAddress peer = a.named.get(src);
            if (peer == null) continue; // only our own sockets ever answer here
            long now = System.currentTimeMillis();
            if (!a.permitted(peer.getAddress(), now)) continue;
            Integer ch = a.channelFor(peer, now);
            byte[] frame = ch != null
                    ? TurnCodec.channelData(ch, buf, 0, p.getLength())
                    : new TurnCodec.Build(TurnCodec.DATA, TurnCodec.CLASS_INDICATION, Wire.random(12))
                            .addr(TurnCodec.A_PEER, peer)
                            .attr(TurnCodec.A_DATA, Arrays.copyOf(buf, p.getLength()))
                            .finish(null);
            try {
                write(out, writeLock, frame);
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
