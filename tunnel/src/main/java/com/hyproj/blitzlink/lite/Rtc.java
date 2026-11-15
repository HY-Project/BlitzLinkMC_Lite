package com.hyproj.blitzlink.lite;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.PortAllocatorConfig;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtcpMuxPolicy;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;

/**
 * libwebrtc through webrtc-java: one process-wide factory (no audio devices, no video), PeerConnections that carry only
 * DataChannels, and small future-based wrappers around the callback API.
 *
 * <p>Where native BlitzLink runs str0m on one UDP socket, Lite lets libwebrtc gather and run ICE itself: host candidates
 * on the LAN interfaces plus server-reflexive ones from STUN (full ICE, no trickle — every candidate goes into the SDP,
 * because native peers never trickle either). ICE-TCP is off: native peers only speak UDP, and the TURN fallback already
 * runs over TCP.
 */
final class Rtc {
    /** System property that points libwebrtc's extraction directory somewhere executable (the game directory). */
    static final String EXTRACT_DIR = "webrtc.java.extract.dir";

    private static PeerConnectionFactory factory;
    @SuppressWarnings("unused")
    private static HeadlessAudioDeviceModule audio;

    private Rtc() {}

    /** Loads libwebrtc (extracting it under {@code nativeDir} unless the property is already set) and makes the factory. */
    static synchronized PeerConnectionFactory factory(Path nativeDir) {
        if (factory != null) return factory;
        if (System.getProperty(EXTRACT_DIR) == null && nativeDir != null) {
            try {
                java.nio.file.Files.createDirectories(nativeDir);
                System.setProperty(EXTRACT_DIR, nativeDir.toAbsolutePath().toString());
            } catch (java.io.IOException ignored) {
                // falls back to the temporary directory
            }
        }
        HeadlessLinux.prepare(nativeDir);
        audio = new HeadlessAudioDeviceModule();
        factory = new PeerConnectionFactory(audio);
        return factory;
    }

    static List<RTCIceServer> stunServers(List<String> urls) {
        List<String> out = new ArrayList<>();
        for (String u : urls) {
            String hp = NetConfig.stunHost(u);
            if (hp != null) out.add("stun:" + hp);
        }
        if (out.isEmpty()) return List.of();
        RTCIceServer s = new RTCIceServer();
        s.urls = out;
        return List.of(s);
    }

    static RTCIceServer turnServer(TurnInfo t) {
        RTCIceServer s = new RTCIceServer();
        s.urls = new ArrayList<>(t.urls());
        s.username = t.username();
        s.password = t.credential();
        return s;
    }

    static RTCConfiguration config(List<RTCIceServer> servers, boolean relayOnly) {
        RTCConfiguration c = new RTCConfiguration();
        c.iceServers = new ArrayList<>(servers);
        c.iceTransportPolicy = relayOnly ? RTCIceTransportPolicy.RELAY : RTCIceTransportPolicy.ALL;
        c.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;
        c.rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE;
        PortAllocatorConfig p = new PortAllocatorConfig();
        if (!relayOnly) p.setFlag(PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP);
        c.portAllocatorConfig = p;
        return c;
    }

    /** TURN details as the relay passes them ({@code relay::TurnInfo}). */
    record TurnInfo(List<String> urls, String username, String credential) {}

    /** One PeerConnection plus the futures and events the sessions wait on. Callbacks arrive on libwebrtc's threads. */
    static final class Peer implements PeerConnectionObserver {
        interface Events {
            default void connection(RTCPeerConnectionState s) {}

            default void ice(RTCIceConnectionState s) {}

            default void channel(RTCDataChannel dc) {}

            default void selectedPair(String remoteAddress, int remotePort, String remoteType) {}

            default void localCandidate(String candidateSdp) {}
        }

        final RTCPeerConnection pc;
        final CompletableFuture<Void> gathered = new CompletableFuture<>();
        final CompletableFuture<Void> relayCandidate = new CompletableFuture<>();
        private volatile Events events = new Events() {};
        private volatile boolean closed;

        Peer(PeerConnectionFactory f, RTCConfiguration config) {
            this.pc = f.createPeerConnection(config, this);
        }

        void listen(Events e) {
            this.events = e;
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            if (candidate.sdp.contains(" typ relay")) relayCandidate.complete(null);
            events.localCandidate(candidate.sdp);
        }

        @Override
        public void onIceGatheringChange(RTCIceGatheringState state) {
            if (state == RTCIceGatheringState.COMPLETE) gathered.complete(null);
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            events.connection(state);
        }

        @Override
        public void onIceConnectionChange(RTCIceConnectionState state) {
            events.ice(state);
        }

        @Override
        public void onDataChannel(RTCDataChannel dataChannel) {
            events.channel(dataChannel);
        }

        @Override
        public void onSelectedCandidatePairChanged(String remoteAddress, int remotePort, String candidateType) {
            events.selectedPair(remoteAddress, remotePort, candidateType);
        }

        CompletableFuture<RTCSessionDescription> createOffer() {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            pc.createOffer(new RTCOfferOptions(), sdpObserver(f));
            return f;
        }

        CompletableFuture<RTCSessionDescription> createAnswer() {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            pc.createAnswer(new RTCAnswerOptions(), sdpObserver(f));
            return f;
        }

        CompletableFuture<Void> setLocal(RTCSessionDescription d) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            pc.setLocalDescription(d, setObserver(f));
            return f;
        }

        CompletableFuture<Void> setRemote(RTCSdpType type, String sdp) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            pc.setRemoteDescription(new RTCSessionDescription(type, sdp), setObserver(f));
            return f;
        }

        /** The local SDP once gathering is complete, or after {@code maxMs} with whatever was gathered by then. */
        CompletableFuture<String> localSdpAfterGathering(CompletableFuture<Void> enough, long maxMs) {
            return enough.completeOnTimeout(null, maxMs, TimeUnit.MILLISECONDS).thenApply(v -> {
                RTCSessionDescription d = pc.getLocalDescription();
                if (d == null) throw new IllegalStateException("no local description");
                return cleanSdp(d.sdp);
            });
        }

        RTCDataChannel createChannel(String label) {
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true;
            return pc.createDataChannel(label, init);
        }

        boolean isClosed() {
            return closed;
        }

        /** Closes it (the peer sees the SCTP/DTLS shutdown) — blocking, so never on a libwebrtc callback thread. */
        void close() {
            if (closed) return;
            closed = true;
            try {
                pc.close();
            } catch (RuntimeException ignored) {
            }
        }

        private static CreateSessionDescriptionObserver sdpObserver(CompletableFuture<RTCSessionDescription> f) {
            return new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    f.complete(description);
                }

                @Override
                public void onFailure(String error) {
                    f.completeExceptionally(new IllegalStateException(error));
                }
            };
        }

        private static SetSessionDescriptionObserver setObserver(CompletableFuture<Void> f) {
            return new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    f.complete(null);
                }

                @Override
                public void onFailure(String error) {
                    f.completeExceptionally(new IllegalStateException(error));
                }
            };
        }
    }

    /**
     * Keeps only what native (str0m) peers understand: UDP candidates. libwebrtc does not gather TCP ones here, but a
     * remote browser-style SDP could still carry them.
     */
    static String cleanSdp(String sdp) {
        StringBuilder out = new StringBuilder(sdp.length());
        for (String line : sdp.split("\r\n")) {
            if (line.startsWith("a=candidate:") && !isUdpCandidate(line)) continue;
            if (line.isEmpty()) continue;
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    static boolean isUdpCandidate(String line) {
        String[] f = line.split(" ");
        return f.length > 2 && f[2].equalsIgnoreCase("udp");
    }

    /** {@code candidate:… 1 udp <prio> <ip> <port> typ host …} → the host candidate's address, or null. */
    static InetSocketAddress hostCandidate(String candidateSdp) {
        String s = candidateSdp.startsWith("a=") ? candidateSdp.substring(2) : candidateSdp;
        String[] f = s.split(" ");
        if (f.length < 8 || !f[2].equalsIgnoreCase("udp") || !f[6].equals("typ") || !f[7].equals("host")) return null;
        return Wire.parseAddr((f[4].contains(":") ? "[" + f[4] + "]" : f[4]) + ":" + f[5]);
    }

    /**
     * A DataChannel with blocking helpers for the TCP pipes: wait until open, wait until the send buffer drains below a
     * mark. Messages are copied out of libwebrtc's buffer before {@code onMessage} returns.
     */
    static final class Channel implements RTCDataChannelObserver {
        final RTCDataChannel dc;
        final String label;
        private final Object lock = new Object();
        private volatile Consumer<byte[]> onMessage = b -> {};
        private volatile Runnable onOpen = () -> {};
        private volatile Runnable onClose = () -> {};
        private volatile boolean open;
        private volatile boolean closed;
        private boolean openFired;
        private boolean closeFired;

        Channel(RTCDataChannel dc) {
            this.dc = dc;
            this.label = dc.getLabel();
        }

        /** Registers the observer; fires {@code open} right away if the channel is already open. */
        Channel start(Consumer<byte[]> message, Runnable open, Runnable close) {
            this.onMessage = message;
            this.onOpen = open;
            this.onClose = close;
            dc.registerObserver(this);
            onStateChange();
            return this;
        }

        @Override
        public void onBufferedAmountChange(long previousAmount) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onStateChange() {
            RTCDataChannelState s;
            try {
                s = dc.getState();
            } catch (RuntimeException e) {
                s = RTCDataChannelState.CLOSED;
            }
            boolean fireOpen = false;
            boolean fireClose = false;
            synchronized (lock) {
                if (s == RTCDataChannelState.OPEN && !openFired) {
                    open = true;
                    openFired = fireOpen = true;
                } else if ((s == RTCDataChannelState.CLOSED || s == RTCDataChannelState.CLOSING) && !closeFired) {
                    open = false;
                    closed = true;
                    closeFired = fireClose = true;
                }
                lock.notifyAll();
            }
            if (fireOpen) onOpen.run();
            if (fireClose) onClose.run();
        }

        @Override
        public void onMessage(RTCDataChannelBuffer buffer) {
            ByteBuffer d = buffer.data;
            byte[] b = new byte[d.remaining()];
            d.get(b);
            onMessage.accept(b);
        }

        boolean isOpen() {
            return open;
        }

        boolean isClosed() {
            return closed;
        }

        /** Blocks until open (true) or closed/timeout (false). */
        boolean awaitOpen(long ms) throws InterruptedException {
            long until = System.currentTimeMillis() + ms;
            synchronized (lock) {
                while (!open && !closed) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) return false;
                    lock.wait(left);
                }
                return open;
            }
        }

        long buffered() {
            try {
                return dc.getBufferedAmount();
            } catch (RuntimeException e) {
                return 0;
            }
        }

        /** Blocks while more than {@code mark} bytes wait to be sent (false once the channel closed). */
        boolean awaitBelow(long mark, long maxMs) throws InterruptedException {
            long until = maxMs <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + maxMs;
            while (true) {
                // Asked outside the lock: the query hops to libwebrtc's thread, which may be waiting for this lock in
                // onBufferedAmountChange.
                long amount = buffered();
                if (closed) return false;
                if (amount <= mark) return true;
                long left = until - System.currentTimeMillis();
                if (left <= 0) return false;
                synchronized (lock) {
                    // onBufferedAmountChange wakes us; the short timeout covers a wake-up between the query and here.
                    lock.wait(Math.min(left, 20));
                }
            }
        }

        /** Blocking send (binary). Throws when the channel is not open. */
        void send(byte[] b, int off, int len) throws Exception {
            dc.send(new RTCDataChannelBuffer(ByteBuffer.wrap(b, off, len), true));
        }

        /** Text message without blocking — safe from any thread (meta replies). */
        void sendText(String s) {
            if (closed) return;
            try {
                dc.sendAsync(new RTCDataChannelBuffer(ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)), false));
            } catch (RuntimeException ignored) {
            }
        }

        void close() {
            try {
                dc.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
