package com.hyproj.blitzlink.fabric;

import com.hyproj.blitzlink.client.BlitzLinkClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.PauseScreen;

public final class BlitzLinkFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlitzLinkClient.init();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen pause) {
                BlitzLinkClient.decoratePauseScreen(pause, w -> Screens.getWidgets(screen).add(w));
            }
        });
    }
}
