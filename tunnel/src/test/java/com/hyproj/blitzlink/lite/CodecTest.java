package com.hyproj.blitzlink.lite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** The pieces that must match native BlitzLink byte for byte. */
class CodecTest {
    @Test
    void codes() {
        assertEquals("MCJE-7K3Q", Wire.normalizeCode(" mcje-7k3q "));
        assertEquals("MCJE-7K3Q", Wire.normalizeCode("7k3q"));
        assertEquals("MCJE-7K3Q", Wire.normalizeCode("mc je - 7k 3q"));
        assertNull(Wire.normalizeCode("MCJE-7K3O"));
        assertNull(Wire.normalizeCode("ABCD-7K3Q"));
        assertNull(Wire.normalizeCode("127.0.0.1"));
    }

    @Test
    void identity() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 7);
        Identity id = Identity.fromSeed(seed);
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 3);
        byte[] sig = id.proof(nonce);
        assertTrue(Identity.verify(id.publicKey(), nonce, sig));
        assertFalse(Identity.verifyRelay(id.publicKey(), nonce, sig), "contexts differ");
        assertTrue(Identity.verifyRelay(id.publicKey(), nonce, id.relaySig(nonce)));
        assertEquals(19, id.fingerprint().length());
        assertEquals(id.fingerprint(), Identity.fingerprint(id.publicKey()));
    }

    @Test
    void urls() {
        assertEquals("wss://relay.example.org/relay/mcje/ws", NetConfig.wsUrl("https://relay.example.org/relay/mcje"));
        assertEquals("ws://127.0.0.1:1946/ws", NetConfig.wsUrl("http://127.0.0.1:1946/"));
        assertEquals("1.2.3.4:25565", NetConfig.turnHost("turn:1.2.3.4:25565?transport=tcp"));
        assertNull(NetConfig.turnHost("turn:1.2.3.4:25565?transport=udp"));
        assertEquals("relay.example.org:3478", NetConfig.stunHost("relay.example.org"));
        assertNull(NetConfig.stunHost("off"));
        assertTrue(NetConfig.isPlaceholder("https://blitzlink.example.com/relay/mcje"));
        assertFalse(NetConfig.isPlaceholder("http://127.0.0.1:1947"));
        assertFalse(NetConfig.isPlaceholder("stun:[2001:db8::1]:3478"));
    }

    @Test
    void stunAndTurnMessages() {
        byte[] tx = Wire.random(12);
        InetSocketAddress a = new InetSocketAddress("203.0.113.5", 40000);
        byte[] m = new TurnCodec.Build(TurnCodec.ALLOCATE, TurnCodec.CLASS_SUCCESS, tx)
                .addr(TurnCodec.A_RELAYED, a).u32(TurnCodec.A_LIFETIME, 600).finish(new byte[16]);
        TurnCodec.Msg p = TurnCodec.Msg.parse(m);
        assertNotNull(p);
        assertEquals(TurnCodec.ALLOCATE, p.method);
        assertEquals(TurnCodec.CLASS_SUCCESS, p.cls);
        assertEquals(a, p.xaddr(p.get(TurnCodec.A_RELAYED)));
        assertEquals(600, p.lifetime());
        assertTrue(p.integrityOk(new byte[16]));
        assertFalse(p.integrityOk(new byte[] {1, 2, 3}));
        byte[] cd = TurnCodec.channelData(0x4001, new byte[] {1, 2, 3}, 0, 3);
        assertEquals(8, cd.length);
        assertEquals(8, TurnCodec.frameLen(cd, cd.length));
        assertEquals(0x4001, TurnCodec.channelOf(cd));
        String[] cred = TurnCodec.credentials(new byte[32], 4_000_000_000L, "ab");
        assertArrayEquals(TurnCodec.longTermKey(cred[0], cred[1]), TurnCodec.keyFor(new byte[32], cred[0], 0, true));
        assertNull(TurnCodec.keyFor(new byte[32], "1:00", 5, true), "expired");
    }

    @Test
    void sdpCleaning() {
        String sdp = "v=0\r\na=candidate:1 1 udp 2122260223 192.168.0.5 50000 typ host\r\n"
                + "a=candidate:2 1 tcp 1518280447 192.168.0.5 9 typ host tcptype active\r\n";
        String c = Rtc.cleanSdp(sdp);
        assertTrue(c.contains(" udp "));
        assertFalse(c.contains(" tcp "));
        assertEquals(new InetSocketAddress("192.168.0.5", 50000),
                Rtc.hostCandidate("candidate:1 1 udp 2122260223 192.168.0.5 50000 typ host generation 0"));
        assertNull(Rtc.hostCandidate("candidate:1 1 udp 1 1.2.3.4 5 typ srflx raddr 0.0.0.0 rport 0"));
    }

    @Test
    void proxyHeader() {
        byte[] h = ProxyV2.header(new InetSocketAddress("203.0.113.5", 40000), new InetSocketAddress("127.0.0.1", 25565));
        assertEquals(28, h.length);
        assertArrayEquals(new byte[] {0x21, 0x11, 0x00, 0x0C}, Arrays.copyOfRange(h, 12, 16));
        assertArrayEquals(new byte[] {(byte) 203, 0, 113, 5}, Arrays.copyOfRange(h, 16, 20));
        assertEquals(16, ProxyV2.header(null, new InetSocketAddress("127.0.0.1", 25565)).length);
    }
}
