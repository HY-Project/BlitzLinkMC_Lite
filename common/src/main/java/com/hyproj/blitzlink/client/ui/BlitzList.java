package com.hyproj.blitzlink.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Scrolling list of stacked full-width rows with 2px gaps:
 * rows are {@code rgba(0,0,0,.3)} with a transparent border that turns olive-bright on hover; section titles are
 * brass, uppercase, small. Scroll rail {@code #1c1f15} with an olive-bright thumb.
 */
public class BlitzList extends AbstractWidget {
    public interface Item {
        int height();

        void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered);

        default boolean clickable() {
            return false;
        }

        default void click(double localX, double localY, int w) {}
    }

    /** Brass section title (Barlow 600, uppercase, tracked). */
    public record Header(Component text, Component right) implements Item {
        public Header(Component text) {
            this(text, null);
        }

        @Override
        public int height() {
            return 16;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
            BlitzTheme.text(g, font, BlitzTheme.ui(Component.literal(text.getString().toUpperCase(java.util.Locale.ROOT))), x + 1, y + 6, BlitzTheme.BRASS);
            if (right != null) BlitzTheme.textRight(g, font, BlitzTheme.ui(right), x + w - 1, y + 6, BlitzTheme.MUTED);
        }
    }

    /** A muted one-liner (empty list, loading, error). */
    public record Note(Component text, int color) implements Item {
        @Override
        public int height() {
            return 14;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, boolean hovered) {
            BlitzTheme.text(g, font, BlitzTheme.clip(font, BlitzTheme.ui(text), w - 6), x + 4, y + 3, color);
        }
    }

    private final List<Item> items = new ArrayList<>();
    private double scroll;

    public BlitzList(int x, int y, int w, int h) {
        super(x, y, w, h, Component.empty());
    }

    public void setItems(List<? extends Item> list) {
        items.clear();
        items.addAll(list);
        scroll = Math.min(scroll, maxScroll());
    }

    private int contentHeight() {
        int h = 0;
        for (Item i : items) h += i.height() + 2;
        return h;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - getHeight());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth() - 6, h = getHeight();
        g.enableScissor(x, y, x + getWidth(), y + h);
        int cy = y - (int) scroll;
        for (Item item : items) {
            int ih = item.height();
            if (cy + ih >= y && cy <= y + h) {
                boolean hover = item.clickable() && mouseX >= x && mouseX < x + w && mouseY >= Math.max(cy, y) && mouseY < Math.min(cy + ih, y + h);
                item.draw(g, font, x, cy, w, hover);
            }
            cy += ih + 2;
        }
        g.disableScissor();
        double max = maxScroll();
        if (max > 0) {
            g.fill(x + getWidth() - 3, y, x + getWidth(), y + h, 0xFF1C1F15);
            int thumb = Math.max(16, (int) (h * (h / (double) contentHeight())));
            int ty = y + (int) ((h - thumb) * (scroll / max));
            g.fill(x + getWidth() - 3, ty, x + getWidth(), ty + thumb, BlitzTheme.OLIVE_BRIGHT);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!isMouseOver(mx, my)) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 18));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (!active || !visible || !isMouseOver(e.x(), e.y())) return false;
        int cy = getY() - (int) scroll;
        for (Item item : items) {
            int ih = item.height();
            if (e.y() >= cy && e.y() < cy + ih) {
                if (item.clickable() && e.x() < getX() + getWidth() - 6) {
                    playDownSound(Minecraft.getInstance().getSoundManager());
                    item.click(e.x() - getX(), e.y() - cy, getWidth() - 6);
                    return true;
                }
                return false;
            }
            cy += ih + 2;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    /** Row frame shared by server and mod rows. */
    public static void rowFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hovered, int fill) {
        g.fill(x, y, x + w, y + h, fill);
        if (hovered) {
            g.fill(x, y, x + w, y + h, BlitzTheme.ROW_HOVER);
            BlitzTheme.border(g, x, y, w, h, BlitzTheme.OLIVE_BRIGHT);
        }
    }
}
