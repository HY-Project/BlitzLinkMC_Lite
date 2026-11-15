package com.hyproj.blitzlink.neoforge;

import java.nio.file.Path;
import java.util.List;

import com.hyproj.blitzlink.mods.ModEntry;
import com.hyproj.blitzlink.platform.Platform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

public final class NeoForgePlatform implements Platform {
    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String loaderVersion() {
        return modVersion("neoforge");
    }

    @Override
    public String modVersion(String modId) {
        return ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }

    @Override
    public List<ModEntry> mods() {
        return NeoForgeMods.collect();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLEnvironment.getDist() == Dist.DEDICATED_SERVER;
    }
}
