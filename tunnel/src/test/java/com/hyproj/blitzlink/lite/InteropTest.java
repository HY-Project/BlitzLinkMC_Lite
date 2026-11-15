package com.hyproj.blitzlink.lite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Lite ↔ native BlitzLink, both directions, direct UDP and TURN, through the real {@code blitzlink-relay}.
 * Needs the native binaries: {@code -Dblitzlink.native.dir=<dir with blitzlink-relay and blitzlink-tunnel>}
 * (CI builds them from HY-Project/BlitzLinkMC; locally {@code cargo build --release} in native BlitzLink's native/).
 */
@EnabledIfSystemProperty(named = "blitzlink.native.dir", matches = ".+")
class InteropTest {
    private static Path bin;
    private static Process relay;
    private static String relayUrl;

    private static Path exe(String name) {
        boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path p = bin.resolve(name + (win ? ".exe" : ""));
        if (!Files.isExecutable(p)) throw new IllegalStateException("missing " + p);
        return p;
    }

    @BeforeAll
    static void startRelay() throws Exception {
        bin = Path.of(System.getProperty("blitzlink.native.dir"));
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        relay = new ProcessBuilder(exe("blitzlink-relay").toString(), "--listen", "127.0.0.1:" + port, "--no-stun")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        relayUrl = "http://127.0.0.1:" + port;
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("relay did not start");
    }

    @AfterAll
    static void stopRelay() throws Exception {
        if (relay != null) {
            relay.destroy();
            relay.waitFor(3, TimeUnit.SECONDS);
        }
    }

    private static Rig.Side liteJoin(String code, boolean turnOnly) {
        try {
            return Rig.lite("join", Rig.joinArgs(code, relayUrl, turnOnly), Rig.tempDir("join"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Rig.Side nativeJoin(String code, boolean turnOnly) {
        try {
            return Rig.nativeSide(exe("blitzlink-tunnel"), "join", Rig.joinArgs(code, relayUrl, turnOnly), Rig.tempDir("njoin"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void run(boolean nativeHost, boolean nativeJoin, boolean turnOnly) throws Exception {
        Path dir = Rig.tempDir("host");
        try (Rig.EchoServer echo = new Rig.EchoServer()) {
            List<String> args = Rig.hostArgs(echo.port(), relayUrl, dir);
            try (Rig.Side host = nativeHost ? Rig.nativeSide(exe("blitzlink-tunnel"), "host", args, dir) : Rig.lite("host", args, dir)) {
                String via = Rig.roundTrip(host, echo, code -> nativeJoin ? nativeJoin(code, turnOnly) : liteJoin(code, turnOnly));
                assertEquals(turnOnly ? "turn" : "udp", via);
            }
        }
    }

    @Test
    void nativeHostLiteJoinUdp() throws Exception {
        run(true, false, false);
    }

    @Test
    void nativeHostLiteJoinTurn() throws Exception {
        run(true, false, true);
    }

    @Test
    void liteHostNativeJoinUdp() throws Exception {
        run(false, true, false);
    }

    @Test
    void liteHostNativeJoinTurn() throws Exception {
        run(false, true, true);
    }

    @Test
    void liteHostLiteJoinRealRelay() throws Exception {
        run(false, false, false);
    }
}
