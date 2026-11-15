package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;

/**
 * {@code tunnel host} — runs next to a Minecraft server, like native {@code host.rs}: registers with the relay (join code,
 * public list), answers each joiner's offer with a new PeerConnection, proves the server identity on {@code meta} and
 * pipes every {@code mc-N} channel to {@code --target}. Joiners may be native or Lite; the relay and the channels are the
 * same either way.
 *
 * <p>All session state lives on one thread ({@code brain}); libwebrtc callbacks and socket threads only post to it.
 */
final class Host implements LiteTunnel.Engine {
    record Options(InetSocketAddress target, int port, String relay, List<String> stun, Path keyFile, Path codeFile,
            boolean isPublic, boolean upnp, boolean turn, InetSocketAddress turnListen, boolean proxyProtocol) {}

    private static final long CONNECT_TIMEOUT_MS = 15_000;
    private static final int MAX_PENDING = 32;
    private static final int MAX_PEERS = 256;
    private static final long DISCONNECT_GRACE_MS = 10_000;
    private static final long TURN_TTL_S = 300;
    private static final long STUN_EVERY_MS = 300_000;
    private static final long STUN_RETRY_MS = 5_000;
    private static final long GATHER_MS = 1_500;
    /**
     * Short blocking jobs (dialing the server, closing PeerConnections). Platform threads on purpose, like every socket
     * thread here: with virtual threads, a socket read and a write on the same TCP connection stalled each other on Windows
     * under load (the TURN path froze until ICE gave up).
     */
    static final Executor ASYNC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "blitzlink-async");
        t.setDaemon(true);
        return t;
    });

    private final Options opt;
    private final LiteTunnel.Emitter out;
    private final Log log;
    private final Path nativeDir;
    private final ScheduledExecutorService brain = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blitzlink-host");
        t.setDaemon(true);
        return t;
    });
    private final CountDownLatch done = new CountDownLatch(1);

    private Identity identity;
    private PeerConnectionFactory factory;
    private TurnServer turn;
    private int turnPort;
    private InetAddress lan;
    private List<InetSocketAddress> stunServers = List.of();
    private volatile InetSocketAddress publicAddr;

    // brain-only
    private final Map<Integer, HostPeer> peers = new HashMap<>();
    private int nextPeer = 1;
    private JsonElement info = JsonNull.INSTANCE;
    private JsonObject lastStatus;

    // shared with the relay thread / TURN threads
    private volatile JsonObject listing;
    private volatile boolean isPublic;
    private final Map<Integer, String> turnPeers = new ConcurrentHashMap<>();
    private final Set<InetSocketAddress> localCandidates = ConcurrentHashMap.newKeySet();
    private volatile String relayState = "connecting";
    private volatile String relayCode;
    private volatile String relayReason;
    private volatile String upnpState;
    private volatile Upnp.Mapped mapped;
    private volatile RelaySocket relaySocket;
    private volatile boolean stopped;

    Host(Options opt, LiteTunnel.Emitter out, Log log, Path nativeDir) {
        this.opt = opt;
        this.out = out;
        this.log = log;
        this.nativeDir = nativeDir;
        this.isPublic = opt.isPublic();
        this.upnpState = opt.upnp() ? "searching" : "off";
    }

    /** One joiner. */
    private static final class HostPeer {
        final int id;
        final Rtc.Peer rtc;
        final long created = System.currentTimeMillis();
        final List<Pipe> pipes = new ArrayList<>();
        final Set<InetSocketAddress> candidates = new LinkedHashSet<>();
        Rtc.Channel meta;
        boolean opened;
        Long disconnectedAt;
        volatile InetSocketAddress remote;

        HostPeer(int id, Rtc.Peer rtc) {
            this.id = id;
            this.rtc = rtc;
        }
    }

    @Override
    public void run() throws LiteTunnel.Failure {
        boolean[] created = new boolean[1];
        try {
            identity = Identity.loadOrCreate(opt.keyFile(), created);
        } catch (IOException e) {
            log.info(e.getMessage());
            throw new LiteTunnel.Failure("badKey");
        }
        if (created[0]) log.info("created a new host key (" + opt.keyFile() + "), fingerprint " + identity.fingerprint());
        factory = LiteTunnel.factory(nativeDir, log);
        lan = primaryIpv4();
        stunServers = Stun.resolve(opt.stun());
        publicAddr = Stun.publicAddress(stunServers, 700);
        if (publicAddr != null) log.info("STUN: public address " + publicAddr.getAddress().getHostAddress());
        else if (!stunServers.isEmpty()) log.info("STUN: no answer, continuing without a public address (LAN and TURN only)");

        if (opt.turn()) {
            turn = new TurnServer(log, 64, lan, this::resolvePeer);
            InetSocketAddress bind = opt.turnListen() != null ? opt.turnListen() : new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
            int port;
            try {
                port = turn.listen(bind);
            } catch (IOException e) {
                log.info(e.getMessage());
                throw new LiteTunnel.Failure("turnBind");
            }
            JsonObject tp = Json.obj("turnPort");
            tp.addProperty("port", port);
            out.emit(tp);
            if (opt.turnListen() != null) log.info("TURN: listening on " + Wire.addr(new InetSocketAddress(bind.getAddress(), port)) + " (direct)");
            turnPort = opt.turnListen() != null ? port : opt.port();
        }

        Thread.ofPlatform().daemon().name("blitzlink-relay-link").start(this::relayLink);
        if (opt.upnp()) Thread.ofPlatform().daemon().name("blitzlink-upnp").start(this::upnp);
        brain.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
        if (!stunServers.isEmpty()) scheduleStun(publicAddr != null ? STUN_EVERY_MS : STUN_RETRY_MS);
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void control(JsonObject m) {
        String t = Json.str(m, "t");
        if (t == null) return;
        switch (t) {
            case "listing" -> {
                JsonObject body = m.has("listing") && m.get("listing").isJsonObject() ? m.getAsJsonObject("listing") : m;
                listing = Json.listing(body);
            }
            case "public" -> {
                JsonElement v = m.has("value") ? m.get("value") : m.get("public");
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) isPublic = v.getAsBoolean();
                else log.info("unreadable control line (`public` without `value`)");
            }
            case "meta" -> {
                JsonElement i = m.get("info");
                if (i == null) {
                    log.info("unreadable control line (`meta` without `info`)");
                    return;
                }
                brain.execute(() -> setInfo(i));
            }
            case "turnPeer" -> {
                long port = Json.num(m, "port", -1);
                String addr = Json.str(m, "addr");
                if (port < 0 || port > 65535 || addr == null) {
                    log.info("unreadable control line (bad `turnPeer`)");
                    return;
                }
                if (turnPeers.size() > 4096) turnPeers.clear();
                turnPeers.put((int) port, addr);
            }
            case "quit" -> stop();
            default -> log.info("unreadable control line (unknown message `" + t + "`)");
        }
    }

    @Override
    public void stop() {
        if (stopped) return;
        stopped = true;
        CountDownLatch finished = new CountDownLatch(1);
        brain.execute(() -> {
            try {
                shutdown();
            } finally {
                finished.countDown();
            }
        });
        try {
            finished.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        brain.shutdownNow();
        done.countDown();
    }

    private void shutdown() {
        for (HostPeer p : new ArrayList<>(peers.values())) {
            for (Pipe pipe : new ArrayList<>(p.pipes)) pipe.closeAll();
            // Tells the joiner the session is closing (SCTP/DTLS), so it reports `closed` right away.
            p.rtc.close();
        }
        peers.clear();
        RelaySocket ws = relaySocket;
        if (ws != null) ws.close();
        if (turn != null) turn.close();
        Upnp.Mapped m = mapped;
        if (m != null) m.close();
    }

    // ---- offers ----

    /** Called by the relay link for every offer: the answer SDP plus TURN details, or a refusal reason. */
    CompletableFuture<JsonObject> onOffer(String sdp) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        brain.execute(() -> accept(sdp, f));
        return f;
    }

    private void accept(String sdp, CompletableFuture<JsonObject> reply) {
        long pending = peers.values().stream().filter(p -> !p.opened).count();
        if (stopped || pending >= MAX_PENDING || peers.size() >= MAX_PEERS) {
            reply.completeExceptionally(new IllegalStateException("busy"));
            return;
        }
        Rtc.Peer rtc;
        try {
            rtc = new Rtc.Peer(factory, Rtc.config(Rtc.stunServers(opt.stun()), false));
        } catch (RuntimeException e) {
            reply.completeExceptionally(e);
            return;
        }
        HostPeer hp = new HostPeer(nextPeer++, rtc);
        peers.put(hp.id, hp);
        rtc.listen(new Rtc.Peer.Events() {
            @Override
            public void connection(RTCPeerConnectionState s) {
                brain.execute(() -> onConnection(hp, s));
            }

            @Override
            public void ice(RTCIceConnectionState s) {
                brain.execute(() -> onIce(hp, s));
            }

            @Override
            public void channel(RTCDataChannel dc) {
                brain.execute(() -> onChannel(hp, dc));
            }

            @Override
            public void selectedPair(String address, int port, String type) {
                InetSocketAddress a = Wire.parseAddr((address.contains(":") ? "[" + address + "]" : address) + ":" + port);
                if (a != null) hp.remote = a;
            }

            @Override
            public void localCandidate(String candidateSdp) {
                InetSocketAddress c = Rtc.hostCandidate(candidateSdp);
                if (c != null) brain.execute(() -> {
                    hp.candidates.add(c);
                    localCandidates.add(c);
                });
            }
        });
        rtc.setRemote(RTCSdpType.OFFER, sdp)
                .thenComposeAsync(v -> rtc.createAnswer(), ASYNC)
                .thenComposeAsync(rtc::setLocal, ASYNC)
                .thenComposeAsync(v -> rtc.localSdpAfterGathering(rtc.gathered, GATHER_MS), ASYNC)
                .whenCompleteAsync((answer, err) -> {
                    if (err != null) {
                        brain.execute(() -> closePeer(hp));
                        reply.completeExceptionally(err.getCause() != null ? err.getCause() : err);
                        return;
                    }
                    JsonObject a = new JsonObject();
                    a.addProperty("sdp", answer);
                    JsonObject t = turnInfo();
                    if (t != null) a.add("turn", t);
                    reply.complete(a);
                }, ASYNC);
    }

    /** The embedded TURN for the answer — public IP (if STUN answered) and LAN IP, the game (or turn-listen) port. */
    private JsonObject turnInfo() {
        if (turn == null) return null;
        Set<String> ips = new LinkedHashSet<>();
        InetSocketAddress p = publicAddr;
        if (p != null) ips.add(p.getAddress().getHostAddress());
        if (lan != null) ips.add(lan.getHostAddress());
        if (ips.isEmpty()) ips.add("127.0.0.1");
        JsonArray urls = new JsonArray();
        ips.stream().limit(2).forEach(ip -> urls.add("turn:" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + turnPort + "?transport=tcp"));
        String[] cred = turn.credentials(TURN_TTL_S);
        JsonObject t = new JsonObject();
        t.add("urls", urls);
        t.addProperty("username", cred[0]);
        t.addProperty("credential", cred[1]);
        return t;
    }

    /** TURN may only reach this host's own WebRTC sockets: a named candidate, or another one on the same port. */
    private InetSocketAddress resolvePeer(InetSocketAddress to) {
        if (localCandidates.contains(to)) return to;
        for (InetSocketAddress c : localCandidates) {
            if (c.getPort() == to.getPort()) return c;
        }
        return null;
    }

    // ---- peer events (brain) ----

    private void onConnection(HostPeer hp, RTCPeerConnectionState s) {
        if (!peers.containsKey(hp.id)) return;
        switch (s) {
            case FAILED, CLOSED -> closePeer(hp);
            case DISCONNECTED -> {
                if (hp.disconnectedAt == null) hp.disconnectedAt = System.currentTimeMillis();
            }
            case CONNECTED -> hp.disconnectedAt = null;
            default -> {}
        }
    }

    private void onIce(HostPeer hp, RTCIceConnectionState s) {
        if (!peers.containsKey(hp.id)) return;
        switch (s) {
            case DISCONNECTED -> {
                if (hp.disconnectedAt == null) hp.disconnectedAt = System.currentTimeMillis();
            }
            case CONNECTED, COMPLETED -> hp.disconnectedAt = null;
            default -> {}
        }
    }

    private void onChannel(HostPeer hp, RTCDataChannel dc) {
        Rtc.Channel ch = new Rtc.Channel(dc);
        if (!peers.containsKey(hp.id)) {
            ch.close();
            return;
        }
        if ("meta".equals(ch.label)) {
            hp.meta = ch;
            ch.start(b -> brain.execute(() -> onMeta(hp, b)), () -> brain.execute(() -> onMetaOpen(hp)), () -> brain.execute(() -> closePeer(hp)));
        } else if (ch.label.startsWith("mc-") && hp.opened) {
            Pipe[] holder = new Pipe[1];
            Pipe pipe = new Pipe(ch, port -> brain.execute(() -> onPipeDone(hp, holder[0], port)));
            holder[0] = pipe;
            hp.pipes.add(pipe);
            ch.start(pipe::inbound, () -> ASYNC.execute(() -> connectTarget(hp, pipe)), pipe::channelClosed);
        } else {
            ch.close();
        }
    }

    private void onMetaOpen(HostPeer hp) {
        if (hp.opened || !peers.containsKey(hp.id)) return;
        hp.opened = true;
        log.info("peer connected (" + (hp.remote == null ? "?" : Wire.addr(hp.remote)) + ")");
    }

    private void onMeta(HostPeer hp, byte[] data) {
        if (data.length > 256 * 1024 || hp.meta == null) return;
        JsonObject m;
        try {
            m = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        switch (Objects.requireNonNullElse(Json.str(m, "t"), "")) {
            case "probe" -> {
                byte[] nonce = Wire.unhex(Json.str(m, "nonce"));
                if (nonce == null || nonce.length != 32) return;
                JsonObject r = Json.obj("info");
                r.addProperty("key", Wire.hex(identity.publicKey()));
                r.addProperty("sig", Wire.hex(identity.proof(nonce)));
                r.add("info", info);
                hp.meta.sendText(r.toString());
            }
            case "ping" -> {
                JsonObject r = Json.obj("pong");
                r.add("n", Json.raw(m.get("n")));
                hp.meta.sendText(r.toString());
            }
            default -> {}
        }
    }

    private void setInfo(JsonElement i) {
        info = i;
        JsonObject msg = Json.obj("info");
        msg.add("info", i);
        String text = msg.toString();
        for (HostPeer p : peers.values()) {
            if (p.meta != null && p.meta.isOpen()) p.meta.sendText(text);
        }
    }

    /** Dials the Minecraft server for one {@code mc-N} channel (socket thread). */
    private void connectTarget(HostPeer hp, Pipe pipe) {
        Socket s = new Socket();
        try {
            s.connect(opt.target(), 5000);
            InetSocketAddress real = realAddress(hp.remote);
            if (opt.proxyProtocol()) {
                OutputStream os = s.getOutputStream();
                os.write(ProxyV2.header(real, opt.target()));
                os.flush();
            }
            // Before any byte reaches the server: the mod maps 127.0.0.1:<localPort> to the player's real address.
            JsonObject p = Json.obj("peer");
            p.addProperty("localPort", s.getLocalPort());
            p.addProperty("addr", real == null ? "unknown" : Wire.addr(real));
            out.emit(p);
            pipe.attach(s, null);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            pipe.closeAll();
        }
    }

    /** The player's address: the selected pair's remote, or — through our TURN — that allocation's TCP peer. */
    private InetSocketAddress realAddress(InetSocketAddress remote) {
        if (remote == null) return null;
        if (turn != null && isOwnAddress(remote.getAddress())) {
            InetSocketAddress tcp = turn.tcpPeerOfRelayPort(remote.getPort());
            if (tcp != null) {
                if (tcp.getAddress().isLoopbackAddress()) {
                    InetSocketAddress mapped = Wire.parseAddr(turnPeers.get(tcp.getPort()));
                    if (mapped != null) return mapped;
                }
                return tcp;
            }
        }
        return remote;
    }

    private static boolean isOwnAddress(InetAddress ip) {
        if (ip.isLoopbackAddress() || ip.isAnyLocalAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(ip) != null;
        } catch (SocketException e) {
            return false;
        }
    }

    private void onPipeDone(HostPeer hp, Pipe pipe, int localPort) {
        hp.pipes.remove(pipe);
        if (localPort != 0) {
            JsonObject c = Json.obj("peerClosed");
            c.addProperty("localPort", localPort);
            out.emit(c);
        }
    }

    private void closePeer(HostPeer hp) {
        if (peers.remove(hp.id) == null) return;
        for (Pipe p : new ArrayList<>(hp.pipes)) p.closeAll();
        localCandidates.removeAll(hp.candidates);
        if (hp.opened) log.info("peer disconnected (" + (hp.remote == null ? "?" : Wire.addr(hp.remote)) + ")");
        ASYNC.execute(hp.rtc::close);
    }

    // ---- timers (brain) ----

    private void tick() {
        long now = System.currentTimeMillis();
        for (HostPeer p : new ArrayList<>(peers.values())) {
            boolean stale = !p.opened && now - p.created > CONNECT_TIMEOUT_MS;
            boolean gone = p.disconnectedAt != null && now - p.disconnectedAt > DISCONNECT_GRACE_MS;
            if (stale || gone) closePeer(p);
        }
        report();
    }

    private void report() {
        JsonObject s = Json.obj("status");
        s.addProperty("state", "online".equals(relayState) ? "online" : "reconnecting");
        s.addProperty("code", relayCode);
        s.addProperty("public", isPublic);
        s.addProperty("udpPort", udpPort());
        s.addProperty("peers", peers.values().stream().filter(p -> p.opened).count());
        s.addProperty("tunnels", peers.values().stream().mapToInt(p -> p.pipes.size()).sum());
        s.addProperty("turnAllocs", turn == null ? 0 : turn.active());
        if (relayReason != null) s.addProperty("reason", relayReason);
        InetSocketAddress p = publicAddr;
        if (p != null) s.addProperty("publicAddr", Wire.addr(p));
        String u = upnpState;
        if (u.startsWith("ok") || u.equals("failed")) s.addProperty("upnp", u.startsWith("ok"));
        if (!s.equals(lastStatus)) {
            lastStatus = s;
            out.emit(s);
        }
    }

    /** libwebrtc picks a UDP port per connection; report the newest connection's LAN port (0 before the first one). */
    private int udpPort() {
        int port = 0;
        for (HostPeer p : peers.values()) {
            for (InetSocketAddress c : p.candidates) port = c.getPort();
        }
        return port;
    }

    private void scheduleStun(long delayMs) {
        brain.schedule(() -> ASYNC.execute(() -> {
            if (stopped) return;
            InetSocketAddress a = Stun.publicAddress(stunServers, 700);
            if (a != null && (publicAddr == null || !a.getAddress().equals(publicAddr.getAddress()))) {
                log.info("STUN: public address " + a.getAddress().getHostAddress());
            }
            if (a != null) publicAddr = a;
            if (!stopped) scheduleStun(a != null ? STUN_EVERY_MS : STUN_RETRY_MS);
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    // ---- relay link (its own thread) ----

    private static final class Retry extends Exception {
        Retry(String m) {
            super(m, null, false, false);
        }
    }

    private static final class Fatal extends Exception {
        Fatal(String m) {
            super(m, null, false, false);
        }
    }

    private void relayLink() {
        // Wait for the mod's first `listing` (up to 5 s; later ones arrive as `update`).
        for (int i = 0; i < 50 && listing == null && !stopped; i++) sleep(100);
        long backoff = 2000;
        while (!stopped) {
            relayState = "connecting";
            boolean[] wasOnline = new boolean[1];
            try {
                session(wasOnline);
                return;
            } catch (Fatal f) {
                log.info("relay: " + f.getMessage() + ", giving up");
                JsonObject e = Json.obj("error");
                e.addProperty("reason", f.getMessage());
                out.emit(e);
                relayState = "failed";
                relayReason = f.getMessage();
                return;
            } catch (Retry r) {
                if (stopped) return;
                log.info("relay: " + r.getMessage() + ", retrying in " + backoff / 1000 + "s");
                relayState = "retrying";
                relayReason = r.getMessage();
                if (wasOnline[0]) backoff = 2000;
            } finally {
                RelaySocket ws = relaySocket;
                if (ws != null) ws.close();
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private JsonObject currentListing() {
        JsonObject l = listing;
        return l != null ? l : Json.defaultListing();
    }

    private void session(boolean[] wasOnline) throws Retry, Fatal {
        RelaySocket ws;
        try {
            ws = RelaySocket.connect(NetConfig.wsUrl(opt.relay()), 10_000);
        } catch (IOException e) {
            throw new Retry("cannot connect (" + e.getMessage() + ")");
        }
        relaySocket = ws;
        try {
            JsonObject hello = next(ws, 10_000);
            if (!"hello".equals(Json.str(hello, "t"))) throw new Retry("no hello");
            if (Json.num(hello, "relay", -1) != Wire.RELAY_PROTOCOL) throw new Fatal("version");
            byte[] nonce = Wire.unhex(Json.str(hello, "nonce"));
            if (nonce == null || nonce.length != 32) throw new Retry("unreadable message (nonce)");

            JsonObject sent = currentListing();
            boolean sentPublic = isPublic;
            String want = readCode();
            JsonObject host = Json.obj("host");
            host.addProperty("relay", Wire.RELAY_PROTOCOL);
            host.addProperty("key", Wire.hex(identity.publicKey()));
            host.addProperty("sig", Wire.hex(identity.relaySig(nonce)));
            host.add("listing", sent);
            host.addProperty("public", sentPublic);
            if (want != null) host.addProperty("code", want);
            else host.add("code", JsonNull.INSTANCE);
            send(ws, host);

            JsonObject reply = next(ws, 10_000);
            String t = Json.str(reply, "t");
            if ("error".equals(t)) {
                String reason = Objects.requireNonNullElse(Json.str(reply, "reason"), "badMessage");
                if (reason.equals("badSignature") || reason.equals("version")) throw new Fatal(reason);
                throw new Retry(reason);
            }
            if (!"hosted".equals(t)) throw new Retry("unexpected reply");
            String code = Json.str(reply, "code");
            String fingerprint = Json.str(reply, "fingerprint");
            writeCode(code);
            log.info("relay: join code " + code + (sentPublic ? " (public)" : ""));
            JsonObject hosted = Json.obj("hosted");
            hosted.addProperty("code", code);
            hosted.addProperty("fingerprint", fingerprint);
            out.emit(hosted);
            relayCode = code;
            relayState = "online";
            relayReason = null;
            wasOnline[0] = true;

            long nextRefresh = System.currentTimeMillis() + 5000;
            long nextPing = System.currentTimeMillis() + 20_000;
            long lastText = System.currentTimeMillis();
            while (true) {
                JsonObject m;
                try {
                    m = ws.next(500);
                } catch (IOException e) {
                    throw new Retry("connection to relay lost");
                }
                if (stopped) return;
                if (m != null && "offer".equals(Json.str(m, "t"))) {
                    JsonElement session = Json.raw(m.get("session"));
                    String sdp = Objects.requireNonNullElse(Json.str(m, "sdp"), "");
                    onOffer(sdp).whenCompleteAsync((answer, err) -> {
                        JsonObject msg;
                        if (err == null) {
                            msg = Json.obj("answer");
                            msg.add("session", session);
                            msg.add("sdp", answer.get("sdp"));
                            if (answer.has("turn")) msg.add("turn", answer.get("turn"));
                        } else {
                            Throwable c = err.getCause() != null ? err.getCause() : err;
                            log.info("refused a WebRTC offer (" + c.getMessage() + ")");
                            msg = Json.obj("refuse");
                            msg.add("session", session);
                        }
                        try {
                            ws.send(msg);
                        } catch (IOException ignored) {
                            // the relay link reconnects on its own
                        }
                    }, ASYNC);
                }
                long now = System.currentTimeMillis();
                if (now >= nextRefresh) {
                    nextRefresh = now + 5000;
                    JsonObject l = currentListing();
                    boolean p = isPublic;
                    // The relay drops a host after 75 s without a text message (pings do not count), so a quiet
                    // server repeats its unchanged listing once a minute instead of reconnecting every 75 s.
                    if (!l.equals(sent) || p != sentPublic || now - lastText >= 60_000) {
                        lastText = now;
                        JsonObject u = Json.obj("update");
                        u.add("listing", l);
                        if (p != sentPublic) u.addProperty("public", p);
                        sent = l;
                        sentPublic = p;
                        send(ws, u);
                    }
                }
                if (now >= nextPing) {
                    nextPing = now + 20_000;
                    try {
                        ws.ping();
                    } catch (IOException e) {
                        throw new Retry(e.getMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Retry("interrupted");
        }
    }

    private static JsonObject next(RelaySocket ws, long ms) throws Retry, InterruptedException {
        try {
            JsonObject m = ws.next(ms);
            if (m == null) throw new Retry("no answer");
            return m;
        } catch (IOException e) {
            throw new Retry("closed");
        }
    }

    private static void send(RelaySocket ws, JsonObject m) throws Retry {
        try {
            ws.send(m);
        } catch (IOException e) {
            throw new Retry(e.getMessage());
        }
    }

    private String readCode() {
        try {
            String s = Files.readString(opt.codeFile(), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCode(String code) {
        try {
            Path dir = opt.codeFile().toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Files.writeString(opt.codeFile(), code + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    // ---- UPnP ----

    private void upnp() {
        List<Integer> tcp = new ArrayList<>(List.of(opt.port()));
        if (opt.turnListen() != null && turnPort != opt.port()) tcp.add(turnPort);
        try {
            Upnp.Mapped m = Upnp.open(tcp, log);
            mapped = m;
            upnpState = "ok " + (m.external() == null ? "?" : m.external());
            while (!stopped) {
                for (int i = 0; i < 50 * 60 && !stopped; i++) sleep(1000);
                if (!stopped) m.renew();
            }
        } catch (IOException e) {
            log.info("UPnP: " + e.getMessage() + ", relying on hole punching and TURN");
            upnpState = "failed";
        }
    }

    // ---- helpers ----

    /** This PC's outbound LAN address (no packet is sent; the OS only picks a route). */
    static InetAddress primaryIpv4() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress a = s.getLocalAddress();
            return a instanceof Inet4Address && !a.isAnyLocalAddress() ? a : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
