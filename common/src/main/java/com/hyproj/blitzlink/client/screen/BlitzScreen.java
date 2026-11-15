package com.hyproj.blitzlink.client.screen;

import com.hyproj.blitzlink.client.ui.BlitzButton;
import com.hyproj.blitzlink.client.ui.BlitzTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Frame for every BlitzLink screen: dark backdrop over the
 * panorama with a vignette, a square centered panel, a header (display-font title + underline tabs) separated by a 1px
 * line, a body, and a right-aligned footer.
 */
public abstract class BlitzScreen extends Screen {
    protected static final int HEADER = 30;
    protected static final int FOOTER = 30;
    protected static final int PAD = 12;

    protected final Screen parent;
    protected int mx, my, mw, mh;

    protected BlitzScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    public Screen parent() {
        return parent;
    }

    /** Panel size for this screen (clamped to the window with a 16px gutter). */
    protected int panelWidth() {
        return 560;
    }

    protected int panelHeight() {
        return 340;
    }

    @Override
    protected void init() {
        mw = Math.min(width - 32, panelWidth());
        mh = Math.min(height - 32, panelHeight());
        mx = (width - mw) / 2;
        my = (height - mh) / 2;
    }

    protected int bodyTop() {
        return my + HEADER + 1;
    }

    protected int bodyBottom() {
        return my + mh - FOOTER;
    }

    protected int footerY() {
        return my + mh - FOOTER + (FOOTER - 20) / 2;
    }

    protected BlitzButton backButton() {
        return addRenderableWidget(new BlitzButton(mx + mw - PAD - 80, footerY(), 80, 20, Component.translatable("blitzlink.back"),
                BlitzButton.Kind.DEFAULT, this::onClose));
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (minecraft.level == null) {
            extractPanorama(g, a);
            g.fill(0, 0, width, height, BlitzTheme.argb(0.82f, 0x0B0C0A));
        } else {
            g.fill(0, 0, width, height, BlitzTheme.OVERLAY);
        }
        BlitzTheme.vignette(g, width, height);
        drawPanel(g);
    }

    protected void drawPanel(GuiGraphicsExtractor g) {
        BlitzTheme.panel(g, mx, my, mw, mh);
        BlitzTheme.hline(g, mx, mx + mw, my + HEADER, BlitzTheme.LINE);
        BlitzTheme.hline(g, mx, mx + mw, my + mh - FOOTER, BlitzTheme.LINE);
        BlitzTheme.text(g, font, headerTitle(), mx + PAD, my + 11, BlitzTheme.KHAKI);
    }

    /** The header text: a bold UI-font heading; the browser shows the brand mark instead. */
    protected Component headerTitle() {
        return BlitzTheme.display(title);
    }

    /** Small brand line in the bottom-left corner. */
    protected void drawBrand(GuiGraphicsExtractor g) {
        BlitzTheme.text(g, font, BlitzTheme.ui(Component.literal("BlitzLink · HY Project")), 6, height - 12, BlitzTheme.MUTED);
    }
}
