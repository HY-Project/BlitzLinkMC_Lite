package com.hyproj.blitzlink.client.screen;

import java.util.ArrayList;
import java.util.List;

import com.hyproj.blitzlink.client.BlitzLinkClient;
import com.hyproj.blitzlink.client.RelayJoin;
import com.hyproj.blitzlink.client.SavedServers;
import com.hyproj.blitzlink.client.ui.BlitzButton;
import com.hyproj.blitzlink.client.ui.BlitzList;
import com.hyproj.blitzlink.client.ui.BlitzTheme;
import com.hyproj.blitzlink.mods.ModCompare;
import com.hyproj.blitzlink.mods.ServerInfo;
import com.hyproj.blitzlink.platform.Platform;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Join by code: progress while the sidecar does the relay exchange and opens WebRTC, then the server card (shown
 * once the host identity is verified) — name, MOTD, facts, and the host's mod list compared with ours. Red rows (missing / wrong version)
 * keep 「참가」 off; 「그래도 접속」 goes through a red warning first.
 */
public final class JoinScreen extends BlitzScreen {
    private final String code;
    private final RelayJoin join;
    private ModCompare.Result result;
    private BlitzList mods;
    private BlitzButton joinButton;
    private boolean waitingForPort;
    private boolean warn;
    private float progress;
    private long started = System.currentTimeMillis();

    /** Dev runs only: press 「참가」 by itself once the mod check passes ({@code -Dblitzlink.devScreen=joinNow:CODE}). */
    private boolean autoJoin;

    public JoinScreen autoJoin() {
        this.autoJoin = true;
        return this;
    }

    public JoinScreen(Screen parent, String code) {
        super(Component.literal(code), parent);
        this.code = code;
        this.join = RelayJoin.start(code);
    }

    @Override
    protected int panelHeight() {
        return 380;
    }

    /** The join code, in the code face (Courier Prime). */
    @Override
    protected Component headerTitle() {
        return BlitzTheme.mono(code);
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        addRenderableWidget(new BlitzButton(mx + mw - PAD - 80, footerY(), 80, 20, Component.translatable("blitzlink.back"), BlitzButton.Kind.DEFAULT, this::onClose));
        if (warn) {
            initWarn();
            return;
        }
        ServerInfo info = join.info();
        if (info == null) return;
        if (result == null) {
            result = ModCompare.compare(info, Platform.get().loaderName(), SharedConstants.getProtocolVersion(), Platform.get().mods());
        }
        int listTop = bodyTop() + 74;
        mods = addRenderableWidget(new BlitzList(mx + PAD, listTop, mw - PAD * 2, bodyBottom() - listTop - 6));
        mods.setItems(modItems());
        String name = info.name().isBlank() ? code : info.name();
        boolean fav = SavedServers.get().isFavorite(code);
        addRenderableWidget(new BlitzButton(mx + mw - PAD - 90, bodyTop() + 8, 90, 16,
                Component.translatable(fav ? "blitzlink.favorite.remove" : "blitzlink.favorite.add"), BlitzButton.Kind.DEFAULT, () -> {
            SavedServers.get().toggleFavorite(code, name);
            rebuildWidgets();
        }));
        int bx = mx + mw - PAD - 80 - 6;
        if (result.block() == ModCompare.Block.NONE) {
            joinButton = addRenderableWidget(new BlitzButton(bx - 100, footerY(), 100, 20, Component.translatable("blitzlink.join"), BlitzButton.Kind.PRIMARY, this::doJoin));
            joinButton.active = result.canJoin();
            if (!result.canJoin()) {
                addRenderableWidget(new BlitzButton(bx - 100 - 6 - 110, footerY(), 110, 20, Component.translatable("blitzlink.join_anyway"), BlitzButton.Kind.DANGER, () -> {
                    warn = true;
                    rebuildWidgets();
                }));
            }
        }
    }

    private void initWarn() {
        int w = Math.min(320, mw - 40);
        int x = (width - w) / 2;
        int y = height / 2 + 26;
        addRenderableWidget(new BlitzButton(x + w - 100 - 12, y, 100, 20, Component.translatable("blitzlink.join_anyway"), BlitzButton.Kind.DANGER, () -> {
            warn = false;
            doJoin();
        }));
        addRenderableWidget(new BlitzButton(x + w - 100 - 12 - 80 - 6, y, 80, 20, Component.translatable("blitzlink.cancel"), BlitzButton.Kind.DEFAULT, () -> {
            warn = false;
            rebuildWidgets();
        }));
    }

    private List<BlitzList.Item> modItems() {
        List<BlitzList.Item> items = new ArrayList<>();
        long bad = result.badCount();
        items.add(new BlitzList.Header(Component.translatable("blitzlink.mods.server"),
                bad > 0 ? Component.translatable("blitzlink.mods.bad_count", bad) : Component.translatable("blitzlink.mods.count", result.server().size())));
        if (result.server().isEmpty()) {
            items.add(new BlitzList.Note(Component.translatable("bukkit".equals(join.info().loader()) ? "blitzlink.mods.none_bukkit" : "blitzlink.mods.none"), BlitzTheme.MUTED));
        }
        result.server().forEach(r -> items.add(new ModRow(r)));
        if (!result.plugins().isEmpty()) {
            items.add(new BlitzList.Header(Component.translatable("blitzlink.mods.plugins"), Component.translatable("blitzlink.mods.count", result.plugins().size())));
            result.plugins().forEach(r -> items.add(new PluginRow(r)));
        }
        if (!result.clientOnly().isEmpty()) {
            items.add(new BlitzList.Header(Component.translatable("blitzlink.mods.client_only")));
            result.clientOnly().forEach(r -> items.add(new ModRow(r)));
        }
        return items;
    }

    /** One mod: red background + ✕ when it must be installed or updated, olive ✓ when it matches. */
    private record ModRow(ModCompare.Row r) implements BlitzList.Item {
        @Override
        public int height() {
            return 20;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font f, int x, int y, int w, boolean hovered) {
            boolean bad = r.bad();
            g.fill(x, y, x + w, y + 20, bad ? BlitzTheme.ROW_BAD : BlitzTheme.ROW);
            if (bad) {
                BlitzTheme.border(g, x, y, w, 20, BlitzTheme.RED);
                BlitzTheme.leftBar(g, x, y, 20, BlitzTheme.RED_BRIGHT);
            } else if (r.status() == ModCompare.Status.CLIENT_EXTRA) {
                BlitzTheme.leftBar(g, x, y, 20, BlitzTheme.BRASS);
            }
            String mark;
            int markColor;
            switch (r.status()) {
                case OK -> { mark = "✓"; markColor = BlitzTheme.OLIVE_BRIGHT; }
                case MISSING, VERSION -> { mark = "✕"; markColor = BlitzTheme.RED_BRIGHT; }
                case CLIENT_EXTRA -> { mark = "!"; markColor = BlitzTheme.BRASS; }
                default -> { mark = "·"; markColor = BlitzTheme.MUTED; }
            }
            BlitzTheme.text(g, f, BlitzTheme.ui(Component.literal(mark)), x + 8, y + 6, markColor);
            Component detail = switch (r.status()) {
                case MISSING -> Component.translatable("blitzlink.mods.missing", r.serverVersion());
                case VERSION -> Component.translatable("blitzlink.mods.version", r.serverVersion(), r.clientVersion());
                case OK -> Component.literal(r.serverVersion());
                case CLIENT_EXTRA -> Component.translatable("blitzlink.mods.extra", r.clientVersion());
                default -> Component.translatable("blitzlink.mods.not_needed", r.serverVersion());
            };
            Component d = BlitzTheme.ui(detail);
            int dColor = bad ? BlitzTheme.RED_BRIGHT : r.status() == ModCompare.Status.NOT_NEEDED ? BlitzTheme.MUTED : BlitzTheme.KHAKI;
            BlitzTheme.textRight(g, f, d, x + w - 8, y + 6, dColor);
            int nameColor = r.status() == ModCompare.Status.NOT_NEEDED ? BlitzTheme.MUTED : BlitzTheme.PAPER;
            Component name = BlitzTheme.ui(Component.literal(r.name() + "  ").append(Component.literal(r.id()).withColor(BlitzTheme.MUTED)));
            BlitzTheme.text(g, f, BlitzTheme.clip(f, name, w - 30 - f.width(d) - 10), x + 20, y + 6, nameColor);
        }
    }

    /** A server plugin (Bukkit family): nothing to install on the client, shown for information. */
    private record PluginRow(ModCompare.Row r) implements BlitzList.Item {
        @Override
        public int height() {
            return 18;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font f, int x, int y, int w, boolean hovered) {
            g.fill(x, y, x + w, y + 18, BlitzTheme.ROW);
            BlitzTheme.text(g, f, BlitzTheme.ui(Component.literal("·")), x + 8, y + 5, BlitzTheme.OLIVE_BRIGHT);
            Component d = BlitzTheme.ui(Component.translatable("blitzlink.mods.plugin", r.serverVersion()));
            BlitzTheme.textRight(g, f, d, x + w - 8, y + 5, BlitzTheme.MUTED);
            BlitzTheme.text(g, f, BlitzTheme.clip(f, BlitzTheme.ui(Component.literal(r.name())), w - 36 - f.width(d)), x + 20, y + 5, BlitzTheme.TEXT);
        }
    }

    private void doJoin() {
        if (join.listenPort() > 0) {
            ServerInfo info = join.info();
            BlitzLinkClient.connect(parent, join, info == null || info.name().isBlank() ? code : info.name());
        } else {
            waitingForPort = true; // the sidecar reports its local port right after the P2P channel opens
        }
    }

    @Override
    public void tick() {
        if (join.ready() && mods == null && !warn) rebuildWidgets();
        if (autoJoin && result != null && result.canJoin() && !waitingForPort) {
            autoJoin = false;
            doJoin();
            return;
        }
        if (waitingForPort && join.listenPort() > 0) {
            waitingForPort = false;
            doJoin();
        }
        float target = switch (join.phase()) {
            case RELAY -> 0.25f;
            case CONNECTING -> 0.55f;
            case TURN -> 0.75f;
            case OPEN -> join.info() == null ? 0.9f : 1f;
            default -> progress;
        };
        progress = Mth.lerp(0.25f, progress, target);
    }

    @Override
    public void onClose() {
        RelayJoin.closeActive();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (warn) {
            drawWarn(g);
            super.extractRenderState(g, mouseX, mouseY, a);
            return;
        }
        ServerInfo info = join.info();
        if (info == null || join.phase() == RelayJoin.Phase.FAILED) {
            drawProgress(g);
        } else {
            drawCard(g, info);
        }
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    /** Loading curtain: monospace fact box with a 3px brass bar, a 6px progress bar, muted status. */
    private void drawProgress(GuiGraphicsExtractor g) {
        int x = mx + PAD, w = mw - PAD * 2;
        int y = bodyTop() + 24;
        boolean failed = join.phase() == RelayJoin.Phase.FAILED;
        Component head = failed ? Component.translatable("blitzlink.join.failed") : Component.translatable("blitzlink.join.connecting");
        BlitzTheme.text(g, font, BlitzTheme.display(head), x, y, failed ? BlitzTheme.RED_BRIGHT : BlitzTheme.PAPER);
        y += 22;
        g.fill(x, y, x + w, y + 34, BlitzTheme.argb(0.6f, 0x0A0A08));
        BlitzTheme.leftBar(g, x, y, 34, failed ? BlitzTheme.RED : BlitzTheme.BRASS);
        Component fact = failed ? Component.translatableWithFallback("blitzlink.error." + join.error(), join.error()) : Component.translatable("blitzlink.join.fact");
        g.textWithWordWrap(font, BlitzTheme.mono(fact.getString()), x + 10, y + 6, w - 16, failed ? BlitzTheme.RED_BRIGHT : BlitzTheme.TEXT, false);
        y += 46;
        if (!failed) {
            g.fill(x, y, x + w, y + 6, BlitzTheme.TRACK);
            g.fill(x, y, x + Math.round(w * progress), y + 6, BlitzTheme.BRASS);
            y += 12;
            Component status = Component.translatable("blitzlink.join.phase." + join.phase().name().toLowerCase(java.util.Locale.ROOT));
            long secs = (System.currentTimeMillis() - started) / 1000;
            BlitzTheme.text(g, font, BlitzTheme.ui(status.copy().append(" · " + secs + "s")), x, y, BlitzTheme.MUTED);
        }
    }

    private void drawCard(GuiGraphicsExtractor g, ServerInfo info) {
        int x = mx + PAD, w = mw - PAD * 2;
        int y = bodyTop() + 8;
        String name = info.name().isBlank() ? code : info.name();
        ModCompare.Block block = result == null ? ModCompare.Block.NONE : result.block();
        if (block != ModCompare.Block.NONE) {
            // Can't join this server at all: the whole header row turns red with ✕, like the public list.
            g.fill(x - 4, y - 2, x + w - 96, y + 14, BlitzTheme.ROW_BAD);
            BlitzTheme.border(g, x - 4, y - 2, w - 92, 16, BlitzTheme.RED);
            BlitzTheme.text(g, font, BlitzTheme.ui(Component.literal("✕")), x, y + 2, BlitzTheme.RED_BRIGHT);
            x += 10;
        }
        int bw = BlitzTheme.serverBadges(g, font, info.mcVersion(), info.loader(), x, y + 1, block);
        BlitzTheme.text(g, font, BlitzTheme.clip(font, BlitzTheme.display(Component.literal(name)), w - 110 - bw), x + bw + 6, y + 2, BlitzTheme.PAPER);
        x = mx + PAD;
        y += 16;
        String motd = info.motd().lines().findFirst().orElse("");
        BlitzTheme.text(g, font, BlitzTheme.clip(font, BlitzTheme.ui(Component.literal(motd)), w), x, y, BlitzTheme.KHAKI);
        y += 14;
        // facts: two columns of label (muted) / value; mismatches in red
        boolean loaderBad = result != null && result.block() == ModCompare.Block.LOADER;
        boolean protoBad = result != null && result.block() == ModCompare.Block.PROTOCOL;
        fact(g, x, y, Component.translatable("blitzlink.fact.version"), Component.literal(info.mcVersion()), protoBad);
        fact(g, x + w / 2, y, Component.translatable("blitzlink.fact.loader"),
                Component.literal(BlitzLinkScreen.loaderLabel(info.loader()) + (info.loaderVersion().isBlank() ? "" : " " + info.loaderVersion())), loaderBad);
        y += 12;
        fact(g, x, y, Component.translatable("blitzlink.fact.players"), Component.literal(info.players() + " / " + info.maxPlayers()), false);
        fact(g, x + w / 2, y, Component.translatable("blitzlink.fact.fingerprint"), BlitzTheme.mono(join.fingerprint() == null ? "?" : join.fingerprint()), false);
        y += 14;
        if (result != null && result.block() != ModCompare.Block.NONE) {
            Component why = result.block() == ModCompare.Block.LOADER
                    ? Component.translatable("blitzlink.block.loader", BlitzLinkScreen.loaderLabel(info.loader()), BlitzLinkScreen.loaderLabel(Platform.get().loaderName()))
                    : Component.translatable("blitzlink.block.protocol", info.mcVersion(), SharedConstants.getCurrentVersion().name());
            BlitzTheme.text(g, font, BlitzTheme.ui(why), mx + PAD, footerY() + 6, BlitzTheme.RED_BRIGHT);
        } else if (result != null && result.badCount() > 0) {
            BlitzTheme.text(g, font, BlitzTheme.ui(Component.translatable("blitzlink.mods.install_first")), mx + PAD, footerY() + 6, BlitzTheme.RED_BRIGHT);
        } else if (waitingForPort) {
            BlitzTheme.text(g, font, BlitzTheme.ui(Component.translatable("blitzlink.join.opening")), mx + PAD, footerY() + 6, BlitzTheme.MUTED);
        }
    }

    private void fact(GuiGraphicsExtractor g, int x, int y, Component label, Component value, boolean bad) {
        Component l = BlitzTheme.ui(label);
        BlitzTheme.text(g, font, l, x, y, BlitzTheme.MUTED);
        BlitzTheme.text(g, font, value.getStyle().getFont().equals(BlitzTheme.MONO) ? value : BlitzTheme.ui(value), x + 58, y, bad ? BlitzTheme.RED_BRIGHT : BlitzTheme.TEXT);
    }

    private void drawWarn(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, height, BlitzTheme.BACKDROP);
        int w = Math.min(320, mw - 40), h = 96;
        int x = (width - w) / 2, y = height / 2 - h / 2;
        BlitzTheme.redPanel(g, x, y, w, h);
        BlitzTheme.text(g, font, BlitzTheme.display(Component.translatable("blitzlink.warn.title")), x + 12, y + 10, BlitzTheme.RED_BRIGHT);
        g.textWithWordWrap(font, BlitzTheme.ui(Component.translatable("blitzlink.warn.body", result == null ? 0 : result.badCount())), x + 12, y + 26, w - 24, BlitzTheme.TEXT, false);
    }
}
