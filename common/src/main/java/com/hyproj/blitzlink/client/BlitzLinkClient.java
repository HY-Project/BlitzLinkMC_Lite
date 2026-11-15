package com.hyproj.blitzlink.client;

import java.util.function.Consumer;

import com.hyproj.blitzlink.BlitzLink;
import com.hyproj.blitzlink.client.screen.BlitzLinkScreen;
import com.hyproj.blitzlink.client.screen.HostScreen;
import com.hyproj.blitzlink.client.ui.BlitzButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;

/** Client-side glue shared by both loaders. */
public final class BlitzLinkClient {
    /** One-shot: the next JoinMultiplayerScreen is opened on purpose (the 「바닐라 멀티플레이」 tab) and must not be replaced. */
    private static boolean bypassOnce;
    /** 「서버 열기」 from the title screen: host the world the player is about to open. */
    private static boolean hostNextWorld;

    private BlitzLinkClient() {}

    public static void init() {
        BlitzLink.LOGGER.debug("BlitzLink client ready");
    }

    /** Opens the vanilla multiplayer screen without BlitzLink taking it over. */
    public static void openVanillaMultiplayer(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        bypassOnce = true;
        mc.gui.setScreen(mc.options.skipMultiplayerWarning ? new JoinMultiplayerScreen(parent) : new SafetyScreen(parent));
    }

    public static void hostNextWorld() {
        hostNextWorld = true;
    }

    public static void cancelHostNextWorld() {
        hostNextWorld = false;
    }

    /**
     * Called from {@code GuiSetScreenMixin} for every {@code gui.setScreen}: the multiplayer screen (and its safety
     * warning) becomes the BlitzLink screen; returning to the game after 「서버 열기」 shows the host panel.
     */
    public static Screen redirect(Screen next, Screen current) {
        Minecraft mc = Minecraft.getInstance();
        Screen dev = devScreen(next);
        if (dev != null) return dev;
        if (next instanceof JoinMultiplayerScreen || next instanceof SafetyScreen) {
            if (bypassOnce) {
                // SafetyScreen → JoinMultiplayerScreen is a second setScreen; keep the bypass for it.
                if (next instanceof JoinMultiplayerScreen) bypassOnce = false;
                return next;
            }
            Screen parent = current instanceof BlitzLinkScreen b ? b.parent() : current;
            return new BlitzLinkScreen(parent);
        }
        if (next == null && hostNextWorld && mc.getSingleplayerServer() != null && mc.player != null) {
            hostNextWorld = false;
            return HostScreen.forRunningWorld(null, true);
        }
        return next;
    }

    private static boolean devScreenShown;

    /**
     * Dev runs only ({@code -Dblitzlink.dev=true}, set by the Gradle run configs): {@code -Dblitzlink.devScreen=browser|host|
     * import|join:MCJE-XXXX|joinNow:MCJE-XXXX} opens that BlitzLink screen once, right after the title screen, for screenshots and checks.
     */
    private static Screen devScreen(Screen next) {
        if (devScreenShown || !(next instanceof net.minecraft.client.gui.screens.TitleScreen) || !Boolean.getBoolean("blitzlink.dev")) return null;
        String which = System.getProperty("blitzlink.devScreen", "");
        if (which.isBlank()) return null;
        devScreenShown = true;
        BlitzLinkScreen browser = new BlitzLinkScreen(next);
        if (which.equals("settings")) return new com.hyproj.blitzlink.client.screen.SettingsScreen(browser);
        if (which.equals("import")) return new com.hyproj.blitzlink.client.screen.ImportScreen(browser);
        if (which.equals("host")) return BlitzLinkScreen.onHostTab(next);
        if (which.startsWith("join:")) return new com.hyproj.blitzlink.client.screen.JoinScreen(browser, which.substring(5));
        if (which.startsWith("joinNow:")) return new com.hyproj.blitzlink.client.screen.JoinScreen(browser, which.substring(8)).autoJoin();
        return browser;
    }

    /** Joins through the local tunnel port: Minecraft connects to 127.0.0.1:port, the sidecar carries it over WebRTC. */
    public static void connect(Screen parent, RelayJoin join, String displayName) {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = new ServerData(displayName, join.code, ServerData.Type.OTHER);
        SavedServers.get().touchRecent(join.code, displayName);
        ConnectScreen.startConnecting(parent, mc, new ServerAddress("127.0.0.1", join.listenPort()), data, false, null);
    }

    /**
     * The pause menu of a singleplayer world gets 「BlitzLink 서버 열기」 (「BlitzLink 서버 관리」 while hosting) where the old
     * 「랜 서버 열기」 sat: right above 「저장 후 나가기」, which moves down one row.
     */
    public static void decoratePauseScreen(PauseScreen pause, Consumer<AbstractWidget> add) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) return;
        AbstractWidget quit = null;
        for (var child : pause.children()) {
            if (child instanceof AbstractWidget w && w.getWidth() >= 200 && (quit == null || w.getY() > quit.getY())) quit = w;
        }
        if (quit == null) return; // the short pause menu (F3+Esc) has no buttons
        boolean hosting = com.hyproj.blitzlink.host.HostController.current() != null
                && com.hyproj.blitzlink.host.HostController.current().server() == mc.getSingleplayerServer();
        Component label = Component.translatable(hosting ? "blitzlink.pause.manage" : "blitzlink.pause.open");
        add.accept(new BlitzButton(quit.getX(), quit.getY(), quit.getWidth(), 20, label, BlitzButton.Kind.PRIMARY,
                () -> mc.gui.setScreen(HostScreen.forRunningWorld(pause, false))));
        quit.setY(quit.getY() + 24);
    }
}
