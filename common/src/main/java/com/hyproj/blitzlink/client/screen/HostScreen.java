package com.hyproj.blitzlink.client.screen;

import java.util.ArrayList;
import java.util.List;

import com.hyproj.blitzlink.client.ui.BlitzButton;
import com.hyproj.blitzlink.client.ui.BlitzList;
import com.hyproj.blitzlink.client.ui.BlitzTheme;
import com.hyproj.blitzlink.config.BlitzConfig;
import com.hyproj.blitzlink.host.HostController;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.HttpUtil;

/**
 * 「서버 열기」 for the running singleplayer world: before — options and
 * 「서버 열기」; while running — the join code big in brass monospace with 「복사」, status, players and 「서버 닫기」.
 */
public final class HostScreen extends BlitzScreen {
    private boolean isPublic = BlitzConfig.get().publicListing;
    private boolean cheats;
    private final boolean autoStart;
    private BlitzList players;
    private Component flash;
    private long flashUntil;

    private HostScreen(Screen parent, boolean autoStart) {
        super(Component.translatable("blitzlink.host.title"), parent);
        this.autoStart = autoStart;
    }

    /** {@code autoStart}: came from 「서버 열기」 on the title screen — open right away with the saved defaults. */
    public static HostScreen forRunningWorld(Screen parent, boolean autoStart) {
        return new HostScreen(parent, autoStart);
    }

    @Override
    protected int panelHeight() {
        return 300;
    }

    @Override
    protected void init() {
        super.init();
        if (autoStart && running() == null && minecraft.getSingleplayerServer() != null) startHosting();
        backButton();
        HostController h = running();
        int x = mx + PAD, y = bodyTop() + 12;
        if (h == null) {
            addRenderableWidget(toggle(x, y + 30, Component.translatable("blitzlink.host.public"), isPublic, () -> {
                isPublic = !isPublic;
                rebuildWidgets();
            }));
            addRenderableWidget(toggle(x, y + 56, Component.translatable("blitzlink.host.upnp"), BlitzConfig.get().upnp, () -> {
                BlitzConfig.get().upnp = !BlitzConfig.get().upnp;
                BlitzConfig.get().save();
                rebuildWidgets();
            }));
            addRenderableWidget(toggle(x, y + 82, Component.translatable("blitzlink.host.cheats"), cheats, () -> {
                cheats = !cheats;
                rebuildWidgets();
            }));
            addRenderableWidget(new BlitzButton(mx + mw - PAD - 80 - 6 - 120, footerY(), 120, 20, Component.translatable("blitzlink.host.open"),
                    BlitzButton.Kind.PRIMARY, this::startHosting));
        } else {
            addRenderableWidget(new BlitzButton(mx + mw - PAD - 70, y + 4, 70, 18, Component.translatable("blitzlink.copy"), BlitzButton.Kind.DEFAULT, () -> {
                if (h.code() != null) {
                    minecraft.keyboardHandler.setClipboard(h.code());
                    flash = Component.translatable("blitzlink.copied");
                    flashUntil = System.currentTimeMillis() + 1500;
                }
            }));
            addRenderableWidget(toggle(x, y + 50, Component.translatable("blitzlink.host.public"), h.isPublic(), () -> {
                h.setPublic(!h.isPublic());
                rebuildWidgets();
            }));
            players = addRenderableWidget(new BlitzList(x, y + 78, mw - PAD * 2, bodyBottom() - (y + 78) - 6));
            addRenderableWidget(new BlitzButton(mx + mw - PAD - 80 - 6 - 110, footerY(), 110, 20, Component.translatable("blitzlink.host.close"),
                    BlitzButton.Kind.DANGER, this::stopHosting));
        }
    }

    private BlitzButton toggle(int x, int y, Component label, boolean on, Runnable action) {
        Component text = label.copy().append(": ").append(Component.translatable(on ? "blitzlink.on" : "blitzlink.off"));
        return new BlitzButton(x, y, 200, 20, text, on ? BlitzButton.Kind.PRIMARY : BlitzButton.Kind.DEFAULT, action);
    }

    private HostController running() {
        HostController h = HostController.current();
        IntegratedServer s = minecraft.getSingleplayerServer();
        return h != null && s != null && h.server() == s ? h : null;
    }

    private void startHosting() {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) return;
        int port = server.isPublished() ? server.getPort() : HttpUtil.getAvailablePort();
        if (!server.isPublished() && !server.publishServer(MinecraftServer.MultiplayerScope.LAN, cheats, port)) {
            flash = Component.translatable("blitzlink.host.publish_failed");
            flashUntil = System.currentTimeMillis() + 4000;
            return;
        }
        HostController h = HostController.start(server, port, isPublic);
        if (h == null) {
            flash = Component.translatable(com.hyproj.blitzlink.config.BlitzConfig.get().relayConfigured()
                    ? "blitzlink.error.sidecarFailed" : "blitzlink.error.notConfigured");
            flashUntil = System.currentTimeMillis() + 6000;
        }
        if (minecraft.gui.screen() == this) rebuildWidgets();
    }

    private void stopHosting() {
        HostController h = running();
        if (h != null) h.stop();
        rebuildWidgets();
    }

    @Override
    public void tick() {
        HostController h = running();
        if (h != null && players != null) {
            List<BlitzList.Item> items = new ArrayList<>();
            List<ServerPlayer> list = h.server().getPlayerList().getPlayers();
            items.add(new BlitzList.Header(Component.translatable("blitzlink.host.players"), Component.literal(list.size() + " / " + h.server().getMaxPlayers())));
            for (ServerPlayer p : list) {
                String name = p.getGameProfile().name();
                items.add(new BlitzList.Note(Component.literal(name), BlitzTheme.TEXT));
            }
            players.setItems(items);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        Font f = font;
        int x = mx + PAD, y = bodyTop() + 12;
        HostController h = running();
        if (h == null) {
            BlitzTheme.text(g, f, BlitzTheme.display(Component.translatable("blitzlink.host.heading")), x, y, BlitzTheme.PAPER);
            BlitzTheme.text(g, f, BlitzTheme.ui(Component.translatable("blitzlink.host.desc1")), x, y + 14, BlitzTheme.MUTED);
        } else {
            BlitzTheme.text(g, f, BlitzTheme.ui(Component.translatable("blitzlink.host.code")), x, y, BlitzTheme.MUTED);
            String code = h.code();
            Component big = code == null ? BlitzTheme.ui(Component.translatable("blitzlink.host.waiting")) : BlitzTheme.mono(code);
            g.pose().pushMatrix();
            g.pose().translate(x, y + 12);
            g.pose().scale(code == null ? 1f : 2f, code == null ? 1f : 2f);
            BlitzTheme.text(g, f, big, 0, 0, code == null ? BlitzTheme.MUTED : BlitzTheme.BRASS);
            g.pose().popMatrix();
            Component state = switch (h.state()) {
                case ONLINE -> Component.translatable("blitzlink.host.state.online", h.port());
                case RECONNECTING -> Component.translatable("blitzlink.host.state.reconnecting");
                case FAILED -> Component.translatable("blitzlink.host.state.failed", h.error() == null ? "?" : h.error());
                default -> Component.translatable("blitzlink.host.state.starting");
            };
            int sc = h.state() == HostController.State.FAILED ? BlitzTheme.RED_BRIGHT : BlitzTheme.MUTED;
            BlitzTheme.textRight(g, f, BlitzTheme.ui(state), mx + mw - PAD, y + 30, sc);
            if (h.fingerprint() != null) {
                BlitzTheme.textRight(g, f, BlitzTheme.mono(h.fingerprint()), mx + mw - PAD, y + 54, BlitzTheme.MUTED);
            }
        }
        if (flash != null && System.currentTimeMillis() < flashUntil) {
            BlitzTheme.text(g, f, BlitzTheme.ui(flash), mx + PAD, footerY() + 6, BlitzTheme.BRASS);
        }
    }
}
