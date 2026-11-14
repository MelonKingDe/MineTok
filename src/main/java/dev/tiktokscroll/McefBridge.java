package dev.tiktokscroll;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.lang.reflect.Method;

/** All calls into MCEF Modern live here (API verified from the real jar). */
public final class McefBridge {
    /** Internal browser resolution (portrait, like a phone). The overlay just scales it. */
    public static final int BROWSER_W = 540;
    public static final int BROWSER_H = 960;

    private static MCEFApi.Initialization init;
    private static MCEFApi api;
    private static MCEFBrowser browser;
    private static boolean initStarted;
    private static int ticks;

    // reflection cache for the texture blit (signature differs between MC versions)
    private static Method blitMethod;
    private static boolean blitSearched;

    private McefBridge() {}

    public static boolean isReady() { return browser != null; }

    /** 0..1 download/initialisation progress. */
    public static float progress() {
        if (init == null) return 0f;
        try {
            float p = init.getPercentage();
            return p > 1.5f ? p / 100f : Math.max(0f, Math.min(1f, p));
        } catch (Throwable t) { return 0f; }
    }

    /** Call every client tick while in a world. */
    public static void tick(boolean autoActive) {
        if (browser == null) {
            try {
                if (!initStarted) { initStarted = true; init = MCEFApi.initialize(); }
                if (api == null && init != null && init.isDone()) api = init.getFuture().getNow(null);
                if (api != null) {
                    browser = api.createBrowser(Config.INSTANCE.url, false);
                    browser.resize(BROWSER_W, BROWSER_H);
                    setFrameRate(30);
                    TikTokScrollMod.LOGGER.info("TikTok browser created");
                }
            } catch (Throwable t) {
                TikTokScrollMod.LOGGER.error("MCEF init failed", t);
                initStarted = true; // don't retry every tick
            }
            return;
        }
        ticks++;
        if (ticks % 40 == 0) run(Scripts.BOOT);                 // every 2 s (idempotent)
        if (ticks % 20 == 0) run(Scripts.setAuto(autoActive));  // every 1 s
    }

    // ---- JavaScript (via the CefBrowser, reflection so jcef need not be on the compile classpath) ----
    public static void run(String js) {
        if (browser == null) return;
        try {
            Object cef = browser.getCefBrowser();
            String url = (String) cef.getClass().getMethod("getURL").invoke(cef);
            cef.getClass().getMethod("executeJavaScript", String.class, String.class, int.class).invoke(cef, js, url, 0);
        } catch (Throwable t) {
            TikTokScrollMod.LOGGER.debug("JS failed: {}", t.toString());
        }
    }

    private static void setFrameRate(int fps) {
        try {
            Object cef = browser.getCefBrowser();
            cef.getClass().getMethod("setWindowlessFrameRate", int.class).invoke(cef, fps);
        } catch (Throwable ignored) {}
    }

    public static void next() { run(Scripts.NEXT); }
    public static void prev() { run(Scripts.PREV); }

    public static void close() {
        if (browser != null) { try { browser.close(); } catch (Throwable ignored) {} browser = null; }
    }

    public static void setFocus(boolean f) { if (browser != null) browser.setFocus(f); }

    // ---- input forwarding (coordinates are already in browser pixels) ----
    public static void mouseMove(int bx, int by) { if (browser != null) browser.onMouseMoved(bx, by); }
    public static void mouseWheel(int bx, int by, double delta) { if (browser != null) browser.onMouseScrolled(bx, by, delta); }
    public static void mouseClicked(MouseButtonEvent e, boolean dbl, int bx, int by) {
        if (browser != null) browser.onMouseClicked(new MouseButtonEvent(bx, by, e.buttonInfo()), dbl);
    }
    public static void mouseReleased(MouseButtonEvent e, int bx, int by) {
        if (browser != null) browser.onMouseReleased(new MouseButtonEvent(bx, by, e.buttonInfo()));
    }
    public static void keyPressed(KeyEvent e)   { if (browser != null) browser.onKeyPressed(e); }
    public static void keyReleased(KeyEvent e)  { if (browser != null) browser.onKeyReleased(e); }
    public static void charTyped(CharacterEvent e) { if (browser != null) browser.onCharTyped(e); }

    // ---- drawing ----
    public static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        if (browser == null) return;
        GpuTextureView view = browser.getTextureView();
        if (view == null) return;
        try {
            if (!blitSearched) { blitSearched = true; blitMethod = findBlit(); }
            if (blitMethod == null) return;
            Class<?>[] p = blitMethod.getParameterTypes();
            Object[] a = new Object[p.length];
            int[] ints = { x, y, x + w, y + h };
            float[] fl = { 0f, 1f, 0f, 1f };
            int ii = 0, ff = 0;
            for (int i = 0; i < p.length; i++) {
                String n = p[i].getSimpleName();
                if (p[i] == GpuTextureView.class) a[i] = view;
                else if (n.equals("RenderPipeline")) a[i] = pipeline();
                else if (n.equals("GpuSampler")) a[i] = sampler();
                else if (p[i] == int.class) a[i] = ints[ii++];
                else if (p[i] == float.class) a[i] = fl[ff++];
            }
            blitMethod.invoke(g, a);
        } catch (Throwable t) {
            TikTokScrollMod.LOGGER.error("Drawing the browser texture failed", t);
            blitMethod = null;
        }
    }

    private static Method findBlit() {
        Method best = null;
        for (Method m : GuiGraphicsExtractor.class.getMethods()) {
            if (!m.getName().equals("blit")) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean view = false, ok = true; int ints = 0, floats = 0;
            for (Class<?> c : p) {
                if (c == GpuTextureView.class) view = true;
                else if (c == int.class) ints++;
                else if (c == float.class) floats++;
                else if (!c.getSimpleName().equals("RenderPipeline") && !c.getSimpleName().equals("GpuSampler")) ok = false;
            }
            if (ok && view && ints == 4 && (floats == 4 || floats == 0)) { best = m; if (floats == 4) break; }
        }
        if (best == null) {
            TikTokScrollMod.LOGGER.error("No suitable GuiGraphicsExtractor.blit(GpuTextureView ...) found. Candidates:");
            for (Method m : GuiGraphicsExtractor.class.getMethods())
                if (m.getName().equals("blit")) TikTokScrollMod.LOGGER.error("  {}", m);
        }
        return best;
    }

    private static Object pipeline() throws Exception {
        return Class.forName("net.minecraft.client.renderer.RenderPipelines").getField("GUI_TEXTURED").get(null);
    }

    private static Object sampler() throws Exception {
        Class<?> rs = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
        Object cache = rs.getMethod("getSamplerCache").invoke(null);
        Class<?> fm = Class.forName("com.mojang.blaze3d.textures.FilterMode");
        Object linear = fm.getField("LINEAR").get(null);
        return cache.getClass().getMethod("getClampToEdge", fm).invoke(cache, linear);
    }
}
