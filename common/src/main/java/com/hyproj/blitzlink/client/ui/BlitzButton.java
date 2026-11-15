package com.hyproj.blitzlink.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * Button: square, 1px border, no hover transition.
 * DEFAULT = dark fill, line border → khaki border on hover; PRIMARY = olive fill, bright border → bright fill on hover;
 * DANGER = red border and text; TAB = text tab with a 2px brass underline when selected; MENU = title-menu item with the
 * 3px left bar. Disabled = 45% opacity; focus = 1px brass outline 2px outside.
 */
public class BlitzButton extends AbstractButton {
    public enum Kind { DEFAULT, PRIMARY, DANGER, TAB, MENU }

    private final Kind kind;
    private final Runnable action;
    private boolean selected;

    public BlitzButton(int x, int y, int w, int h, Component label, Kind kind, Runnable action) {
        super(x, y, w, h, label);
        this.kind = kind;
        this.action = action;
    }

    public BlitzButton selected(boolean v) {
        this.selected = v;
        return this;
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (active) action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hot = active && isHoveredOrFocused();
        float alpha = active ? 1f : 0.45f;
        Component label = kind == Kind.TAB || kind == Kind.MENU ? BlitzTheme.display(getMessage()) : BlitzTheme.ui(getMessage());
        int textColor;
        switch (kind) {
            case PRIMARY -> {
                g.fill(x, y, x + w, y + h, BlitzTheme.withAlpha(hot ? BlitzTheme.OLIVE_BRIGHT : BlitzTheme.OLIVE, alpha));
                BlitzTheme.border(g, x, y, w, h, BlitzTheme.withAlpha(BlitzTheme.OLIVE_BRIGHT, alpha));
                textColor = BlitzTheme.PAPER;
            }
            case DANGER -> {
                g.fill(x, y, x + w, y + h, BlitzTheme.withAlpha(BlitzTheme.BUTTON, alpha));
                BlitzTheme.border(g, x, y, w, h, BlitzTheme.withAlpha(hot ? BlitzTheme.RED_BRIGHT : BlitzTheme.RED, alpha));
                textColor = BlitzTheme.RED_BRIGHT;
            }
            case TAB -> {
                textColor = selected ? BlitzTheme.PAPER : hot ? BlitzTheme.KHAKI : BlitzTheme.MUTED;
                if (selected) g.fill(x, y + h - 2, x + w, y + h, BlitzTheme.BRASS);
            }
            case MENU -> {
                BlitzTheme.fadeRight(g, x, y, w, h, hot ? BlitzTheme.argb(0.75f, 0x4B5320) : BlitzTheme.argb(0.78f, 0x0C0D0A));
                if (hot) BlitzTheme.leftBar(g, x, y, h, BlitzTheme.BRASS);
                textColor = hot ? BlitzTheme.PAPER : BlitzTheme.TEXT;
            }
            default -> {
                g.fill(x, y, x + w, y + h, BlitzTheme.withAlpha(BlitzTheme.BUTTON, alpha));
                BlitzTheme.border(g, x, y, w, h, BlitzTheme.withAlpha(hot ? BlitzTheme.KHAKI : BlitzTheme.LINE, alpha));
                textColor = BlitzTheme.TEXT;
            }
        }
        if (isFocused() && kind != Kind.MENU) BlitzTheme.border(g, x - 2, y - 2, w + 4, h + 4, BlitzTheme.BRASS);
        int ty = y + (h - font.lineHeight) / 2 + 1;
        Component fit = BlitzTheme.clip(font, label, w - 8);
        int color = BlitzTheme.withAlpha(textColor, alpha);
        if (kind == Kind.MENU) {
            BlitzTheme.text(g, font, fit, x + 10, ty, color);
        } else {
            BlitzTheme.textCentered(g, font, fit, x + w / 2, ty, color);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
