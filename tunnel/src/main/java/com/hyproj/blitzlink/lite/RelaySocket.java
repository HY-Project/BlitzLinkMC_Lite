package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A WebSocket to the relay ({@code GET /ws}): one JSON text frame = one message, tagged by {@code t}. Sends no
 * {@code Origin} (the relay's allowlist only applies to web pages), like the native sidecar.
 */
final class RelaySocket implements AutoCloseable {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final JsonObject CLOSED = new JsonObject();

    private final LinkedBlockingQueue<JsonObject> inbox = new LinkedBlockingQueue<>();
    private final WebSocket ws;
    private volatile boolean closed;
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

    private RelaySocket(String url, long timeoutMs) throws IOException {
        try {
            this.ws = HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .buildAsync(URI.create(url), new Listener())
                    .get(timeoutMs + 1000, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("timeout");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new IOException(c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage(), c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IllegalArgumentException e) {
            throw new IOException("bad relay URL " + url);
        }
    }

    static RelaySocket connect(String url, long timeoutMs) throws IOException {
        return new RelaySocket(url, timeoutMs);
    }

    /** Next message, or null on timeout; throws once the socket closed. */
    JsonObject next(long timeoutMs) throws IOException, InterruptedException {
        JsonObject m = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (m == CLOSED) {
            inbox.add(CLOSED);
            throw new IOException("closed");
        }
        return m;
    }

    synchronized void send(JsonObject msg) throws IOException {
        if (closed) throw new IOException("closed");
        String text = msg.toString();
        try {
            sending = sending.thenCompose(v -> ws.sendText(text, true));
            sending.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("send failed: " + e.getMessage(), e);
        }
    }

    synchronized void ping() throws IOException {
        try {
            sending = sending.thenCompose(v -> ws.sendPing(ByteBuffer.allocate(0)));
            sending.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("ping failed: " + e.getMessage(), e);
        }
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(2, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
        }
        ws.abort();
        inbox.add(CLOSED);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > Wire.MAX_MESSAGE * 2) {
                partial.setLength(0);
                webSocket.abort();
            } else if (last) {
                String s = partial.toString();
                partial.setLength(0);
                try {
                    inbox.add(JsonParser.parseString(s).getAsJsonObject());
                } catch (RuntimeException e) {
                    JsonObject bad = new JsonObject();
                    bad.addProperty("t", "_unreadable");
                    inbox.add(bad);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            inbox.add(CLOSED);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            inbox.add(CLOSED);
        }
    }
}
