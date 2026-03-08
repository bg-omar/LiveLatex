package com.omariskandarani.livelatex.html

/**
 * HTML page template for LaTeX preview. Part of LatexHtml multi-file object.
 */

internal fun buildHtml(fullTextHtml: String, macrosJs: String): String = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>LaTeX Preview</title>
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'self' 'unsafe-inline' data: blob: https://cdn.jsdelivr.net;
                 script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net;
                 style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net;
                 img-src * data: blob:;
                 font-src https://cdn.jsdelivr.net data:;">
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
    .wrap { padding: 40px 20px 40px; max-width:980px; margin:0 auto; }
    .mj   { font-size:16px; line-height:1.45; transition:font-size .2s; }
    .full-text { white-space: normal; }
    table { border-collapse: collapse; margin-top: 0.2em; margin-bottom: 0.2em; }
    a { color: inherit; }
    /* cite and ref links */
    a.ll-cite, a.ll-ref, a.ll-eqref { text-decoration: underline; cursor: pointer; color: var(--muted); }
    a.ll-cite:hover, a.ll-ref:hover, a.ll-eqref:hover { color: var(--fg); }
    h1, h2, h3, h4, h5 { margin-top: 0.8em; margin-bottom: 0.2em; }
    figcaption { margin-top: 0.1em; margin-bottom: 0.2em; }
    /* Reduce space after display math (MathJax block equations) */
    .mjx-container[jax="CHTML"][display="true"] { margin-bottom: 0.2em; }
    /* zero-size line markers that don't affect layout */
    .syncline { display:inline-block; width:0; height:0; overflow:hidden; }
    html, body { height: 100%; margin: 0; }
    body { overflow-y: auto; }
    .wrap { min-height: 100vh; padding-top: 56px; }
    /* Floating zoom toolbar: reserve top space so title is not covered when scrolled to top */
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
    .ll-label { display: none; }
    /* \label{} anchors invisible (same/next line under \section/\subsection) */
    /* Preview caret marker */
    .caret-mark { display:inline-block; border-left: 1.5px solid #4F46E5; height: 1em; margin-left:-0.75px; animation: llblink 1s step-end infinite; }
    @keyframes llblink { 50% { border-color: transparent; } }
    .sync-target { outline: 2px dashed #10b981; outline-offset: 2px; }
    #ll-debug { position: fixed; right: 10px; bottom: 10px; background: rgba(0,0,0,0.6); color: #fff; font: 12px/1.35 monospace; padding: 8px 10px; border-radius: 6px; z-index: 9999; max-width: 46vw; max-height: 40vh; overflow: auto; white-space: pre-wrap; display: none; }
    #ll-debug.visible { display: block; }
    
    /***** Thin top bar: verborgen – alle knoppen zitten in de IDE-toolbalk boven de preview *****/
    .ll-topbar {
      display: none !important;
      top: 0; left: 0; right: 0;
      z-index: 200;
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      min-height: 28px;
      background: var(--bg);
      border-bottom: 1px solid var(--border);
    }
    .ll-topbar .title {
      font-size: 13px;
      font-weight: 600;
      opacity: .9;
      margin-right: 4px;
      white-space: nowrap;
    }
    .ll-topbar .chapters {
      min-width: 160px;
      max-width: 50vw;
    }
    .ll-topbar select {
      width: 100%;
      padding: 2px 6px;
      font-size: 12px;
      min-height: 22px;
      border: 1px solid var(--border);
      background: var(--bg);
      color: var(--fg);
      border-radius: 4px;
    }
    .ll-topbar .spacer { flex: 1 1 auto; }
    .ll-topbar .btn {
      font-size: 13px;
      padding: 2px 8px;
      min-width: 28px;
      min-height: 22px;
      border-radius: 4px;
      border: 1px solid var(--border);
      background: var(--bg);
      color: var(--fg);
      cursor: pointer;
      line-height: 1;
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

    /* Hamburger menu */
    .ll-topbar .hamburger {
      font-size: 16px;
      padding: 2px 6px;
      min-width: 24px;
      min-height: 22px;
      cursor: pointer;
      background: transparent;
      border: 1px solid transparent;
      color: var(--fg);
      border-radius: 4px;
      line-height: 1;
    }
    .ll-topbar .hamburger:hover { background: var(--border); }
    .ll-topbar .menu-wrap { position: relative; }
    .ll-topbar .menu-panel {
      display: none;
      position: absolute;
      top: 100%;
      left: 0;
      margin-top: 4px;
      min-width: 200px;
      padding: 8px 12px;
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 6px;
      box-shadow: 0 4px 12px rgba(0,0,0,.12);
      z-index: 300;
    }
    .ll-topbar .menu-panel.open { display: block; }
    .ll-topbar .menu-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 0;
      font-size: 12px;
      cursor: pointer;
      user-select: none;
    }
    .ll-topbar .menu-item:hover { opacity: .9; }
    .ll-topbar .menu-item input { accent-color: var(--fg); cursor: pointer; }

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
    window.addEventListener('message', (ev) => {
      const d = ev.data || {};
      if (d && d.type === 'sync-line' && Number.isFinite(d.abs)) {
        if (typeof window.__llAutoScroll === 'function' && !window.__llAutoScroll()) return;
        let mergedAbs = d.abs;
        if (Array.isArray(window.__llO2M) && window.__llO2M.length &&
            mergedAbs>=1 && mergedAbs<=window.__llO2M.length) {
          mergedAbs = window.__llO2M[mergedAbs-1];
        }

        if (!window.__llMarks || !window.__llMarks.length) {
          if (typeof window.__collectMarks === 'function') window.__collectMarks();
        }
        const allMarks  = window.__llMarks || [];
        const realMarks = allMarks.filter(m => !m.synthetic); // <— ignore synthetic here

        if (realMarks.length) {
          // === mark-based scroll (unchanged, but use realMarks) ===
          let lo=0, hi=realMarks.length-1, ans=0;
          while (lo<=hi){ const mid=(lo+hi)>>1; if (realMarks[mid].abs<=mergedAbs){ ans=mid; lo=mid+1; } else hi=mid-1; }
          const mark = realMarks[ans];
          try { if (mark) window.__llActiveIdx = ans; } catch(_){}
          const g = window.__llGuards || (window.__llGuards = { suppressEmitUntil:0, echoId:null, echoUntil:0 });
          const now = Date.now();
          if (mark && g.echoId === mark.id && now < g.echoUntil) return;
          g.suppressEmitUntil = now + 350;
          if (typeof window.__scrollToMark === 'function') {
            try { if (typeof updateDebug==='function') updateDebug({event:'host-sync', origAbs:d.abs, mergedAbs, targetMark: mark?.id || null}); } catch(_){}
            window.__scrollToMark(mark, d.mode || 'center');
          }
        } else {
          // === fallback to syncline anchors ===
          if (window.sync && typeof window.sync.scrollToAbs === 'function') {
            window.sync.scrollToAbs(mergedAbs, d.mode || 'center', { fallback:'syncline' });
          }
        }
      }

      if (d && d.type === 'sync-mark' && typeof d.id === 'string') {
        if (typeof window.__llAutoScroll === 'function' && !window.__llAutoScroll()) return;
        if (!window.__llMarks || !window.__llMarks.length) {
          if (typeof window.__collectMarks === 'function') window.__collectMarks();
        }
        const m = (window.__llMarks || []).find(x => x.id === d.id);
        try {
          const marks = window.__llMarks || [];
          const idx = marks.findIndex(x => x && x.id === (m && m.id));
          if (idx >= 0) window.__llActiveIdx = idx;
        } catch(_){}
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

  <script>
  (function(){
    function llAutoScroll() {
      try { return localStorage.getItem('ll_auto_scroll') !== 'false'; } catch(_) { return true; }
    }
    function llAutoScrollEditor() {
      try { return localStorage.getItem('ll_auto_scroll_editor') !== 'false'; } catch(_) { return true; }
    }
    window.__llAutoScroll = llAutoScroll;
    window.__llAutoScrollEditor = llAutoScrollEditor;

    document.addEventListener('DOMContentLoaded', () => {
      const hamburger = document.getElementById('ll-hamburger');
      const panel = document.getElementById('ll-menu-panel');
      const cbScroll = document.getElementById('ll-auto-scroll');
      const cbScrollEditor = document.getElementById('ll-auto-scroll-editor');

      try {
        cbScroll.checked = localStorage.getItem('ll_auto_scroll') !== 'false';
        cbScrollEditor.checked = localStorage.getItem('ll_auto_scroll_editor') !== 'false';
      } catch(_) {}

      hamburger?.addEventListener('click', (e) => {
        e.stopPropagation();
        panel?.classList.toggle('open');
      });
      panel?.addEventListener('click', (e) => e.stopPropagation());
      document.addEventListener('click', () => panel?.classList.remove('open'));

      cbScroll?.addEventListener('change', () => {
        try { localStorage.setItem('ll_auto_scroll', cbScroll.checked ? 'true' : 'false'); } catch(_) {}
      });
      cbScrollEditor?.addEventListener('change', () => {
        try { localStorage.setItem('ll_auto_scroll_editor', cbScrollEditor.checked ? 'true' : 'false'); } catch(_) {}
      });
      document.getElementById('ll-clear-cache')?.addEventListener('click', () => {
        panel?.classList.remove('open');
        if (typeof window.__llHostClearCache === 'function') window.__llHostClearCache();
      });
    });
  })();
  </script>

</head>
<body>
<div class="ll-topbar">
  <div class="menu-wrap">
    <button id="ll-hamburger" class="hamburger" title="Options">☰</button>
    <div id="ll-menu-panel" class="menu-panel">
      <label class="menu-item"><input type="checkbox" id="ll-auto-scroll" checked> Auto scroll preview</label>
      <label class="menu-item"><input type="checkbox" id="ll-auto-scroll-editor" checked> Auto scroll editor</label>
      <div id="ll-clear-cache" class="menu-item" style="cursor:pointer;" title="Clear TikZ/LaTeX cache for this paper">Clear cache for paper</div>
    </div>
  </div>
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
        .map(el => ({
          el,
          id:  el.dataset.id || '',
          abs: +(el.dataset.abs || 0),
          synthetic: el.dataset.synthetic === '1' || el.classList.contains('llmark--synthetic')
        }))
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
    window.addEventListener('DOMContentLoaded', () => {
      const hasMarks = (window.__llMarks && window.__llMarks.length) || document.querySelector('.llmark');
      const firstSyncline = document.querySelector('.syncline');
      if (!hasMarks && firstSyncline) {
        const m = document.createElement('span');
        m.className = 'llmark llmark--synthetic';
        m.dataset.id = 'doc-start';
        m.dataset.abs = firstSyncline.dataset.abs || '1';
        m.dataset.synthetic = '1';                // <— add this
        firstSyncline.parentNode.insertBefore(m, firstSyncline);
        if (typeof window.__collectMarks === 'function') window.__collectMarks();
      }
    });
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
      if (typeof window.__llAutoScrollEditor === 'function' && window.__llAutoScrollEditor()) {
        try { if (typeof window.__jbcefMoveCaret === 'function') window.__jbcefMoveCaret({ line: origAbs, markId: m.id }); } catch(_){}
      }
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

    // Observe all mark sentinels (they're zero-size; that's fine — CHTML boxes exist)
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
    // Prefer the following heading's text as label; fallback to id
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

  window.jumpToMarkId = jumpToMarkId;

  window.__selectMarkInTopbar = function(id){
    const sel = document.getElementById('ll-chapters'); if (!sel) return;
    if (sel.value !== id) sel.value = id;
  };

  function sendSectionsToHost() {
    if (typeof window.__llHostSectionsReady !== 'function') return;
    if (!window.__llMarks || !window.__llMarks.length) { if (typeof window.__collectMarks === 'function') window.__collectMarks(); }
    var marks = window.__llMarks || [];
    var arr = marks.map(function(m){ var lab = labelFromMark(m).label; return { id: m.id, abs: m.abs, label: lab }; });
    window.__llHostSectionsReady(JSON.stringify(arr));
  }
  window.sendSectionsToHost = sendSectionsToHost;

  window.addEventListener('DOMContentLoaded', () => {
    populateChapters();
    sendSectionsToHost();
    setTimeout(function(){ populateChapters(); sendSectionsToHost(); }, 450);

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
      nav.innerHTML = items.map(i => `<a href="#" class="${'$'}{i.lvl==\\\\\\\\\\\\\\\\\\\\\\\\\=2?'lvl2':i.lvl===3?'lvl3':''}" data-id="${'$'}{i.id}">${'$'}{i.label}</a>`).join('');
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
<script>
(function(){
  function setStatus(el, msg) {
    const s = el.querySelector('.tikz-status');
    if (s) s.textContent = msg;
  }

  // Click handler: ask host to render by key
  document.addEventListener('click', function(e){
    const btn = e.target.closest && e.target.closest('.tikz-load');
    if (!btn) return;
    const key = btn.dataset.tikzKey;
    if (!key) return;
    const holder = btn.closest('.tikz-lazy');
    btn.disabled = true;
    setStatus(holder, 'Rendering…');

    try {
      // Ask the host (your Kotlin side) to render this key.
      // Your host already listens to preview messages (you post 'preview-mark' etc).
      // Handle this new 'tikz-render' similarly in host, then post a 'tikz-render-result'.
      window.postMessage({ type: 'tikz-render', key }, '*');
    } catch(_) {}
  }, true);

  // Host → page: receive render result
  window.addEventListener('message', function(ev){
    const d = ev.data || {};
    if (d && d.type === 'tikz-render-result' && d.key) {
      const holder = document.querySelector('.tikz-lazy[data-tikz-key="'+d.key+'"]');
      if (!holder) return;

      if (d.ok && d.svgText) {
        holder.outerHTML = '<span class="tikz-wrap" style="display:block;margin:12px 0;">' + d.svgText + '</span>';
      } else if (d.ok && d.url) {
        holder.outerHTML = '<img src="'+d.url+'" alt="tikz" style="max-width:100%;height:auto;display:block;margin:10px auto;"/>';
      } else {
        const msg = (d.error || 'TikZ render failed').toString().slice(0, 2000);
        holder.innerHTML =
          '<pre class="tikz-error" style="background:#0001;border:1px solid var(--border);padding:8px;overflow:auto;">'
          + msg.replace(/[<>&]/g, s=>({ '<':'&lt;','>':'&gt;','&':'&amp;' }[s])) + '</pre>';
      }
    }
  }, false);
})();
</script>
<script>
(function(){
  const TIMEOUT_MS = 20000;

  function callHostAsync(key){
    if (typeof window.__llHostRenderTikz === 'function') {
      // fire-and-accept: host will postMessage the result later
      try { window.__llHostRenderTikz(key); } catch(_) {}
      return;
    }
    // Fallback for older host: request via postMessage
    window.postMessage({ type: 'tikz-render', key }, '*');
  }

  function setStatus(wrap, msg){ const s=wrap.querySelector('.tikz-status'); if (s) s.textContent = msg; }

  function install(){
    document.querySelectorAll('.tikz-load').forEach(btn=>{
      if (btn.__llBound) return; btn.__llBound = true;
      btn.addEventListener('click', (e)=>{
        e.preventDefault();
        const key = btn.dataset.tikzKey;
        const wrap = btn.closest('.tikz-lazy');
        setStatus(wrap, 'Rendering…');

        // Start render
        callHostAsync(key);

        // Timeout guard
        clearTimeout(btn.__llTimeout);
        btn.__llTimeout = setTimeout(()=>{
          setStatus(wrap, 'Render timed out. Is LaTeX installed? Check logs.');
        }, TIMEOUT_MS);
      });
    });
  }

  // Page receives the host result
  window.addEventListener('message', (ev)=>{
    const d = ev.data || {};
    if (d.type !== 'tikz-render-result' || !d.key) return;
    const wrap = document.querySelector('.tikz-lazy[data-tikz-key="'+d.key+'"]');
    if (!wrap) return;
    // clear any timeouts
    const btn = wrap.querySelector('.tikz-load'); if (btn && btn.__llTimeout) clearTimeout(btn.__llTimeout);

    if (d.ok) {
      if (d.svgText) wrap.innerHTML = d.svgText;
      else if (d.url) wrap.innerHTML = '<img alt="tikz" src="'+d.url+'">';
      else wrap.innerHTML = '<div>Rendered.</div>';
    } else {
      setStatus(wrap, 'Failed: ' + (d.error || 'unknown error'));
    }
  }, false);

  document.addEventListener('DOMContentLoaded', install);
  new MutationObserver(install).observe(document.documentElement, {subtree:true, childList:true});
})();
</script>

'\'\
</body>
</html>
""".trimIndent()
