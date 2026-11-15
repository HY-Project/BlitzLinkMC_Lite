package com.hyproj.blitzlink.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Text input: {@code #15170f} fill, 1px line border, brass border when focused.
 * Built on an unbordered EditBox; (x, y, w, h) is the whole box, the text sits inside with 7px padding.
 */
public class BlitzField extends EditBox {
    private static final int PAD_X = 7;

    private final int boxX, boxY, boxW, boxH;

    public BlitzField(int x, int y, int w, int h, Component narration) {
        super(Minecraft.getInstance().font, x + PAD_X, y + (h - 8) / 2, w - PAD_X * 2, 9, narration);
        this.boxX = x;
        this.boxY = y;
        this.boxW = w;
        this.boxH = h;
        setBordered(false);
        setTextColor(BlitzTheme.TEXT);
        setTextShadow(false);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, BlitzTheme.INPUT);
        BlitzTheme.border(g, boxX, boxY, boxW, boxH, isFocused() ? BlitzTheme.BRASS : isHovered() ? BlitzTheme.KHAKI : BlitzTheme.LINE);
        super.extractWidgetRenderState(g, mouseX, mouseY, a);
    }

    @Override
    public boolean isMouseOver(double mx, double my) {
        return visible && mx >= boxX && my >= boxY && mx < boxX + boxW && my < boxY + boxH;
    }
}
