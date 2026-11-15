package com.hyproj.blitzlink.fabric;

import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.command.BlitzCommand;
import com.hyproj.blitzlink.host.HostController;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class BlitzLinkFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        BlitzLink.init();
        ServerLifecycleEvents.SERVER_STARTED.register(HostController::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(HostController::onServerStopping);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> BlitzCommand.register(dispatcher));
    }
}
