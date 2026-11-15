package com.hyproj.blitzlink.mixin.client;

import com.hyproj.blitzlink.client.BlitzLinkClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 「멀티플레이」 opens BlitzLink instead of the vanilla screen (both loaders; NeoForge's ScreenEvent.Opening runs after). */
@Mixin(Gui.class)
public abstract class GuiSetScreenMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen blitzlink$redirect(Screen next) {
        return BlitzLinkClient.redirect(next, ((Gui) (Object) this).screen());
    }
}
