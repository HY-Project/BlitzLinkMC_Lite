package com.hyproj.blitzlink.mixin;

import com.hyproj.blitzlink.net.TurnMux;
import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts {@link TurnMux} at the head of every accepted game-port connection (dedicated and 「서버 열기」 alike). */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public abstract class ServerChannelInitMixin {
    @Inject(method = "initChannel", at = @At("TAIL"))
    private void blitzlink$addTurnMux(Channel channel, CallbackInfo ci) {
        channel.pipeline().addFirst(TurnMux.NAME, new TurnMux());
    }
}
