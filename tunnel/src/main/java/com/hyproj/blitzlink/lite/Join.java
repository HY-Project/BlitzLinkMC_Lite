package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;

/**
 * {@code tunnel join --code MCJE-XXXX} — native {@code join.rs} on libwebrtc:
 * <ol>
 * <li>A PeerConnection with host and server-reflexive candidates makes the offer (channel {@code meta}); the relay
 *     returns the host's answer, fingerprint, listing and TURN details.</li>
 * <li>If that connection has not opened within 2.5 s (or failed), a second one that uses only the host's embedded TURN
 *     (relay candidates, a new offer through the relay) starts alongside. The first to open wins; nothing in 14 s is
 *     {@code rtcFailed}.</li>
 * <li>On {@code meta}: {@code probe} → the host's signed {@code info}; the fingerprint must equal the relay's
 *     ({@code badIdentity} otherwise). Then a local port: one {@code mc-N} channel per TCP connection.</li>
 * </ol>
 */
final class Join implements LiteTunnel.Engine {
    record Options(String code, String relay, List<String> stun, boolean turnOnly) {}

    private static final long TURN_AFTER_MS = 2500;
    private static final long TOTAL_MS = 14_000;
    private static final long PROBE_TIMEOUT_MS = 6000;
    private static final long DISCONNECT_GRACE_MS = 10_000;
    private static final long PING_EVERY_MS = 15_000;
    private static final long GATHER_MS = 1500;
    private static final long TURN_GATHER_MS = 5000;

    private final Options opt;
    private final LiteTunnel.Emitter out;
    private final Log log;
    private final Path nativeDir;
    private final LinkedBlockingQueue<Runnable> events = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blitzlink-join-timer");
        t.setDaemon(true);
        return t;
    });
    private final List<Attempt> attempts = new ArrayList<>();
    private volatile boolean quit;

    private PeerConnectionFactory factory;
    private String fingerprint;
    private JsonObject listing;
    private Rtc.TurnInfo turnInfo;

    Join(Options opt, LiteTunnel.Emitter out, Log log, Path nativeDir) {
        this.opt = opt;
        this.out = out;
        this.log = log;
        this.nativeDir = nativeDir;
    }

    /** One connection attempt — direct ("udp") or through the host's TURN ("turn"). */
    private final class Attempt {
        final String via;
        final Rtc.Peer rtc;
        final Rtc.Channel meta;
        volatile boolean failed;
        volatile boolean open;
        volatile Long disconnectedAt;

        Attempt(String via, Rtc.Peer rtc) {
            this.via = via;
            this.rtc = rtc;
            this.meta = new Rtc.Channel(rtc.createChannel("meta"));
            rtc.listen(new Rtc.Peer.Events() {
                @Override
                public void connection(RTCPeerConnectionState s) {
                    post(() -> onConnection(Attempt.this, s));
                }

                @Override
                public void ice(RTCIceConnectionState s) {
                    post(() -> onIce(Attempt.this, s));
                }
            });
        }
    }

    private void post(Runnable r) {
        events.add(r);
    }

    @Override
    public void control(JsonObject m) {
        if ("quit".equals(Json.str(m, "t"))) stop();
    }

    @Override
    public void stop() {
        quit = true;
        post(() -> {});
    }

    @Override
    public void run() throws LiteTunnel.Failure {
        String code = Wire.normalizeCode(opt.code());
        if (code == null) throw new LiteTunnel.Failure("noSuchCode");
        JsonObject st = Json.obj("status");
        st.addProperty("state", "relay");
        st.addProperty("code", code);
        out.emit(st);
        factory = LiteTunnel.factory(nativeDir, log);
        try {
            Attempt winner = race(code);
            if (winner == null) return; // quit
            new Session(winner).run();
        } finally {
            for (Attempt a : attempts) a.rtc.close();
            timer.shutdownNow();
        }
    }

    // ---- relay exchange ----

    /** Offer → the host's answer through the relay (the relay socket is closed afterwards). */
    private JsonObject exchange(String code, String sdp) throws LiteTunnel.Failure {
        String url = NetConfig.wsUrl(opt.relay());
        RelaySocket ws;
        try {
            ws = RelaySocket.connect(url, 6000);
        } catch (IOException e) {
            log.info("relay " + url + ": " + e.getMessage());
            throw new LiteTunnel.Failure("relayUnreachable");
        }
        try (ws) {
            while (true) {
                JsonObject m;
                try {
                    m = ws.next(12_000);
                } catch (IOException e) {
                    throw new LiteTunnel.Failure("relayUnreachable");
                }
                if (m == null) throw new LiteTunnel.Failure("hostTimeout");
                switch (Objects.requireNonNullElse(Json.str(m, "t"), "")) {
                    case "hello" -> {
                        if (Json.num(m, "relay", -1) != Wire.RELAY_PROTOCOL) throw new LiteTunnel.Failure("version");
                        JsonObject j = Json.obj("join");
                        j.addProperty("relay", Wire.RELAY_PROTOCOL);
                        j.addProperty("code", code);
                        j.addProperty("sdp", sdp);
                        try {
                            ws.send(j);
                        } catch (IOException e) {
                            throw new LiteTunnel.Failure("relayUnreachable");
                        }
                    }
                    case "answer" -> {
                        return m;
                    }
                    case "error" -> throw new LiteTunnel.Failure(Objects.requireNonNullElse(Json.str(m, "reason"), "badPacket"));
                    case "_unreadable" -> throw new LiteTunnel.Failure("badPacket");
                    default -> {}
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiteTunnel.Failure("relayUnreachable");
        }
    }

    private static String sdpOf(CompletableFuture<String> f, String what) throws LiteTunnel.Failure {
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new LiteTunnel.Failure("rtc: " + what + " failed");
        }
    }

    private void accept(Attempt a, String answerSdp) throws LiteTunnel.Failure {
        try {
            a.rtc.setRemote(RTCSdpType.ANSWER, answerSdp).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new LiteTunnel.Failure("badPacket");
        }
    }

    /** First attempt: host + srflx candidates. */
    private Attempt udpAttempt() throws LiteTunnel.Failure {
        Attempt a = new Attempt("udp", new Rtc.Peer(factory, Rtc.config(Rtc.stunServers(opt.stun()), false)));
        attempts.add(a);
        return a;
    }

    private String offer(Attempt a, CompletableFuture<Void> enough, long gatherMs) throws LiteTunnel.Failure {
        CompletableFuture<String> sdp = a.rtc.createOffer()
                .thenComposeAsync(a.rtc::setLocal, Host.ASYNC)
                .thenComposeAsync(v -> a.rtc.localSdpAfterGathering(enough, gatherMs), Host.ASYNC);
        return sdpOf(sdp, "offer");
    }

    // ---- the race ----

    private Attempt race(String code) throws LiteTunnel.Failure {
        Attempt first = udpAttempt();
        String offer = offer(first, first.rtc.gathered, GATHER_MS);
        JsonObject ans = exchange(code, offer);
        accept(first, Objects.requireNonNullElse(Json.str(ans, "sdp"), ""));
        fingerprint = Json.str(ans, "fingerprint");
        listing = ans.has("listing") && ans.get("listing").isJsonObject() ? ans.getAsJsonObject("listing") : new JsonObject();
        turnInfo = parseTurn(ans.get("turn"));
        JsonObject st = Json.obj("status");
        st.addProperty("state", "connecting");
        out.emit(st);

        CompletableFuture<Attempt> winner = new CompletableFuture<>();
        AtomicInteger pending = new AtomicInteger();
        boolean[] turnStarted = {false};
        Runnable startTurn = () -> {
            if (turnStarted[0] || turnInfo == null || quit) return;
            turnStarted[0] = true;
            pending.incrementAndGet();
            JsonObject t = Json.obj("status");
            t.addProperty("state", "turn");
            out.emit(t);
            Thread.ofVirtual().name("blitzlink-join-turn").start(() -> {
                try {
                    Attempt a = new Attempt("turn", new Rtc.Peer(factory, Rtc.config(List.of(Rtc.turnServer(turnInfo)), true)));
                    synchronized (attempts) {
                        attempts.add(a);
                    }
                    watch(a, winner);
                    String o = offer(a, a.rtc.relayCandidate.thenCompose(v -> a.rtc.gathered.completeOnTimeout(null, 300, TimeUnit.MILLISECONDS)), TURN_GATHER_MS);
                    if (!o.contains(" typ relay")) {
                        log.info("TURN: no relayed candidate from the host's TURN");
                        throw new LiteTunnel.Failure("rtcFailed");
                    }
                    log.info("TURN: connected to host TURN");
                    JsonObject a2 = exchange(code, o);
                    accept(a, Objects.requireNonNullElse(Json.str(a2, "sdp"), ""));
                } catch (LiteTunnel.Failure | RuntimeException e) {
                    post(() -> attemptFailed(null, e.getMessage()));
                }
            });
        };

        if (opt.turnOnly()) {
            first.failed = true;
            first.rtc.close();
            startTurn.run();
            if (!turnStarted[0]) throw new LiteTunnel.Failure("rtcFailed");
        } else {
            pending.incrementAndGet();
            watch(first, winner);
        }
        long start = System.currentTimeMillis();
        this.pending = pending;
        this.startTurn = startTurn;
        while (!winner.isDone()) {
            if (quit) return null;
            long now = System.currentTimeMillis();
            if (now - start >= TOTAL_MS) throw new LiteTunnel.Failure("rtcFailed");
            if (!turnStarted[0] && turnInfo != null && now - start >= TURN_AFTER_MS) startTurn.run();
            if (pending.get() <= 0 && (turnStarted[0] || turnInfo == null)) throw new LiteTunnel.Failure("rtcFailed");
            drain(100);
        }
        Attempt w = winner.join();
        synchronized (attempts) {
            for (Attempt a : attempts) {
                if (a != w) Host.ASYNC.execute(a.rtc::close);
            }
            attempts.clear();
            attempts.add(w);
        }
        return w;
    }

    private AtomicInteger pending;
    private Runnable startTurn;

    /** Completes {@code winner} when this attempt's {@code meta} opens. */
    private void watch(Attempt a, CompletableFuture<Attempt> winner) {
        a.meta.start(b -> post(() -> onMeta(a, b)), () -> post(() -> {
            a.open = true;
            winner.complete(a);
        }), () -> post(() -> onMetaClosed(a)));
    }

    private void attemptFailed(Attempt a, String why) {
        if (a != null) {
            if (a.failed || a.open) return;
            a.failed = true;
        }
        log.info("connection attempt failed (" + why + ")");
        if (pending != null) pending.decrementAndGet();
        if (startTurn != null) startTurn.run();
    }

    private void onConnection(Attempt a, RTCPeerConnectionState s) {
        if (s == RTCPeerConnectionState.FAILED || s == RTCPeerConnectionState.CLOSED) {
            if (!a.open) attemptFailed(a, "rtcFailed");
            else if (session != null && session.conn == a) session.dead = true;
        } else if (s == RTCPeerConnectionState.DISCONNECTED) {
            if (a.disconnectedAt == null) a.disconnectedAt = System.currentTimeMillis();
        } else if (s == RTCPeerConnectionState.CONNECTED) {
            a.disconnectedAt = null;
        }
    }

    private void onIce(Attempt a, RTCIceConnectionState s) {
        if (s == RTCIceConnectionState.DISCONNECTED) {
            if (a.disconnectedAt == null) a.disconnectedAt = System.currentTimeMillis();
        } else if (s == RTCIceConnectionState.CONNECTED || s == RTCIceConnectionState.COMPLETED) {
            a.disconnectedAt = null;
        } else if (s == RTCIceConnectionState.FAILED && !a.open) {
            attemptFailed(a, "rtcFailed");
        }
    }

    private void onMetaClosed(Attempt a) {
        if (session != null && session.conn == a) session.dead = true;
        else if (!a.open) attemptFailed(a, "rtcFailed");
    }

    private void onMeta(Attempt a, byte[] data) {
        if (session != null && session.conn == a) session.onMeta(data);
    }

    /** Runs queued events for up to {@code ms}. */
    private void drain(long ms) {
        try {
            Runnable r = events.poll(ms, TimeUnit.MILLISECONDS);
            while (r != null) {
                r.run();
                r = events.poll();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            quit = true;
        }
    }

    private static Rtc.TurnInfo parseTurn(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        List<String> urls = new ArrayList<>();
        if (o.has("urls") && o.get("urls").isJsonArray()) {
            for (JsonElement u : o.getAsJsonArray("urls")) {
                if (u.isJsonPrimitive() && NetConfig.turnHost(u.getAsString()) != null) urls.add(u.getAsString());
            }
        }
        String user = Json.str(o, "username");
        String cred = Json.str(o, "credential");
        return urls.isEmpty() || user == null || cred == null ? null : new Rtc.TurnInfo(urls, user, cred);
    }

    // ---- the open session ----

    private volatile Session session;

    private final class Session {
        final Attempt conn;
        final byte[] nonce = Wire.random(32);
        final long probeAt = System.currentTimeMillis();
        final ConcurrentHashMap<Pipe, Boolean> live = new ConcurrentHashMap<>();
        boolean verified;
        volatile boolean dead;
        long pingN;
        long pingAt;
        ServerSocket listener;
        int nextTun;

        Session(Attempt conn) {
            this.conn = conn;
        }

        void run() throws LiteTunnel.Failure {
            session = this;
            log.info("connected via " + conn.via);
            JsonObject st = Json.obj("status");
            st.addProperty("state", "open");
            st.addProperty("via", conn.via);
            out.emit(st);
            JsonObject probe = Json.obj("probe");
            probe.addProperty("nonce", Wire.hex(nonce));
            conn.meta.sendText(probe.toString());
            long nextPing = System.currentTimeMillis() + PING_EVERY_MS;
            try {
                while (true) {
                    drain(100);
                    long now = System.currentTimeMillis();
                    if (quit) {
                        closeAll();
                        // Tells the host right away (SCTP/DTLS shutdown), so it frees the session.
                        conn.rtc.close();
                        return;
                    }
                    Long d = conn.disconnectedAt;
                    if (d != null && now - d > DISCONNECT_GRACE_MS) dead = true;
                    if (dead) {
                        closeAll();
                        if (failure != null) throw new LiteTunnel.Failure(failure);
                        if (!verified) throw new LiteTunnel.Failure("rtcFailed");
                        log.info("connection closed");
                        out.emit(Json.obj("closed"));
                        return;
                    }
                    if (!verified && now - probeAt > PROBE_TIMEOUT_MS) throw new LiteTunnel.Failure("timeout");
                    if (verified && now >= nextPing) {
                        nextPing = now + PING_EVERY_MS;
                        pingN++;
                        pingAt = now;
                        JsonObject p = Json.obj("ping");
                        p.addProperty("n", pingN);
                        conn.meta.sendText(p.toString());
                    }
                }
            } finally {
                closeListener();
            }
        }

        void onMeta(byte[] data) {
            if (data.length > 256 * 1024) return;
            JsonObject m;
            try {
                m = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                return;
            }
            String t = Objects.requireNonNullElse(Json.str(m, "t"), "");
            if (t.equals("info")) {
                JsonElement infoEl = m.has("info") ? m.get("info") : com.google.gson.JsonNull.INSTANCE;
                if (!verified) {
                    byte[] key = Wire.unhex(Json.str(m, "key"));
                    byte[] sig = Wire.unhex(Json.str(m, "sig"));
                    if (key == null || key.length != 32 || !Identity.verify(key, nonce, sig) || !Identity.fingerprint(key).equals(fingerprint)) {
                        // Signature wrong, or not the key the relay vouched for: someone may be in between.
                        failure = "badIdentity";
                        dead = true;
                        return;
                    }
                    verified = true;
                    emitMeta(infoEl, System.currentTimeMillis() - probeAt);
                    openListener();
                    return;
                }
                emitMeta(infoEl, null);
            } else if (t.equals("pong") && Json.num(m, "n", -1) == pingN && pingAt != 0) {
                JsonObject st = Json.obj("status");
                st.addProperty("state", "open");
                st.addProperty("via", conn.via);
                st.addProperty("rttMs", System.currentTimeMillis() - pingAt);
                pingAt = 0;
                out.emit(st);
            }
        }

        void emitMeta(JsonElement info, Long rtt) {
            JsonObject e = Json.obj("meta");
            e.add("info", info);
            e.addProperty("fingerprint", fingerprint);
            e.add("listing", listing);
            e.addProperty("via", conn.via);
            if (rtt != null) e.addProperty("rttMs", rtt);
            else e.add("rttMs", com.google.gson.JsonNull.INSTANCE);
            out.emit(e);
        }

        void openListener() {
            if (listener != null) return;
            try {
                listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                failure = "listen: " + e.getMessage();
                dead = true;
                return;
            }
            JsonObject l = Json.obj("listen");
            l.addProperty("port", listener.getLocalPort());
            out.emit(l);
            ServerSocket ss = listener;
            Thread.ofVirtual().name("blitzlink-join-accept").start(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket s = ss.accept();
                        post(() -> onLocal(s));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        /** One local TCP connection (status ping or login) → its own {@code mc-N} channel. */
        void onLocal(Socket s) {
            if (dead) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                return;
            }
            nextTun++;
            Rtc.Channel ch = new Rtc.Channel(conn.rtc.createChannel("mc-" + nextTun));
            Pipe[] holder = new Pipe[1];
            Pipe pipe = new Pipe(ch, port -> live.remove(holder[0]));
            holder[0] = pipe;
            live.put(pipe, Boolean.TRUE);
            ch.start(pipe::inbound, () -> {}, pipe::channelClosed);
            pipe.attach(s, null);
        }

        void closeAll() {
            for (Pipe p : new ArrayList<>(live.keySet())) p.closeAll();
            closeListener();
        }

        void closeListener() {
            ServerSocket l = listener;
            if (l != null) {
                try {
                    l.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** A session-ending reason set from an event (badIdentity, …) — reported instead of the generic one. */
    private volatile String failure;
}
