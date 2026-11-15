package com.hyproj.blitzlink.neoforge;

import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.client.BlitzLinkClient;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = BlitzLink.MOD_ID, dist = Dist.CLIENT)
public final class BlitzLinkNeoForgeClient {
    public BlitzLinkNeoForgeClient() {
        BlitzLinkClient.init();
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) -> {
            if (e.getScreen() instanceof PauseScreen pause) {
                BlitzLinkClient.decoratePauseScreen(pause, e::addListener);
            }
        });
    }
}
