package com.hyproj.blitzlink.client.screen;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hyproj.blitzlink.client.PublicServers;
import com.hyproj.blitzlink.client.ui.BlitzButton;
import com.hyproj.blitzlink.client.ui.BlitzField;
import com.hyproj.blitzlink.client.ui.BlitzTheme;
import com.hyproj.blitzlink.config.BlitzConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Advanced settings: a custom signalling relay and STUN servers. When set they override the network built into the jar
 * (data/net.json) and are passed to the sidecar as {@code --relay} / {@code --stun}; the public list uses the same relay.
 * 「시험」 checks {@code GET <relay>/health} → {@code ok}.
 */
public final class SettingsScreen extends BlitzScreen {
    private BlitzField relayField;
    private BlitzField stunField;
    private Component status;
    private int statusColor = BlitzTheme.MUTED;
    private String relayDraft;
    private String stunDraft;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("blitzlink.settings.title"), parent);
        BlitzConfig cfg = BlitzConfig.get();
        relayDraft = cfg.relay == null ? "" : cfg.relay;
        stunDraft = cfg.stun == null ? "" : String.join(", ", cfg.stun);
    }

    @Override
    protected int panelHeight() {
        return 300;
    }

    @Override
    protected void init() {
        super.init();
        int x = mx + PAD, w = mw - PAD * 2;
        int y = bodyTop() + 30;
        relayField = addRenderableWidget(new BlitzField(x, y, w - 86, 22, Component.translatable("blitzlink.settings.relay")));
        relayField.setMaxLength(256);
        relayField.setValue(relayDraft);
        relayField.setResponder(s -> relayDraft = s);
        String builtIn = BlitzConfig.builtInRelay();
        relayField.setHint(BlitzTheme.ui(builtIn == null ? Component.translatable("blitzlink.settings.relay_none") : Component.literal(builtIn)).withColor(BlitzTheme.MUTED));
        addRenderableWidget(new BlitzButton(x + w - 80, y + 1, 80, 20, Component.translatable("blitzlink.settings.test"), BlitzButton.Kind.DEFAULT, this::test));

        y += 52;
        stunField = addRenderableWidget(new BlitzField(x, y, w, 22, Component.translatable("blitzlink.settings.stun")));
        stunField.setMaxLength(512);
        stunField.setValue(stunDraft);
        stunField.setResponder(s -> stunDraft = s);
        List<String> builtInStun = BlitzConfig.builtInStun();
        stunField.setHint(BlitzTheme.ui(builtInStun.isEmpty() ? Component.translatable("blitzlink.settings.stun_none") : Component.literal(String.join(", ", builtInStun))).withColor(BlitzTheme.MUTED));

        int fy = footerY();
        addRenderableWidget(new BlitzButton(mx + mw - PAD - 80, fy, 80, 20, Component.translatable("blitzlink.back"), BlitzButton.Kind.DEFAULT, this::onClose));
        addRenderableWidget(new BlitzButton(mx + mw - PAD - 80 - 6 - 90, fy, 90, 20, Component.translatable("blitzlink.settings.save"), BlitzButton.Kind.PRIMARY, this::save));
        addRenderableWidget(new BlitzButton(mx + mw - PAD - 80 - 6 - 90 - 6 - 90, fy, 90, 20, Component.translatable("blitzlink.settings.reset"), BlitzButton.Kind.DEFAULT, () -> {
            relayDraft = "";
            stunDraft = "";
            rebuildWidgets();
        }));
    }

    /** "" = use the built-in relay; otherwise an http(s) URL. Returns null when invalid. */
    private static String cleanRelay(String s) {
        String t = s.strip();
        if (t.isEmpty()) return "";
        try {
            URI u = URI.create(t);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || u.getHost() == null) return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /** Comma/space separated {@code stun:host:port} entries; null when one is malformed. */
    private static List<String> cleanStun(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("[,\\s]+")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            if (!p.startsWith("stun:")) p = "stun:" + p;
            if (p.length() <= 5 || p.length() > 128) return null;
            out.add(p);
        }
        return out;
    }

    private void test() {
        String relay = cleanRelay(relayDraft);
        if (relay == null) {
            setStatus(Component.translatable("blitzlink.settings.bad_relay"), BlitzTheme.RED_BRIGHT);
            return;
        }
        String target = relay.isEmpty() ? BlitzConfig.builtInRelay() : relay;
        if (target == null) {
            setStatus(Component.translatable("blitzlink.error.notConfigured"), BlitzTheme.RED_BRIGHT);
            return;
        }
        setStatus(Component.translatable("blitzlink.settings.testing", target), BlitzTheme.MUTED);
        PublicServers.health(target).thenAccept(ok -> minecraft.execute(() -> setStatus(
                Component.translatable(ok ? "blitzlink.settings.test_ok" : "blitzlink.settings.test_failed", target),
                ok ? BlitzTheme.OLIVE_BRIGHT : BlitzTheme.RED_BRIGHT)));
    }

    private void save() {
        String relay = cleanRelay(relayDraft);
        List<String> stun = cleanStun(stunDraft);
        if (relay == null) {
            setStatus(Component.translatable("blitzlink.settings.bad_relay"), BlitzTheme.RED_BRIGHT);
            return;
        }
        if (stun == null) {
            setStatus(Component.translatable("blitzlink.settings.bad_stun"), BlitzTheme.RED_BRIGHT);
            return;
        }
        BlitzConfig cfg = BlitzConfig.get();
        cfg.relay = relay;
        cfg.stun = stun;
        cfg.save();
        BlitzLinkScreen.forgetPublicList();
        setStatus(Component.translatable(cfg.relayConfigured() ? "blitzlink.settings.saved" : "blitzlink.error.notConfigured"),
                cfg.relayConfigured() ? BlitzTheme.OLIVE_BRIGHT : BlitzTheme.RED_BRIGHT);
    }

    private void setStatus(Component c, int color) {
        status = c;
        statusColor = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int x = mx + PAD, w = mw - PAD * 2;
        int y = bodyTop() + 12;
        BlitzTheme.text(g, font, BlitzTheme.display(Component.translatable("blitzlink.settings.relay")), x, y + 4, BlitzTheme.PAPER);
        y += 30 + 26;
        BlitzTheme.text(g, font, BlitzTheme.display(Component.translatable("blitzlink.settings.stun")), x, y + 4, BlitzTheme.PAPER);
        y += 30 + 22;
        g.textWithWordWrap(font, BlitzTheme.ui(Component.translatable("blitzlink.settings.help")), x, y, w, BlitzTheme.MUTED, false);
        if (status != null) BlitzTheme.text(g, font, BlitzTheme.clip(font, BlitzTheme.ui(status), w - 300), x, footerY() + 6, statusColor);
    }
}
