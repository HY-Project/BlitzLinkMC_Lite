package com.hyproj.blitzlink.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.hyproj.blitzlink.client.BlitzLinkClient;
import com.hyproj.blitzlink.client.PublicServers;
import com.hyproj.blitzlink.client.RelayJoin;
import com.hyproj.blitzlink.client.SavedServers;
import com.hyproj.blitzlink.code.JoinCode;
import com.hyproj.blitzlink.mods.ModCompare;
import com.hyproj.blitzlink.platform.Platform;
import com.hyproj.blitzlink.client.ui.BlitzButton;
import com.hyproj.blitzlink.client.ui.BlitzField;
import com.hyproj.blitzlink.client.ui.BlitzList;
import com.hyproj.blitzlink.client.ui.BlitzTheme;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Replaces the vanilla multiplayer screen: tabs 「중계 참가」 · 「바닐라 멀티플레이」 ·
 * 「서버 열기」. The relay tab takes a MCJE- code (or picks from the public list / favourites / recent).
 */
public final class BlitzLinkScreen extends BlitzScreen {
    private enum Tab { RELAY, HOST }

    private static Tab lastTab = Tab.RELAY;
    private static List<PublicServers.Entry> cachedPublic;
    private static long cachedAt;

    private Tab tab = lastTab;
    private BlitzField codeField;
    private BlitzList list;
    private Component error;
    private boolean loading;
    private String publicError;
    private String typed = "";

    public BlitzLinkScreen(Screen parent) {
        super(Component.literal("BlitzLink"), parent);
    }

    @Override
    protected Component headerTitle() {
        return BlitzTheme.brand(title);
    }

    /** Settings changed the relay: the cached public list belongs to the old one. */
    static void forgetPublicList() {
        cachedPublic = null;
        cachedAt = 0;
    }

    /** The browser opened on its 「서버 열기」 tab. */
    public static BlitzLinkScreen onHostTab(Screen parent) {
        lastTab = Tab.HOST;
        BlitzLinkScreen s = new BlitzLinkScreen(parent);
        s.tab = Tab.HOST;
        return s;
    }

    @Override
    protected void init() {
        super.init();
        // Back on the browser with no game connection: the previous join tunnel has nothing left to carry.
        if (minecraft.getConnection() == null) RelayJoin.closeActive();
        // Back here means a world picked for 「서버 열기」 was not opened after all.
        BlitzLinkClient.cancelHostNextWorld();
        int tx = mx + PAD + font.width(headerTitle()) + 18;
        tx = tab(tx, Component.translatable("blitzlink.tab.relay"), tab == Tab.RELAY, () -> switchTab(Tab.RELAY));
        tx = tab(tx, Component.translatable("blitzlink.tab.vanilla"), false, () -> BlitzLinkClient.openVanillaMultiplayer(this));
        tab(tx, Component.translatable("blitzlink.tab.host"), tab == Tab.HOST, () -> switchTab(Tab.HOST));
        backButton();
        addRenderableWidget(new BlitzButton(mx + mw - PAD - 52, my + 7, 52, 16, Component.translatable("blitzlink.settings.open"),
                BlitzButton.Kind.DEFAULT, () -> minecraft.gui.setScreen(new SettingsScreen(this))));
        if (tab == Tab.RELAY) initRelay();
        else initHost();
    }

    private int tab(int x, Component label, boolean selected, Runnable action) {
        int w = font.width(BlitzTheme.display(label)) + 16;
        addRenderableWidget(new BlitzButton(x, my + 6, w, HEADER - 6, label, BlitzButton.Kind.TAB, action).selected(selected));
        return x + w + 3;
    }

    private void switchTab(Tab t) {
        if (codeField != null) typed = codeField.getValue();
        tab = lastTab = t;
        rebuildWidgets();
    }

    // ---- 중계 참가 ----

    private void initRelay() {
        int y = bodyTop() + 10;
        int btnW = 80;
        codeField = addRenderableWidget(new BlitzField(mx + PAD, y, mw - PAD * 2 - btnW - 6, 22, Component.translatable("blitzlink.code.hint")));
        codeField.setMaxLength(32);
        codeField.setHint(BlitzTheme.ui(Component.translatable("blitzlink.code.hint")).withColor(BlitzTheme.MUTED));
        codeField.setValue(typed);
        codeField.setResponder(s -> error = null);
        setInitialFocus(codeField);
        addRenderableWidget(new BlitzButton(mx + mw - PAD - btnW, y + 1, btnW, 20, Component.translatable("blitzlink.connect"),
                BlitzButton.Kind.PRIMARY, this::submitCode));
        int listTop = y + 22 + 18;
        list = addRenderableWidget(new BlitzList(mx + PAD, listTop, mw - PAD * 2, bodyBottom() - listTop - 6));
        addRenderableWidget(new BlitzButton(mx + mw - PAD - 64, listTop - 14, 64, 12, Component.translatable("blitzlink.refresh"),
                BlitzButton.Kind.DEFAULT, () -> refresh(true)));
        refresh(false);
        rebuildList();
    }

    private void submitCode() {
        String raw = codeField.getValue();
        Optional<String> code = JoinCode.normalize(raw);
        if (code.isEmpty()) {
            error = Component.translatable("blitzlink.code.invalid");
            return;
        }
        open(code.get());
    }

    private void open(String code) {
        typed = code;
        minecraft.gui.setScreen(new JoinScreen(this, code));
    }

    private void refresh(boolean force) {
        if (loading) return;
        if (!force && cachedPublic != null && System.currentTimeMillis() - cachedAt < 30_000) return;
        loading = true;
        publicError = null;
        PublicServers.fetch().whenComplete((servers, err) -> minecraft.execute(() -> {
            loading = false;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                publicError = "notConfigured".equals(cause.getMessage()) ? "notConfigured" : cause.getClass().getSimpleName();
            } else {
                cachedPublic = servers;
                cachedAt = System.currentTimeMillis();
            }
            if (list != null) rebuildList();
        }));
        rebuildList();
    }

    private void rebuildList() {
        List<BlitzList.Item> items = new ArrayList<>();
        items.add(new BlitzList.Header(Component.translatable("blitzlink.list.public")));
        if (loading && cachedPublic == null) {
            items.add(new BlitzList.Note(Component.translatable("blitzlink.list.loading"), BlitzTheme.MUTED));
        } else if ("notConfigured".equals(publicError)) {
            items.add(new BlitzList.Note(Component.translatable("blitzlink.error.notConfigured"), BlitzTheme.RED_BRIGHT));
        } else if (publicError != null && cachedPublic == null) {
            items.add(new BlitzList.Note(Component.translatable("blitzlink.list.error", publicError), BlitzTheme.RED_BRIGHT));
        } else if (cachedPublic == null || cachedPublic.isEmpty()) {
            items.add(new BlitzList.Note(Component.translatable("blitzlink.list.empty"), BlitzTheme.MUTED));
        } else {
            for (PublicServers.Entry e : cachedPublic) items.add(new PublicRow(e));
        }
        SavedServers saved = SavedServers.get();
        if (!saved.favorites.isEmpty()) {
            items.add(new BlitzList.Header(Component.translatable("blitzlink.list.favorites")));
            saved.favorites.forEach(s -> items.add(new SavedRow(s)));
        }
        if (!saved.recent.isEmpty()) {
            items.add(new BlitzList.Header(Component.translatable("blitzlink.list.recent")));
            saved.recent.forEach(s -> items.add(new SavedRow(s)));
        }
        list.setItems(items);
    }

    /**
     * One public server: badges (game version + VANILLA/NEOFORGE/FABRIC/BUKKIT), name, MOTD, mods, players, code.
     * A server this client can't join (other game version, or NeoForge↔Fabric) gets a red background and ✕; clicking
     * it still opens the card, which says why.
     */
    private final class PublicRow implements BlitzList.Item {
        private final PublicServers.Entry e;
        private final ModCompare.Block block;

        PublicRow(PublicServers.Entry e) {
            this.e = e;
            this.block = ModCompare.block(e.loader(), e.protocol(), Platform.get().loaderName(), SharedConstants.getProtocolVersion());
        }

        @Override
        public int height() {
            return 28;
        }

        @Override
        public boolean clickable() {
            return true;
        }

        @Override
        public void click(double lx, double ly, int w) {
            open(e.code());
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font f, int x, int y, int w, boolean hovered) {
            boolean bad = block != ModCompare.Block.NONE;
            BlitzList.rowFrame(g, x, y, w, 28, hovered, bad ? BlitzTheme.ROW_BAD : BlitzTheme.ROW);
            int left = x + 6;
            if (bad) {
                BlitzTheme.border(g, x, y, w, 28, BlitzTheme.RED);
                BlitzTheme.leftBar(g, x, y, 28, BlitzTheme.RED_BRIGHT);
                BlitzTheme.text(g, f, BlitzTheme.ui(Component.literal("✕")), x + 7, y + 10, BlitzTheme.RED_BRIGHT);
                left = x + 18;
            }
            Component code = BlitzTheme.mono(e.code());
            int right = x + w - 6;
            BlitzTheme.textRight(g, f, code, right, y + 4, bad ? BlitzTheme.MUTED : BlitzTheme.BRASS);
            BlitzTheme.textRight(g, f, BlitzTheme.ui(Component.literal(e.players() + "/" + e.maxPlayers())), right, y + 16, BlitzTheme.MUTED);
            int badges = BlitzTheme.serverBadges(g, f, e.mcVersion(), e.loader(), left, y + 3, block);
            int nameX = left + badges + 6;
            int nameMax = right - f.width(code) - 10 - nameX;
            BlitzTheme.text(g, f, BlitzTheme.clip(f, BlitzTheme.ui(Component.literal(e.name())), nameMax), nameX, y + 4, bad ? BlitzTheme.MUTED : BlitzTheme.PAPER);
            String count = e.modCount() <= 0 ? "" : Component.translatable("bukkit".equals(e.loader()) ? "blitzlink.list.plugins" : "blitzlink.list.mods", e.modCount()).getString();
            String motd = e.motd().lines().findFirst().orElse("").replaceAll("§.", "");
            String meta = count + (count.isEmpty() || motd.isEmpty() ? "" : " · ") + motd;
            if (bad) meta = Component.translatable(block == ModCompare.Block.LOADER ? "blitzlink.list.incompatible_loader" : "blitzlink.list.incompatible_version").getString();
            BlitzTheme.text(g, f, BlitzTheme.clip(f, BlitzTheme.ui(Component.literal(meta)), right - 40 - left), left, y + 16, bad ? BlitzTheme.RED_BRIGHT : BlitzTheme.MUTED);
        }
    }

    private final class SavedRow implements BlitzList.Item {
        private final SavedServers.Saved s;

        SavedRow(SavedServers.Saved s) {
            this.s = s;
        }

        @Override
        public int height() {
            return 18;
        }

        @Override
        public boolean clickable() {
            return true;
        }

        @Override
        public void click(double lx, double ly, int w) {
            open(s.code);
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font f, int x, int y, int w, boolean hovered) {
            BlitzList.rowFrame(g, x, y, w, 18, hovered, BlitzTheme.ROW);
            Component code = BlitzTheme.mono(s.code);
            BlitzTheme.textRight(g, f, code, x + w - 6, y + 5, BlitzTheme.BRASS);
            BlitzTheme.text(g, f, BlitzTheme.clip(f, BlitzTheme.ui(Component.literal(s.name == null ? s.code : s.name)), w - 20 - f.width(code)), x + 6, y + 5, BlitzTheme.TEXT);
        }
    }

    static String loaderLabel(String loader) {
        return switch (loader) {
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            case "bukkit" -> "Bukkit";
            default -> "Vanilla";
        };
    }

    // ---- 서버 열기 ----

    private void initHost() {
        int y = bodyTop() + 14;
        int w = Math.min(240, (mw - PAD * 2 - 6) / 2);
        if (minecraft.getSingleplayerServer() != null) {
            addRenderableWidget(new BlitzButton(mx + PAD, y + 56, w, 22, Component.translatable("blitzlink.host.panel"), BlitzButton.Kind.PRIMARY,
                    () -> minecraft.gui.setScreen(HostScreen.forRunningWorld(this, false))));
        } else {
            addRenderableWidget(new BlitzButton(mx + PAD, y + 56, w, 22, Component.translatable("blitzlink.host.pick_world"), BlitzButton.Kind.PRIMARY, () -> {
                BlitzLinkClient.hostNextWorld();
                minecraft.gui.setScreen(new SelectWorldScreen(this));
            }));
            addRenderableWidget(new BlitzButton(mx + PAD + w + 6, y + 56, w, 22, Component.translatable("blitzlink.host.import_zip"), BlitzButton.Kind.DEFAULT,
                    () -> minecraft.gui.setScreen(new ImportScreen(this))));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tab == Tab.RELAY && codeField != null && codeField.isFocused()
                && event.isConfirmation()) {
            submitCode();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        if (tab == Tab.RELAY) {
            int y = bodyTop() + 10 + 22 + 4;
            if (error != null) BlitzTheme.text(g, font, BlitzTheme.ui(error), mx + PAD, y, BlitzTheme.RED_BRIGHT);
        } else {
            int y = bodyTop() + 14;
            BlitzTheme.text(g, font, BlitzTheme.display(Component.translatable("blitzlink.host.heading")), mx + PAD, y, BlitzTheme.PAPER);
            int ny = y + 16;
            for (Component line : List.of(Component.translatable("blitzlink.host.desc1"), Component.translatable("blitzlink.host.desc2"))) {
                g.textWithWordWrap(font, BlitzTheme.ui(line), mx + PAD, ny, mw - PAD * 2, BlitzTheme.MUTED, false);
                ny += 14;
            }
            int dy = y + 56 + 22 + 16;
            BlitzTheme.text(g, font, BlitzTheme.ui(Component.translatable("blitzlink.host.dedicated")), mx + PAD, dy, BlitzTheme.BRASS);
            g.textWithWordWrap(font, BlitzTheme.ui(Component.translatable("blitzlink.host.dedicated_desc")), mx + PAD, dy + 12, mw - PAD * 2, BlitzTheme.MUTED, false);
        }
        drawBrand(g);
    }
}
