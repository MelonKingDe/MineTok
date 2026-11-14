package dev.tiktokscroll;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Saved to config/tiktokscroll.json. Positions/sizes are in GUI-scaled pixels. */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tiktokscroll.json");

    public static Config INSTANCE = new Config();

    public boolean visible = true;
    public boolean autoScroll = true;
    /** Auto-scroll only runs if you did something (moved, looked, clicked) in the last N seconds. */
    public int activeWindowSeconds = 20;
    public String url = "https://www.tiktok.com/foryou";
    public int x = 10;
    public int y = 40;
    public int width = 135;
    public float opacity = 1.0f;
    /** Sound volume 0..1 (applied to the TikTok video element). */
    public float volume = 1.0f;
    /** "brave" (default), "chrome", "edge" or "auto": which installed browser to use first (others are fallbacks). */
    public String browser = "brave";
    /** Path to chrome/msedge/brave. Empty = auto-detect. */
    public String chromePath = "";
    /** true = hidden Chrome (no window). Set false if the video does not play or has no sound. */
    public boolean headless = true;
    /** Set true if red and blue look swapped. */
    public boolean swapRedBlue = false;

    /** Crop of the 540x960 browser page, in browser pixels: hides TikTok's sidebar, top bar and player controls. */
    public int cropLeft = 82;
    public int cropTop = 72;
    public int cropRight = 0;
    public int cropBottom = 80;

    public int cropW() { return Math.max(50, ChromeBridge.BROWSER_W - cropLeft - cropRight); }
    public int cropH() { return Math.max(50, ChromeBridge.BROWSER_H - cropTop - cropBottom); }
    /** Overlay height follows the cropped aspect ratio. */
    public int height() { return Math.round(width * (float) cropH() / cropW()); }

    public static void load() {
        try {
            if (Files.exists(FILE)) INSTANCE = GSON.fromJson(Files.readString(FILE), Config.class);
        } catch (Exception e) {
            TikTokScrollMod.LOGGER.warn("Could not read config, using defaults", e);
        }
        if (INSTANCE == null) INSTANCE = new Config();
        save();
    }

    public static void save() {
        try { Files.writeString(FILE, GSON.toJson(INSTANCE)); }
        catch (Exception e) { TikTokScrollMod.LOGGER.warn("Could not save config", e); }
    }
}
