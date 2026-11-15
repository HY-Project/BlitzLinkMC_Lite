package com.hyproj.blitzlink.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyproj.blitzlink.config.BlitzConfig;

/** The relay's public list ({@code GET /servers}; the relay caches it for 5 s). Only small listings — never mod lists. */
public final class PublicServers {
    public record Entry(String code, String fingerprint, String name, String motd, String mcVersion, int protocol, String loader,
                        String loaderVersion, int modCount, String modsHash, int players, int maxPlayers) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    private PublicServers() {}

    public static CompletableFuture<List<Entry>> fetch() {
        if (!BlitzConfig.get().relayConfigured()) return CompletableFuture.failedFuture(new IllegalStateException("notConfigured"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(BlitzConfig.get().relayUrl() + "/servers"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            List<Entry> out = new ArrayList<>();
            if (o.has("servers")) {
                for (JsonElement el : o.getAsJsonArray("servers")) {
                    JsonObject s = el.getAsJsonObject();
                    out.add(new Entry(str(s, "code"), str(s, "fingerprint"), str(s, "name"), str(s, "motd"), str(s, "mcVersion"),
                            num(s, "protocol"), str(s, "loader"), str(s, "loaderVersion"), num(s, "modCount"), str(s, "modsHash"),
                            num(s, "players"), num(s, "maxPlayers")));
                }
            }
            return out;
        });
    }

    /** Advanced settings 「시험」: {@code GET <relay>/health} must answer {@code ok}. */
    public static CompletableFuture<Boolean> health(String relayBase) {
        String base = relayBase.endsWith("/") ? relayBase.substring(0, relayBase.length() - 1) : relayBase;
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(6)).GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200 && r.body().strip().equalsIgnoreCase("ok"))
                .exceptionally(e -> false);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
