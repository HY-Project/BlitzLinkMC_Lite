package com.hyproj.blitzlink.lite;

/** Where the engine's human-readable log goes (the Minecraft log or the Bukkit logger). English, like native. */
@FunctionalInterface
public interface Log {
    void info(String message);

    default void warn(String message) {
        info(message);
    }
}
