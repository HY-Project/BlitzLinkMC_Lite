package com.hyproj.blitzlink.lite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Lite host ↔ Lite join over real WebRTC (loopback/LAN), through the Java test relay. */
class TunnelE2eTest {
    private static Rig.Side join(String code, String relay, boolean turnOnly) {
        try {
            return Rig.lite("join", Rig.joinArgs(code, relay, turnOnly), Rig.tempDir("join"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void directUdp() throws Exception {
        Path dir = Rig.tempDir("host");
        try (TestRelay relay = new TestRelay(); Rig.EchoServer echo = new Rig.EchoServer();
                Rig.Side host = Rig.lite("host", Rig.hostArgs(echo.port(), relay.url(), dir), dir)) {
            assertEquals("udp", Rig.roundTrip(host, echo, code -> join(code, relay.url(), false)));
        }
    }

    @Test
    void turnOnly() throws Exception {
        Path dir = Rig.tempDir("host");
        try (TestRelay relay = new TestRelay(); Rig.EchoServer echo = new Rig.EchoServer();
                Rig.Side host = Rig.lite("host", Rig.hostArgs(echo.port(), relay.url(), dir), dir)) {
            assertEquals("turn", Rig.roundTrip(host, echo, code -> join(code, relay.url(), true)));
        }
    }
}
