package com.hyproj.blitzlink.command;

import com.hyproj.blitzlink.host.HostController;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /blitzlink code | public on|off | regen} (admins). */
public final class BlitzCommand {
    private BlitzCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("blitzlink")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("code").executes(c -> code(c.getSource())))
                .then(Commands.literal("public")
                        .then(Commands.literal("on").executes(c -> setPublic(c.getSource(), true)))
                        .then(Commands.literal("off").executes(c -> setPublic(c.getSource(), false))))
                .then(Commands.literal("regen").executes(c -> {
                    if (HostController.current() == null) return notHosting(c.getSource());
                    HostController.regenerate();
                    c.getSource().sendSuccess(() -> Component.literal("[BlitzLink] Requesting a new join code"), true);
                    return 1;
                }))
                .executes(c -> code(c.getSource())));
    }

    private static int code(CommandSourceStack src) {
        HostController h = HostController.current();
        if (h == null) return notHosting(src);
        String code = h.code();
        if (code == null) {
            src.sendSuccess(() -> Component.literal("[BlitzLink] Registering with the relay (" + h.state().name() + ")"), false);
        } else {
            src.sendSuccess(() -> Component.literal("[BlitzLink] Join code: " + code + "  (identity " + (h.fingerprint() == null ? "?" : h.fingerprint()) + ")"), false);
        }
        return 1;
    }

    private static int setPublic(CommandSourceStack src, boolean v) {
        HostController h = HostController.current();
        if (h == null) return notHosting(src);
        h.setPublic(v);
        src.sendSuccess(() -> Component.literal(v ? "[BlitzLink] Listed publicly" : "[BlitzLink] Removed from the public list"), true);
        return 1;
    }

    private static int notHosting(CommandSourceStack src) {
        src.sendFailure(Component.literal("[BlitzLink] This server is not hosted through BlitzLink"));
        return 0;
    }
}
