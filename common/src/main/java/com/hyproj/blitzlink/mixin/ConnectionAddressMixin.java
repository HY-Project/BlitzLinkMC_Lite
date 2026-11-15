package com.hyproj.blitzlink.mixin;

import java.net.SocketAddress;

import com.hyproj.blitzlink.net.PeerAddresses;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tunnelled players reach the server from 127.0.0.1 (the host sidecar dials the game port). On the first read of the
 * address we swap the field itself for the real one the sidecar reported ({@code peer{localPort, addr}}), so logs,
 * bans and everything else that reads {@code address} sees the player's address.
 */
@Mixin(Connection.class)
public abstract class ConnectionAddressMixin {
    @Shadow
    private SocketAddress address;

    private boolean blitzlink$resolved;

    private void blitzlink$resolve() {
        if (blitzlink$resolved || address == null) return;
        SocketAddress real = PeerAddresses.resolveWaiting(address);
        if (real != address) {
            address = real;
            blitzlink$resolved = true;
        } else if (!PeerAddresses.isTunnelCandidate(address)) {
            blitzlink$resolved = true;
        }
    }

    @Inject(method = "getRemoteAddress", at = @At("HEAD"))
    private void blitzlink$remote(CallbackInfoReturnable<SocketAddress> cir) {
        blitzlink$resolve();
    }

    @Inject(method = "getLoggableAddress", at = @At("HEAD"))
    private void blitzlink$loggable(boolean logIPs, CallbackInfoReturnable<String> cir) {
        blitzlink$resolve();
    }
}
