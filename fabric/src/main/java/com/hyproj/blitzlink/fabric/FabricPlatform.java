package com.hyproj.blitzlink.fabric;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hyproj.blitzlink.mods.ModEntry;
import com.hyproj.blitzlink.mods.ModSides;
import com.hyproj.blitzlink.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;

public final class FabricPlatform implements Platform {
    private static final Set<String> BUILTIN = Set.of("minecraft", "java", "fabricloader", "fabric-api", "mixinextras", "blitzlink");

    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public String loaderVersion() {
        return modVersion("fabricloader");
    }

    @Override
    public String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    @Override
    public List<ModEntry> mods() {
        Set<String> content = ModSides.contentNamespaces();
        List<ModEntry> out = new ArrayList<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            ModMetadata m = c.getMetadata();
            // Jar-in-jar children (Fabric API modules and bundled libraries) travel with their parent.
            if (c.getContainingMod().isPresent()) continue;
            String id = m.getId();
            ModEntry.Side side;
            if (BUILTIN.contains(id) || "builtin".equals(m.getType())) {
                side = ModEntry.Side.BUILTIN;
            } else if (m.getEnvironment() == ModEnvironment.SERVER) {
                side = ModEntry.Side.SERVER;
            } else if (m.getEnvironment() == ModEnvironment.CLIENT) {
                side = ModEntry.Side.CLIENT;
            } else {
                side = content.contains(id) ? ModEntry.Side.REQUIRED : ModEntry.Side.OPTIONAL;
            }
            out.add(new ModEntry(id, m.getName(), m.getVersion().getFriendlyString(), ModSides.override(id, side)));
        }
        return out;
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isDedicatedServer() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
    }
}
