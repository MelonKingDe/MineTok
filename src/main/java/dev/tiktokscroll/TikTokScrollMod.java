package dev.tiktokscroll;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TikTokScrollMod implements ClientModInitializer {
    public static final String ID = "tiktokscroll";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));

    private static KeyMapping toggle, next, prev, auto, layout;

    // activity tracking
    private static long lastActiveMs = 0;
    private static Vec3 lastPos = Vec3.ZERO;
    private static float lastYaw, lastPitch;

    @Override
    public void onInitializeClient() {
        Config.load();

        toggle = reg("toggle", GLFW.GLFW_KEY_O);
        next   = reg("next",   GLFW.GLFW_KEY_RIGHT_BRACKET);
        prev   = reg("prev",   GLFW.GLFW_KEY_LEFT_BRACKET);
        auto   = reg("auto",   GLFW.GLFW_KEY_BACKSLASH);
        layout = reg("layout", GLFW.GLFW_KEY_K);

        ClientTickEvents.END_CLIENT_TICK.register(TikTokScrollMod::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> McefBridge.close());

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(ID, "overlay"), (graphics, tickCounter) -> {
                    Config c = Config.INSTANCE;
                    if (!c.visible) return;
                    if (Minecraft.getInstance().screen instanceof LayoutScreen) return; // the screen draws it itself
                    drawOverlay(graphics, c);
                });
    }

    static void drawOverlay(net.minecraft.client.gui.GuiGraphics g, Config c) {
        if (McefBridge.isReady()) {
            McefBridge.draw(g, c.x, c.y, c.width, c.height(), c.opacity);
        } else {
            g.fill(c.x, c.y, c.x + c.width, c.y + c.height(), 0xAA000000);
            g.drawString(Minecraft.getInstance().font, Component.translatable("tiktokscroll.loading"),
                    c.x + 6, c.y + 6, 0xFFFFFFFF);
        }
    }

    private static KeyMapping reg(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key." + ID + "." + name, InputConstants.Type.KEYSYM, key, CATEGORY));
    }

    public static boolean isActivelyPlaying() {
        return System.currentTimeMillis() - lastActiveMs < Config.INSTANCE.activeWindowSeconds * 1000L;
    }

    private static void trackActivity(Minecraft mc) {
        var p = mc.player;
        if (p == null || mc.screen != null || mc.isPaused()) return;
        Vec3 pos = p.position();
        boolean moved = pos.distanceToSqr(lastPos) > 1.0E-4;
        boolean looked = Math.abs(p.getYRot() - lastYaw) > 0.01f || Math.abs(p.getXRot() - lastPitch) > 0.01f;
        boolean keys = mc.options.keyAttack.isDown() || mc.options.keyUse.isDown() || mc.options.keyJump.isDown();
        if (moved || looked || keys) lastActiveMs = System.currentTimeMillis();
        lastPos = pos; lastYaw = p.getYRot(); lastPitch = p.getXRot();
    }

    private static void tick(Minecraft mc) {
        Config c = Config.INSTANCE;

        while (toggle.consumeClick()) { c.visible = !c.visible; Config.save(); }
        while (auto.consumeClick()) {
            c.autoScroll = !c.autoScroll; Config.save();
            if (mc.player != null)
                mc.player.displayClientMessage(Component.translatable(
                        c.autoScroll ? "tiktokscroll.auto.on" : "tiktokscroll.auto.off"), true);
        }
        while (next.consumeClick()) McefBridge.next();
        while (prev.consumeClick()) McefBridge.prev();
        while (layout.consumeClick()) { if (mc.screen == null) mc.setScreen(new LayoutScreen()); }

        trackActivity(mc);

        // The browser is kept alive (so audio keeps working) whenever we're in a world.
        if (mc.level != null) {
            boolean autoNow = c.autoScroll && isActivelyPlaying() && mc.screen == null;
            McefBridge.tick(autoNow);
        }
    }
}
