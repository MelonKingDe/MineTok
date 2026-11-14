package dev.tiktokscroll;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge to the "mcef-codec" mod (Chromium with H.264/AAC, needed for TikTok).
 * Everything is called through reflection, so this mod compiles without the codec jar;
 * at runtime the player just needs the mcef-codec jar in the mods folder.
 */
public final class McefBridge {
    /** Internal browser resolution (portrait, like a phone). The overlay just scales it. */
    public static final int BROWSER_W = 540;
    public static final int BROWSER_H = 960;

    private static final String SERVICE = "com.doomscroll.cef.api.CefService";

    private static Object init;
    private static Object browser;
    private static boolean initStarted, failed, audioStarted;
    private static volatile boolean audioOn = true;
    private static boolean lastVisible = true;
    private static int ticks;

    private static Method blitMethod;
    private static boolean blitSearched;
    private static Object samplerCached;

    private McefBridge() {}

    public static boolean isReady() { return browser != null; }

    /** 0..1 download/initialisation progress. */
    public static float progress() {
        if (init == null) return 0f;
        try {
            float p = ((Number) invoke(init, "getPercentage")).floatValue();
            return p > 1.5f ? p / 100f : Math.max(0f, Math.min(1f, p));
        } catch (Throwable t) { return 0f; }
    }

    /** Call when not in a world: stops the sound. */
    public static void silence() { audioOn = false; }

    /** Call every client tick while in a world. */
    public static void tick(boolean autoActive, boolean visible) {
        audioOn = visible;
        if (browser == null) {
            if (failed) return;
            try {
                if (!initStarted) {
                    initStarted = true;
                    init = Class.forName(SERVICE).getMethod("initialize").invoke(null);
                }
                if (init != null && (Boolean) invoke(init, "isDone")) {
                    Object service = invoke(invoke(init, "getFuture"), "join");
                    try {
                        browser = invoke(service, "createBrowser", Config.INSTANCE.url, false, false);
                    } catch (NoSuchMethodException e) {
                        browser = invoke(service, "createBrowser", Config.INSTANCE.url, false);
                    }
                    invoke(browser, "resize", BROWSER_W, BROWSER_H);
                    try { invoke(browser, "setFrameRate", 30); } catch (Throwable ignored) {}
                    startAudio();
                    TikTokScrollMod.LOGGER.info("TikTok browser created");
                }
            } catch (ClassNotFoundException e) {
                failed = true;
                TikTokScrollMod.LOGGER.error("mcef-codec is not installed. Put the mcef-codec jar into the mods folder.");
            } catch (Throwable t) {
                failed = true;
                TikTokScrollMod.LOGGER.error("Browser init failed", t);
            }
            return;
        }
        ticks++;
        if (ticks % 40 == 0) run(Scripts.BOOT);                 // every 2 s (idempotent)
        if (ticks % 20 == 0) run(Scripts.setAuto(autoActive && visible));
        if (visible != lastVisible) {                           // pause video while hidden
            lastVisible = visible;
            run(visible ? Scripts.RESUME : Scripts.PAUSE);
        }
    }

    // ---- JavaScript ----
    public static void run(String js) {
        if (browser == null) return;
        try {
            Object cef = invoke(browser, "getCefBrowser");
            String url = (String) invoke(cef, "getURL");
            invoke(cef, "executeJavaScript", js, url, 0);
        } catch (Throwable t) {
            TikTokScrollMod.LOGGER.debug("JS failed: {}", t.toString());
        }
    }

    public static void next() { run(Scripts.NEXT); }
    public static void prev() { run(Scripts.PREV); }

    public static void close() {
        Object b = browser;
        browser = null; // also ends the audio thread
        if (b != null) { try { invoke(b, "close"); } catch (Throwable ignored) {} }
    }

    // ---- input forwarding (coordinates are already in browser pixels) ----
    private static void tryInvoke(String name, Object... a) {
        if (browser == null) return;
        try { invoke(browser, name, a); } catch (Throwable t) { TikTokScrollMod.LOGGER.debug("{} failed: {}", name, t.toString()); }
    }
    public static void setFocus(boolean f) { tryInvoke("setFocus", f); }
    public static void mouseMove(int bx, int by) { tryInvoke("onMouseMoved", bx, by); }
    public static void mouseWheel(int bx, int by, double delta) { tryInvoke("onMouseScrolled", bx, by, delta); }
    public static void mouseClicked(MouseButtonEvent e, boolean dbl, int bx, int by) {
        tryInvoke("onMouseClicked", new MouseButtonEvent(bx, by, e.buttonInfo()), dbl);
    }
    public static void mouseReleased(MouseButtonEvent e, int bx, int by) {
        tryInvoke("onMouseReleased", new MouseButtonEvent(bx, by, e.buttonInfo()));
    }
    public static void keyPressed(KeyEvent e)      { tryInvoke("onKeyPressed", e); }
    public static void keyReleased(KeyEvent e)     { tryInvoke("onKeyReleased", e); }
    public static void charTyped(CharacterEvent e) { tryInvoke("onCharTyped", e); }

    // ---- audio: the codec library hands out mono 16-bit PCM; we play it with javax.sound ----
    private static void startAudio() {
        if (audioStarted) return;
        audioStarted = true;
        Thread t = new Thread(McefBridge::audioLoop, "tiktokscroll-audio");
        t.setDaemon(true);
        t.start();
    }

    private static void audioLoop() {
        SourceDataLine line = null;
        int rate = 0;
        ByteBuffer buf = null;
        int chunk = 0;
        try {
            while (browser != null) {
                try {
                    Object b = browser;
                    if (b == null) break;
                    boolean has = (Boolean) invoke(b, "hasAudioStream");
                    int r = ((Number) invoke(b, "audioSampleRate")).intValue();
                    if (!has || r <= 0) { Thread.sleep(50); continue; }
                    if (line == null || r != rate) {
                        if (line != null) { line.drain(); line.close(); }
                        rate = r;
                        AudioFormat f = new AudioFormat(rate, 16, 1, true, false);
                        line = AudioSystem.getSourceDataLine(f);
                        line.open(f, (rate / 10) * 2);  // ~100 ms buffer
                        line.start();
                        chunk = (rate * 20 / 1000) * 2; // 20 ms
                        buf = ByteBuffer.allocateDirect(chunk);
                    }
                    buf.clear();
                    invoke(b, "readAudio", buf, chunk);
                    buf.flip();
                    if (!audioOn) continue;             // keep draining, but stay silent
                    byte[] out = new byte[buf.remaining()];
                    buf.get(out);
                    applyVolume(out, Config.INSTANCE.volume);
                    line.write(out, 0, out.length);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    TikTokScrollMod.LOGGER.debug("audio: {}", t.toString());
                    Thread.sleep(200);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (line != null) { line.stop(); line.close(); }
            audioStarted = false;
        }
    }

    private static void applyVolume(byte[] pcm, float gain) {
        if (Math.abs(gain - 1f) < 0.01f) return;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            s = Math.max(-32768, Math.min(32767, Math.round(s * gain)));
            pcm[i] = (byte) s;
            pcm[i + 1] = (byte) (s >> 8);
        }
    }

    // ---- drawing ----
    public static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        if (browser == null) return;
        try {
            Object view = invoke(browser, "getTextureView");
            if (view == null) return;
            if (!blitSearched) { blitSearched = true; blitMethod = findBlit(); }
            if (blitMethod == null) return;
            Class<?>[] p = blitMethod.getParameterTypes();
            Object[] a = new Object[p.length];
            int[] ints = { x, y, x + w, y + h };
            float[] fl = { 0f, 1f, 0f, 1f };
            int ii = 0, ff = 0;
            for (int i = 0; i < p.length; i++) {
                String n = p[i].getSimpleName();
                if (n.equals("GpuTextureView")) a[i] = view;
                else if (n.equals("RenderPipeline")) a[i] = pipeline();
                else if (n.equals("GpuSampler")) a[i] = sampler();
                else if (p[i] == int.class) a[i] = ints[ii++];
                else if (p[i] == float.class) a[i] = fl[ff++];
            }
            blitMethod.invoke(g, a);
        } catch (Throwable t) {
            TikTokScrollMod.LOGGER.error("Drawing the browser texture failed", t);
            blitMethod = null;
            blitSearched = false;
        }
    }

    private static Method findBlit() {
        Method best = null;
        for (Method m : GuiGraphicsExtractor.class.getMethods()) {
            if (!m.getName().equals("blit")) continue;
            boolean view = false, ok = true; int ints = 0, floats = 0;
            for (Class<?> c : m.getParameterTypes()) {
                String n = c.getSimpleName();
                if (n.equals("GpuTextureView")) view = true;
                else if (c == int.class) ints++;
                else if (c == float.class) floats++;
                else if (!n.equals("RenderPipeline") && !n.equals("GpuSampler")) ok = false;
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
        if (samplerCached != null) return samplerCached;
        Class<?> rs = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
        Class<?> fm = Class.forName("com.mojang.blaze3d.textures.FilterMode");
        Object linear = fm.getField("LINEAR").get(null);
        Object cache = rs.getMethod("getSamplerCache").invoke(null);
        samplerCached = cache.getClass().getMethod("getClampToEdge", fm).invoke(cache, linear);
        return samplerCached;
    }

    // ---- reflection helper: finds a public/accessible method by name + argument types ----
    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        StringBuilder key = new StringBuilder(target.getClass().getName()).append('#').append(name);
        for (Object a : args) key.append('|').append(a == null ? "null" : a.getClass().getName());
        Method m = CACHE.get(key.toString());
        if (m == null) {
            m = find(target.getClass(), name, args);
            CACHE.put(key.toString(), m);
        }
        return m.invoke(target, args);
    }

    private static Method find(Class<?> c, String name, Object[] args) throws NoSuchMethodException {
        for (Method m : c.getMethods()) if (matches(m, name, args)) return access(m);
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) if (matches(m, name, args)) return access(m);
        throw new NoSuchMethodException(c.getName() + "." + name + "/" + args.length);
    }

    private static Method access(Method m) { try { m.setAccessible(true); } catch (Throwable ignored) {} return m; }

    private static boolean matches(Method m, String name, Object[] args) {
        if (!m.getName().equals(name) || m.getParameterCount() != args.length) return false;
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (args[i] == null) { if (p[i].isPrimitive()) return false; continue; }
            if (!wrap(p[i]).isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == boolean.class) return Boolean.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == long.class) return Long.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        return Character.class;
    }
}
