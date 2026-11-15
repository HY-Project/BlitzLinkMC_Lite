package com.hyproj.blitzlink.mods;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hyproj.blitzlink.platform.Platform;
import net.minecraft.SharedConstants;

/**
 * What the host tells a joining client over the P2P {@code meta} channel before the Minecraft connection opens:
 * game version, loader and the full mod list. It never goes through the hyproj relay (only the small Listing does).
 */
public record ServerInfo(String name, String motd, String mcVersion, int protocol, String loader, String loaderVersion,
                         int players, int maxPlayers, boolean onlineMode, List<ModEntry> mods) {
    public static final int VERSION = 1;

    public static ServerInfo local(String name, String motd, int players, int maxPlayers, boolean onlineMode) {
        Platform p = Platform.get();
        return new ServerInfo(name, motd, SharedConstants.getCurrentVersion().name(), SharedConstants.getProtocolVersion(),
                p.loaderName(), p.loaderVersion(), players, maxPlayers, onlineMode, p.mods());
    }

    /** Stable short hash of the mod set (the relay listing carries it so the public list can show "same mods as you"). */
    public String modsHash() {
        long h = 1125899906842597L;
        for (ModEntry m : mods.stream().sorted(java.util.Comparator.comparing(ModEntry::id)).toList()) {
            if (m.side() == ModEntry.Side.SERVER || m.side() == ModEntry.Side.CLIENT) continue;
            for (char c : (m.id() + "@" + m.version() + ";").toCharArray()) h = 31 * h + c;
        }
        return String.format(Locale.ROOT, "%016x", h);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("name", name);
        o.addProperty("motd", motd);
        o.addProperty("mcVersion", mcVersion);
        o.addProperty("protocol", protocol);
        o.addProperty("loader", loader);
        o.addProperty("loaderVersion", loaderVersion);
        o.addProperty("players", players);
        o.addProperty("maxPlayers", maxPlayers);
        o.addProperty("onlineMode", onlineMode);
        JsonArray arr = new JsonArray();
        for (ModEntry m : mods) {
            JsonObject e = new JsonObject();
            e.addProperty("id", m.id());
            e.addProperty("name", m.name());
            e.addProperty("version", m.version());
            e.addProperty("side", m.side().name().toLowerCase(Locale.ROOT));
            arr.add(e);
        }
        o.add("mods", arr);
        return o;
    }

    public static ServerInfo fromJson(JsonObject o) {
        List<ModEntry> mods = new ArrayList<>();
        if (o.has("mods") && o.get("mods").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("mods")) {
                JsonObject e = el.getAsJsonObject();
                ModEntry.Side side;
                try {
                    side = ModEntry.Side.valueOf(str(e, "side", "optional").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    side = ModEntry.Side.OPTIONAL;
                }
                String id = str(e, "id", "?");
                mods.add(new ModEntry(clip(id, 64), clip(str(e, "name", id), 64), clip(str(e, "version", "?"), 48), side));
                if (mods.size() >= 2000) break;
            }
        }
        return new ServerInfo(clip(str(o, "name", ""), 48), clip(str(o, "motd", ""), 160), clip(str(o, "mcVersion", "?"), 16),
                num(o, "protocol", 0), str(o, "loader", "vanilla"), clip(str(o, "loaderVersion", ""), 24),
                num(o, "players", 0), num(o, "maxPlayers", 0), o.has("onlineMode") && o.get("onlineMode").getAsBoolean(), mods);
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }

    private static int num(JsonObject o, String k, int d) {
        try {
            return o.has(k) ? o.get(k).getAsInt() : d;
        } catch (RuntimeException e) {
            return d;
        }
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\p{Cntrl}", "");
        return t.length() <= max ? t : t.substring(0, max);
    }
}
