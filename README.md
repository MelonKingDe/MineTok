# TikTok Scroll (Fabric, Minecraft 26.2)

Shows TikTok in an in-game overlay. Auto-scrolls to the next video while you are actively playing.

## Requirements
Java 25, Fabric Loader, Fabric API, MCEF Modern (0.3.3+mc26.2.jcef146.0.10).
Chromium (~150 MB) downloads on first start.

## Keys (Controls -> "TikTok Scroll")
O show/hide | ] next | [ previous | \ toggle auto-scroll | K layout / interact screen

## K screen
Move/Resize: drag video to move, drag bottom-right square or mouse wheel to resize.
Interact: click, log in, like.

## Config: config/tiktokscroll.json
activeWindowSeconds: auto-scroll only runs if you moved/looked/clicked within this time.

## Build
1. Versions in gradle.properties are already set for 26.2 (Loader 0.20.1, Loom 1.19-SNAPSHOT, Fabric API 0.161.0+26.2)
2. ./gradlew build  ->  build/libs/tiktok-scroll-1.0.0.jar

## Known limits
- Stock Chromium has no H.264 codec. If TikTok videos stay black, use the codec build of the
  browser library (menntdev/mcef-codec, 26.2) instead of MCEF Modern.
- TikTok may show login/captcha; use the K screen's Interact mode.
