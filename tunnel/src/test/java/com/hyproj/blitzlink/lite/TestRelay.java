package com.hyproj.blitzlink.lite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A minimal stand-in for {@code blitzlink-relay} (WebSocket {@code /ws} only) so the tunnel tests need no Rust: hello,
 * host registration with the signature check, join → offer → answer, and the TURN details passed through. The real relay
 * (native/blitzlink-relay in HY-Project/BlitzLinkMC) is used by {@link InteropTest}.
 */
final class TestRelay implements AutoCloseable {
    private final ServerSocket server;
    private final Map<String, Conn> hosts = new ConcurrentHashMap<>();
    private final Map<Long, Conn> joins = new ConcurrentHashMap<>();
    private final AtomicLong sessions = new AtomicLong(1);

    TestRelay() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread.ofVirtual().start(() -> serve(s));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private final class Conn {
        final Socket s;
        final OutputStream out;
        String fingerprint;
        JsonObject listing;

        Conn(Socket s) throws IOException {
            this.s = s;
            this.out = s.getOutputStream();
        }

        synchronized void send(JsonObject o) {
            try {
                byte[] p = o.toString().getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write(0x81);
                if (p.length < 126) f.write(p.length);
                else {
                    f.write(126);
                    f.write(p.length >> 8);
                    f.write(p.length);
                }
                f.write(p);
                out.write(f.toByteArray());
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private void serve(Socket s) {
        try (s) {
            InputStream in = s.getInputStream();
            String key = null;
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    String l = line.toString().trim();
                    line.setLength(0);
                    if (l.isEmpty()) break;
                    if (l.toLowerCase().startsWith("sec-websocket-key:")) key = l.substring(18).trim();
                } else {
                    line.append((char) c);
                }
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            Conn conn = new Conn(s);
            conn.out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] nonce = Wire.random(32);
            JsonObject hello = Json.obj("hello");
            hello.addProperty("relay", 1);
            hello.addProperty("nonce", Wire.hex(nonce));
            conn.send(hello);
            String role = null;
            while (true) {
                String text = readText(in, conn);
                if (text == null) return;
                JsonObject m = JsonParser.parseString(text).getAsJsonObject();
                switch (Json.str(m, "t")) {
                    case "host" -> {
                        byte[] k = Wire.unhex(Json.str(m, "key"));
                        if (!Identity.verifyRelay(k, nonce, Wire.unhex(Json.str(m, "sig")))) {
                            JsonObject e = Json.obj("error");
                            e.addProperty("reason", "badSignature");
                            conn.send(e);
                            return;
                        }
                        role = "host";
                        String code = Json.str(m, "code");
                        if (code == null) code = "MCJE-" + "23456789ABCDEFGHJKMNPQRSTVWXYZ".charAt((int) (Math.random() * 30)) + "K3Q";
                        conn.fingerprint = Identity.fingerprint(k);
                        conn.listing = m.getAsJsonObject("listing");
                        hosts.put(code, conn);
                        JsonObject h = Json.obj("hosted");
                        h.addProperty("code", code);
                        h.addProperty("fingerprint", conn.fingerprint);
                        conn.send(h);
                    }
                    case "update" -> conn.listing = m.getAsJsonObject("listing");
                    case "answer", "refuse" -> {
                        Conn j = joins.remove(m.get("session").getAsLong());
                        if (j == null) continue;
                        if (Json.str(m, "t").equals("refuse")) {
                            JsonObject e = Json.obj("error");
                            e.addProperty("reason", "refused");
                            j.send(e);
                            continue;
                        }
                        JsonObject a = Json.obj("answer");
                        a.add("sdp", m.get("sdp"));
                        a.addProperty("fingerprint", conn.fingerprint);
                        a.add("listing", conn.listing);
                        if (m.has("turn")) a.add("turn", m.get("turn"));
                        j.send(a);
                    }
                    case "join" -> {
                        Conn h = hosts.get(Wire.normalizeCode(Json.str(m, "code")));
                        if (h == null) {
                            JsonObject e = Json.obj("error");
                            e.addProperty("reason", "noSuchCode");
                            conn.send(e);
                            continue;
                        }
                        long id = sessions.getAndIncrement();
                        joins.put(id, conn);
                        JsonObject o = Json.obj("offer");
                        o.addProperty("session", id);
                        o.add("sdp", m.get("sdp"));
                        h.send(o);
                    }
                    default -> {}
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** One text message from a (masked) client; null when the socket closed. Pings are answered. */
    private static String readText(InputStream in, Conn conn) throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            long len = b1 & 0x7F;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : new byte[4];
            byte[] p = in.readNBytes((int) len);
            for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
            int op = b0 & 0x0F;
            if (op == 8) return null;
            if (op == 9) {
                synchronized (conn) {
                    conn.out.write(new byte[] {(byte) 0x8A, 0});
                    conn.out.flush();
                }
                continue;
            }
            if (op == 10) continue;
            msg.write(p);
            if ((b0 & 0x80) != 0) return msg.toString(StandardCharsets.UTF_8);
        }
    }
}
