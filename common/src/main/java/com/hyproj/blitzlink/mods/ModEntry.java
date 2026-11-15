package com.hyproj.blitzlink.mods;

/**
 * One loaded mod as the host advertises it over the P2P {@code meta} channel (never through hyproj).
 *
 * @param id      mod id
 * @param name    display name
 * @param version version string as the loader reports it
 * @param side    whether a joining client must have it
 */
public record ModEntry(String id, String name, String version, Side side) {
    public enum Side {
        /** Content or network payloads the client must match (registries, packets). */
        REQUIRED,
        /** Runs on both sides but a client without it can still join (libraries, tweaks). */
        OPTIONAL,
        /** Server-only (Fabric environment "server", or listed in serverOnlyMods). */
        SERVER,
        /** Client-only (Fabric environment "client"). Never required from the other side. */
        CLIENT,
        /** A Bukkit/Spigot/Paper plugin — server-side only, listed for information. */
        PLUGIN,
        /** The game, the loader and their own parts — compared through the loader/version check instead. */
        BUILTIN
    }
}
