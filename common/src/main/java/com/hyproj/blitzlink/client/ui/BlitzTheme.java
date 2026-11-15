package com.hyproj.blitzlink.client.ui;

import com.hyproj.blitzlink.BlitzLink;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;

/**
 * The BlitzLink look: olive drab, khaki, brass and telegram
 * paper, red only for warnings. Every corner is square (no rounded corners anywhere) — 1px line borders,
 * a 3px brass bar on the left of menu items and rows, a 2px brass underline on the active tab.
 */
public final class BlitzTheme {
    // ---- tokens (ARGB) ----
    public static final int BG = 0xFF0B0C0A;
    public static final int PANEL = argb(0.94f, 0x10120D);
    public static final int LINE = 0xFF3A3F2A;
    public static final int OLIVE = 0xFF4B5320;
    public static final int OLIVE_BRIGHT = 0xFF6F7D33;
    public static final int KHAKI = 0xFFC8B98A;
    public static final int PAPER = 0xFFE9DFC4;
    public static final int TEXT = 0xFFE4DFCF;
    public static final int MUTED = 0xFF9A9581;
    public static final int BRASS = 0xFFD2A038;
    public static final int RED = 0xFFC42A22;
    public static final int RED_BRIGHT = 0xFFFF4A3D;

    public static final int BUTTON = argb(0.9f, 0x1E2116);
    public static final int INPUT = 0xFF15170F;
    public static final int ROW = argb(0.3f, 0x000000);
    public static final int ROW_HOVER = argb(0.14f, 0x6F7D33);
    public static final int ROW_BAD = argb(0.28f, 0xC42A22);
    public static final int BACKDROP = argb(0.55f, 0x000000);
    public static final int OVERLAY = argb(0.6f, 0x060705);
    public static final int TRACK = argb(0.12f, 0xFFFFFF);
    public static final int LOG = argb(0.4f, 0x000000);

    // ---- type ----
    /** Black Ops One — the stencil face is kept for the BlitzLink brand mark only. */
    public static final FontDescription BRAND = new FontDescription.Resource(BlitzLink.id("brand"));
    /** Noto Sans KR Bold — headings, tabs, menu items (a real Bold face: Minecraft's fake bold doubles TTF glyphs). */
    public static final FontDescription UI_BOLD = new FontDescription.Resource(BlitzLink.id("ui_bold"));
    /** Noto Sans KR — every piece of UI text (Latin and Hangul). */
    public static final FontDescription UI = new FontDescription.Resource(BlitzLink.id("ui"));
    public static final FontDescription MONO = new FontDescription.Resource(BlitzLink.id("mono"));

    private BlitzTheme() {}

    public static int argb(float alpha, int rgb) {
        return (Math.round(alpha * 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** Headings, tabs and menu items: Noto Sans KR, bold. */
    public static MutableComponent display(Component c) {
        return c.copy().withStyle(s -> s.withFont(UI_BOLD).withBold(false));
    }

    public static MutableComponent ui(Component c) {
        return c.copy().withStyle(s -> s.withFont(UI));
    }

    public static MutableComponent mono(String s) {
        return Component.literal(s).withStyle(st -> st.withFont(MONO));
    }

    /** The brand mark ("BLITZLINK"): uppercase stencil, the only place the military face appears. */
    public static MutableComponent brand(Component c) {
        return Component.literal(c.getString().toUpperCase(java.util.Locale.ROOT)).withStyle(s -> s.withFont(BRAND));
    }

    // ---- shapes ----

    /** 1px border inside (x, y, w, h). */
    public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.outline(x, y, w, h, color);
    }

    /** A modal panel: fill + 1px line + a soft shadow band below. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fillGradient(x, y + h, x + w, y + h + 14, argb(0.45f, 0), 0);
        g.fill(x, y, x + w, y + h, PANEL);
        border(g, x, y, w, h, LINE);
    }

    public static void redPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        panel(g, x, y, w, h);
        border(g, x - 2, y - 2, w + 4, h + 4, argb(0.5f, 0xC42A22));
        border(g, x, y, w, h, RED);
    }

    public static void hline(GuiGraphicsExtractor g, int x0, int x1, int y, int color) {
        g.fill(x0, y, x1, y + 1, color);
    }

    /** The 3px brass bar on the left of menu items / rows / the loading fact. */
    public static void leftBar(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        g.fill(x, y, x + 3, y + h, color);
    }

    /** Menu item background: a horizontal fade to transparent. */
    public static void fadeRight(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        int steps = 16;
        for (int i = 0; i < steps; i++) {
            int x0 = x + w * i / steps;
            int x1 = x + w * (i + 1) / steps;
            g.fill(x0, y, x1, y + h, withAlpha(color, 1f - (float) i / steps));
        }
    }

    /** Vignette: `radial-gradient(ellipse at 50% 40%, transparent 40%, rgba(0,0,0,.65) 100%)`, approximated with edge bands. */
    public static void vignette(GuiGraphicsExtractor g, int w, int h) {
        int band = Math.max(24, Math.min(w, h) / 4);
        int dark = argb(0.65f, 0);
        g.fillGradient(0, 0, w, band, dark, 0);
        g.fillGradient(0, h - band, w, h, 0, dark);
        for (int i = 0; i < band; i += 2) {
            int c = withAlpha(dark, 1f - (float) i / band);
            g.fill(i, 0, i + 2, h, c);
            g.fill(w - i - 2, 0, w - i, h, c);
        }
    }

    /**
     * A small square badge: 1px border and text in the same colour, uppercase.
     * Returns the badge width so callers can line several up.
     */
    public static int badge(GuiGraphicsExtractor g, Font f, String label, int x, int y, int color, boolean bad) {
        Component c = ui(Component.literal(label.toUpperCase(java.util.Locale.ROOT)));
        int w = f.width(c) + 8;
        if (bad) g.fill(x, y, x + w, y + 11, argb(0.35f, 0xC42A22));
        border(g, x, y, w, 11, bad ? RED : withAlpha(color, 0.8f));
        g.text(f, c, x + 4, y + 2, bad ? RED_BRIGHT : color, false);
        return w;
    }

    /** Loader badge colours: vanilla khaki, NeoForge brass, Fabric paper, Bukkit family olive. */
    public static int loaderColor(String loader) {
        return switch (loader == null ? "" : loader) {
            case "neoforge" -> BRASS;
            case "fabric" -> PAPER;
            case "bukkit" -> OLIVE_BRIGHT;
            default -> KHAKI;
        };
    }

    public static String loaderBadge(String loader) {
        return switch (loader == null ? "" : loader) {
            case "neoforge" -> "NEOFORGE";
            case "fabric" -> "FABRIC";
            case "bukkit" -> "BUKKIT";
            default -> "VANILLA";
        };
    }

    /** Version badge + loader badge; red with background when this client can't join. Returns total width. */
    public static int serverBadges(GuiGraphicsExtractor g, Font f, String mcVersion, String loader, int x, int y,
                                   com.hyproj.blitzlink.mods.ModCompare.Block block) {
        int w = badge(g, f, mcVersion == null || mcVersion.isBlank() ? "?" : mcVersion, x, y, MUTED,
                block == com.hyproj.blitzlink.mods.ModCompare.Block.PROTOCOL);
        w += 3;
        w += badge(g, f, loaderBadge(loader), x + w, y, loaderColor(loader), block == com.hyproj.blitzlink.mods.ModCompare.Block.LOADER);
        return w;
    }

    public static void text(GuiGraphicsExtractor g, Font f, Component c, int x, int y, int color) {
        g.text(f, c, x, y, color, false);
    }

    public static void textRight(GuiGraphicsExtractor g, Font f, Component c, int right, int y, int color) {
        g.text(f, c, right - f.width(c), y, color, false);
    }

    public static void textCentered(GuiGraphicsExtractor g, Font f, Component c, int cx, int y, int color) {
        g.text(f, c, cx - f.width(c) / 2, y, color, false);
    }

    /** Clips {@code c} to {@code maxWidth} with an ellipsis. */
    public static Component clip(Font f, Component c, int maxWidth) {
        if (f.width(c) <= maxWidth) return c;
        String s = c.getString();
        net.minecraft.network.chat.Style style = c.getStyle();
        while (!s.isEmpty() && f.width(Component.literal(s + "…").withStyle(style)) > maxWidth) s = s.substring(0, s.length() - 1);
        return Component.literal(s + "…").withStyle(style);
    }
}
