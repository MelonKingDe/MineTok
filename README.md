# TikTok Scroll (Fabric, Minecraft 26.2)

Shows TikTok in an in-game overlay and auto-scrolls to the next video while you are actively playing.

## How it works
The mod starts the Google Chrome (or Microsoft Edge) that is already installed on your PC, hidden, with its own
profile folder (<game dir>/tiktokscroll-profile). It reads the page as screenshots over the DevTools protocol
(127.0.0.1 only) and sends your mouse/keyboard input back. Chrome plays the sound itself.
No third-party browser library is needed.

## Requirements
Fabric API + Google Chrome, Microsoft Edge or Brave installed.

## Keys (Controls -> "TikTok Scroll")
O show/hide | ] next | [ previous | \ toggle auto-scroll | K layout / interact screen

## K screen
Interact (default): click, log in, like. Move/Resize: drag video / bottom-right square / mouse wheel.

## Config: config/tiktokscroll.json
activeWindowSeconds, volume (0..1), opacity, url, browser ("brave" default, "chrome", "edge", "auto"), chromePath, headless, swapRedBlue.
If the video has no sound in headless mode, set "headless": false (Chrome then runs in a window off-screen).

## Build
./gradlew build  -> build/libs/tiktok-scroll-*.jar
