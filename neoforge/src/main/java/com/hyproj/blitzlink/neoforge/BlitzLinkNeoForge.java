package com.hyproj.blitzlink.neoforge;

import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.host.HostController;
import com.hyproj.blitzlink.command.BlitzCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(BlitzLink.MOD_ID)
public final class BlitzLinkNeoForge {
    public BlitzLinkNeoForge(IEventBus modBus, ModContainer container) {
        BlitzLink.init();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> HostController.onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> HostController.onServerStopping(e.getServer()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> BlitzCommand.register(e.getDispatcher()));
    }
}
