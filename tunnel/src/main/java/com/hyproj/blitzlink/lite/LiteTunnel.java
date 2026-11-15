package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import dev.onvoid.webrtc.PeerConnectionFactory;

/**
 * BlitzLink Lite's tunnel: the in-process stand-in for native BlitzLink's {@code blitzlink-tunnel} sidecar. It takes the
 * sidecar's command line ({@code host …} / {@code join …}, docs/en/PROTOCOL.md) and produces the very same JSON events,
 * so the mod and the Bukkit plugin drive it exactly like the native one — only no executable is unpacked or started.
 *
 * <p>Events are delivered in order on one dispatcher thread; callers hop to the game thread themselves.
 */
public final class LiteTunnel implements AutoCloseable {
    /** Where events go (one JSON object per sidecar stdout line). */
    @FunctionalInterface
    interface Emitter {
        void emit(JsonObject event);
    }

    /** A host or join session. {@link #run()} blocks until it ends. */
    interface Engine {
        void run() throws Failure;

        void control(JsonObject message);

        void stop();
    }

    /** Ends a session with an {@code error{reason}} (the sidecar's exit code 1). */
    static final class Failure extends Exception {
        Failure(String reason) {
            super(reason, null, false, false);
        }
    }

    private static final Set<LiteTunnel> LIVE = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> LIVE.forEach(LiteTunnel::close), "blitzlink-tunnel-reaper"));
    }

    private final String label;
    private final Engine engine;
    private final ExecutorService dispatch;
    private final Runnable onExit;
    private volatile boolean closed;
    private volatile boolean alive = true;

    private LiteTunnel(String label, Engine engine, ExecutorService dispatch, Runnable onExit) {
        this.label = label;
        this.engine = engine;
        this.dispatch = dispatch;
        this.onExit = onExit;
    }

    /**
     * Starts {@code mode} ("host"/"join") with the sidecar's arguments. {@code workDir} holds relative paths and the
     * unpacked libwebrtc ({@code natives/}). {@code onExit} runs when the session ends by itself (not after {@link #close()}).
     */
    public static LiteTunnel start(String mode, List<String> args, Path workDir, Consumer<JsonObject> onEvent, Runnable onExit, Log log)
            throws IOException {
        ExecutorService dispatch = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blitzlink-" + mode + "-events");
            t.setDaemon(true);
            return t;
        });
        Emitter emitter = e -> dispatch.execute(() -> {
            try {
                onEvent.accept(e);
            } catch (RuntimeException ex) {
                log.info("event handler failed: " + ex);
            }
        });
        Path nativeDir = workDir.resolve("natives");
        Engine engine = switch (mode) {
            case "host" -> new Host(hostOptions(args, workDir), emitter, log, nativeDir);
            case "join" -> new Join(joinOptions(args), emitter, log, nativeDir);
            default -> throw new IOException("unknown mode " + mode);
        };
        LiteTunnel t = new LiteTunnel("tunnel-" + mode, engine, dispatch, onExit);
        LIVE.add(t);
        Thread.ofPlatform().daemon().name("blitzlink-" + mode).start(() -> t.runEngine(emitter, log));
        return t;
    }

    private void runEngine(Emitter emitter, Log log) {
        try {
            engine.run();
        } catch (Failure f) {
            log.info("exiting: " + f.getMessage());
            JsonObject e = Json.obj("error");
            e.addProperty("reason", f.getMessage());
            emitter.emit(e);
        } catch (RuntimeException | LinkageError e) {
            log.info("exiting: " + e);
            JsonObject ev = Json.obj("error");
            ev.addProperty("reason", "internal");
            emitter.emit(ev);
        } finally {
            alive = false;
            LIVE.remove(this);
            dispatch.execute(() -> {
                if (!closed) onExit.run();
            });
            dispatch.shutdown();
        }
    }

    /** One control message ({@code listing}, {@code meta}, {@code public}, {@code turnPeer}, {@code quit}). */
    public void send(String type, JsonObject body) {
        if (closed || !alive) return;
        JsonObject o = body == null ? new JsonObject() : body.deepCopy();
        o.addProperty("t", type);
        engine.control(o);
    }

    public boolean isAlive() {
        return !closed && alive;
    }

    public String label() {
        return label;
    }

    /** Like {@code quit}: the peers are told the session is closing, then it ends. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        LIVE.remove(this);
        engine.stop();
    }

    // ---- libwebrtc ----

    private static volatile Throwable loadFailure;

    /** The process-wide factory, or {@code webrtcUnavailable} when libwebrtc cannot load on this machine. */
    static PeerConnectionFactory factory(Path nativeDir, Log log) throws Failure {
        if (loadFailure != null) throw new Failure("webrtcUnavailable");
        try {
            return Rtc.factory(nativeDir);
        } catch (Throwable t) {
            loadFailure = t;
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log.info("libwebrtc could not be loaded on " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ": " + c);
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
                log.info("on a headless Linux server, install the X11/GLib libraries libwebrtc links against, e.g. "
                        + "`apt install libx11-6 libxext6 libxfixes3 libxdamage1 libxtst6 libxrandr2 libxcomposite1 libglib2.0-0 libgbm1 libdrm2` "
                        + "(or use native BlitzLink, which has no such dependency)");
            }
            throw new Failure("webrtcUnavailable");
        }
    }

    // ---- the sidecar's command line ----

    static final class Args {
        private final List<String> args;
        private final List<String> used = new ArrayList<>();

        Args(List<String> args) {
            this.args = args;
        }

        String value(String flag) throws IOException {
            String v = null;
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals(flag)) {
                    if (i + 1 >= args.size()) throw new IOException(flag + " needs a value");
                    v = args.get(i + 1);
                } else if (args.get(i).startsWith(flag + "=")) {
                    v = args.get(i).substring(flag.length() + 1);
                }
            }
            return v;
        }

        List<String> values(String flag) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals(flag) && i + 1 < args.size()) out.add(args.get(++i));
                else if (args.get(i).startsWith(flag + "=")) out.add(args.get(i).substring(flag.length() + 1));
            }
            return out;
        }

        boolean flag(String flag) {
            return args.contains(flag);
        }
    }

    /** {@code --relay}/{@code --stun} or the jar's net.json; a placeholder relay is {@code notConfigured}. */
    static String[] net(Args a, List<String> stunOut) throws IOException {
        NetConfig.Defaults d = NetConfig.defaults();
        List<String> stun = new ArrayList<>();
        for (String s : a.values("--stun")) {
            for (String p : s.split(",")) {
                if (!p.isBlank()) stun.add(p.trim());
            }
        }
        if (stun.isEmpty()) stun.addAll(d.stun());
        for (String s : stun) {
            if (!NetConfig.isPlaceholder(s)) stunOut.add(s);
        }
        String relay = a.value("--relay");
        if (relay == null) relay = d.relay();
        if (NetConfig.isPlaceholder(relay)) throw new IOException("notConfigured");
        return new String[] {relay};
    }

    static Host.Options hostOptions(List<String> raw, Path workDir) throws IOException {
        Args a = new Args(raw);
        List<String> stun = new ArrayList<>();
        String relay = net(a, stun)[0];
        String target = a.value("--target");
        String port = a.value("--port");
        if (target == null || port == null) throw new IOException("host needs --target and --port");
        InetSocketAddress tgt = Wire.parseAddr(target);
        if (tgt == null) throw new IOException("bad --target " + target);
        InetSocketAddress turnListen = null;
        String tl = a.value("--turn-listen");
        if (tl != null) {
            turnListen = Wire.parseAddr(tl);
            if (turnListen == null) throw new IOException("bad --turn-listen " + tl);
        }
        String key = a.value("--key-file");
        String code = a.value("--code-file");
        return new Host.Options(tgt, Integer.parseInt(port), relay, stun,
                workDir.resolve(key == null ? "blitzlink-host.key" : key),
                workDir.resolve(code == null ? "blitzlink-code.txt" : code),
                a.flag("--public"), a.flag("--upnp"), !a.flag("--no-turn"), turnListen, a.flag("--proxy-protocol"));
    }

    static Join.Options joinOptions(List<String> raw) throws IOException {
        Args a = new Args(raw);
        List<String> stun = new ArrayList<>();
        String relay = net(a, stun)[0];
        String code = a.value("--code");
        if (code == null) throw new IOException("join needs --code");
        return new Join.Options(code, relay, stun, a.flag("--turn-only"));
    }
}
