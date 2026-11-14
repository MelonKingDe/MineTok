package dev.tiktokscroll;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses the Chrome / Edge that is already installed on the PC (it has H.264 and plays its own sound).
 * It is started hidden (headless) with a separate profile; this class talks to it over the DevTools
 * protocol on 127.0.0.1: screencast frames in, mouse/keyboard events out.
 */
public final class ChromeBridge {
    public static final int BROWSER_W = 540;
    public static final int BROWSER_H = 960;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Identifier TEX_ID = Identifier.fromNamespaceAndPath("tiktokscroll", "browser");

    private static volatile boolean started, ready, failed, frameSeen;
    private static volatile float progress;
    private static Process process;
    private static volatile WebSocket ws;
    private static final AtomicInteger IDS = new AtomicInteger(1);
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tiktokscroll-send"); t.setDaemon(true); return t;
    });

    private static volatile int[] pendingPixels;
    private static volatile int pendingW, pendingH;
    private static NativeImage image;
    private static DynamicTexture texture;
    private static int texW, texH;
    private static Method blitMethod;
    private static boolean blitSearched;

    private static boolean lastVisible = true;
    private static int ticks;

    private ChromeBridge() {}

    public static boolean isReady() { return frameSeen; }
    public static float progress() { return progress; }

    // ------------------------------------------------------------------ lifecycle
    public static void silence() { setVisible(false); }

    public static void tick(boolean autoActive, boolean visible) {
        if (!started && !failed) {
            started = true;
            Thread t = new Thread(ChromeBridge::startup, "tiktokscroll-chrome");
            t.setDaemon(true);
            t.start();
        }
        if (!ready) return;
        ticks++;
        if (ticks % 40 == 0) run(Scripts.BOOT);
        if (ticks % 20 == 0) run(Scripts.setAuto(autoActive && visible, Config.INSTANCE.volume));
        setVisible(visible);
    }

    private static void setVisible(boolean visible) {
        if (!ready || visible == lastVisible) return;
        lastVisible = visible;
        run(visible ? Scripts.RESUME : Scripts.PAUSE);
    }

    private static void startup() {
        try {
            String exe = findChrome();
            if (exe == null) {
                failed = true;
                TikTokScrollMod.LOGGER.error("No Chrome/Edge/Brave found. Install one of them or set \"chromePath\" in config/tiktokscroll.json");
                return;
            }
            progress = 0.1f;
            int port;
            try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
            Path profile = FabricLoader.getInstance().getGameDir().resolve("tiktokscroll-profile");
            Files.createDirectories(profile);

            List<String> cmd = new ArrayList<>(List.of(exe,
                    "--remote-debugging-port=" + port,
                    "--user-data-dir=" + profile.toAbsolutePath(),
                    "--no-first-run", "--no-default-browser-check", "--disable-extensions",
                    "--autoplay-policy=no-user-gesture-required",
                    "--window-size=" + BROWSER_W + "," + BROWSER_H,
                    "--hide-scrollbars", "about:blank"));
            if (Config.INSTANCE.headless) cmd.add(1, "--headless=new");
            else {
                cmd.add(1, "--window-position=-12000,-12000");
                cmd.add(1, "--disable-backgrounding-occluded-windows");
                cmd.add(1, "--disable-renderer-backgrounding");
            }
            process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { Process p = process; if (p != null) p.destroy(); }));

            HttpClient http = HttpClient.newHttpClient();
            String wsUrl = null;
            for (int i = 0; i < 150 && wsUrl == null; i++) {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/json/list")).build(), HttpResponse.BodyHandlers.ofString());
                    JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
                    for (JsonElement e : arr) {
                        JsonObject o = e.getAsJsonObject();
                        if ("page".equals(o.get("type").getAsString()) && o.has("webSocketDebuggerUrl")) {
                            wsUrl = o.get("webSocketDebuggerUrl").getAsString();
                            break;
                        }
                    }
                } catch (Exception ignored) {}
                if (wsUrl == null) Thread.sleep(200);
            }
            if (wsUrl == null) { failed = true; TikTokScrollMod.LOGGER.error("Chrome did not open its DevTools port"); return; }
            progress = 0.5f;

            ws = http.newWebSocketBuilder().buildAsync(URI.create(wsUrl), new Listener()).join();
            JsonObject ua = new JsonObject(); ua.addProperty("userAgent", UA);
            send("Emulation.setUserAgentOverride", ua);
            JsonObject m = new JsonObject();
            m.addProperty("width", BROWSER_W); m.addProperty("height", BROWSER_H);
            m.addProperty("deviceScaleFactor", 1); m.addProperty("mobile", false);
            send("Emulation.setDeviceMetricsOverride", m);
            send("Page.enable", null);
            JsonObject nav = new JsonObject(); nav.addProperty("url", Config.INSTANCE.url);
            send("Page.navigate", nav);
            JsonObject sc = new JsonObject();
            sc.addProperty("format", "jpeg"); sc.addProperty("quality", 75);
            sc.addProperty("maxWidth", BROWSER_W); sc.addProperty("maxHeight", BROWSER_H);
            sc.addProperty("everyNthFrame", 1);
            send("Page.startScreencast", sc);
            ready = true;
            progress = 0.8f;
            TikTokScrollMod.LOGGER.info("Chrome started: {}", exe);
        } catch (Throwable t) {
            failed = true;
            TikTokScrollMod.LOGGER.error("Could not start Chrome", t);
        }
    }

    public static void close() {
        ready = false; frameSeen = false;
        WebSocket w = ws; ws = null;
        if (w != null) { try { w.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Throwable ignored) {} }
        Process p = process; process = null;
        if (p != null) p.destroy();
    }

    private static String findChrome() {
        String cfg = Config.INSTANCE.chromePath;
        if (cfg != null && !cfg.isBlank() && Files.exists(Path.of(cfg))) return cfg;

        String want = Config.INSTANCE.browser == null ? "auto" : Config.INSTANCE.browser.toLowerCase();
        // name -> candidate paths
        java.util.LinkedHashMap<String, List<String>> c = new java.util.LinkedHashMap<>();
        for (String n : new String[] { "chrome", "edge", "brave" }) c.put(n, new ArrayList<>());
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            for (String env : new String[] { "ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA" }) {
                String b = System.getenv(env);
                if (b == null) continue;
                c.get("chrome").add(b + "\\Google\\Chrome\\Application\\chrome.exe");
                c.get("edge").add(b + "\\Microsoft\\Edge\\Application\\msedge.exe");
                c.get("brave").add(b + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
            }
        } else if (os.contains("mac")) {
            c.get("chrome").add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            c.get("edge").add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            c.get("brave").add("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser");
        } else {
            for (String d : new String[] { "/usr/bin/", "/usr/local/bin/", "/snap/bin/" }) {
                c.get("chrome").add(d + "google-chrome"); c.get("chrome").add(d + "google-chrome-stable");
                c.get("chrome").add(d + "chromium"); c.get("chrome").add(d + "chromium-browser");
                c.get("edge").add(d + "microsoft-edge");
                c.get("brave").add(d + "brave-browser"); c.get("brave").add(d + "brave");
            }
            c.get("brave").add("/opt/brave.com/brave/brave");
        }
        // the chosen browser first, then the others as fallback
        List<String> order = new ArrayList<>();
        if (c.containsKey(want)) order.add(want);
        for (String n : c.keySet()) if (!order.contains(n)) order.add(n);
        for (String n : order)
            for (String p : c.get(n)) if (Files.exists(Path.of(p))) { TikTokScrollMod.LOGGER.info("Using browser: {} ({})", n, p); return p; }
        return null;
    }

    // ------------------------------------------------------------------ DevTools protocol
    private static void send(String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("id", IDS.getAndIncrement());
        o.addProperty("method", method);
        if (params != null) o.add("params", params);
        String text = o.toString();
        SENDER.submit(() -> {
            WebSocket w = ws;
            if (w != null) { try { w.sendText(text, true).join(); } catch (Throwable ignored) {} }
        });
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder sb = new StringBuilder();

        @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            sb.append(data);
            if (last) { String msg = sb.toString(); sb.setLength(0); handle(msg); }
            w.request(1);
            return null;
        }
        @Override public void onError(WebSocket w, Throwable error) {
            TikTokScrollMod.LOGGER.warn("DevTools connection error: {}", error.toString());
        }
    }

    private static void handle(String msg) {
        if (!msg.contains("Page.screencastFrame\"")) return;
        try {
            JsonObject p = JsonParser.parseString(msg).getAsJsonObject().getAsJsonObject("params");
            JsonObject ack = new JsonObject();
            ack.addProperty("sessionId", p.get("sessionId").getAsInt());
            send("Page.screencastFrameAck", ack);
            byte[] jpg = Base64.getDecoder().decode(p.get("data").getAsString());
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(jpg));
            if (bi == null) return;
            int w = bi.getWidth(), h = bi.getHeight();
            int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
            pendingW = w; pendingH = h; pendingPixels = px;
            frameSeen = true; progress = 1f;
        } catch (Throwable t) {
            TikTokScrollMod.LOGGER.debug("frame: {}", t.toString());
        }
    }

    public static void run(String js) {
        if (!ready) return;
        JsonObject p = new JsonObject();
        p.addProperty("expression", js);
        send("Runtime.evaluate", p);
    }
    public static void next() { run(Scripts.NEXT); }
    public static void prev() { run(Scripts.PREV); }

    /** Overlay mouse position -> browser page pixel (accounts for the crop). */
    public static int toBx(double mouseX) {
        Config c = Config.INSTANCE;
        return c.cropLeft + (int) ((mouseX - c.x) * c.cropW() / c.width);
    }
    public static int toBy(double mouseY) {
        Config c = Config.INSTANCE;
        return c.cropTop + (int) ((mouseY - c.y) * c.cropH() / c.height());
    }

    // ------------------------------------------------------------------ input (browser pixels)
    public static void setFocus(boolean f) {}

    private static void mouse(String type, int x, int y, int button, int clicks, double dy) {
        if (!ready) return;
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("x", x); p.addProperty("y", y);
        p.addProperty("button", button == 1 ? "right" : button == 2 ? "middle" : "left");
        p.addProperty("clickCount", clicks);
        if (type.equals("mouseWheel")) { p.addProperty("deltaX", 0); p.addProperty("deltaY", dy); p.addProperty("button", "none"); }
        if (type.equals("mouseMoved")) p.addProperty("button", "none");
        send("Input.dispatchMouseEvent", p);
    }
    public static void mouseMove(int bx, int by) { mouse("mouseMoved", bx, by, 0, 0, 0); }
    public static void mouseWheel(int bx, int by, double delta) { mouse("mouseWheel", bx, by, 0, 0, -delta * 100.0); }
    public static void mouseClicked(MouseButtonEvent e, boolean dbl, int bx, int by) {
        mouse("mouseMoved", bx, by, 0, 0, 0);
        mouse("mousePressed", bx, by, e.button(), dbl ? 2 : 1, 0);
    }
    public static void mouseReleased(MouseButtonEvent e, int bx, int by) {
        mouse("mouseReleased", bx, by, e.button(), 1, 0);
    }

    /** GLFW key -> {windowsVirtualKeyCode, DOM key name} for non-text keys. */
    private static Object[] special(int glfw) {
        return switch (glfw) {
            case 259 -> new Object[] { 8, "Backspace" };
            case 258 -> new Object[] { 9, "Tab" };
            case 257, 335 -> new Object[] { 13, "Enter" };
            case 261 -> new Object[] { 46, "Delete" };
            case 263 -> new Object[] { 37, "ArrowLeft" };
            case 265 -> new Object[] { 38, "ArrowUp" };
            case 262 -> new Object[] { 39, "ArrowRight" };
            case 264 -> new Object[] { 40, "ArrowDown" };
            case 268 -> new Object[] { 36, "Home" };
            case 269 -> new Object[] { 35, "End" };
            default -> null;
        };
    }
    private static void key(KeyEvent e, boolean down) {
        Object[] s = special(e.key());
        if (!ready || s == null) return;
        JsonObject p = new JsonObject();
        p.addProperty("type", down ? "rawKeyDown" : "keyUp");
        p.addProperty("windowsVirtualKeyCode", (Integer) s[0]);
        p.addProperty("key", (String) s[1]);
        send("Input.dispatchKeyEvent", p);
    }
    public static void keyPressed(KeyEvent e) { key(e, true); }
    public static void keyReleased(KeyEvent e) { key(e, false); }
    public static void charTyped(CharacterEvent e) {
        if (!ready) return;
        JsonObject p = new JsonObject();
        p.addProperty("text", new String(Character.toChars(e.codepoint())));
        send("Input.insertText", p);
    }

    // ------------------------------------------------------------------ drawing
    private static void upload(int[] px, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (image == null || w != texW || h != texH) {
            image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            texture = new DynamicTexture(() -> "tiktokscroll", image);
            mc.getTextureManager().register(TEX_ID, texture);
            texW = w; texH = h;
        }
        boolean swap = Config.INSTANCE.swapRedBlue;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                if (swap) c = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF);
                image.setPixel(x, y, c);
            }
        }
        texture.upload();
    }

    public static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        int[] px = pendingPixels;
        if (px != null) {
            pendingPixels = null;
            try { upload(px, pendingW, pendingH); }
            catch (Throwable t) { TikTokScrollMod.LOGGER.error("Texture upload failed", t); }
        }
        if (texture == null) return;
        try {
            if (!blitSearched) { blitSearched = true; blitMethod = findBlit(); }
            if (blitMethod == null) return;
            Object pipeline = Class.forName("net.minecraft.client.renderer.RenderPipelines").getField("GUI_TEXTURED").get(null);
            // blit(pipeline, id, x, y, u, v, width, height, uWidth, vHeight, textureWidth, textureHeight)
            Config c = Config.INSTANCE;
            float f = texW / (float) BROWSER_W;   // texture may differ from 540x960
            blitMethod.invoke(g, pipeline, TEX_ID, x, y, c.cropLeft * f, c.cropTop * f, w, h,
                    Math.round(c.cropW() * f), Math.round(c.cropH() * f), texW, texH);
        } catch (Throwable t) {
            TikTokScrollMod.LOGGER.error("Drawing failed", t);
            blitMethod = null; blitSearched = false;
        }
    }

    private static Method findBlit() {
        for (Method m : GuiGraphicsExtractor.class.getMethods()) {
            if (!m.getName().equals("blit") || m.getParameterCount() != 12) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0].getSimpleName().equals("RenderPipeline") && p[1] == Identifier.class
                    && p[2] == int.class && p[3] == int.class && p[4] == float.class && p[5] == float.class) return m;
        }
        TikTokScrollMod.LOGGER.error("No blit(RenderPipeline, Identifier, ...12 args) found. Candidates:");
        for (Method m : GuiGraphicsExtractor.class.getMethods())
            if (m.getName().equals("blit")) TikTokScrollMod.LOGGER.error("  {}", m);
        return null;
    }
}
