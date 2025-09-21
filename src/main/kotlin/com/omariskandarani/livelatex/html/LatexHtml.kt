package com.omariskandarani.livelatex.html

import java.io.File
import java.nio.file.Paths
import java.util.regex.Matcher
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Minimal LaTeX → HTML previewer for prose + MathJax math.
 * - Parses user \newcommand / \def into MathJax macros
 * - Converts common prose constructs (sections, lists, tables, theorems, etc.)
 * - Leaves math regions intact ($...$, \[...\], \(...\), equation/align/...)
 * - Inserts invisible line anchors to sync scroll with editor
 */
object LatexHtml {

    // Last computed line maps between original main file lines and merged (inlined) lines
    private var lineMapOrigToMergedJson: String? = null
    private var lineMapMergedToOrigJson: String? = null

    // ─────────────────────────── PUBLIC ENTRY ───────────────────────────

    private const val BEGIN_DOCUMENT = "\\begin{document}"
    private const val END_DOCUMENT = "\\end{document}"
    private const val LABEL_REGEX =  "\\\\label\\{[^}]*\\}"
    private const val EM_HTML = "<em>$1</em>"
    val rxNew = Regex(
        """\\newcommand\{\\([A-Za-z@]+)\}(?:\[(\d+)])?(?:\[[^\]]*])?\{(.+?)\}""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun slugify(s: String): String =
        s.lowercase()
            .replace(Regex("""\\[A-Za-z@]+"""), "")   // drop TeX control sequences
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')

    fun wrap(texSource: String): String {
        val srcNoComments = stripLineComments(texSource)
        val userMacros    = extractNewcommands(srcNoComments)
        val macrosJs      = buildMathJaxMacros(userMacros)
        val titleMeta     = extractTitleMeta(srcNoComments)
        val tikzPreamble  = collectTikzPreamble(srcNoComments)


        // Find body & absolute line offset of the first body line
        val beginIdx  = texSource.indexOf(BEGIN_DOCUMENT)
        val absOffset = if (beginIdx >= 0)
            texSource.substring(0, beginIdx).count { it == '\n' } + 1
        else
            1

        val body0 = stripPreamble(texSource)
        val body1 = stripLineComments(body0)
        val body2 = sanitizeForMathJaxProse(body1)
        val body2b = convertIncludeGraphics(body2) // <-- Add image conversion here
        val body2c = convertTikzPictures(body2b, srcNoComments)
        val body2d = convertSstTikzMacros(body2c, srcNoComments)
        val body3 = applyProseConversions(body2d, titleMeta, absOffset, srcNoComments)
        val body3b = convertParagraphsOutsideTags(body3)
        val body4 = applyInlineFormattingOutsideTags(body3b)
        val body4c = fixInlineBoundarySpaces(body4)
        // Insert anchors (no blanket escaping here; we preserve math)
        val withAnchors = injectLineAnchors(body4c, absOffset, everyN = 1)

        return buildHtml(withAnchors, macrosJs)
    }



    // ── Shared tiny helpers (define ONCE) ─────────────────────────────────────────
    private fun isEscaped(s: String, i: Int): Boolean {
        var k = i - 1
        var bs = 0
        while (k >= 0 && s[k] == '\\') { bs++; k-- }
        return (bs and 1) == 1   // odd number of backslashes → escaped
    }

    private fun skipWsAndComments(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            when (s[i]) {
                ' ', '\t', '\r', '\n' -> i++
                '%' -> {
                    if (!isEscaped(s, i)) {
                        while (i < s.length && s[i] != '\n') i++  // skip comment to EOL
                    } else return i
                }
                else -> return i
            }
        }
        return i
    }

    private fun findBalancedSquare(s: String, open: Int): Int {
        if (open < 0 || open >= s.length || s[open] != '[') return -1
        var i = open
        var depth = 0
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
                '\\' -> if (i + 1 < s.length) i++   // skip escaped char
            }
            i++
        }
        return -1
    }


    // ─────────────────────────── PAGE BUILDER ───────────────────────────

    private fun buildHtml(fullTextHtml: String, macrosJs: String): String = """
<!DOCTYPE html>
<html>
<head>
  <meta charset=\"UTF-8\">
  <title>LaTeX Preview</title>
  <meta http-equiv=\"Content-Security-Policy\"
        content=\"default-src 'self' 'unsafe-inline' data: blob: https://cdn.jsdelivr.net;
                 script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net;
                 style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net;
                 img-src * data: blob:;
                 font-src https://cdn.jsdelivr.net data:;\">
  <script>
    // Line maps injected from Kotlin (orig->merged, merged->orig)
    window.__llO2M = ${lineMapOrigToMergedJson ?: "[]"};
    window.__llM2O = ${lineMapMergedToOrigJson ?: "[]"};
  </script>
  <script>
    // Re-entrancy / echo guards
    window.__llGuards = {
      suppressEmitUntil: 0, // while > now: preview won't emit preview-mark
      echoId: null,         // last id we sent to editor
      echoUntil: 0          // ignore editor echoes for this id until this time
    };
  </script>
  <style>
    :root { --bg:#ffffff; --fg:#111827; --muted:#6b7280; --border:#e5e7eb; }
    @media (prefers-color-scheme: dark) { :root { --bg:#0f1115; --fg:#e5e7eb; --muted:#9ca3af; --border:#2d3748; } }
    html, body { height:100%; margin:0; background:var(--bg); color:var(--fg); }
    body { font-family: system-ui, -apple-system, Segoe UI, Roboto, Ubuntu, Cantarell, sans-serif; }
    .wrap { padding:16px 20px 40px; max-width:980px; margin:0 auto; }
    .mj   { font-size:16px; line-height:1.45; transition:font-size .2s; }
    .full-text { white-space: normal; }
    table { border-collapse: collapse; margin-top: 0.2em; margin-bottom: 0.2em; }
    a { color: inherit; }
    h1, h2, h3, h4, h5 { margin-top: 0.8em; margin-bottom: 0.2em; }
    figcaption { margin-top: 0.1em; margin-bottom: 0.2em; }
    /* Reduce space after display math (MathJax block equations) */
    .mjx-container[jax="CHTML"][display="true"] { margin-bottom: 0.2em; }
    /* zero-size line markers that don't affect layout */
    .syncline { display:inline-block; width:0; height:0; overflow:hidden; }
    html, body { height: 100%; margin: 0; }
    body { overflow-y: auto; }
    .wrap { min-height: 100vh; }
    /* Floating zoom toolbar styles */
    .floating-toolbar {
      position: fixed;
      top: 16px;
      left: 50%;
      transform: translateX(-50%);
      z-index: 100;
      background: var(--bg);
      border: 1px solid var(--border);
      box-shadow: 0 2px 8px rgba(0,0,0,0.08);
      border-radius: 8px;
      padding: 8px 20px;
      display: flex;
      align-items: center;
      gap: 10px;
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.3s;
    }
    .floating-toolbar.visible {
      opacity: 1;
      pointer-events: auto;
    }
    .floating-toolbar button {
      font-size: 16px;
      padding: 4px 12px;
      border-radius: 4px;
      border: 1px solid var(--border);
      background: var(--bg);
      color: var(--fg);
      cursor: pointer;
      transition: background .2s;
    }
    .floating-toolbar button:hover {
      background: var(--border);
    }
    .multicol-wrap { display: flex; gap: 1em; margin: 0.5em 0; }
    .multicol-col { flex: 1 1 0; padding: 0 0.5em; }
    strong, em, u, small { display: inline; }
    /* Preview caret marker */
    .caret-mark { display:inline-block; border-left: 1.5px solid #4F46E5; height: 1em; margin-left:-0.75px; animation: llblink 1s step-end infinite; }
    @keyframes llblink { 50% { border-color: transparent; } }
    .sync-target { outline: 2px dashed #10b981; outline-offset: 2px; }
    #ll-debug { position: fixed; right: 10px; bottom: 10px; background: rgba(0,0,0,0.6); color: #fff; font: 12px/1.35 monospace; padding: 8px 10px; border-radius: 6px; z-index: 9999; max-width: 46vw; max-height: 40vh; overflow: auto; white-space: pre-wrap; display: none; }
    #ll-debug.visible { display: block; }
    
    /***** Top bar (chapters + zoom) *****/
    .ll-topbar {
      position: fixed;
      top: 0;
      z-index: 200;
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      background: var(--bg);
      border-bottom: 1px solid var(--border);
    }
    .ll-topbar .title {
      font-weight: 600;
      opacity: .85;
      margin-right: 8px;
      white-space: nowrap;
    }
    .ll-topbar .chapters {
      min-width: 220px;
      max-width: 60vw;
    }
    .ll-topbar select {
      width: 100%;
      padding: 4px 8px;
      border: 1px solid var(--border);
      background: var(--bg);
      color: var(--fg);
      border-radius: 6px;
    }
    .ll-topbar .spacer { flex: 1 1 auto; }
    .ll-topbar .btn {
      font-size: 14px;
      padding: 4px 10px;
      border-radius: 6px;
      border: 1px solid var(--border);
      background: var(--bg);
      color: var(--fg);
      cursor: pointer;
    }
    .ll-topbar .btn:hover { background: var(--border); }
    
    /* Topbar hide/show */
    .ll-topbar {
      transition: transform .22s ease, opacity .22s ease;
      will-change: transform, opacity;
    }
    .ll-topbar.is-hidden {
      transform: translateY(-110%);
      opacity: 0;
    }
    .ll-topbar.is-pinned {
      transform: none !important;
      opacity: 1 !important;
    }
    
    /* Pin control styling */
    .ll-topbar .pin {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 0 8px;
      opacity: .9;
      user-select: none;
    }
    .ll-topbar .pin input { accent-color: currentColor; }

  </style>
  <script>
    // MathJax config
    window.MathJax = {
      tex: {
        tags: 'ams', tagSide: 'right', tagIndent: '0.8em',
        inlineMath: [['\\(','\\)'], ['$', '$']],
        displayMath: [['\\[','\\]'], ['$$','$$']],
        processEscapes: true,
        packages: {'[+]': ['ams','bbox','base','textmacros']},
        macros: $macrosJs
      },
      options: { skipHtmlTags: ['script','noscript','style','textarea','pre','code'] },
      startup: {
        ready: () => { MathJax.startup.defaultReady(); try { window.sync.init(); } catch(e){} }
      }
    };
  </script>
  <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js"></script>

 <script>
(function () {
  const dbgEl = () => document.getElementById('ll-debug');
  let lastT = 0, lastSig = '';

  function updateDebug(data){
    const el = dbgEl(); if (!el) return;
    const now = Date.now();
    if (now - lastT < 150) return;
    lastT = now;

    const sig = data.event + '|' + JSON.stringify(data);
    if (sig === lastSig) return;
    lastSig = sig;

    data.scrollY = window.scrollY;
    data.viewportHeight = window.innerHeight;
    if (data.event === 'scrollToAbs' && window.sync && window.sync.lastEl) {
      const r = window.sync.lastEl.getBoundingClientRect();
      data.targetTop = r.top;
      data.targetAbs = window.sync.lastEl.dataset.abs;
    }
    if (!window.__llDebugMapsPrinted) {
      data.llO2M = window.__llO2M;
      data.llM2O = window.__llM2O;
      window.__llDebugMapsPrinted = true;
    }
    const ts = new Date().toLocaleTimeString();
    const prev = el.textContent || '';
    el.textContent = ts + ' ' + JSON.stringify(data) + '\n' + prev;
    const lines = el.textContent.split('\n');
    if (lines.length > 200) el.textContent = lines.slice(0, 200).join('\n');
    el.classList.add('visible');
  }

  // === NEW state for idempotent scrolls ===
  let _lastTargetAbs = -1;
  let _lastPlannedTop = -1;
  let _lastScrollTs = 0;

  const sync = {
    idx: [], lastEl: null,
    init(){ this.idx = Array.from(document.querySelectorAll('.syncline')).map(el => ({ el, abs:+el.dataset.abs||0 })); },
    scrollToAbs(line, mode='center', meta){
      if (!this.idx.length) this.init();
      const arr = this.idx; if (!arr.length) return;

      // binary search: last anchor with abs <= line
      let lo=0, hi=arr.length-1, ans=0;
      while (lo<=hi){ const mid=(lo+hi)>>1; if (arr[mid].abs<=line){ ans=mid; lo=mid+1; } else hi=mid-1; }
      const target = arr[ans] && arr[ans].el; if (!target) return;

      if (this.lastEl) this.lastEl.classList.remove('sync-target');
      target.classList.add('sync-target'); this.lastEl = target;

      let plannedTop;
      if (mode==='center'){
        const r = target.getBoundingClientRect();
        plannedTop = Math.max(0, Math.min(
          window.scrollY + r.top - (window.innerHeight/2),
          Math.max(0, (document.scrollingElement || document.documentElement).scrollHeight - window.innerHeight)
        ));
      } else {
        // emulate scrollIntoView(start) deterministically
        const r = target.getBoundingClientRect();
        plannedTop = Math.max(0, window.scrollY + r.top - 8);
      }

      // === NEW: idempotency guards ===
      const now = Date.now();
      const sameTarget = (line === _lastTargetAbs);
      const sameY = Math.abs(plannedTop - _lastPlannedTop) < 1;
      const tooSoon = (now - _lastScrollTs) < 50; // collapse back-to-back frames

      if (sameTarget && sameY && tooSoon) {
        return; // skip duplicate
      }

      window.scrollTo({ top: plannedTop });
      _lastTargetAbs = line;
      _lastPlannedTop = plannedTop;
      _lastScrollTs = now;

      updateDebug({event:'scrollToAbs', mergedAbs: line, mode, meta});
    }
  };
  window.sync = sync;

  document.addEventListener('DOMContentLoaded', () => sync.init());
})();
</script>

<script>
  (function(){
    let rafHandle = 0;
    let pending = null;
    let lastKey = '';
    let lastTs = 0;

    window.addEventListener('message', (ev) => {
      const d = ev.data || {};
      if (d && d.type === 'sync-line' && Number.isFinite(d.abs)) {
        let mergedAbs = d.abs;
        if (Array.isArray(window.__llO2M) && window.__llO2M.length && mergedAbs>=1 && mergedAbs<=window.__llO2M.length) {
          mergedAbs = window.__llO2M[mergedAbs-1];
        }
        if (!window.__llMarks || !window.__llMarks.length) {
          if (typeof window.__collectMarks === 'function') window.__collectMarks();
        }
        const marks = window.__llMarks || [];
        if (marks.length) {
          // binary search to pick mark ...
          let lo=0, hi=marks.length-1, ans=0;
          while (lo<=hi) { const mid=(lo+hi)>>1; if (marks[mid].abs<=mergedAbs){ ans=mid; lo=mid+1; } else hi=mid-1; }
          const mark = marks[ans];
          try { if (mark) window.__llActiveIdx = ans; } catch(_){}

          // >>> NEW: ignore echoes and suppress outbound emissions briefly
          const g = window.__llGuards || (window.__llGuards = { suppressEmitUntil:0, echoId:null, echoUntil:0 });
          const now = Date.now();
          if (mark && g.echoId === mark.id && now < g.echoUntil) {
            return; // ignore editor echo for the same mark
          }
          g.suppressEmitUntil = now + 350; // don't emit while we perform programmatic scroll
    
          if (typeof window.__scrollToMark === 'function') {
            if (typeof updateDebug === 'function') updateDebug({event:'host-sync', origAbs:d.abs, mergedAbs, targetMark: mark?.id || null});
            window.__scrollToMark(mark, d.mode || 'center');
          }
          // End NEW
        }
      }
    
      if (d && d.type === 'sync-mark' && typeof d.id === 'string') {
        if (!window.__llMarks || !window.__llMarks.length) {
          if (typeof window.__collectMarks === 'function') window.__collectMarks();
        }
        const m = (window.__llMarks || []).find(x => x.id === d.id);
        try {
          const marks = window.__llMarks || [];
          const idx = marks.findIndex(x => x && x.id === (m && m.id));
          if (idx >= 0) window.__llActiveIdx = idx;
        } catch(_){}
        // >>> NEW: same guards for explicit mark jumps
        const g = window.__llGuards || (window.__llGuards = { suppressEmitUntil:0, echoId:null, echoUntil:0 });
        g.suppressEmitUntil = Date.now() + 350;
    
        if (typeof window.__scrollToMark === 'function') window.__scrollToMark(m, d.mode || 'center');
      }
    }, false);
  })();
</script>
  <script>
  (function () {
    const STEP = 1.15, MIN = 0.5, MAX = 3.0;

    function applyZoom(z) {
      const mj = document.querySelector('.mj');
      if (!mj) return;
      mj.style.fontSize = (16 * z) + 'px';
      window._zoom = z;
      try { localStorage.setItem('ll_zoom', String(z)); } catch(_) {}
    }

    // Expose for any other code that wants to adjust zoom
    window.setZoom = function (factor) {
      const z0 = window._zoom || 1.0;
      const z1 = Math.max(MIN, Math.min(z0 * factor, MAX));
      if (Math.abs(z1 - z0) < 1e-3) return;
      applyZoom(z1);
    };

    // One listener for both buttons (robust even if DOM changes)
    document.addEventListener('click', (e) => {
      const btn = e.target && e.target.closest && e.target.closest('#zoom-in, #zoom-out');
      if (!btn) return;
      e.preventDefault();
      if (btn.id === 'zoom-in')  window.setZoom(STEP);
      if (btn.id === 'zoom-out') window.setZoom(1 / STEP);
    }, true);

    // Restore zoom on load
    window.addEventListener('DOMContentLoaded', () => {
      let z = 1.0;
      try { z = parseFloat(localStorage.getItem('ll_zoom')) || 1.0; } catch(_) {}
      applyZoom(z);
    });
  })();
  </script>

</head>
<body>
<div class="ll-topbar">
  <button id="zoom-out" class="btn" title="Zoom Out">−</button>
  <button id="zoom-in"  class="btn" title="Zoom In">+</button>
  <div class="chapters">
    <select id="ll-chapters"></select>
  </div>
  <div class="spacer"></div>
</div>


  <div class="wrap mj">
    <div id="ll-scroll-sentinel" style="height:1px; margin:0; padding:0;"></div>
    <div class="full-text">$fullTextHtml</div>
  </div>
  <div id="ll-spacer" style="height:0;"></div>
  <div id="ll-debug" title="LiveLaTeX debug HUD (press D to toggle)"></div>
  

  <script>
    (function(){
      document.addEventListener('keydown', function(e){
        if ((e.key === 'd' || e.key === 'D') && !e.metaKey && !e.ctrlKey && !e.altKey) {
          var el = document.getElementById('ll-debug'); if (!el) return;
          el.classList.toggle('visible');
        }
      }, false);
    })();
  </script>
  
  <script>
  (function(){
    function collectMarks() {
      window.__llMarks = Array.from(document.querySelectorAll('.llmark'))
        .map(el => ({ el, id: el.dataset.id || '', abs: +(el.dataset.abs || 0) }))
        .filter(m => m.abs > 0)
        .sort((a,b) => a.abs - b.abs);
    }
    window.__collectMarks = collectMarks;

    let currentMarkId = null;
    window.__scrollToMark = function(mark, mode) {
      if (!mark) return;
      if (mark.id === currentMarkId) return;  // idempotent: same semantic target
      currentMarkId = mark.id;

      // optional visual hint
      try {
        document.querySelectorAll('.llmark.__active').forEach(e => e.classList.remove('__active'));
        mark.el.classList.add('__active');
        mark.el.style.outline = '2px dashed #10b981';
        mark.el.style.outlineOffset = '2px';
        setTimeout(() => { mark.el.style.outline = 'none'; mark.el.classList.remove('__active'); }, 700);
      } catch(_) {}

      const r = mark.el.getBoundingClientRect();
      const plannedTop = Math.max(0, window.scrollY + r.top - (mode === 'start' ? 8 : (window.innerHeight/2)));
      window.scrollTo({ top: plannedTop });
      // Keep hysteresis index aligned with the programmatic target
      try {
        const marks = window.__llMarks || [];
        const idx = marks.findIndex(x => x && x.id === mark.id);
        if (idx >= 0) window.__llActiveIdx = idx;
      } catch(_){}

      if (typeof updateDebug === 'function') updateDebug({event:'scrollToMark', id: mark.id, abs: mark.abs, mode});
    };

    window.addEventListener('DOMContentLoaded', collectMarks, false);
    // Re-collect after MathJax typesets
    document.addEventListener('DOMContentLoaded', () => setTimeout(collectMarks, 400));
  })();
  </script>


  
  <script>
(function(){
  // IntersectionObserver-based scroll spy with dwell-time debounce.
  // Picks the mark most visible inside a top band, with a tiny dwell to prevent flapping.
  const BAND_TOP = 0.12;     // top band starts 12% from viewport top
  const BAND_BOTTOM = 0.70;  // bottom of focus band at 70%
  const DWELL_MS = 140;      // how long a new candidate must dominate before switching
  const EPS = 0.015;         // tiny ratio epsilon to avoid ties fighting

  let io = null;
  let visible = new Map();   // id -> { ratio, ts }
  let currentId = null;
  let pendingId = null;
  let pendingSince = 0;
  let _raf = 0;

  function ensureMarks(){
    if (!window.__llMarks || !window.__llMarks.length) {
      if (typeof window.__collectMarks === 'function') window.__collectMarks();
    }
    return window.__llMarks || [];
  }

  function mergedAbsToOrig(mergedAbs){
    if (Array.isArray(window.__llM2O) && window.__llM2O.length && mergedAbs>=1 && mergedAbs<=window.__llM2O.length) {
      return window.__llM2O[mergedAbs-1]; // 1-based
    }
    return mergedAbs;
  }

  function bestByRatio(){
    // choose max ratio inside band; if tie within EPS pick the lower element (later mark)
    let best = null, bestRatio = -1;
    for (const [id, v] of visible.entries()){
      const r = v.ratio || 0;
      if (r > bestRatio + EPS || (Math.abs(r - bestRatio) <= EPS && v.order > (best?.order ?? -1))) {
        best = v; bestRatio = r;
      }
    }
    return best;
  }

  function emitIfStable(){
    const now = Date.now();
    if (now < (window.__llGuards?.suppressEmitUntil || 0)) return;

    const marks = ensureMarks(); if (!marks.length) return;

    const candidate = bestByRatio();
    if (!candidate) return;

    if (candidate.id !== currentId) {
      if (pendingId !== candidate.id) {
        pendingId = candidate.id;
        pendingSince = now;
      }
      if (now - pendingSince < DWELL_MS) return; // not stable long enough
      // switch
      currentId = pendingId;
      pendingId = null;
      try { if (typeof window.__selectMarkInTopbar === 'function') window.__selectMarkInTopbar(currentId); } catch(_){}
      const m = marks.find(x => x.id === currentId);
      if (!m) return;

      // set echo guard so editor reply doesn't bounce us back
      const g = window.__llGuards || (window.__llGuards = { suppressEmitUntil:0, echoId:null, echoUntil:0 });
      g.echoId = m.id; g.echoUntil = now + 450;

      const origAbs = mergedAbsToOrig(m.abs);
      try { if (typeof window.__jbcefMoveCaret === 'function') window.__jbcefMoveCaret({ line: origAbs, markId: m.id }); } catch(_){}
      try { window.postMessage({ type: 'preview-mark', id: m.id, origAbs }, '*'); } catch(_){}
      try { if (typeof updateDebug === 'function') updateDebug({ event:'preview-scroll', id:m.id, mergedAbs:m.abs, origAbs }); } catch(_){}
    }
  }

  function scheduleEmit(){
    if (_raf) cancelAnimationFrame(_raf);
    _raf = requestAnimationFrame(() => { _raf = 0; emitIfStable(); });
  }

  function setupObserver(){
    // Focus band: only count visibility between BAND_TOP and BAND_BOTTOM.
    const topPct = Math.round(BAND_TOP*100);
    const bottomPct = Math.round((1-BAND_BOTTOM)*100);
    const rootMargin = `${'$'}{-topPct}% 0px ${'$'}{-bottomPct}% 0px`;

    io = new IntersectionObserver((entries) => {
      const marks = ensureMarks();
      for (const e of entries){
        const el = e.target;
        const id = el.dataset.id || '';
        if (!id) continue;
        if (e.isIntersecting) {
          // ratio is how much of the mark's (tiny) box sits in the band — we boost with order so later ties win
          if (!visible.has(id)) visible.set(id, { id, ratio: e.intersectionRatio || 0, order: marks.findIndex(x => x.id === id) });
          const v = visible.get(id);
          v.ratio = e.intersectionRatio || 0;
          // Keep order cached
        } else {
          visible.delete(id);
        }
      }
      scheduleEmit();
    }, {
      root: null,
      rootMargin,
      threshold: [0, 0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 1]
    });

    // Observe all mark sentinels (they’re zero-size; that’s fine — CHTML boxes exist)
    const marks = ensureMarks();
    marks.forEach(m => io.observe(m.el));
  }

  window.addEventListener('DOMContentLoaded', () => {
    // (Re)collect marks after MathJax typesets
    setTimeout(() => {
      ensureMarks();
      setupObserver();
      // First pass
      scheduleEmit();
    }, 450);
  });

  window.addEventListener('resize', scheduleEmit);
})();


</script>


<script>
(function(){
  function labelFromMark(m){
    // Prefer the following heading’s text as label; fallback to id
    const next = m.el.nextElementSibling;
    let label = (next && /^h[2-5]$/i.test(next.tagName) ? (next.textContent||'').trim() : m.id) || m.id;
    // Indent by level inferred from id prefix
    const lvl = m.id.startsWith('subsubsection-') ? 3 : m.id.startsWith('subsection-') ? 2 : m.id.startsWith('section-') ? 1 : 0;
    if (lvl === 2) label = '  • ' + label;
    if (lvl === 3) label = '    ▹ ' + label;
    return { label, lvl };
  }

  function populateChapters(){
    const sel = document.getElementById('ll-chapters'); if (!sel) return;
    if (!window.__llMarks || !window.__llMarks.length) {
      if (typeof window.__collectMarks === 'function') window.__collectMarks();
    }
    const marks = window.__llMarks || [];
    sel.innerHTML = marks.map(m => {
      const lab = labelFromMark(m).label;
      // Kotlin triple-quoted safety: avoid ${'$'}{...} by building strings at runtime
      return '<option value="' + m.id + '">' + lab.replace(/</g,'&lt;').replace(/>/g,'&gt;') + '</option>';
    }).join('');
  }

  function mergedAbsToOrig(mergedAbs){
    if (Array.isArray(window.__llM2O) && window.__llM2O.length && mergedAbs>=1 && mergedAbs<=window.__llM2O.length) {
      return window.__llM2O[mergedAbs-1];
    }
    return mergedAbs;
  }

  function jumpToMarkId(id){
    try { window.llTopbarShow && window.llTopbarShow(); } catch(_){}

        // >>> NEW GUARD: prevent feedback loop & ignore editor echo
    const g = window.__llGuards || (window.__llGuards = { suppressEmitUntil:0, echoId:null, echoUntil:0 });
    const now = Date.now();
    g.suppressEmitUntil = now + 350;  // don't emit preview-mark during our programmatic scroll
    g.echoId = id;                     // we expect the editor to echo this mark back
    g.echoUntil = now + 450;           // ignore that echo briefly
    // <<< NEW GUARD

    // Scroll preview
    window.postMessage({ type:'sync-mark', id, mode:'start' }, '*');

    // Also notify editor
    if (!window.__llMarks || !window.__llMarks.length) {
      if (typeof window.__collectMarks === 'function') window.__collectMarks();
    }
    const m = (window.__llMarks || []).find(x => x.id === id);
     if (m) {
        try {
          const marks = window.__llMarks || [];
          const idx = marks.findIndex(x => x && x.id === (m && m.id));
          if (idx >= 0) window.__llActiveIdx = idx;
        } catch(_){}
      const mergedAbs = m.abs;
      const origAbs = (Array.isArray(window.__llM2O) && window.__llM2O.length >= mergedAbs)
        ? window.__llM2O[mergedAbs - 1]
        : mergedAbs;
      try { if (typeof window.__jbcefMoveCaret === 'function') window.__jbcefMoveCaret({ line: origAbs, markId: id }); } catch(_){}
      try { window.postMessage({ type:'preview-mark', id, origAbs }, '*'); } catch(_){}
    }
  }

  // Keep select synced with current active mark (called from your preview scroll emitter)
  window.__selectMarkInTopbar = function(id){
    const sel = document.getElementById('ll-chapters'); if (!sel) return;
    if (sel.value !== id) sel.value = id;
  };

  window.addEventListener('DOMContentLoaded', () => {
    populateChapters();
    // re-populate after typeset/layout
    setTimeout(populateChapters, 450);

    const sel = document.getElementById('ll-chapters');
    if (sel) sel.addEventListener('change', e => jumpToMarkId(e.target.value));
  });
})();
</script>


  <script>
  (function(){
    function refreshNav(){
      const nav = document.getElementById('ll-nav'); if (!nav) return;
      if (!window.__llMarks || !window.__llMarks.length) { if (typeof window.__collectMarks==='function') window.__collectMarks(); }
      const items = (window.__llMarks || []).map(m => {
        const id = m.id;
        const lvl = id.startsWith('subsubsection-') ? 3 : id.startsWith('subsection-') ? 2 : id.startsWith('section-') ? 1 : 0;
        const next = m.el.nextElementSibling;
        const label = (next && /^h[2-5]$/i.test(next.tagName) ? (next.textContent||'').trim() : id) || id;
        return { id, label, lvl };
      });
      nav.innerHTML = items.map(i => `<a href="#" class="${'$'}{i.lvl===2?'lvl2':i.lvl===3?'lvl3':''}" data-id="${'$'}{i.id}">${'$'}{i.label}</a>`).join('');
        nav.onclick = (e) => {
          const a = e.target.closest('a'); if (!a) return;
          e.preventDefault();
          const id = a.dataset.id;
          // Delegate so the guard + editor notify happen in one place
          jumpToMarkId(id);
        };

    }
    window.addEventListener('DOMContentLoaded', () => setTimeout(refreshNav, 450));
  })();
  </script>

</body>
</html>
""".trimIndent()


    // ──────────────────────── PIPELINE HELPERS ────────────────────────

    private fun applyProseConversions(s: String, meta: TitleMeta, absOffset: Int,
                                      fullSourceNoComments: String): String {
        var t = s
        t = convertLlmark(t, absOffset)
        t = convertMakeTitle(t, meta)
        t = convertSiunitx(t)
        t = convertHref(t)
        t = convertSections(t, absOffset)
        t = convertFigureEnvs(t)
        t = convertIncludeGraphics(t)
        t = convertMulticols(t)

        t = convertLongtablesToTables(t)                 // longtable → table/tabular
        t = convertTcolorboxes(t)                        // ← NEW: render tcolorbox
        t = convertTikzPictures(t, fullSourceNoComments) // compile tikz to SVG (cached)


        t = convertTableEnvs(t)
        t = convertItemize(t)
        t = convertEnumerate(t)
        t = convertDescription(t)
        t = convertTabulars(t)
        t = convertTheBibliography(t)
        t = stripAuxDirectives(t)
        t = t.replace(Regex("""\\label\{[^}]*}"""), "") // belt-and-suspenders
        return t
    }

    /** Keep only the document body; MathJax doesn’t understand the preamble. */
    private fun stripPreamble(s: String): String {
        val begin = s.indexOf(BEGIN_DOCUMENT)
        val end   = s.lastIndexOf(END_DOCUMENT)
        return if (begin >= 0 && end > begin) s.substring(begin + BEGIN_DOCUMENT.length, end) else s
    }

    /**
     * Remove % line comments (safe heuristic):
     * cuts at the first unescaped % per line (so \% is preserved).
     */
    private fun stripLineComments(s: String): String =
        s.lines().joinToString("\n") { line ->
            val cut = firstUnescapedPercent(line)
            if (cut >= 0) line.substring(0, cut) else line
        }

    private fun firstUnescapedPercent(line: String): Int {
        var i = 0
        while (true) {
            val j = line.indexOf('%', i)
            if (j < 0) return -1
            var bs = 0
            var k = j - 1
            while (k >= 0 && line[k] == '\\') { bs++; k-- }
            if (bs % 2 == 0) return j  // even backslashes → % is not escaped
            i = j + 1                   // odd backslashes → escaped, keep searching
        }
    }

    // Balanced-arg helpers (unchanged from before, keep them if you already added)
    private fun findBalancedBrace(s: String, open: Int): Int {
        if (open < 0 || open >= s.length || s[open] != '{') return -1
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
                '\\' -> if (i + 1 < s.length) i++ // skip next char
            }
            i++
        }
        return -1
    }

    private fun replaceCmd1ArgBalanced(s: String, cmd: String, wrap: (String) -> String): String {
        val rx = Regex("""\\$cmd\s*\{""")
        val sb = StringBuilder(s.length)
        var pos = 0
        while (true) {
            val m = rx.find(s, pos) ?: break
            val start = m.range.first
            val braceOpen = m.range.last
            val braceClose = findBalancedBrace(s, braceOpen)
            if (braceClose < 0) {
                // Malformed command: skip this match and continue
                sb.append(s, pos, start + 1)
                pos = start + 1
                continue
            }
            sb.append(s, pos, start)
            val inner = s.substring(braceOpen + 1, braceClose)
            sb.append(wrap(inner))
            pos = braceClose + 1
        }
        sb.append(s, pos, s.length)
        return sb.toString()
    }

    private fun replaceCmd2ArgsBalanced(
        s: String, cmd: String, render: (String, String) -> String
    ): String {
        val rx = Regex("""\\$cmd\*?""")   // allow starred form; args parsed manually
        val sb = StringBuilder(s.length)
        var pos = 0
        while (true) {
            val m = rx.find(s, pos) ?: break
            var j = m.range.last + 1

            // optional star was matched; just continue parsing from here
            j = skipWsAndComments(s, j)

            // optional [..] argument
            if (j < s.length && s[j] == '[') {
                val optClose = findBalancedSquare(s, j)
                if (optClose > j) j = skipWsAndComments(s, optClose + 1)
                else { sb.append(s, pos, j + 1); pos = j + 1; continue } // malformed; skip safely
            }

            // first {arg}
            if (j >= s.length || s[j] != '{') { sb.append(s, pos, j); pos = j; continue }
            val aOpen = j
            val aClose = findBalancedBrace(s, aOpen)
            if (aClose < 0) { sb.append(s, pos, aOpen); pos = aOpen; break }
            val a = s.substring(aOpen + 1, aClose)

            j = skipWsAndComments(s, aClose + 1)

            // second {arg}
            if (j >= s.length || s[j] != '{') { sb.append(s, pos, j); pos = j; continue }
            val bOpen = j
            val bClose = findBalancedBrace(s, bOpen)
            if (bClose < 0) { sb.append(s, pos, bOpen); pos = bOpen; break }
            val b = s.substring(bOpen + 1, bClose)

            // emit replacement
            sb.append(s, pos, m.range.first)
            sb.append(render(a, b))
            pos = bClose + 1
        }
        sb.append(s, pos, s.length)
        return sb.toString()
    }


    // Escape just once, then inject tags; do NOT escape again afterwards.
    private fun formatInlineProseNonMath(s0: String): String {
        fun apply(t0: String, alreadyEscaped: Boolean): String {
            var t = t0
            // convert LaTeX '\\' line breaks first
            t = t.replace(Regex("""(?<!\\)\\\\\s*"""), "<br/>")

            // Unescape LaTeX specials (plain text), then escape only '&'
            if (!alreadyEscaped) {
                t = unescapeLatexSpecials(t)
                t = t.replace("\\&","&amp;")
            }

            // ── NEW: \verb and \verb*  (any delimiter) ───────────────────
            t = Regex("""\\verb\*?(.)(.+?)\1""", RegexOption.DOT_MATCHES_ALL)
                .replace(t) { m ->
                    val code = htmlEscapeAll(m.groupValues[2])
                    "<code>$code</code>"
                }
            // replace commands -> placeholders (so escaping won't hit them)
            fun wrap(tag: String, inner: String) = "\u0001$tag\u0002$inner\u0001/$tag\u0002"
            // ── NEW: paragraph breaks / noindent ─────────────────────────
            t = t.replace(Regex("""\\noindent\b"""), "")
            t = t.replace(Regex("""\\smallbreak\b"""), """<div style="height:.5em"></div>""")
                .replace(Regex("""\\medbreak\b"""),   """<div style="height:1em"></div>""")
                .replace(Regex("""\\bigbreak\b"""),   """<div style="height:1.5em"></div>""")

            val rec: (String) -> String = { inner -> apply(inner, true) }



            // Existing inline formatting (balanced)
            t = replaceCmd1ArgBalanced(t, "textbf")    { "<strong>${rec(it)}</strong>" }
            t = replaceCmd1ArgBalanced(t, "emph")      { "<em>${rec(it)}</em>" }
            t = replaceCmd1ArgBalanced(t, "textit")    { "<em>${rec(it)}</em>" }
            t = replaceCmd1ArgBalanced(t, "itshape")   { "<em>${rec(it)}</em>" }
            t = replaceCmd1ArgBalanced(t, "underline") { "<u>${rec(it)}</u>" }
            t = replaceCmd1ArgBalanced(t, "uline")     { "<u>${rec(it)}</u>" }
            t = replaceCmd1ArgBalanced(t, "footnotesize"){ "<small>${rec(it)}</small>" }

            // ── NEW: boxes ───────────────────────────────────────────────
            t = replaceCmd1ArgBalanced(t, "mbox") { """<span style="white-space:nowrap;">${rec(it)}</span>""" }
            t = replaceCmd1ArgBalanced(t, "fbox") {
                """<span style="display:inline-block;border:1px solid var(--fg);padding:0 .25em;">${rec(it)}</span>"""
            }

            // ── NEW: textual symbol macros → Unicode ─────────────────────
            t = replaceTextSymbols(t)



            // restore placeholders to real tags
            t = t.replace("\u0001", "<").replace("\u0002", ">")
            return t
        }
        return apply(s0, false)
    }

    private fun convertParagraphsOutsideTags(html: String): String {
        val rxTag = Regex("(<[^>]+>)")
        val parts = rxTag.split(html)
        val tags  = rxTag.findAll(html).map { it.value }.toList()

        val out = StringBuilder(html.length + 256)
        for (i in parts.indices) {
            val chunkRaw = parts[i]
            if (!chunkRaw.contains('<') && !chunkRaw.contains('>')) {
                val chunk = chunkRaw.trim()
                if (chunk.isNotEmpty()) {
                    if (Regex("""\n{2,}""").containsMatchIn(chunk)) {
                        // Real paragraph breaks → wrap each paragraph
                        val paras = chunk.split(Regex("""\n{2,}"""))
                            .map { it.trim() }.filter { it.isNotEmpty() }
                            .joinToString("") { p -> "<p>${latexProseToHtmlWithMath(p)}</p>" }
                        out.append(paras)
                    } else {
                        // Inline-only text → DO NOT wrap in <p>
                        out.append(latexProseToHtmlWithMath(chunk))
                    }
                }
            } else {
                out.append(chunkRaw)
            }
            if (i < tags.size) out.append(tags[i])
        }

        // Defensive: unwrap accidental <p> directly inside list/desc/figcaption
        return out.toString()
            .replace(Regex("""<(li|dd|dt)>\s*<p>(.*?)</p>\s*</\1>""", RegexOption.DOT_MATCHES_ALL)) { m ->
                "<${m.groupValues[1]}>${m.groupValues[2]}</${m.groupValues[1]}>"
            }
            .replace(Regex("""(<figcaption[^>]*>)\s*<p>(.*?)</p>\s*(</figcaption>)""", RegexOption.DOT_MATCHES_ALL), "$1$2$3")
    }



    // ───────────────────────────── MACROS ─────────────────────────────

    private data class Macro(val def: String, val nargs: Int)

    /** Parse \newcommand and \def from the WHOLE source (pre + body). */
    private fun extractNewcommands(s: String): Map<String, Macro> {
        val out = LinkedHashMap<String, Macro>()

        // --- Improved \newcommand parser ---
        val rxNewStart = Regex("""\\newcommand\{\\([A-Za-z@]+)\}(?:\[(\d+)])?(?:\[[^\]]*])?\{""")
        var pos = 0
        while (true) {
            val m = rxNewStart.find(s, pos) ?: break
            val name = m.groupValues[1]
            val nargs = m.groupValues[2].ifEmpty { "0" }.toInt()
            val bodyOpen = m.range.last
            val bodyClose = findBalancedBrace(s, bodyOpen)
            if (bodyClose < 0) {
                pos = m.range.last + 1
                continue // skip malformed
            }
            val body = s.substring(bodyOpen + 1, bodyClose).trim()
            out[name] = Macro(body, nargs)
            pos = bodyClose + 1
        }

        // \def\foo{...} (unchanged)
        val rxDef = Regex("""\\def\\([A-Za-z@]+)\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)
        rxDef.findAll(s).forEach { m ->
            out.putIfAbsent(m.groupValues[1], Macro(m.groupValues[2].trim(), 0))
        }

        return out
    }

    /** Build MathJax tex.macros (JSON-like) from user + base shims. */
    private fun buildMathJaxMacros(user: Map<String, Macro>): String {
        // Lightweight shims for common packages (physics, siunitx, etc.)
        val base = linkedMapOf(
            "ae"   to Macro("\\unicode{x00E6}", 0),
            "AE"   to Macro("\\unicode{x00C6}", 0),
            "vb"   to Macro("\\mathbf{#1}", 1),
            "bm"   to Macro("\\boldsymbol{#1}", 1),
            "dv"   to Macro("\\frac{d #1}{d #2}", 2),
            "pdv"  to Macro("\\frac{\\partial #1}{\\partial #2}", 2),
            "abs"  to Macro("\\left|#1\\right|", 1),
            "norm" to Macro("\\left\\lVert #1\\right\\rVert", 1),
            "qty"  to Macro("\\left(#1\\right)", 1),
            "qtyb" to Macro("\\left[#1\\right]", 1),
            "qed"  to Macro("\\square", 0),

            // siunitx placeholders (convertSiunitx does the formatting)
            "si"   to Macro("\\mathrm{#1}", 1),
            "num"  to Macro("{#1}", 1),

            // handy aliases
            "Lam"  to Macro("\\Lambda", 0),
            "rc"   to Macro("r_c", 0),

        )

        // Merge with user macros (user wins)
        val merged = LinkedHashMap<String, Macro>()
        merged.putAll(base)
        merged.putAll(user)

        val parts = merged.map { (k, v) ->
            if (v.nargs > 0) "\"$k\": [${jsonEscape(v.def)}, ${v.nargs}]"
            else              "\"$k\": ${jsonEscape(v.def)}"
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun jsonEscape(tex: String): String =
        "\"" + tex
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""


    // ──────────────────────── PROSE CONVERSIONS ────────────────────────
    private fun convertLongtable(text: String): String {
        val out = StringBuilder(text.length + 512)
        var i = 0
        while (true) {
            val start = text.indexOf("\\begin{longtable}", i)
            if (start < 0) { out.append(text.substring(i)); break }

            out.append(text.substring(i, start))

            // Balanced colspec after \begin{longtable}
            val colOpen = text.indexOf('{', start + "\\begin{longtable}".length)
            val colClose = findBalancedBrace(text, colOpen)
            if (colOpen < 0 || colClose < 0) {
                // malformed – bail out safely
                out.append(text.substring(start))
                break
            }
            val spec = text.substring(colOpen + 1, colClose)

            val endTag = text.indexOf("\\end{longtable}", colClose + 1)
            if (endTag < 0) {
                out.append(text.substring(start))
                break
            }

            var body = text.substring(colClose + 1, endTag)

            // Extract caption (and remove trailing \\ if present)
            var captionHtml = ""
            Regex("""\\caption\{([^}]*)\}""").find(body)?.let { cap ->
                captionHtml =
                    """<figcaption style="opacity:.8;margin:6px 0 10px;">${escapeHtmlKeepBackslashes(cap.groupValues[1])}</figcaption>"""
                body = Regex("""\\caption\{[^}]*\}\s*\\\\\s*""").replace(body, "") // caption + row break
                body = body.replace(cap.value, "") // fallback: just in case there was no \\ after
            }

            // Remove duplicate header (continued pages) and paging tokens
            body = Regex("""\\endfirsthead.*?\\endhead""", RegexOption.DOT_MATCHES_ALL).replace(body, "")
            body = body
                .replace("\\endfirsthead", "")
                .replace("\\endhead", "")
                .replace("\\endfoot", "")
                .replace("\\endlastfoot", "")
                .replace(Regex("""\\label\{[^}]*\}"""), "") // belts-and-suspenders

            // Hand off to existing tabular converter by synthesizing a tabular block.
            val asTabular = "\\begin{tabular}{$spec}\n$body\n\\end{tabular}"

            // Wrap like your table figures do (tabular converter will run later).
            out.append("""<figure style="margin:14px 0;">$asTabular$captionHtml</figure>""")

            i = endTag + "\\end{longtable}".length
        }
        return out.toString()
    }

    private fun collectTikzPreamble(srcNoComments: String): String {
        val preamble = srcNoComments.substringBefore("\\begin{document}")
        val libs = Regex("""\\usetikzlibrary\{[^}]*}""").findAll(preamble).joinToString("\n") { it.value }
        val sets = Regex("""\\tikzset\{[^}]*}""").findAll(preamble).joinToString("\n") { it.value }
        val needsTikzCd = Regex("""\\begin\{tikzcd}|\\usepackage\{tikz-cd}""").containsMatchIn(preamble)
        return buildString {
            appendLine("\\usepackage{tikz}")
            if (needsTikzCd) appendLine("\\usepackage{tikz-cd}")
            if (libs.isNotBlank()) appendLine(libs)
            if (sets.isNotBlank()) appendLine(sets)
        }
    }



    private fun convertSections(s: String, absOffset: Int): String {
        fun inject(kind: String, tag: String, input: String): String {
            val rx = Regex("""\\$kind\*?\{([^}]*)\}""")
            return rx.replace(input) { m ->
                val title = m.groupValues[1]
                val id    = "$kind-${slugify(title)}"
                val abs   = absOffset + input.substring(0, m.range.first).count { it == '\n' } + 1
                val htm   = latexProseToHtmlWithMath(title)
                """<span class="llmark" data-id="$id" data-abs="$abs"></span><$tag id="$id">$htm</$tag>"""
            }
        }
        var t = s
        t = inject("section", "h2", t)
        t = inject("subsection", "h3", t)
        t = inject("subsubsection", "h4", t)
        // paragraph (optional)
        t = Regex("""\\paragraph\{([^}]*)\}""").replace(t) { m ->
            val title = m.groupValues[1]
            val id    = "paragraph-${slugify(title)}"
            val abs   = absOffset + t.substring(0, m.range.first).count { it == '\n' } + 1
            val htm   = latexProseToHtmlWithMath(title)
            """<span class="llmark" data-id="$id" data-abs="$abs"></span><h5 id="$id" style="margin:1em 0 .3em 0;">$htm</h5>"""
        }
        // \texorpdfstring: keep your prior choice (prefer text arg)
        t = t.replace(Regex("""\\texorpdfstring\{([^}]*)\}\{([^}]*)\}""")) {
            latexProseToHtmlWithMath(it.groupValues[2])
        }
        // appendix divider
        t = t.replace(
            Regex("""\\appendix"""),
            """<hr style="border:none;border-top:1px solid var(--border);margin:16px 0;"/>"""
        )
        return t
    }


    private fun convertLlmark(s: String, absOffset: Int): String {
        // \llmark{key}  or  \llmark[Title]{key}
        val rx = Regex("""\\llmark(?:\[([^]]*)])?\{([^}]*)\}""")
        return rx.replace(s) { m ->
            val titleOpt = m.groupValues[1]
            val key      = m.groupValues[2].ifBlank { "mark" }
            val id       = "mark-${slugify(key)}"
            val absLine  = absOffset + s.substring(0, m.range.first).count { it == '\n' } + 1
            val capHtml  = if (titleOpt.isNotBlank())
                """<div style="opacity:.7;margin:.2em 0;">${latexProseToHtmlWithMath(titleOpt)}</div>"""
            else ""
            """<span class="llmark" data-id="$id" data-abs="$absLine"></span>$capHtml"""
        }
    }

    private fun unescapeLatexSpecials(t0: String): String {
        var t = t0
        // $ needs quoteReplacement, otherwise treated as a (missing) group reference
        t = Regex("""\\\$""").replace(t, Matcher.quoteReplacement("$"))

        // The rest are safe literal replacements (no $ in replacement)
        t = Regex("""\\&""").replace(t, "&")
        t = Regex("""\\%""").replace(t, "%")
        t = Regex("""\\#""").replace(t, "#")
        t = Regex("""\\_""").replace(t, "_")
        t = Regex("""\\\{""").replace(t, "{")
        t = Regex("""\\\}""").replace(t, "}")
        t = Regex("""\\~\{\}""").replace(t, "~")
        t = Regex("""\\\^\{\}""").replace(t, "^")
        return t
    }



    /**
     * Convert LaTeX prose to HTML, preserving math regions ($...$, \[...\], \(...\)).
     * Only escapes HTML and converts text formatting in non-math regions.
     */
    private val MATH_ENVS = setOf(
        "equation","equation*","align","align*","aligned","gather","gather*",
        "multline","multline*","flalign","flalign*","alignat","alignat*",
        "bmatrix","pmatrix","vmatrix","Bmatrix","Vmatrix","smallmatrix",
        "matrix","cases","split"
    )

    private fun latexProseToHtmlWithMath(s: String): String {
        fun tryWrap(cmd: String, openIdx: Int): String? {
            if (!s.regionMatches(openIdx, "\\$cmd", 0, cmd.length + 1)) return null
            var j = openIdx + cmd.length + 1
            // Optional whitespace
            while (j < s.length && s[j].isWhitespace()) j++
            if (j >= s.length || s[j] != '{') return null
            val close = findBalancedBraceAllowMath(s, j)
            if (close < 0) return null
            val inner = s.substring(j + 1, close)
            val before = s.substring(0, openIdx)
            val after  = s.substring(close + 1)
            val tag = when (cmd) {
                "textbf"       -> "strong"
                "emph", "textit", "itshape" -> "em"
                "underline", "uline" -> "u"
                "small", "footnotesize" -> "small"
                else -> return null
            }
            // Recurse on the inner with full math-preserving pipeline
            return before + "<$tag>" + latexProseToHtmlWithMath(inner) + "</$tag>" + latexProseToHtmlWithMath(after)
        }

        // Try each wrapper at the earliest backslash, to avoid O(n^2).
        run {
            var i = s.indexOf('\\')
            while (i >= 0) {
                for (cmd in arrayOf("textbf","emph","textit","itshape","underline","uline","small","footnotesize")) {
                    val rep = tryWrap(cmd, i)
                    if (rep != null) return rep // whole string rebuilt; we’re done
                }
                i = s.indexOf('\\', i + 1)
            }
        }

        val sb = StringBuilder()
        var i = 0
        val n = s.length

        fun startsAt(idx: Int, tok: String): Boolean =
            idx >= 0 && idx + tok.length <= n && s.regionMatches(idx, tok, 0, tok.length)

        while (i < n) {
            // Next inline/display math delimiters
            val nextDollar = run {
                var j = s.indexOf('$', i)
                while (j >= 0 && j < n && isEscaped(s, j)) j = s.indexOf('$', j + 1)
                j
            }
            val nextBracket = s.indexOf("\\[", i)
            val nextParen   = s.indexOf("\\(", i)
            val nextBegin   = s.indexOf("\\begin{", i)

            // choose earliest of the four (>=0)
            val candidates = listOf(nextDollar, nextBracket, nextParen, nextBegin).filter { it >= 0 }
            val next = if (candidates.isEmpty()) n else candidates.minOrNull()!!

            // non-math chunk
            sb.append(formatInlineProseNonMath(s.substring(i, next)))

            if (next == n) break

            // handle math spans
            if (next == nextDollar) {
                val isDouble = startsAt(next, "$$")
                val closeIdx = if (isDouble) s.indexOf("$$", next + 2) else s.indexOf('$', next + 1)
                val end = if (closeIdx >= 0) closeIdx + (if (isDouble) 2 else 1) else n
                sb.append(s.substring(next, end)); i = end; continue
            }
            if (next == nextBracket) {
                val closeIdx = s.indexOf("\\]", next + 2)
                val end = if (closeIdx >= 0) closeIdx + 2 else n
                sb.append(s.substring(next, end)); i = end; continue
            }
            if (next == nextParen) {
                val closeIdx = s.indexOf("\\)", next + 2)
                val end = if (closeIdx >= 0) closeIdx + 2 else n
                sb.append(s.substring(next, end)); i = end; continue
            }
            // \begin{env} … \end{env}
            if (next == nextBegin) {
                val nameOpen = next + "\\begin{".length
                val nameClose = s.indexOf('}', nameOpen)
                val env = if (nameClose > nameOpen) s.substring(nameOpen, nameClose) else ""
                if (env in MATH_ENVS) {
                    val endTok = "\\end{$env}"
                    val endAt = s.indexOf(endTok, nameClose + 1).let { if (it < 0) n else it + endTok.length }
                    sb.append(s.substring(next, endAt)); i = endAt; continue
                }
                // not a math env → treat as prose
                sb.append("\\begin{"); i = nameOpen
            }
        }
        return sb.toString()
    }


    private fun convertMulticols(s: String): String {
        val rx = Regex("""\\begin\{multicols\}\{(\d+)\}(.+?)\\end\{multicols\}""",
            RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val n = (m.groupValues[1].toIntOrNull() ?: 2).coerceIn(1, 8)
            val body = latexProseToHtmlWithMath(m.groupValues[2].trim())
            """<div class="multicol" style="-webkit-column-count:$n;column-count:$n;-webkit-column-gap:1.2em;column-gap:1.2em;">$body</div>"""
        }
    }

    private fun convertItemize(s: String): String {
        println("[DEBUG] convertItemize called with input:\n" + s)
        val rx = Regex("""\\begin\{itemize\}(?:\[[^\]]*])?(.+?)\\end\{itemize\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val body = m.groupValues[1]
            val parts = Regex("""(?m)^\s*\\item\s*""")
                .split(body).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@replace ""
            val lis = parts.joinToString("") { item -> "<li>${proseNoBr(item)}</li>" }
            """<ul style="margin:12px 0 12px 24px;">$lis</ul>"""
        }
    }

    private fun convertEnumerate(s: String): String {
        println("[DEBUG] convertEnumerate called with input:\n" + s)
        val rx = Regex("""\\begin\{enumerate\}(?:\[[^\]]*])?(.+?)\\end\{enumerate\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val body = m.groupValues[1]
            val parts = Regex("""(?m)^\s*\\item\s*""")
                .split(body).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@replace ""
            val lis = parts.joinToString("") { item -> "<li>${proseNoBr(item)}</li>" }
            """<ol style="margin:12px 0 12px 24px;">$lis</ol>"""
        }
    }

    private fun convertDescription(s: String): String {
        // Match description env; keep DOT_MATCHES_ALL so body spans newlines.
        val rxEnv = Regex("""\\begin\{description\}(?:\[[^\]]*])?(.+?)\\end\{description\}""", RegexOption.DOT_MATCHES_ALL)
        return rxEnv.replace(s) { envMatch ->
            val body = envMatch.groupValues[1]

            // Each \item with optional [label], up to next \item or end
            val rxItem = Regex("""(?ms)^\s*\\item(?:\s*\[([^\]]*)])?\s*(.*?)\s*(?=^\s*\\item|\z)""")

            val items = rxItem.findAll(body).map { m ->
                val rawLabel   = m.groupValues[1] // may be empty
                val rawContent = m.groupValues[2]

                // Peel a single top-level \textbf{...}/\emph{...}/\textit{...} so math inside doesn’t break it
                val (peeled, tag) = peelTopLevelTextWrapper(rawLabel)
                val labelHtmlInner = latexProseToHtmlWithMath(peeled)
                val labelHtml = when (tag) {
                    "strong" ->
                        if (labelHtmlInner.contains("<strong>", ignoreCase = true)) labelHtmlInner
                        else "<strong>$labelHtmlInner</strong>"
                    "em" ->
                        if (labelHtmlInner.contains("<em>", ignoreCase = true)) labelHtmlInner
                        else "<em>$labelHtmlInner</em>"
                    else -> labelHtmlInner
                }

                val contentHtml = latexProseToHtmlWithMath(rawContent)

                // Ensure the term is bold overall (without double-wrapping)
                val termHtml = when {
                    labelHtml.isBlank() -> ""
                    labelHtml.contains("<strong>", ignoreCase = true) -> labelHtml
                    else -> "<strong>$labelHtml</strong>"
                }

                "<dt>$termHtml</dt><dd>$contentHtml</dd>"
            }.joinToString("")

            """<dl style="margin:12px 0 12px 24px;">$items</dl>"""
        }
    }


    // Strip a single *top-level* \textbf{...}/\emph{...}/\textit{...} wrapper (if present),
// even if its contents include inline/display math. Returns (inner, tag) where tag is "strong"/"em".
    private fun peelTopLevelTextWrapper(raw: String): Pair<String, String?> {
        fun peel(cmd: String, tag: String): Pair<String,String?>? {
            val rx = Regex("""^\s*\\$cmd\s*\{""")
            val m = rx.find(raw) ?: return null
            val open = m.range.last
            val close = findBalancedBrace(raw, open)
            if (close < 0) return null
            // Ensure there’s nothing but optional whitespace after the brace
            val tail = raw.substring(close + 1).trim()
            if (tail.isNotEmpty()) return null
            val inner = raw.substring(open + 1, close)
            return inner to tag
        }
        return peel("textbf","strong")
            ?: peel("emph","em")
            ?: peel("textit","em")
            ?: (raw to null)
    }


    private data class ColSpec(val align: String?, val widthPct: Int?)

    private fun convertTcolorboxes(s: String): String {
        val rx = Regex("""\\begin\{tcolorbox\}(?:\[((?:\\]|[^\]])*?)\])?(.+?)\\end\{tcolorbox\}""",
            RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val opts = (m.groupValues.getOrNull(1) ?: "").trim()
            val body = m.groupValues[2].trim()

            val kv = parseTcolorOptions(opts) // mapOf("title"->"...","colback"->"...","colframe"->"...")

            val titleHtml = kv["title"]?.let { latexProseToHtmlWithMath(it) } ?: ""
            val colBack   = kv["colback"]?.let { xcolorToCss(it) } ?: "#f8fafc"   // soft gray/blue default
            val colFrame  = kv["colframe"]?.let { xcolorToCss(it) } ?: "#1e3a8a"  // deep blue default

            val inner = latexProseToHtmlWithMath(body)

            buildString {
                append("<div class=\"tcb\" style=\"")
                append("background:").append(colBack).append(';')
                append("border:1px solid ").append(colFrame).append(';')
                append("border-left-width:4px;border-radius:8px;")
                append("padding:10px 12px;margin:12px 0;\">")
                if (titleHtml.isNotEmpty()) {
                    append("<div class=\"tcb-title\" style=\"font-weight:600;margin-bottom:6px;\">")
                    append(titleHtml).append("</div>")
                }
                append("<div class=\"tcb-body\">").append(inner).append("</div></div>")
            }
        }
    }

    private fun parseTcolorOptions(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        while (i < s.length) {
            skipWs()
            val eq = s.indexOf('=', i)
            if (eq < 0) break

            val key = s.substring(i, eq).trim()
            var j = eq + 1
            // value may be {…} or bare up to next top-level comma
            skipWs()

            val value: String
            if (j < s.length && s[j] == '{') {
                val close = findBalancedBrace(s, j)  // you already have this helper
                value = if (close > j) s.substring(j + 1, close) else ""
                i = if (close > j) close + 1 else s.length
            } else {
                var depth = 0
                var k = j
                while (k < s.length) {
                    when (s[k]) {
                        '{' -> depth++
                        '}' -> depth = maxOf(0, depth - 1)
                        ',' -> if (depth == 0) break
                    }
                    k++
                }
                value = s.substring(j, k).trim()
                i = if (k < s.length && s[k] == ',') k + 1 else k
            }

            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    // Balanced { ... } that tolerates $...$, \[...\], \(...\) and nested braces.
    private fun findBalancedBraceAllowMath(s: String, open: Int): Int {
        if (open < 0 || open >= s.length || s[open] != '{') return -1
        var i = open
        var depth = 0
        var inDollar = false
        var inDoubleDollar = false
        var inBracket = false
        var inParen = false

        fun startsAt(idx: Int, tok: String) =
            idx >= 0 && idx + tok.length <= s.length && s.regionMatches(idx, tok, 0, tok.length)

        while (i < s.length) {
            // toggle $$ first
            if (startsAt(i, "$$")) { inDoubleDollar = !inDoubleDollar; i += 2; continue }
            // single $
            if (!inDoubleDollar && s[i] == '$') { inDollar = !inDollar; i++; continue }
            // \[ \] \( \)
            if (!inDollar && !inDoubleDollar) {
                if (startsAt(i, "\\[")) { inBracket = true; i += 2; continue }
                if (startsAt(i, "\\]") && inBracket) { inBracket = false; i += 2; continue }
                if (startsAt(i, "\\(")) { inParen = true; i += 2; continue }
                if (startsAt(i, "\\)") && inParen) { inParen = false; i += 2; continue }
            }
            if (!inDollar && !inDoubleDollar && !inBracket && !inParen) {
                when (s[i]) {
                    '{' -> { depth++; if (depth == 1 && i != open) {/* nested arg */} }
                    '}' -> { depth--; if (depth == 0) return i }
                    '\\' -> { if (i + 1 < s.length) i++ } // skip escaped next char
                }
            } else {
                if (s[i] == '\\' && i + 1 < s.length) i++ // skip escapes inside math too
            }
            i++
        }
        return -1
    }

    // Minimal xcolor mix: "blue!5!white" or plain "blue"
    private fun xcolorToCss(x: String): String {
        fun base(c: String): IntArray = when (c.lowercase()) {
            "black"-> intArrayOf(0,0,0)
            "white"-> intArrayOf(255,255,255)
            "red"  -> intArrayOf(220,38,38)
            "green"-> intArrayOf(22,163,74)
            "blue" -> intArrayOf(37,99,235)
            "cyan" -> intArrayOf(6,182,212)
            "magenta","violet","purple" -> intArrayOf(168,85,247)
            "yellow"-> intArrayOf(234,179,8)
            "orange"-> intArrayOf(249,115,22)
            "gray","grey"-> intArrayOf(156,163,175)
            "brown"-> intArrayOf(150,95,59)
            else -> intArrayOf(30,58,138) // fallback deep-blue
        }
        val m = Regex("""^\s*([A-Za-z]+)(?:!([0-9]{1,3})!([A-Za-z]+))?\s*$""").matchEntire(x)
        if (m != null) {
            val c1 = base(m.groupValues[1])
            val pct = m.groupValues[2].toIntOrNull()
            val c2Name = m.groupValues[3]
            val rgb = if (pct != null && c2Name.isNotEmpty()) {
                val t = pct.coerceIn(0,100)/100.0
                val c2 = base(c2Name)
                intArrayOf(
                    (c1[0]*(1-t) + c2[0]*t).toInt(),
                    (c1[1]*(1-t) + c2[1]*t).toInt(),
                    (c1[2]*(1-t) + c2[2]*t).toInt()
                )
            } else c1
            return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2])
        }
        return "#1e3a8a"
    }


    // --- Tables ---------------------------------------------------------------

    private fun convertTabulars(text: String): String {
        // We can’t rely on a single regex because colspec may nest (p{...}).
        val out = StringBuilder(text.length + 512)
        var i = 0
        while (true) {
            val start = text.indexOf("\\begin{tabular}{", i)
            if (start < 0) { out.append(text.substring(i)); break }
            out.append(text.substring(i, start))

            // Find balanced colspec: starts at the '{' after \begin{tabular}
            val colOpen = text.indexOf('{', start + "\\begin{tabular}".length)
            val colClose = findBalancedBrace(text, colOpen)
            if (colOpen < 0 || colClose < 0) { out.append(text.substring(start)); break }

            val spec = text.substring(colOpen + 1, colClose)
            val cols = parseColSpecBalanced(spec)

            // Body runs until matching \end{tabular}
            val endTag = text.indexOf("\\end{tabular}", colClose + 1)
            if (endTag < 0) { out.append(text.substring(start)); break }
            var body = text.substring(colClose + 1, endTag).trim()

            // Cleanups: booktabs, hlines, and row spacing \\[6pt]
            body = body
                .replace("\\toprule", "")
                .replace("\\midrule", "")
                .replace("\\bottomrule", "")
                .replace(Regex("""(?m)^\s*\\hline\s*$"""), "")
                .replace(Regex("""(?<!\\)\\\\\s*\[[^\]]*]"""), "\\\\") // turn \\[6pt] into \\

            // Heal early HTML breaks (defensive): turn accidental <br> back into LaTeX \\
            // so the row-splitter works and we don’t render spurious line breaks in cells.
            body = body.replace(Regex("""(?i)<br\s*/?>"""), "\\\\")
            // Split rows on unescaped \\  (allow trailing spaces)
            val rows = Regex("""(?<!\\)\\\\\s*""").split(body)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val trs = rows.joinToString("") { row ->
                val cells = row.split('&').map { it.trim() }
                var cellIdx = 0
                val tds = cols.joinToString("") { col ->
                    if (col.align == "space") {
                        "<td style=\"width:1em;border:none;\"></td>"
                    } else {
                        val raw = if (cellIdx < cells.size) cells[cellIdx] else ""
                        cellIdx++
                        val style = buildString {
                            if (col.align != null) append("text-align:${col.align};")
                            if (col.widthPct != null) append("width:${col.widthPct}%;")
                            append("padding:4px 8px;border:1px solid var(--border);vertical-align:top;")
                        }
                        val cellHtml = latexProseToHtmlWithMath(raw)
                        "<td style=\"$style\">$cellHtml</td>"
                    }
                }
                "<tr>$tds</tr>"
            }

            out.append("""<table style="border:1px solid var(--border);margin:12px 0;width:100%;">$trs</table>""")
            i = endTag + "\\end{tabular}".length
        }
        return out.toString()
    }

    /**
     * Parse a LaTeX tabular column spec (l, c, r, p{...}, |, @{}, !{}, >{}, etc.)
     * into a list of ColSpec (align, widthPct).
     * Ignores vertical rules and other decorations.
     */

    private fun parseColSpecBalanced(spec: String): List<ColSpec> {
        // Handle tokens: l c r | !{...} @{...} >{...} p{...}
        val cols = mutableListOf<ColSpec>()
        var i = 0
        fun skipGroup(openAt: Int): Int = findBalancedBrace(spec, openAt).coerceAtLeast(openAt)
        while (i < spec.length) {
            when (spec[i]) {
                'l' -> { cols += ColSpec("left", null);  i++ }
                'c' -> { cols += ColSpec("center", null); i++ }
                'r' -> { cols += ColSpec("right", null);  i++ }
                'p' -> {
                    val o = spec.indexOf('{', i + 1)
                    if (o > 0) {
                        val c = findBalancedBrace(spec, o)
                        val widthExpr = if (c > o) spec.substring(o + 1, c) else ""
                        cols += ColSpec("left", linewidthToPercent(widthExpr))
                        i = if (c > o) c + 1 else i + 1
                    } else i++
                }
                '|', ' ' -> i++
                '@', '!' , '>' -> {
                    val o = spec.indexOf('{', i + 1)
                    i = if (o > 0) skipGroup(o) + 1 else i + 1
                }
                else -> i++
            }
        }
        return cols
    }

    private fun linewidthToPercent(expr: String): Int? {
        Regex("""^\s*([0-9]*\.?[0-9]+)\s*\\linewidth\s*$""").matchEntire(expr)?.let {
            val f = it.groupValues[1].toDoubleOrNull() ?: return null
            return (f * 100).toInt().coerceIn(1, 100)
        }
        Regex("""^\s*([0-9]{1,3})\s*%\s*$""").matchEntire(expr)?.let {
            return it.groupValues[1].toInt().coerceIn(1, 100)
        }
        return null
    }


    private fun colStyle(cols: List<ColSpec>, idx: Int): Pair<String?, Int?> =
        if (idx < cols.size) cols[idx].align to cols[idx].widthPct else null to null

    /** Very conservative prose text helpers (used inside table/list conversions). */
    private fun proseLatexToHtml(s: String): String {
        // DEPRECATED: Use latexProseToHtmlWithMath for all prose conversions to handle \textbf, \emph, etc. with balanced braces and math preservation.
        return latexProseToHtmlWithMath(s)
    }

    private fun convertHref(s: String): String =
        s.replace(Regex("""\\href\{([^}]*)\}\{([^}]*)\}""")) { m ->
            val url = m.groupValues[1]
            val txt = m.groupValues[2]
            """<a href="${escapeHtmlKeepBackslashes(url)}" target="_blank" rel="noopener">${escapeHtmlKeepBackslashes(txt)}</a>"""
        }

    private fun stripAuxDirectives(s: String): String {
        var t = s
        t = t.replace(Regex("""\\addcontentsline\{[^}]*\}\{[^}]*\}\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\nocite\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\bibliographystyle\{[^}]*\}"""), "")
        t = t.replace(
            Regex("""\\bibliography\{[^}]*\}"""),
            """<div style="opacity:.7;margin:8px 0;">[References: compile in PDF mode]</div>"""
        )
        return t
    }
    // ─────────────────────────── SANITIZER ───────────────────────────

    /** Convert abstract/center/theorem-like to HTML; drop unknown NON-math envs; keep math envs intact. */
    private fun sanitizeForMathJaxProse(bodyText: String): String {
        var s = bodyText

        // Custom titlepage toggles used in your Canon
        s = s.replace("""\\titlepageOpen""".toRegex(), "")
            .replace("""\\titlepageClose""".toRegex(), "")

        // center → HTML
        // center
        s = s.replace(
            Regex("""\\begin\{center\}(.+?)\\end\{center\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m -> """<div style="text-align:center;">${latexProseToHtmlWithMath(m.groupValues[1].trim())}</div>""" }

        // abstract
        s = s.replace(
            Regex("""\\begin\{abstract\}(.+?)\\end\{abstract\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m ->
            val raw = m.groupValues[1].trim()
            val collapsedSingles = raw.replace(Regex("""(?<!\n)\n(?!\n)"""), " ")
            val html = proseNoBr(collapsedSingles)

            val merged =
                if (Regex("""<p\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                    // If your stdlib supports replaceFirst:
                    Regex("""(?i)(<p\b[^>]*>)""").replaceFirst(html, "${'$'}1<strong>Abstract.</strong>&nbsp;")
                } else {
                    "<strong>Abstract.</strong>&nbsp;$html"
                }

            """
    <div class="abstract-block" style="padding:12px;border-left:3px solid var(--border); background:#6b728022; margin:12px 0;">
      $merged
    </div>
    """.trimIndent()
        }





        // theorem-like
        val theoremLike = listOf("theorem","lemma","proposition","corollary","definition","remark","identity")
        for (env in theoremLike) {
            s = s.replace(
                Regex("""\\begin\{$env\}(?:\[(.*?)\])?(.+?)\\end\{$env\}""", RegexOption.DOT_MATCHES_ALL)
            ) { m ->
                val ttl = m.groupValues[1].trim()
                val content = m.groupValues[2].trim()
                val head = if (ttl.isNotEmpty()) "$env ($ttl)" else env
                """
      <div style="font-weight:600;margin-bottom:6px;text-transform:capitalize;">$head.</div>
      ${latexProseToHtmlWithMath(content)}
    """.trimIndent()
            }
        }

        // Split problematic align environments with multiple \tag{...} into separate blocks
        s = convertAlignWithMultipleTagsToBlocks(s)

        val mathEnvs =
            "(?:equation\\*?|align\\*?|aligned\\*?|aligned|gather\\*?|multline\\*?|flalign\\*?|alignat\\*?|bmatrix|pmatrix|vmatrix|Bmatrix|Vmatrix|smallmatrix|matrix|cases|split)"
// Keep prose/envs we transform later:
        val keepEnvs =
            "(?:$mathEnvs|tabular|table|longtable|figure|center|tikzpicture|tcolorbox|thebibliography|itemize|enumerate|description|multicols)"


// NOTE: \w is a regex class — in a raw string use \w (not \\w)
        s = s.replace(Regex("""\\begin\{(?!$keepEnvs)\w+\}"""), "")
        s = s.replace(Regex("""\\end\{(?!$keepEnvs)\w+\}"""), "")

        return s
    }


    // ───────────────────────── SIUNITX SHIMS ─────────────────────────

    private fun convertSiunitx(s: String): String {
        var t = s
        // \num{1.23e-4} → 1.23\times 10^{-4}
        t = t.replace(Regex("""\\num\{([^}]*)\}""")) { m ->
            val raw = m.groupValues[1].trim()
            val sci = Regex("""^\s*([+-]?\d+(?:\.\d+)?)[eE]([+-]?\d+)\s*$""").matchEntire(raw)
            if (sci != null) {
                val a = sci.groupValues[1]
                val b = sci.groupValues[2]
                "$a\\times 10^{${b}}"
            } else raw
        }
        // \si{m.s^{-1}} → \mathrm{m\,s^{-1}}
        t = t.replace(Regex("""\\si\{([^}]*)\}""")) { m ->
            val u = m.groupValues[1].replace(".", "\\,").replace("~", "\\,")
            "\\mathrm{$u}"
        }
        // \SI{<num>}{<unit>} → \num{...}\,\si{...}
        t = t.replace(Regex("""\\SI\{([^}]*)\}\{([^}]*)\}""")) { m ->
            val num  = m.groupValues[1]
            val unit = m.groupValues[2]
            "\\num{$num}\\,\\si{$unit}"
        }
        // common text encodings
        t = t.replace(Regex("""\\textasciitilde\{\}"""), "~")
            .replace(Regex("""\\textasciitilde"""), "~")
            .replace(Regex("""\\&"""), "&")
        return t
    }

    private fun fixInlineBoundarySpaces(html: String): String =
        Regex(
            """</(?:strong|em|u|small|code|span)>(?=(?:<(?!/)|[A-Za-z0-9(]))""",
            RegexOption.IGNORE_CASE
        ).replace(html) { it.value + " " }


    // ───────────────────────────── UTIL ─────────────────────────────
// ── Title meta ────────────────────────────────────────────────────────────────
    private data class TitleMeta(
        val title: String?,
        val authors: String?,        // raw \author{...} content
        val dateRaw: String?         // raw \date{...} content
    )

    private fun findLastCmdArg(src: String, cmd: String): String? {
        val rx = Regex("""\\$cmd\s*\{""")
        var pos = 0
        var last: String? = null
        while (true) {
            val m = rx.find(src, pos) ?: break
            val open = m.range.last
            val close = findBalancedBrace(src, open) ?: break
            last = src.substring(open + 1, close)
            pos = close + 1
        }
        return last
    }

    private fun extractTitleMeta(srcNoComments: String): TitleMeta {
        val ttl = findLastCmdArg(srcNoComments, "title")
        val aut = findLastCmdArg(srcNoComments, "author")
        val dat = findLastCmdArg(srcNoComments, "date")
        return TitleMeta(ttl, aut, dat)
    }

    private fun renderDate(dateRaw: String?): String? {
        if (dateRaw == null) return null // like LaTeX default "today" can be debated; keep null to omit if unspecified
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
        val trimmed = dateRaw.trim()
        if (trimmed.isEmpty()) return ""               // \date{} → empty
        val replaced = trimmed.replace("""\today""", today)
        return latexProseToHtmlWithMath(replaced)
    }

    private fun splitAuthors(raw: String): List<String> {
        // Simple split on \and at top level (good enough for typical \author{A\thanks{...}\and B\thanks{...}})
        return Regex("""\\and""").split(raw).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun processThanksWithin(text: String, notes: MutableList<String>): String {
        var s = text
        while (true) {
            val i = s.indexOf("""\thanks{"""); if (i < 0) break
            val open = s.indexOf('{', i + 1); if (open < 0) break
            val close = findBalancedBrace(s, open); if (close < 0) break
            val content = s.substring(open + 1, close)
            notes += latexProseToHtmlWithMath(content)
            val n = notes.size
            s = s.substring(0, i) + "<sup>$n</sup>" + s.substring(close + 1)
        }
        return s
    }

    private fun buildMakTitleHtml(meta: TitleMeta): String {
        val notes = mutableListOf<String>()

        // Title
        val titleHtml = meta.title?.let { latexProseToHtmlWithMath(processThanksWithin(it, notes)) } ?: ""

        // Authors
        val authorsHtml = meta.authors?.let { raw ->
            val parts = splitAuthors(raw).map { p ->
                val withMarks = processThanksWithin(p, notes)
                """<span class="author">${latexProseToHtmlWithMath(withMarks)}</span>"""
            }
            parts.joinToString("""<span class="author-sep" style="padding:0 .6em;opacity:.5;">·</span>""")
        } ?: ""

        // Date
        val dateHtml = renderDate(meta.dateRaw) ?: ""

        val notesHtml = if (notes.isEmpty()) "" else {
            val lis = notes.mapIndexed { idx, txt -> """<li value="${idx+1}">$txt</li>""" }.joinToString("")
            """<ol class="title-notes" style="margin:.6em 0 0 1.2em;font-size:.95em;">$lis</ol>"""
        }

        return """
<div class="maketitle" style="margin:8px 0 16px;border-bottom:1px solid var(--border);padding-bottom:8px;">
  ${if (titleHtml.isNotEmpty()) """<h1 style="margin:0 0 .25em 0;">$titleHtml</h1>""" else ""}
  ${if (authorsHtml.isNotEmpty()) """<div class="authors" style="margin:.2em 0;">$authorsHtml</div>""" else ""}
  ${if (dateHtml.isNotEmpty()) """<div class="date" style="opacity:.8;margin-top:.15em;">$dateHtml</div>""" else ""}
  $notesHtml
</div>
""".trim()
    }

    private fun convertMakeTitle(body: String, meta: TitleMeta): String =
        body.replace(Regex("""\\maketitle\b""")) { buildMakTitleHtml(meta) }

    /** Escape &,<,> but keep backslashes so MathJax sees TeX. */
    private fun escapeHtmlKeepBackslashes(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // After all conversions & before injectLineAnchors(...)
    private fun applyInlineFormattingOutsideTags(html: String): String {
        val tableRx = Regex("(?is)(<table\\b.*?</table>)")
        val segments = tableRx.split(html)
        val tables   = tableRx.findAll(html).map { it.value }.toList()

        val out = StringBuilder(html.length + 256)
        for (i in segments.indices) {
            out.append(applyInlineFormattingOutsideTags_NoTables(segments[i]))
            if (i < tables.size) out.append(tables[i])
        }
        return out.toString()
    }

    private fun applyInlineFormattingOutsideTags_NoTables(html: String): String {
        val rx = Regex("(<[^>]+>)")
        val parts = rx.split(html)
        val tags  = rx.findAll(html).map { it.value }.toList()
        val out = StringBuilder(html.length + 256)
        for (i in parts.indices) {
            val chunk = parts[i]
            if (!chunk.contains('<') && !chunk.contains('>')) {
                out.append(latexProseToHtmlWithMath(chunk))
            } else out.append(chunk)
            if (i < tags.size) out.append(tags[i])
        }
        return out.toString()
    }

    private fun proseNoBr(s: String): String =
        latexProseToHtmlWithMath(s).replace(Regex("(?i)<br\\s*/?>\\s*"), " ")

    private fun htmlEscapeAll(s: String): String =
        s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

    private fun replaceTextSymbols(t0: String): String {
        var t = t0
        // Punctuation & quotes
        t = t.replace(Regex("""\\textellipsis\b"""),     "…")
            .replace(Regex("""\\textquotedblleft\b"""), "“")
            .replace(Regex("""\\textquotedblright\b"""),"”")
            .replace(Regex("""\\textquoteleft\b"""),    "‘")
            .replace(Regex("""\\textquoteright\b"""),   "’")
            .replace(Regex("""\\textemdash\b"""),       "—")
            .replace(Regex("""\\textendash\b"""),       "–")
        // Symbols
        t = t.replace(Regex("""\\textfractionsolidus\b"""), "⁄")
            .replace(Regex("""\\textdiv\b"""),              "÷")
            .replace(Regex("""\\texttimes\b"""),            "×")
            .replace(Regex("""\\textminus\b"""),            "−")
            .replace(Regex("""\\textpm\b"""),               "±")
            .replace(Regex("""\\textsurd\b"""),             "√")
            .replace(Regex("""\\textlnot\b"""),             "¬")
            .replace(Regex("""\\textasteriskcentered\b"""), "∗")
            .replace(Regex("""\\textbullet\b"""),           "•")
            .replace(Regex("""\\textperiodcentered\b"""),   "·")
            .replace(Regex("""\\textdaggerdbl\b"""),        "‡")
            .replace(Regex("""\\textdagger\b"""),           "†")
            .replace(Regex("""\\textsection\b"""),          "§")
            .replace(Regex("""\\textparagraph\b"""),        "¶")
            .replace(Regex("""\\textbardbl\b"""),           "‖")
            .replace(Regex("""\\textbackslash\b"""),        "&#92;")
        return t
    }



    /**
     * Insert invisible line anchors every Nth source line, but never *inside*
     * math ($...$, $$...$$, \[...\], \(...\)) or math environments.
     * Handles math regions spanning multiple lines robustly.
     */
    private fun injectLineAnchors(s: String, absOffset: Int, everyN: Int = 3): String {
        val mathEnvs = setOf(
            "equation","equation*","align","align*","aligned","aligned*",
            "gather","gather*","multline","multline*","flalign","flalign*",
            "alignat","alignat*","bmatrix","pmatrix","vmatrix","Bmatrix","Vmatrix",
            "smallmatrix","matrix","cases","split"
        )
        var i = 0
        var line = 0
        var inDollar = false
        var inDoubleDollar = false
        var inBracket = false   // \[...\]
        var inParen = false     // \(...\)
        var envDepth = 0

        fun startsAt(idx: Int, tok: String) =
            idx + tok.length <= s.length && s.regionMatches(idx, tok, 0, tok.length)

        val sb = StringBuilder(s.length + 1024)
        while (i < s.length) {
            // toggle $$ first (so we don't flip single $ inside $$...$$)
            if (!inBracket && !inParen) {
                if (startsAt(i, "$$")) {
                    inDoubleDollar = !inDoubleDollar
                    sb.append("$$"); i += 2; continue
                }
                if (!inDoubleDollar && s[i] == '$') {
                    // Only toggle if not escaped
                    val prev = if (i > 0) s[i-1] else ' '
                    if (prev != '\\') {
                        inDollar = !inDollar
                        sb.append('$'); i += 1; continue
                    }
                }
            }
            if (!inDollar && !inDoubleDollar) {
                if (startsAt(i, "\\[")) { inBracket = true;  sb.append("\\["); i += 2; continue }
                if (startsAt(i, "\\]") && inBracket) { inBracket = false; sb.append("\\]"); i += 2; continue }
                if (startsAt(i, "\\(")) { inParen = true;   sb.append("\\("); i += 2; continue }
                if (startsAt(i, "\\)") && inParen) { inParen = false;  sb.append("\\)"); i += 2; continue }

                if (startsAt(i, "\\begin{")) {
                    val end  = s.indexOf('}', i + 7)
                    val name = if (end > 0) s.substring(i + 7, end) else ""
                    if (name in mathEnvs) envDepth++
                    sb.append(s, i, (end + 1).coerceAtMost(s.length))
                    i = (end + 1).coerceAtMost(s.length)
                    continue
                }
                if (startsAt(i, "\\end{")) {
                    val end  = s.indexOf('}', i + 5)
                    val name = if (end > 0) s.substring(i + 5, end) else ""
                    if (name in mathEnvs && envDepth > 0) envDepth--
                    sb.append(s, i, (end + 1).coerceAtMost(s.length))
                    i = (end + 1).coerceAtMost(s.length)
                    continue
                }
            }

            val ch = s[i]
            if (ch == '\n') {
                line++
                sb.append('\n')
                val safeSpot = !inDollar && !inDoubleDollar && !inBracket && !inParen && envDepth == 0
                // Only insert anchor if the *previous* line ended outside math
                if (safeSpot && (line % everyN == 0)) {
                    val absLine = absOffset + line
                    sb.append("<span class=\"syncline\" data-abs=\"$absLine\"></span>")
                }
                i++; continue
            }

            sb.append(ch); i++
        }
        return sb.toString()
    }

    private fun convertTableEnvs(s: String): String {
        val rx = Regex("""\\begin\{table\}(?:\[[^\]]*])?(.+?)\\end\{table\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            var body = m.groupValues[1]
            // extract caption
            var captionHtml = ""
            val capRx = Regex("""\\caption\{([^}]*)\}""")
            val cap = capRx.find(body)
            if (cap != null) {
                captionHtml = """<figcaption style=\"opacity:.8;margin:6px 0 10px;\">${escapeHtmlKeepBackslashes(cap.groupValues[1])}</figcaption>"""
                body = body.replace(cap.value, "")
            }
            // drop \centering, labels
            body = body.replace(Regex("""\\centering"""), "")
                .replace(Regex("""\\label\{[^}]*\}"""), "")
            // wrap; tabular will be converted later
            """<figure style=\"margin:14px 0;\">$body$captionHtml</figure>"""
        }
    }

    private fun convertTheBibliography(s: String): String {
        val rx = Regex(
            """\\begin\{thebibliography\}\{[^}]*\}(.+?)\\end\{thebibliography\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        return rx.replace(s) { m ->
            val body = m.groupValues[1]
            // split on \bibitem{...}
            val entries = Regex("""\\bibitem\{[^}]*\}""")
                .split(body)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (entries.isEmpty()) return@replace ""
            val lis = entries.joinToString("") { "<li>${escapeHtmlKeepBackslashes(it)}</li>" }
            """<h4>References</h4><ol style="margin:12px 0 12px 24px;">$lis</ol>"""
        }
    }

    // --- Figures / includegraphics -------------------------------------------

    private fun convertFigureEnvs(s: String): String {
        val rx = Regex("""\\begin\{figure\}(?:\[[^\]]*])?(.+?)\\end\{figure\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            var body = m.groupValues[1]

            // Strip common figure-local layout tweaks so they don't print
            body = body.replace(Regex("""(?m)^\s*\\setlength\{\\tabcolsep\}\{[^}]*}.*$"""), "")
                .replace(Regex("""(?m)^\s*\\renewcommand\{\\arraystretch\}\{[^}]*}.*$"""), "")

            // Capture first \includegraphics (keep your existing behavior)
            var imgHtml = ""
            val inc = Regex("""\\includegraphics(?:\[[^\]]*])?\{([^}]*)\}""").find(body)
            if (inc != null) {
                val opts = Regex("""\\includegraphics(?:\[([^\]]*)])?\{([^}]*)\}""").find(inc.value)
                val (optStr, path) = if (opts != null) opts.groupValues[1] to opts.groupValues[2] else "" to inc.groupValues[1]
                val style = includeGraphicsStyle(optStr)
                val resolved = resolveImagePath(path)
                imgHtml = """<img src="$resolved" alt="" style="$style">"""
                body = body.replace(inc.value, "")
            }

            // Balanced \caption{...} that allows math and nested braces
            var captionHtml = ""
            run {
                val capIdx = body.indexOf("\\caption{")
                if (capIdx >= 0) {
                    val open = body.indexOf('{', capIdx + "\\caption".length)
                    val close = findBalancedBraceAllowMath(body, open)
                    if (open >= 0 && close > open) {
                        val capTex = body.substring(open + 1, close)
                        captionHtml = """<figcaption style="opacity:.8;margin:6px 0 10px;">${latexProseToHtmlWithMath(capTex)}</figcaption>"""
                        // remove the whole \caption{...} from figure body
                        body = body.removeRange(capIdx, close + 1)
                    }
                }
            }

            // Drop LaTeX-only bits
            body = body.replace(Regex("""\\centering"""), "")
                .replace(Regex("""\\label\{[^}]*\}"""), "")
                .trim()

            // Whatever remains → let later passes handle (e.g., tabular→table)
            val hasSubEnv = Regex("""\\begin\{""").containsMatchIn(body)
            val rest = if (body.isNotEmpty()) {
                if (hasSubEnv) "<div>$body</div>" else "<div>${latexProseToHtmlWithMath(body)}</div>"
            } else ""

            """<figure style="margin:14px 0;text-align:center;">$imgHtml$captionHtml$rest</figure>"""
        }
    }

    private fun toFileUrl(f: File): String = f.toURI().toString()

    private fun resolveImagePath(path: String, baseDirFallback: String = "figures"): String {
        val p = path.trim()
        if (p.isEmpty()) return ""
        // If already a URL (http, https, data), pass through
        if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("data:")) return p

        // Determine base directory: prefer currentBaseDir (directory of main .tex file)
        val baseDir = currentBaseDir?.let { File(it) } ?: File("")

        // If absolute filesystem path, convert directly to file URL
        val abs = File(p)
        if (abs.isAbsolute && abs.exists()) return toFileUrl(abs)

        // Build candidate locations relative to main file dir
        val rel = File(baseDir, p)
        val relFigures = File(baseDir, "figures${File.separator}$p")

        val hasExt = p.contains('.')
        val exts = listOf(".png", ".jpg", ".jpeg", ".svg", ".pdf")

        fun existingWithExt(f: File): String? {
            if (hasExt) return if (f.exists()) toFileUrl(f) else null
            for (e in exts) {
                val c = File(f.parentFile ?: baseDir, f.name + e)
                if (c.exists()) return toFileUrl(c)
            }
            return null
        }

        existingWithExt(rel)?.let { return it }
        existingWithExt(relFigures)?.let { return it }

        // Fallback: return file URL to the first candidate even if missing (browser shows broken img but path is absolute)
        val fallback = if (hasExt) rel else File(rel.parentFile ?: baseDir, rel.name + exts.first())
        return toFileUrl(fallback)
    }

    private fun convertIncludeGraphics(latex: String): String {
        val rx = Regex("""\\includegraphics(\[.*?\])?\{([^}]+)\}""")
        return rx.replace(latex) { match ->
            val opts = match.groups[1]?.value ?: ""
            val path = match.groups[2]?.value ?: ""
            val resolvedPath = resolveImagePath(path)
            val widthMatch = Regex("width=([0-9.]+)\\\\?\\w*").find(opts)
            val width = widthMatch?.groups?.get(1)?.value ?: ""
            val style = if (width.isNotEmpty()) " style=\"max-width:${(width.toFloatOrNull()?.let { it * 100 } ?: 70).toInt()}%\"" else " style=\"max-width:70%\""
            "<img src=\"$resolvedPath\" alt=\"figure\"$style>"
        }
    }

    private fun includeGraphicsStyle(options: String): String {
        // Parse width=..., height=..., scale=... (simple mapping)
        val mWidth  = Regex("""width\s*=\s*([0-9]*\.?[0-9]+)\\linewidth""").find(options)
        if (mWidth != null) {
            val pct = (mWidth.groupValues[1].toDoubleOrNull() ?: 1.0) * 100.0
            return "max-width:${pct.coerceIn(1.0,100.0)}%;height:auto;"
        }
        val absW = Regex("""width\s*=\s*([0-9]*\.?[0-9]+)(cm|mm|pt|px)""").find(options)
        if (absW != null) {
            val w = absW.groupValues[1]
            val unit = absW.groupValues[2]
            return "width:${w}${unit};height:auto;max-width:100%;"
        }
        val scale = Regex("""scale\s*=\s*([0-9]*\.?[0-9]+)""").find(options)?.groupValues?.get(1)?.toDoubleOrNull()
        if (scale != null) {
            val pct = (scale * 100.0).coerceIn(1.0, 500.0)
            return "max-width:${pct}%;height:auto;"
        }
        // default
        return "max-width:100%;height:auto;"
    }

    // —— Config / cache ————————————————————————————————————————————————
    private val tikzCacheDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".livelatex/tikz-cache")
        dir.mkdirs(); dir
    }
    private fun sha256Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun fileUrl(f: File) = f.toURI().toString()

    // Try to find tools once and cache the result
    private data class TikzTools(val dvisvgm: String?, val pdf2svg: String?)
    private var _tikzTools: TikzTools? = null
    private fun findTikzTools(): TikzTools {
        _tikzTools?.let { return it }
        fun which(cmd: String): String? {
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val proc = ProcessBuilder(if (isWin) listOf("where", cmd) else listOf("which", cmd))
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val ok = proc.waitFor() == 0 && out.isNotBlank()
            return if (ok) out.lineSequence().firstOrNull()?.trim() else null
        }
        val tools = TikzTools(
            dvisvgm = which("dvisvgm"),
            pdf2svg = which("pdf2svg")
        )
        _tikzTools = tools
        return tools
    }

    private fun run(cmd: List<String>, cwd: File, timeoutMs: Long = 60_000): Pair<Boolean,String> {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)

        // >>> Add TEXINPUTS so pdflatex finds local *.tex/ TikZ libs in your project
        currentBaseDir?.let { base ->
            val sep = if (System.getProperty("os.name").contains("win", true)) ";" else ":"
            val path = File(base).absolutePath
            // trailing separator keeps the default search path active
            pb.environment()["TEXINPUTS"] = path + sep + File(path, "tex").absolutePath + sep
        }
        // <<<

        val p = pb.start()
        val out = StringBuilder()
        val t = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        t.start()
        val ok = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) { p.destroyForcibly(); return false to "Timeout running: $cmd\n$out" }
        return (p.exitValue() == 0) to out.toString()
    }

    private fun renderTikzToSvg(preamble: String, tikzEnvBlock: String): File? {
        val texDoc = """
        \documentclass[tikz,border=2pt]{standalone}
        \usepackage{amsmath,amssymb}
        $preamble
        \begin{document}
        $tikzEnvBlock
        \end{document}
    """.trimIndent()

        val h = sha256Hex(texDoc)
        val work = File(tikzCacheDir, h).apply { mkdirs() }
        val tex = File(work, "$h.tex")
        val pdf = File(work, "$h.pdf")
        val svg = File(work, "$h.svg")

        // Cache hit
        if (svg.exists()) return svg

        tex.writeText(texDoc)

        // Compile → PDF
        val (ok1, log1) = run(
            listOf("pdflatex", "-interaction=nonstopmode", "-halt-on-error",
                "-output-directory", work.absolutePath, tex.absolutePath),
            work
        )
        if (!ok1 || !pdf.exists()) {
            File(work, "build.log").writeText(log1)
            if (log1.contains("tikzlibrarysstknots.code.tex") && log1.contains("not found", true)) {
                // Surface a clear message in the fallback <pre>
                return null
            }
            return null
        }


        // PDF → SVG
        val tools = findTikzTools()
        val (ok2, log2) =
            if (tools.dvisvgm != null)
                run(listOf(tools.dvisvgm, "--pdf", "--no-fonts", "--exact", "-n", pdf.absolutePath, "-o", svg.absolutePath), work)
            else if (tools.pdf2svg != null)
                run(listOf(tools.pdf2svg, pdf.absolutePath, svg.absolutePath), work)
            else false to "Neither dvisvgm nor pdf2svg is available."

        if (!ok2 || !svg.exists()) {
            File(work, "convert.log").writeText(log2)
            return null
        }
        return svg
    }

    private fun convertLongtablesToTables(s: String): String {
        val rx = Regex("""\\begin\{longtable\}\{(.*?)\}(.+?)\\end\{longtable\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val colspec = m.groupValues[1]
            var body    = m.groupValues[2]

            // Pull out caption (if any)
            val capRe = Regex("""\\caption\{([^}]*)\}\s*\\\\?""")
            var caption = ""
            capRe.find(body)?.let { cap ->
                caption = cap.value           // keep as \caption{...} for your table wrapper
                body    = body.replace(cap.value, "")
            }

            // Drop longtable-only directives and booktabs lines
            body = body
                .replace(Regex("""\\endfirsthead|\\endhead|\\endfoot|\\endlastfoot"""), "")
                .replace(Regex("""\\toprule|\\midrule|\\bottomrule"""), "")
                .trim()

            // Hand off to your existing converters by turning it into a normal table+tabular
            """
\begin{table}
$caption
\begin{tabular}{$colspec}
$body
\end{tabular}
\end{table}
        """.trimIndent()
        }
    }


    // --- path where we cache compiled SVGs
    private fun tikzCacheDir(): File {
        val base = currentBaseDir?.let(::File) ?: File(".")
        val dir  = File(base, ".livelatex-cache/tikz")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sha1(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val b  = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun convertTikzPictures(htmlLike: String, fullSourceNoComments: String): String {
        val rx = Regex("""\\begin\{tikzpicture}(?:\[[^\]]*])?(.+?)\\end\{tikzpicture}""",
            RegexOption.DOT_MATCHES_ALL)

        // 2a) harvest user macros and \usetikzlibrary from the whole source
        val userMacros = extractNewcommands(fullSourceNoComments)
        val texMacroDefs = buildTexNewcommands(userMacros)   // defined below
        val srcLibs = collectUsetikzlibsFromSource(fullSourceNoComments)

        return rx.replace(htmlLike) { m ->
            val body = m.groupValues[1].trim()

            // 2b) heuristics for libs required by the *snippet itself*
            val autoLibs = buildSet {
                if (Regex("""-(\{|)Latex""").containsMatchIn(body)) add("arrows.meta")
                if (Regex("""\b(left|right|above|below)\s*=\s*|[^=]\bof\b""").containsMatchIn(body)) add("positioning")
                if (Regex("""\\begin\{knot}|\bflip crossing/""").containsMatchIn(body)) {
                    addAll(listOf("knots","hobby","intersections","decorations.pathreplacing","shapes.geometric","spath3"))
                }
            }

            val libs = (srcLibs + autoLibs).toSortedSet().joinToString(",")

            val texDoc = """
\documentclass[tikz,border=1pt]{standalone}
\usepackage{amsmath,amssymb,bm}
\usepackage{tikz}
\usetikzlibrary{$libs}
$texMacroDefs
\providecommand{\swirlarrow}{\rightsquigarrow} % fallback
\begin{document}
\begin{tikzpicture}
$body
\end{tikzpicture}
\end{document}
        """.trimIndent()

            val key = sha1(texDoc)  // cache invalidates when macros/libs/body change
            val cache = tikzCacheDir()
            val svg = File(cache, "$key.svg")
            if (svg.exists()) {
                return@replace """<span class="tikz-wrap" style="display:block;margin:12px 0;">${svg.readText()}</span>"""
            }

            // build in an isolated work dir
            val work = File(cache, key).apply { mkdirs() }
            val tex = File(work, "fig.tex").apply { writeText(texDoc) }

            // 2c) if user referenced custom tikz libraries (e.g., sstknots),
            // copy tikzlibrary<name>.code.tex next to fig.tex so TeX can find it
            ensureLocalTikzLibs(srcLibs, work)

            fun run(cmd: List<String>): Pair<Int,String> {
                val pb = ProcessBuilder(cmd).directory(work).redirectErrorStream(true)
                val p = pb.start()
                val log = p.inputStream.bufferedReader().readText()
                return p.waitFor() to log
            }

            // pdflatex (or tectonic if you prefer) → dvisvgm
            val (pcode, plog) = run(listOf("pdflatex","-interaction=nonstopmode","-halt-on-error","fig.tex"))
            if (pcode != 0 || !File(work,"fig.pdf").exists()) {
                val msg = htmlEscapeAll(plog.take(1200))
                return@replace """<div class="tikz-error" style="color:#b91c1c;">[TikZ compile failed]<pre>$msg</pre></div>"""
            }
            val (scode, slog) = run(listOf("dvisvgm","--pdf","--no-fonts","-n","-o","fig.svg","fig.pdf"))
            if (scode != 0 || !File(work,"fig.svg").exists()) {
                val msg = htmlEscapeAll(slog.take(1200))
                return@replace """<div class="tikz-error" style="color:#b91c1c;">[dvisvgm failed]<pre>$msg</pre></div>"""
            }

            val svgText = File(work,"fig.svg").readText()
            svg.writeText(svgText) // cache
            """<span class="tikz-wrap" style="display:block;margin:12px 0;">$svgText</span>"""
        }
    }



    // Detect and render macro calls like \SSTdown, \SSTHopfLink[...]{...}{...}, etc.
    private fun convertSstTikzMacros(s: String, srcNoComments: String): String {
        // If user didn’t \usetikzlibrary{sstknots} but uses \SST* macros, add it.
        val preSeen = collectTikzPreamble(srcNoComments)
        val needsSst = Regex("""\\SST[A-Za-z]""").containsMatchIn(s) &&
                !Regex("""\\usetikzlibrary\{[^}]*\bsstknots\b""").containsMatchIn(preSeen)
        val preamble = if (needsSst) preSeen + "\n\\usetikzlibrary{sstknots}\n" else preSeen

        // Heuristic: match a \SSTName with optional [..] and up to 3 braces (your macros use ≤3)
        val rx = Regex("""\\SST[A-Za-z]+(?:\[[^\]]*])?(?:\{[^{}]*}){0,3}""")
        return rx.replace(s) { m ->
            val svg = renderTikzToSvg(preamble, m.value)
            if (svg != null)
                """<img src="${fileUrl(svg)}" alt="tikz" style="max-width:100%;height:auto;display:block;margin:10px auto;"/>"""
            else
                """<pre style="background:#0001;border:1px solid var(--border);padding:8px;overflow:auto;">[TikZ render failed; see cache logs]\n${escapeHtmlKeepBackslashes(m.value)}</pre>"""
        }
    }

    private fun buildTexNewcommands(macros: Map<String, Macro>): String {
        if (macros.isEmpty()) return ""
        val sb = StringBuilder()
        for ((name, m) in macros) {
            val nargs = m.nargs.coerceAtLeast(0)
            if (nargs == 0) sb.append("\\newcommand{\\$name}{${m.def}}\n")
            else            sb.append("\\newcommand{\\$name}[$nargs]{${m.def}}\n")
        }
        return sb.toString()
    }

    private fun collectUsetikzlibsFromSource(src: String): Set<String> =
        Regex("""\\usetikzlibrary\{([^}]*)}""")
            .findAll(src)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun ensureLocalTikzLibs(libs: Set<String>, workDir: File) {
        val base = currentBaseDir?.let(::File) ?: return
        for (lib in libs) {
            // Only try custom-looking names (not core ones)
            if (lib.matches(Regex("""[a-zA-Z][a-zA-Z0-9\-]*"""))) {
                val fname = "tikzlibrary${lib}.code.tex"
                val candidates = listOf(
                    File(base, fname),
                    File(base, "tikz/$fname"),
                    File(base, "tex/$fname")
                )
                val srcFile = candidates.firstOrNull { it.exists() } ?: continue
                srcFile.copyTo(File(workDir, fname), overwrite = true)
            }
        }
    }


    /** Recursively inline all \input{...} and \include{...} files. */
    fun inlineInputs(source: String, baseDir: String, seen: MutableSet<String> = mutableSetOf()): String {
        val rx = Regex("""\\(input|include)\{([^}]+)\}""")

        var result: String = source

        rx.findAll(source).forEach { m ->
            val cmd = m.groupValues[1]
            val rawPath = m.groupValues[2]
            // Try .tex, .sty, or no extension
            val candidates = listOf(rawPath, "$rawPath.tex", "$rawPath.sty")
            val filePath = candidates
                .map { Paths.get(baseDir, it).toFile() }
                .firstOrNull { it.exists() && it.isFile }
            val absPath = filePath?.absolutePath
            if (absPath != null && absPath !in seen) {
                seen += absPath
                val fileText = filePath.readText()
                val inlined = inlineInputs(fileText, filePath.parent ?: baseDir, seen)
                result = result.replace(m.value, inlined)
            } else if (absPath != null && absPath in seen) {
                result = result.replace(m.value, "% Circular input: $rawPath %")
            } else {
                result = result.replace(m.value, "% Missing input: $rawPath %")
            }
        }
        return result
    }

    private var currentBaseDir: String? = null

    fun wrapWithInputs(texSource: String, mainFilePath: String): String {
        val baseDir = File(mainFilePath).parent ?: ""
        currentBaseDir = baseDir

        // Build marked source to compute orig→merged line mapping across \input/\include expansions
        val markerPrefix = "%%LLM"
        val origLines = texSource.split('\n')
        val marked = buildString(texSource.length + origLines.size * 10) {
            origLines.forEachIndexed { idx, line ->
                append(markerPrefix).append(idx + 1).append("%%").append(line)
                if (idx < origLines.lastIndex) append('\n')
            }
        }
        val inlinedMarked = inlineInputs(marked, baseDir)

        // Compute mapping orig line (1-based) -> merged line (1-based)
        val o2m = IntArray(origLines.size) { it + 1 }
        var searchFrom = 0
        for (i in 1..origLines.size) {
            val token = markerPrefix + i + "%%"
            val idx = inlinedMarked.indexOf(token, searchFrom)
            val pos = if (idx >= 0) idx else inlinedMarked.indexOf(token)
            if (pos >= 0) {
                val before = inlinedMarked.substring(0, pos)
                val mergedLine = before.count { it == '\n' } + 1
                o2m[i - 1] = mergedLine
                if (idx >= 0) searchFrom = idx + token.length
            } else {
                // token not found (rare): fallback to previous mapping or 1
                o2m[i - 1] = if (i > 1) o2m[i - 2] else 1
            }
        }

        // Strip markers
        val fullSource = inlinedMarked.replace(Regex("""${markerPrefix}\d+%%"""), "")

        // Build inverse mapping merged -> original using step function (last original line at/ before m)
        val mergedLinesCount = fullSource.count { it == '\n' } + 1
        val m2o = IntArray(mergedLinesCount) { 1 }
        var j = 0 // index into o2m (0-based)
        for (m in 1..mergedLinesCount) {
            while (j + 1 < o2m.size && o2m[j + 1] <= m) j++
            m2o[m - 1] = j + 1 // original line number (1-based)
        }

        // Cache JSON strings for HTML embedding
        lineMapOrigToMergedJson = o2m.joinToString(prefix = "[", postfix = "]") { it.toString() }
        lineMapMergedToOrigJson = m2o.joinToString(prefix = "[", postfix = "]") { it.toString() }

        val html = wrap(fullSource)
        // keep baseDir for subsequent renders; do not clear to allow incremental refreshes
        return html
    }
}
    private fun convertAlignWithMultipleTagsToBlocks(s: String): String {
        // Matches both align and align* environments lazily with DOTALL
        val rx = Regex("""\\begin\{align(\*?)\}(.+?)\\end\{align\1\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val starred = m.groupValues[1].isNotEmpty()
            val body = m.groupValues[2].trim()
            // Count occurrences of \tag{...}
            val tagCount = Regex("""\\tag\{[^}]*}""").findAll(body).count()
            if (tagCount < 2) {
                // Leave unchanged if not the problematic case
                m.value
            } else {
                // Split on unescaped \\\\ with optional [..] spacing, remove empties
                val parts = Regex("""(?<!\\)\\\\(?:\s*\[[^]]*])?\s*""")
                    .split(body)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (parts.isEmpty()) return@replace m.value
                val blocks = parts.joinToString("\n\n") { line ->
                    // Wrap each line so alignment markers & are handled by aligned
                    """\\[\n\\begin{aligned}\n$line\n\\end{aligned}\n\\]"""
                }
                blocks
            }
        }
    }
