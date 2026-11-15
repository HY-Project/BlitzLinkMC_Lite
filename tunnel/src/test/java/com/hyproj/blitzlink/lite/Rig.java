package com.hyproj.blitzlink.lite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Test plumbing: tunnel sides (Lite in-process or a native sidecar process), an echo "Minecraft server", byte round trips. */
final class Rig {
    private Rig() {}

    /** One host or join tunnel, as the mod sees it: JSON events out, control messages in. */
    interface Side extends AutoCloseable {
        LinkedBlockingQueue<JsonObject> events();

        void send(String type, JsonObject body);

        @Override
        void close();

        /** Next event of type {@code t} within {@code ms} (fails on an {@code error} event). */
        default JsonObject await(String t, long ms) throws InterruptedException {
            long until = System.currentTimeMillis() + ms;
            while (true) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) fail("no `" + t + "` event within " + ms + " ms");
                JsonObject e = events().poll(left, TimeUnit.MILLISECONDS);
                if (e == null) continue;
                String et = Json.str(e, "t");
                if (t.equals(et)) return e;
                if ("error".equals(et)) fail("tunnel error while waiting for `" + t + "`: " + e);
            }
        }
    }

    static final Log LOG = m -> System.out.println("[lite] " + m);

    static Side lite(String mode, List<String> args, Path dir) throws IOException {
        LinkedBlockingQueue<JsonObject> q = new LinkedBlockingQueue<>();
        LiteTunnel t = LiteTunnel.start(mode, args, dir, q::add, () -> {
            JsonObject e = Json.obj("_exit");
            q.add(e);
        }, m -> System.out.println("[lite-" + mode + "] " + m));
        return new Side() {
            @Override
            public LinkedBlockingQueue<JsonObject> events() {
                return q;
            }

            @Override
            public void send(String type, JsonObject body) {
                t.send(type, body);
            }

            @Override
            public void close() {
                t.close();
            }
        };
    }

    /** The native sidecar ({@code blitzlink-tunnel[.exe]} in {@code nativeDir}). */
    static Side nativeSide(Path exe, String mode, List<String> args, Path dir) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), mode));
        cmd.addAll(args);
        cmd.add("--json");
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        LinkedBlockingQueue<JsonObject> q = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) q.add(JsonParser.parseString(line).getAsJsonObject());
                }
            } catch (IOException ignored) {
            }
            q.add(Json.obj("_exit"));
        });
        OutputStream stdin = p.getOutputStream();
        return new Side() {
            @Override
            public LinkedBlockingQueue<JsonObject> events() {
                return q;
            }

            @Override
            public void send(String type, JsonObject body) {
                JsonObject o = body == null ? new JsonObject() : body.deepCopy();
                o.addProperty("t", type);
                try {
                    stdin.write((o + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                } catch (IOException ignored) {
                }
            }

            @Override
            public void close() {
                send("quit", null);
                try {
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (InterruptedException e) {
                    p.destroyForcibly();
                }
            }
        };
    }

    /**
     * An echo "Minecraft server" that splits by first byte like the mod's TurnMux: 0x00–0x03 (STUN) is piped to the
     * host's TURN port, anything else is echoed back.
     */
    static final class EchoServer implements AutoCloseable {
        final ServerSocket ss;
        volatile int turnPort;

        EchoServer() throws IOException {
            // All interfaces: the host's TURN URL names its LAN address, like a real game port.
            ss = new ServerSocket(0, 50);
            Thread.ofVirtual().start(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket s = ss.accept();
                        Thread.ofPlatform().daemon().start(() -> handle(s));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        int port() {
            return ss.getLocalPort();
        }

        private void handle(Socket s) {
            try (s) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                int first = in.read();
                if (first < 0) return;
                if ((first & 0xFC) == 0 && turnPort > 0) {
                    try (Socket t = new Socket(InetAddress.getLoopbackAddress(), turnPort)) {
                        t.getOutputStream().write(first);
                        // Platform threads: a virtual-thread pipe stalled under load on Windows (see Host.ASYNC).
                        Thread a = Thread.ofPlatform().daemon().start(() -> copy(in, sOut(t)));
                        copy(t.getInputStream(), out);
                        a.join();
                    }
                    return;
                }
                out.write(first);
                copy(in, out);
            } catch (IOException | InterruptedException ignored) {
            }
        }

        private static OutputStream sOut(Socket t) {
            try {
                return t.getOutputStream();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void copy(InputStream in, OutputStream out) {
            byte[] b = new byte[16384];
            try {
                int n;
                while ((n = in.read(b)) >= 0) {
                    out.write(b, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            ss.close();
        }
    }

    static JsonObject listing(String name) {
        JsonObject l = new JsonObject();
        l.addProperty("name", name);
        l.addProperty("mcVersion", "26.3");
        l.addProperty("loader", "fabric");
        l.addProperty("maxPlayers", 8);
        return l;
    }

    static List<String> hostArgs(int port, String relay, Path dir) {
        return new ArrayList<>(List.of("--target", "127.0.0.1:" + port, "--port", Integer.toString(port), "--relay", relay,
                "--stun", "off", "--key-file", dir.resolve("host.key").toString(), "--code-file", dir.resolve("code.txt").toString()));
    }

    static List<String> joinArgs(String code, String relay, boolean turnOnly) {
        List<String> a = new ArrayList<>(List.of("--code", code, "--relay", relay, "--stun", "off"));
        if (turnOnly) a.add("--turn-only");
        return a;
    }

    /**
     * Host up → join by code → meta (identity checked) → listen → 1 MB round trip through the echo server → close
     * propagation (the host reports {@code peerClosed}). Returns the join's {@code via}.
     */
    static String roundTrip(Side host, EchoServer echo, java.util.function.Function<String, Side> joiner) throws Exception {
        JsonObject info = new JsonObject();
        info.addProperty("mcVersion", "26.3");
        info.add("mods", new com.google.gson.JsonArray());
        host.send("listing", wrap("listing", listing("Rig")));
        host.send("meta", wrap("info", info));
        JsonObject turnPort = host.await("turnPort", 10_000);
        echo.turnPort = (int) Json.num(turnPort, "port", 0);
        JsonObject hosted = host.await("hosted", 20_000);
        String code = Json.str(hosted, "code");
        assertNotNull(code);
        try (Side join = joiner.apply(code)) {
            JsonObject meta = join.await("meta", 30_000);
            assertEquals(Json.str(hosted, "fingerprint"), Json.str(meta, "fingerprint"), "identity");
            assertEquals("26.3", meta.getAsJsonObject("info").get("mcVersion").getAsString());
            assertEquals("Rig", meta.getAsJsonObject("listing").get("name").getAsString());
            String via = Json.str(meta, "via");
            int port = (int) Json.num(join.await("listen", 10_000), "port", 0);
            byte[] data = new byte[1024 * 1024];
            new Random(7).nextBytes(data);
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
                s.setSoTimeout(30_000);
                Thread w = Thread.ofVirtual().start(() -> {
                    try {
                        s.getOutputStream().write(data);
                        s.getOutputStream().flush();
                    } catch (IOException ignored) {
                    }
                });
                byte[] back = s.getInputStream().readNBytes(data.length);
                w.join();
                assertArrayEquals(data, back, "1 MB round trip");
                JsonObject peer = host.await("peer", 5_000);
                assertNotNull(Json.str(peer, "addr"));
            }
            host.await("peerClosed", 15_000);
            return via;
        }
    }

    static JsonObject wrap(String key, JsonObject v) {
        JsonObject o = new JsonObject();
        o.add(key, v);
        return o;
    }

    static Path tempDir(String name) throws IOException {
        return Files.createTempDirectory("blitzlink-" + name);
    }
}
