package com.hyproj.blitzlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.hyproj.blitzlink.code.JoinCode;
import com.hyproj.blitzlink.mods.ModCompare;
import com.hyproj.blitzlink.mods.ModEntry;
import com.hyproj.blitzlink.mods.ModEntry.Side;
import com.hyproj.blitzlink.mods.ServerInfo;
import org.junit.jupiter.api.Test;

class LogicTest {
    @Test
    void codesNormalise() {
        assertEquals(Optional.of("MCJE-7K3Q"), JoinCode.normalize(" mcje-7k3q "));
        assertEquals(Optional.of("MCJE-7K3Q"), JoinCode.normalize("7k3q"));
        assertEquals(Optional.of("MCJE-7K3Q"), JoinCode.normalize("MCJE7K3Q"));
        assertEquals(Optional.empty(), JoinCode.normalize("MCJE-7K3O"), "O is not in the alphabet");
        assertEquals(Optional.empty(), JoinCode.normalize("MCJE-7K3"));
        assertEquals(Optional.empty(), JoinCode.fromUserInput("7K3Q"), "without the prefix it could be a host name");
        assertEquals(Optional.of("MCJE-ABCD"), JoinCode.fromUserInput("mcje abcd"));
    }

    @Test
    void placeholderNetwork() {
        assertTrue(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("https://blitzlink.example.com/relay/mcje"));
        assertTrue(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("stun:blitzlink.example.com:3478"));
        assertTrue(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("http://example.org"));
        assertTrue(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("wss://relay.test.example/ws"));
        assertFalse(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("https://relay.mydomain.com/relay/mcje"));
        assertFalse(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("http://127.0.0.1:1947"));
        assertFalse(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("stun:notexample.com:3478"));
        assertFalse(com.hyproj.blitzlink.config.BlitzConfig.isPlaceholder("stun:[2001:db8::1]:3478"));
    }

    @Test
    void loaderCompatibility() {
        assertEquals(ModCompare.Block.NONE, ModCompare.block("bukkit", 777, "neoforge", 777));
        assertEquals(ModCompare.Block.NONE, ModCompare.block("vanilla", 777, "fabric", 777));
        assertEquals(ModCompare.Block.NONE, ModCompare.block("fabric", 777, "fabric", 777));
        assertEquals(ModCompare.Block.LOADER, ModCompare.block("fabric", 777, "neoforge", 777));
        assertEquals(ModCompare.Block.LOADER, ModCompare.block("neoforge", 777, "fabric", 777));
        assertEquals(ModCompare.Block.PROTOCOL, ModCompare.block("bukkit", 776, "fabric", 777));
        assertEquals(ModCompare.Block.NONE, ModCompare.block("bukkit", 0, "fabric", 777), "unknown protocol → let the game check");
    }

    private static ServerInfo server(String loader, List<ModEntry> mods) {
        return new ServerInfo("s", "", "26.3", 777, loader, "", 0, 10, true, mods);
    }

    @Test
    void modsCompare() {
        List<ModEntry> host = List.of(
                new ModEntry("create", "Create", "6.0.0", Side.REQUIRED),
                new ModEntry("jei", "JEI", "20.1", Side.REQUIRED),
                new ModEntry("lib", "Lib", "1.0", Side.OPTIONAL),
                new ModEntry("chunky", "Chunky", "1.4", Side.SERVER),
                new ModEntry("neoforge", "NeoForge", "26.3", Side.BUILTIN));
        List<ModEntry> mine = List.of(
                new ModEntry("create", "Create", "6.0.0", Side.REQUIRED),
                new ModEntry("jei", "JEI", "19.9", Side.REQUIRED),
                new ModEntry("extra", "Extra", "2.0", Side.REQUIRED));
        ModCompare.Result r = ModCompare.compare(server("neoforge", host), "neoforge", 777, mine);
        assertEquals(ModCompare.Block.NONE, r.block());
        assertEquals(1, r.badCount(), "jei has another version");
        assertFalse(r.canJoin());
        assertEquals(ModCompare.Status.VERSION, r.server().get(0).status(), "red rows first");
        assertEquals(1, r.clientOnly().size());
        assertTrue(r.server().stream().noneMatch(x -> x.id().equals("neoforge")), "builtins are hidden");

        ModCompare.Result missing = ModCompare.compare(server("neoforge", host), "neoforge", 777, List.of());
        assertEquals(2, missing.badCount());
    }

    @Test
    void bukkitPluginsAreInformational() {
        List<ModEntry> plugins = List.of(new ModEntry("essentialsx", "EssentialsX", "2.21", Side.PLUGIN));
        ModCompare.Result r = ModCompare.compare(server("bukkit", plugins), "fabric", 777,
                List.of(new ModEntry("sodium", "Sodium", "0.9", Side.REQUIRED)));
        assertTrue(r.canJoin());
        assertEquals(1, r.plugins().size());
        assertTrue(r.server().isEmpty());
        assertTrue(r.clientOnly().isEmpty(), "client mods don't matter on a vanilla-protocol server");
    }
}
