package com.hyproj.blitzlink.lite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** JSON helpers and the {@code Listing} shape the relay expects ({@code relay::Listing}, camelCase, defaults filled in). */
final class Json {
    private Json() {}

    static JsonObject obj(String t) {
        JsonObject o = new JsonObject();
        o.addProperty("t", t);
        return o;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    static long num(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** A listing with every field present — what serde's {@code #[serde(default)]} would produce from {@code in}. */
    static JsonObject listing(JsonObject in) {
        JsonObject l = new JsonObject();
        l.addProperty("name", strOr(in, "name", ""));
        l.addProperty("motd", strOr(in, "motd", ""));
        l.addProperty("mcVersion", strOr(in, "mcVersion", ""));
        l.addProperty("protocol", Math.max(0, num(in, "protocol", 0)));
        l.addProperty("loader", strOr(in, "loader", "vanilla"));
        l.addProperty("loaderVersion", strOr(in, "loaderVersion", ""));
        l.addProperty("modCount", Math.clamp(num(in, "modCount", 0), 0, 65535));
        l.addProperty("modsHash", strOr(in, "modsHash", ""));
        l.addProperty("players", Math.max(0, num(in, "players", 0)));
        l.addProperty("maxPlayers", Math.max(0, num(in, "maxPlayers", 20)));
        l.addProperty("onlineMode", bool(in, "onlineMode", true));
        return l;
    }

    static JsonObject defaultListing() {
        JsonObject in = new JsonObject();
        in.addProperty("name", "Minecraft Server");
        return listing(in);
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        String s = str(o, key);
        return s == null ? fallback : s;
    }

    /** The relay's session id as it came (u64 — kept verbatim so no digit is lost). */
    static JsonPrimitive raw(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : new JsonPrimitive(0);
    }
}
