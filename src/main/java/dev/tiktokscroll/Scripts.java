package dev.tiktokscroll;

/** JavaScript injected into the TikTok page. */
public final class Scripts {
    private Scripts() {}

    /** Idempotent bootstrap. Re-injected regularly because TikTok is a single-page app. */
    public static final String BOOT = """
    (function(){
      if (window.__tts) return;
      const S = window.__tts = { auto:false, lastSkip:0 };
      function current(){
        const cy = innerHeight/2; let best=null, bd=1e9;
        for (const v of document.querySelectorAll('video')) {
          const r = v.getBoundingClientRect();
          if (r.height <= 0) continue;
          const d = Math.abs((r.top+r.bottom)/2 - cy);
          if (d < bd) { bd = d; best = v; }
        }
        return best;
      }
      function key(k, code){
        const o = { key:k, code:k, keyCode:code, which:code, bubbles:true, cancelable:true };
        document.dispatchEvent(new KeyboardEvent('keydown', o));
        document.dispatchEvent(new KeyboardEvent('keyup', o));
      }
      S.next = function(){
        S.lastSkip = Date.now();
        const b = document.querySelector('button[data-e2e="arrow-right"]');
        if (b) { b.click(); return; }
        key('ArrowDown', 40);
      };
      S.resume = function(){ const v = current(); if (v) v.play(); };
      S.prev = function(){
        S.lastSkip = Date.now();
        const b = document.querySelector('button[data-e2e="arrow-left"]');
        if (b) { b.click(); return; }
        key('ArrowUp', 38);
      };
      setInterval(function(){
        const v = current();
        if (!v) return;
        v.loop = !S.auto;                       // TikTok loops videos; we remove the loop while auto-scroll is active
        if (!S.auto) return;
        const done = v.ended || (v.duration > 0 && v.currentTime >= v.duration - 0.12);
        if (done && Date.now() - S.lastSkip > 1500) S.next();
      }, 250);
    })();
    """;

    public static String setAuto(boolean on) {
        return "window.__tts && (window.__tts.auto = " + on + ");";
    }
    public static final String PAUSE = "document.querySelectorAll('video').forEach(function(v){v.pause();});";
    public static final String RESUME = "window.__tts && window.__tts.resume();";
    public static final String NEXT = "window.__tts && window.__tts.next();";
    public static final String PREV = "window.__tts && window.__tts.prev();";
}
