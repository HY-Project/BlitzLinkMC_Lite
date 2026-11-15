package com.hyproj.blitzlink.lite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Throughput on this machine (loopback/LAN, so it measures CPU cost, not an internet link): 64 MB echoed through
 * host → join for native↔native, Lite↔Lite and both mixes. Needs {@code -Pblitzlink.native.dir=…} and {@code -Pbench}.
 * Figures go to stdout; nothing is asserted beyond the bytes arriving intact.
 */
@EnabledIfSystemProperty(named = "blitzlink.bench", matches = "true")
class BenchTest {
    private static final int MB = Integer.getInteger("blitzlink.bench.mb", 64);

    private static Path exe(Path bin, String name) {
        boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return bin.resolve(name + (win ? ".exe" : ""));
    }

    @Test
    void throughput() throws Exception {
        Path bin = Path.of(System.getProperty("blitzlink.native.dir"));
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Process relay = new ProcessBuilder(exe(bin, "blitzlink-relay").toString(), "--listen", "127.0.0.1:" + port, "--no-stun")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        String url = "http://127.0.0.1:" + port;
        Thread.sleep(500);
        try {
            for (String combo : System.getProperty("blitzlink.bench.combos", "native-native,lite-lite,native-lite,lite-native").split(",")) {
                for (boolean turn : new boolean[] {false, true}) {
                    double mbps;
                    try {
                        mbps = run(bin, url, combo.startsWith("native"), combo.endsWith("native"), turn);
                    } catch (Exception | AssertionError e) {
                        System.out.printf(Locale.ROOT, "BENCH %-14s %-4s failed: %s%n", combo, turn ? "turn" : "udp", e);
                        continue;
                    }
                    System.out.printf(Locale.ROOT, "BENCH %-14s %-4s %7.1f MB/s%n", combo, turn ? "turn" : "udp", mbps);
                }
            }
        } finally {
            relay.destroy();
            relay.waitFor(3, TimeUnit.SECONDS);
        }
    }

    private static double run(Path bin, String url, boolean nativeHost, boolean nativeJoin, boolean turn) throws Exception {
        Path dir = Rig.tempDir("bench");
        try (Rig.EchoServer echo = new Rig.EchoServer()) {
            List<String> args = Rig.hostArgs(echo.port(), url, dir);
            // -Pblitzlink.bench.turnListen: TURN on its own port (as on Bukkit) instead of piped from the game port.
            if (Boolean.getBoolean("blitzlink.bench.turnListen")) args.addAll(List.of("--turn-listen", "0.0.0.0:0"));
            try (Rig.Side host = nativeHost ? Rig.nativeSide(exe(bin, "blitzlink-tunnel"), "host", args, dir) : Rig.lite("host", args, dir)) {
                host.send("listing", Rig.wrap("listing", Rig.listing("Bench")));
                host.send("meta", Rig.wrap("info", new JsonObject()));
                echo.turnPort = (int) Json.num(host.await("turnPort", 10_000), "port", 0);
                String code = Json.str(host.await("hosted", 20_000), "code");
                List<String> jargs = Rig.joinArgs(code, url, turn);
                Path jdir = Rig.tempDir("benchjoin");
                try (Rig.Side join = nativeJoin ? Rig.nativeSide(exe(bin, "blitzlink-tunnel"), "join", jargs, jdir) : Rig.lite("join", jargs, jdir)) {
                    join.await("meta", 30_000);
                    int lport = (int) Json.num(join.await("listen", 10_000), "port", 0);
                    return echoMbps(lport);
                }
            }
        }
    }

    /** Sends MB megabytes and reads them back; MB/s of payload in one direction. */
    private static double echoMbps(int port) throws Exception {
        byte[] chunk = new byte[64 * 1024];
        java.util.Arrays.fill(chunk, (byte) 0x41); // not 0x00-0x03: the echo server would take it for TURN
        long total = (long) MB * 1024 * 1024;
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(60_000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            long t0 = System.nanoTime();
            Thread w = Thread.ofVirtual().start(() -> {
                try {
                    for (long sent = 0; sent < total; sent += chunk.length) out.write(chunk);
                    out.flush();
                } catch (IOException ignored) {
                }
            });
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            try {
                while (got < total) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    got += n;
                }
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " after " + got / 1024 + " KB", e);
            }
            w.join();
            double secs = (System.nanoTime() - t0) / 1e9;
            assertEquals(total, got);
            return MB / secs;
        }
    }
}
