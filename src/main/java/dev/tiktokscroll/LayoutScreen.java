package dev.tiktokscroll;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Press K in game:
 *  - MOVE/RESIZE mode: drag the video to move it, drag the bottom-right square or use the
 *    mouse wheel to resize (9:16 ratio is kept).
 *  - INTERACT mode: clicks/keys go to the TikTok page (log in, like, comments, ...).
 */
public class LayoutScreen extends Screen {
    private static final int HANDLE = 10;
    private static final int MIN_W = 70, MAX_W = 400;

    private boolean interact = false;
    private boolean dragging, resizing;
    private double grabDx, grabDy;
    private Button modeButton;

    public LayoutScreen() { super(Component.literal("TikTok Layout")); }

    @Override
    protected void init() {
        modeButton = addRenderableWidget(Button.builder(modeText(), b -> {
            interact = !interact;
            b.setMessage(modeText());
        }).bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    private Component modeText() {
        return Component.translatable(interact ? "tiktokscroll.mode.interact" : "tiktokscroll.mode.layout");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        Config c = Config.INSTANCE;
        TikTokScrollMod.drawOverlay(g, c);
        if (!interact) {
            int x2 = c.x + c.width, y2 = c.y + c.height();
            // outline (4 thin rectangles)
            g.fill(c.x, c.y, x2, c.y + 1, 0xFFFFFFFF);
            g.fill(c.x, y2 - 1, x2, y2, 0xFFFFFFFF);
            g.fill(c.x, c.y, c.x + 1, y2, 0xFFFFFFFF);
            g.fill(x2 - 1, c.y, x2, y2, 0xFFFFFFFF);
            g.fill(x2 - HANDLE, y2 - HANDLE, x2, y2, 0xFFFFFFFF);
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override public boolean isPauseScreen() { return false; }

    private boolean inside(double mx, double my) {
        Config c = Config.INSTANCE;
        return mx >= c.x && mx <= c.x + c.width && my >= c.y && my <= c.y + c.height();
    }

    private int bx(double mx) { Config c = Config.INSTANCE; return (int) ((mx - c.x) * McefBridge.BROWSER_W / c.width); }
    private int by(double my) { Config c = Config.INSTANCE; return (int) ((my - c.y) * McefBridge.BROWSER_H / c.height()); }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (super.mouseClicked(e, dbl)) return true;
        double mx = e.x(), my = e.y();
        Config c = Config.INSTANCE;
        if (interact) {
            if (inside(mx, my)) { McefBridge.mouseButton(bx(mx), by(my), e.button(), true); return true; }
            return false;
        }
        int x2 = c.x + c.width, y2 = c.y + c.height();
        if (mx >= x2 - HANDLE && mx <= x2 && my >= y2 - HANDLE && my <= y2) { resizing = true; return true; }
        if (inside(mx, my)) { dragging = true; grabDx = mx - c.x; grabDy = my - c.y; return true; }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        Config c = Config.INSTANCE;
        if (interact) { McefBridge.mouseMove(bx(e.x()), by(e.y())); return true; }
        if (dragging) {
            c.x = (int) Math.max(0, Math.min(this.width - c.width, e.x() - grabDx));
            c.y = (int) Math.max(0, Math.min(this.height - c.height(), e.y() - grabDy));
            return true;
        }
        if (resizing) {
            c.width = (int) Math.max(MIN_W, Math.min(MAX_W, e.x() - c.x));
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (interact) { McefBridge.mouseButton(bx(e.x()), by(e.y()), e.button(), false); }
        if (dragging || resizing) Config.save();
        dragging = resizing = false;
        return super.mouseReleased(e);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (interact && inside(mx, my)) McefBridge.mouseMove(bx(mx), by(my));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Config c = Config.INSTANCE;
        if (interact) {
            if (inside(mx, my)) { McefBridge.mouseWheel(bx(mx), by(my), sy); return true; }
            return false;
        }
        c.width = (int) Math.max(MIN_W, Math.min(MAX_W, c.width + sy * 10));
        Config.save();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (interact && e.key() != 256 /* ESC */) {
            McefBridge.keyPress(e.key(), e.scancode(), e.modifiers(), true);
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        if (interact) McefBridge.keyPress(e.key(), e.scancode(), e.modifiers(), false);
        return super.keyReleased(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (interact) { McefBridge.charTyped((char) e.codepoint(), e.modifiers()); return true; }
        return super.charTyped(e);
    }

    @Override public void removed() { Config.save(); }
}
