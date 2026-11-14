# TikTok Scroll (Fabric, Minecraft 26.2)

Shows TikTok in an in-game overlay. Auto-scrolls to the next video while you are actively playing.

## Requirements (all in the mods folder)
1. This mod (tiktok-scroll-*.jar)
2. mcef-codec-*.jar  (Chromium with H.264/AAC; built together with this mod, see build/libs)
3. Fabric API

## Keys (Controls -> "TikTok Scroll")
O show/hide | ] next | [ previous | \ toggle auto-scroll | K layout / interact screen

## K screen
Interact (default): click, log in, like.  Move/Resize: drag video / bottom-right square / mouse wheel.

## Config: config/tiktokscroll.json
activeWindowSeconds, volume (0..2), opacity, url.

## Build
./gradlew build  -> build/libs/ contains the mod jar AND mcef-codec jar.
