package com.hyproj.blitzlink;

import com.hyproj.blitzlink.platform.Platform;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** BlitzLink — Minecraft server matching over the hyproj.com signalling relay; game data flows peer-to-peer over WebRTC. */
public final class BlitzLink {
    public static final String MOD_ID = "blitzlink";
    public static final Logger LOGGER = LoggerFactory.getLogger("BlitzLink");

    private BlitzLink() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Called once from each loader's entrypoint. */
    public static void init() {
        LOGGER.info("BlitzLink {} on {} {}", Platform.get().modVersion(MOD_ID), Platform.get().loaderName(), Platform.get().loaderVersion());
    }
}
