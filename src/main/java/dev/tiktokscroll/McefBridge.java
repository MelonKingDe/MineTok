package dev.tiktokscroll;

import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * ALL calls into MCEF Modern live in this one class.
 * The calls marked "ADAPT" were written without being able to compile against 26.2,
 * so if the compiler complains, only this file needs fixing. Look at the "Test Mod"
 * in https://github.com/DimasKama/mcef-modern for the exact method names.
 */
public final class McefBridge {
    /** Internal browser resolution (portrait, like a phone). The overlay just scales it. */
    public static final int BROWSER_W = 540;
    public static final int BROWSER_H = 960;

    private static MCEFBrowser browser;
    private static boolean creating;
    private static int ticks;

    private McefBridge() {}

    public static boolean isReady() { return browser != null; }

    public static String statusText() {
        // ADAPT: MCEF Modern exposes an async init future with stage/percentage.
        return "Loading browser...";
    }

    /** Call every client tick while the overlay is visible. */
    public static void tick(boolean autoActive) {
        if (browser == null) {
            if (!creating && MCEFApi.isInitialized()) { // ADAPT
                creating = true;
                browser = MCEFApi.createBrowser(Config.INSTANCE.url, false); // ADAPT: (url, transparent)
                browser.resize(BROWSER_W, BROWSER_H);                        // ADAPT
                browser.setFrameRate(30);                                    // ADAPT: setWindowlessFrameRate(30) in JCEF
            }
            return;
        }
        ticks++;
        if (ticks % 40 == 0) run(Scripts.BOOT);          // every 2 s (idempotent)
        if (ticks % 20 == 0) run(Scripts.setAuto(autoActive)); // every 1 s
    }

    public static void run(String js) {
        if (browser != null) browser.executeJavaScript(js, browser.getURL(), 0); // ADAPT
    }

    public static void next() { run(Scripts.NEXT); }
    public static void prev() { run(Scripts.PREV); }

    public static void close() {
        if (browser != null) { browser.close(); browser = null; creating = false; } // ADAPT
    }

    /** Draw the browser texture scaled into the given GUI rectangle. */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        if (browser == null) return;
        // ADAPT: draw the browser's texture. MCEF Modern 26.x provides a texture/texture view
        // that can be blitted. Example shape of the call (pipeline + texture id + dest rect + UV):
        // g.blit(RenderPipelines.GUI_TEXTURED, browser.getTextureId(), x, y, 0, 0, w, h, BROWSER_W, BROWSER_H);
        browser.render(g, x, y, w, h, alpha); // ADAPT
    }

    // ---- input forwarding (coordinates already converted to browser pixels) ----
    public static void mouseMove(int bx, int by)                    { if (browser != null) browser.sendMouseMove(bx, by); }
    public static void mouseButton(int bx, int by, int btn, boolean down) { if (browser != null) browser.sendMouseButton(bx, by, btn, down); }
    public static void mouseWheel(int bx, int by, double delta)     { if (browser != null) browser.sendMouseWheel(bx, by, delta); }
    public static void keyPress(int glfwKey, int scancode, int mods, boolean down) { if (browser != null) browser.sendKey(glfwKey, scancode, mods, down); }
    public static void charTyped(char c, int mods)                  { if (browser != null) browser.sendChar(c, mods); }
}
