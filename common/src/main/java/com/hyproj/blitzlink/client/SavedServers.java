package com.hyproj.blitzlink.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hyproj.blitzlink.config.BlitzConfig;

/** Favourites and recent codes ({@code config/blitzlink/servers.json}). */
public final class SavedServers {
    public static final class Saved {
        public String code;
        public String name;

        public Saved(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_RECENT = 8;
    private static SavedServers instance;

    public List<Saved> favorites = new ArrayList<>();
    public List<Saved> recent = new ArrayList<>();

    public static SavedServers get() {
        if (instance == null) {
            try {
                Path f = file();
                instance = Files.exists(f) ? GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), SavedServers.class) : null;
            } catch (Exception e) {
                instance = null;
            }
            if (instance == null) instance = new SavedServers();
            if (instance.favorites == null) instance.favorites = new ArrayList<>();
            if (instance.recent == null) instance.recent = new ArrayList<>();
        }
        return instance;
    }

    private static Path file() {
        return BlitzConfig.dir().resolve("servers.json");
    }

    public boolean isFavorite(String code) {
        return favorites.stream().anyMatch(s -> s.code.equals(code));
    }

    public void toggleFavorite(String code, String name) {
        if (!favorites.removeIf(s -> s.code.equals(code))) favorites.add(new Saved(code, name));
        save();
    }

    public void touchRecent(String code, String name) {
        recent.removeIf(s -> s.code.equals(code));
        recent.add(0, new Saved(code, name));
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        favorites.stream().filter(s -> s.code.equals(code)).forEach(s -> s.name = name);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(BlitzConfig.dir());
            Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
