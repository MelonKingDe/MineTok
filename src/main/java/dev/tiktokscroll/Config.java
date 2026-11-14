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
    public static final float ASPECT = 9f / 16f; // width / height

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
    /** Sound volume 0..2 (1 = normal). */
    public float volume = 1.0f;

    public int height() { return Math.round(width / ASPECT); }

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
