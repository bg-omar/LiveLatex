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
    window.__llCharO2M = ${charMapOrigToMergedJson ?: "[]"};
    window.__llCharM2O = ${charMapMergedToOrigJson ?: "[]"};
    window.__llSrcMap = ${srcMapJson ?: "[]"};
  </script>
  <script>
    // Re-entrancy / echo guards
    window.__llGuards = {
      suppressEmitUntil: 0,
      echoId: null,
      echoUntil: 0,
      suppressSelectionEmitUntil: 0,
      suppressSelectionEchoUntil: 0
    };
  </script>
  <style>
    :root { --bg:#ffffff; --fg:#111827; --muted:#6b7280; --border:#e5e7eb; }
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
    .ll-section-heading, h2[id], h3[id], h4[id], h5[id] {
      cursor: pointer;
    }
    .ll-section-heading:hover, h2[id]:hover, h3[id]:hover, h4[id]:hover, h5[id]:hover {
      text-decoration: underline;
      text-decoration-color: var(--muted);
    }
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
    /* Source-map selection mirror (editor ↔ preview) */
    .llsrc.ll-mirror-sel { background: rgba(79,70,229,.28); border-radius: 2px; }
    .llsrc::selection, .llsrc *::selection { background: rgba(79,70,229,.35); }
    #ll-debug { position: fixed; right: 10px; bottom: 10px; background: rgba(0,0,0,0.6); color: #fff; font: 12px/1.35 monospace; padding: 8px 10px; border-radius: 6px; z-index: 9999; max-width: 46vw; max-height: 40vh; overflow: auto; white-space: pre-wrap; display: none; }
    #ll-debug.visible { display: block; }
    
    /***** Thin top bar: hidden – controls live in the IDE toolbar above the preview *****/
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
      options: {
        skipHtmlTags: ['script','noscript','style','textarea','pre','code'],
        ignoreHtmlClass: 'tex2jax_ignore'
      },
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

  function llDebugScrollEnabled(){
    try { return localStorage.getItem('ll_debug_scroll') === 'true'; } catch(_) { return false; }
  }
  function applyDebugScroll(on){
    const el = dbgEl(); if (!el) return;
    if (on) el.classList.add('visible');
    else el.classList.remove('visible');
  }
  window.__llDebugScroll = llDebugScrollEnabled;
  window.__llApplyDebugScroll = function(on){
    try { localStorage.setItem('ll_debug_scroll', on ? 'true' : 'false'); } catch(_){}
    applyDebugScroll(!!on);
  };

  function updateDebug(data){
    if (!llDebugScrollEnabled()) return;
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
    init(){
      this.idx = Array.from(document.querySelectorAll('.syncline'))
        .map(el => ({ el, abs:+el.dataset.abs||0 }))
        .filter(x => x.abs > 0)
        .sort((a,b) => a.abs - b.abs);
    },
    scrollToAbs(line, mode='center', meta){
      if (!this.idx.length) this.init();
      const arr = this.idx;
      // no synclines: continuous callers may fall back to marks
      if (!arr.length) return false;

      // Preamble / before first body anchor: stay at top (do not clamp to a late first syncline).
      if (line < arr[0].abs) {
        window.scrollTo({ top: 0 });
        _lastTargetAbs = line;
        _lastPlannedTop = 0;
        _lastScrollTs = Date.now();
        updateDebug({event:'scrollToAbs', mergedAbs: line, mode, meta, preamble:true, targetAbs:null});
        return true;
      }

      // binary search: last anchor with abs <= line
      let lo=0, hi=arr.length-1, ans=-1;
      while (lo<=hi){ const mid=(lo+hi)>>1; if (arr[mid].abs<=line){ ans=mid; lo=mid+1; } else hi=mid-1; }
      if (ans < 0) {
        window.scrollTo({ top: 0 });
        updateDebug({event:'scrollToAbs', mergedAbs: line, mode, meta, preamble:true});
        return true;
      }
      const target = arr[ans] && arr[ans].el; if (!target) return false;

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
        return true; // skip duplicate
      }

      window.scrollTo({ top: plannedTop });
      _lastTargetAbs = line;
      _lastPlannedTop = plannedTop;
      _lastScrollTs = now;

      updateDebug({event:'scrollToAbs', mergedAbs: line, mode, meta});
      return true;
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

        // Continuous editor follow: line anchors (syncline), not section marks.
        // Mark-based scroll freezes within a section then page-jumps at the next heading.
        const continuous = (d.source === 'scroll' || d.source === 'caret' || d.source === 'initial');
        if (continuous) {
          let did = false;
          if (window.sync && typeof window.sync.scrollToAbs === 'function') {
            did = !!window.sync.scrollToAbs(mergedAbs, d.mode || 'center', { from: d.source || 'scroll' });
          }
          // no synclines: fall back to nearest section mark so follow still works
          if (!did) {
            if (!window.__llMarks || !window.__llMarks.length) {
              if (typeof window.__collectMarks === 'function') window.__collectMarks();
            }
            const realMarks = (window.__llMarks || []).filter(m => !m.synthetic);
            if (realMarks.length && typeof window.__scrollToMark === 'function') {
              let lo=0, hi=realMarks.length-1, ans=0;
              while (lo<=hi){ const mid=(lo+hi)>>1; if (realMarks[mid].abs<=mergedAbs){ ans=mid; lo=mid+1; } else hi=mid-1; }
              if (mergedAbs < realMarks[0].abs) {
                window.scrollTo({ top: 0 });
              } else {
                window.__scrollToMark(realMarks[ans], d.mode || 'center');
              }
              try { if (typeof updateDebug==='function') updateDebug({event:'host-sync', origAbs:d.abs, mergedAbs, fallback:'mark'}); } catch(_){}
            }
          }
          return;
        }

        if (!window.__llMarks || !window.__llMarks.length) {
          if (typeof window.__collectMarks === 'function') window.__collectMarks();
        }
        const allMarks  = window.__llMarks || [];
        const realMarks = allMarks.filter(m => !m.synthetic); // <— ignore synthetic here

        if (realMarks.length) {
          // === mark-based scroll for discrete navigation ===
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
(function(){
  function llSyncSelectionEnabled() {
    try { return localStorage.getItem('ll_sync_selection') === 'true'; } catch(_) { return false; }
  }
  window.__llSyncSelectionEnabled = llSyncSelectionEnabled;

  function origToMerged(off) {
    const m = window.__llCharO2M;
    if (!Array.isArray(m) || !m.length) return off;
    if (off < 0) return 0;
    if (off >= m.length) return m[m.length - 1];
    return m[off];
  }

  function clearMirrorHighlight() {
    document.querySelectorAll('.llsrc.ll-mirror-sel').forEach(el => el.classList.remove('ll-mirror-sel'));
  }

  function nodeAtOffset(container, charOffset) {
    const tw = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
    let n = tw.nextNode(), pos = 0;
    while (n) {
      const len = (n.textContent || '').length;
      if (pos + len >= charOffset) return { node: n, offset: charOffset - pos };
      pos += len;
      n = tw.nextNode();
    }
    return null;
  }

  function applyEditorSelection(mergedStart, mergedEnd) {
    clearMirrorHighlight();
    if (!Number.isFinite(mergedStart) || !Number.isFinite(mergedEnd) || mergedEnd <= mergedStart) return;

    const spans = Array.from(document.querySelectorAll('.llsrc[data-s][data-e]'))
      .filter(el => {
        const s = +el.dataset.s, e = +el.dataset.e;
        return e > mergedStart && s < mergedEnd;
      });
    if (!spans.length) return;

    spans.forEach(el => el.classList.add('ll-mirror-sel'));

    const root = document.querySelector('.mj') || document.body;
    const first = spans[0], last = spans[spans.length - 1];
    const range = document.createRange();
    try {
      const fs = +first.dataset.s, fe = +first.dataset.e;
      const ls = +last.dataset.s, le = +last.dataset.e;
      const startOff = Math.max(0, mergedStart - fs);
      const endOff = Math.max(0, Math.min(last.textContent.length, mergedEnd - ls));
      const startNode = nodeAtOffset(first, startOff) || { node: first.firstChild || first, offset: 0 };
      const endNode = nodeAtOffset(last, endOff) || { node: last.lastChild || last, offset: (last.textContent || '').length };
      range.setStart(startNode.node, startNode.offset);
      range.setEnd(endNode.node, endNode.offset);
      const sel = window.getSelection();
      if (sel) {
        sel.removeAllRanges();
        sel.addRange(range);
      }
    } catch(_) {}
  }
  window.applyEditorSelection = applyEditorSelection;
  window.clearMirrorHighlight = clearMirrorHighlight;

  window.addEventListener('message', (ev) => {
    const d = ev.data || {};
    if (d.type !== 'sync-selection') return;
    if (!llSyncSelectionEnabled()) return;
    const g = window.__llGuards || {};
    if (Date.now() < (g.suppressSelectionEchoUntil || 0)) return;

    if (d.clear) {
      clearMirrorHighlight();
      try { window.getSelection()?.removeAllRanges(); } catch(_) {}
      return;
    }
    if (!Number.isFinite(d.srcStart) || !Number.isFinite(d.srcEnd)) return;
    g.suppressSelectionEchoUntil = Date.now() + 120;
    const m0 = origToMerged(d.srcStart);
    const m1 = origToMerged(d.srcEnd);
    applyEditorSelection(m0, m1);
  }, false);

  let _selRaf = 0;
  function mergedFromNode(node) {
    let el = node && node.nodeType === 3 ? node.parentElement : node;
    while (el) {
      if (el.classList && el.classList.contains('llsrc') && el.dataset.s != null) {
        return { start: +el.dataset.s, end: +el.dataset.e, el };
      }
      el = el.parentElement;
    }
    return null;
  }

  function onPreviewSelectionChange() {
    if (!llSyncSelectionEnabled()) return;
    const g = window.__llGuards || {};
    if (Date.now() < (g.suppressSelectionEmitUntil || 0)) return;
    if (Date.now() < (g.suppressSelectionEchoUntil || 0)) return;

    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return;
    const range = sel.getRangeAt(0);
    const a = mergedFromNode(range.startContainer);
    const b = mergedFromNode(range.endContainer);
    if (!a || !b) return;

    const mStart = Math.min(a.start, b.start);
    const mEnd = Math.max(a.end, b.end);
    const text = sel.toString();
    if (!text || text.length < 1) return;

    g.suppressSelectionEmitUntil = Date.now() + 120;
    try {
      if (typeof window.__jbcefSyncSelection === 'function') {
        window.__jbcefSyncSelection({ mergedStart: mStart, mergedEnd: mEnd, text: text });
      }
    } catch(_) {}
  }

  document.addEventListener('selectionchange', () => {
    if (_selRaf) cancelAnimationFrame(_selRaf);
    _selRaf = requestAnimationFrame(() => { _selRaf = 0; onPreviewSelectionChange(); });
  });
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
  (function () {
    function llInvertScrollH() {
      try { return localStorage.getItem('ll_invert_scroll_h') === 'true'; } catch(_) { return false; }
    }
    function llInvertScrollV() {
      try { return localStorage.getItem('ll_invert_scroll_v') === 'true'; } catch(_) { return false; }
    }
    window.__llInvertScrollH = llInvertScrollH;
    window.__llInvertScrollV = llInvertScrollV;

    function isNestedHorizontalScroller(node) {
      var scrollRoot = document.scrollingElement || document.documentElement;
      var el = node;
      while (el && el !== document.documentElement) {
        // Skip only inner scrollers (tables, code); not the page body/root.
        if (el !== scrollRoot && el !== document.body && el.scrollWidth > el.clientWidth + 1) {
          var ox = window.getComputedStyle(el).overflowX;
          if (ox === 'auto' || ox === 'scroll' || ox === 'overlay') return true;
        }
        el = el.parentElement;
      }
      return false;
    }

    window.addEventListener('wheel', function (e) {
      var invertH = llInvertScrollH();
      var invertV = llInvertScrollV();
      if (!invertH && !invertV) return;

      var dx = e.deltaX || 0;
      var dy = e.deltaY || 0;
      var shiftHoriz = false;
      if (!dx && e.shiftKey && dy) {
        dx = dy;
        dy = 0;
        shiftHoriz = true;
      }

      var doH = invertH && dx !== 0;
      var doV = invertV && dy !== 0 && !shiftHoriz;
      if (doH && isNestedHorizontalScroller(e.target)) doH = false;
      if (!doH && !doV) return;

      e.preventDefault();
      var root = document.scrollingElement || document.documentElement;
      if (doH) root.scrollLeft += -dx;
      if (doV) root.scrollTop += -dy;
    }, { passive: false, capture: true });
  })();
  </script>

  <script>
  (function(){
    function llAutoScroll() {
      try { return localStorage.getItem('ll_auto_scroll') !== 'false'; } catch(_) { return true; }
    }
    window.__llAutoScroll = llAutoScroll;

    document.addEventListener('DOMContentLoaded', () => {
      const hamburger = document.getElementById('ll-hamburger');
      const panel = document.getElementById('ll-menu-panel');
      const cbScroll = document.getElementById('ll-auto-scroll');
      const cbInvertH = document.getElementById('ll-invert-scroll-h');
      const cbInvertV = document.getElementById('ll-invert-scroll-v');

      try {
        cbScroll.checked = localStorage.getItem('ll_auto_scroll') !== 'false';
        if (cbInvertH) cbInvertH.checked = localStorage.getItem('ll_invert_scroll_h') === 'true';
        if (cbInvertV) cbInvertV.checked = localStorage.getItem('ll_invert_scroll_v') === 'true';
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
      cbInvertH?.addEventListener('change', () => {
        try { localStorage.setItem('ll_invert_scroll_h', cbInvertH.checked ? 'true' : 'false'); } catch(_) {}
      });
      cbInvertV?.addEventListener('change', () => {
        try { localStorage.setItem('ll_invert_scroll_v', cbInvertV.checked ? 'true' : 'false'); } catch(_) {}
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
      <label class="menu-item"><input type="checkbox" id="ll-invert-scroll-h"> Inverted scroll-h</label>
      <label class="menu-item"><input type="checkbox" id="ll-invert-scroll-v"> Inverted scroll-v</label>
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
  <div id="ll-debug" title="LiveLaTeX scroll debug HUD (Options → Debug Mode, or press D)"></div>
  

  <script>
    (function(){
      function applyFromStorage(){
        var on = false;
        try { on = localStorage.getItem('ll_debug_scroll') === 'true'; } catch(_){}
        var el = document.getElementById('ll-debug'); if (!el) return;
        if (on) el.classList.add('visible'); else el.classList.remove('visible');
      }
      applyFromStorage();
      document.addEventListener('keydown', function(e){
        if ((e.key === 'd' || e.key === 'D') && !e.metaKey && !e.ctrlKey && !e.altKey) {
          var cur = false;
          try { cur = localStorage.getItem('ll_debug_scroll') === 'true'; } catch(_){}
          var next = !cur;
          try { localStorage.setItem('ll_debug_scroll', next ? 'true' : 'false'); } catch(_){}
          if (typeof window.__llApplyDebugScroll === 'function') window.__llApplyDebugScroll(next);
          else {
            var el = document.getElementById('ll-debug'); if (!el) return;
            if (next) el.classList.add('visible'); else el.classList.remove('visible');
          }
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
      try { if (typeof window.__llHostActiveSection === 'function') window.__llHostActiveSection(currentId); } catch(_){}
      const m = marks.find(x => x.id === currentId);
      if (!m) return;

      // set echo guard so editor reply doesn't bounce us back
      const g = window.__llGuards || (window.__llGuards = { suppressEmitUntil:0, echoId:null, echoUntil:0 });
      g.echoId = m.id; g.echoUntil = now + 450;

      const origAbs = mergedAbsToOrig(m.abs);
      // Preview scroll/spy no longer moves the editor caret (avoids snap-back).
      // Clicks/jumps still call __jbcefMoveCaret from jumpToMarkId.
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
    let m = (window.__llMarks || []).find(x => x.id === id);
    if (!m) {
      // Fallback: .llmark immediately before the heading with this id
      try {
        const heading = document.getElementById(id);
        const prev = heading && heading.previousElementSibling;
        if (prev && prev.classList && prev.classList.contains('llmark')) {
          const abs = +(prev.dataset.abs || 0);
          if (abs > 0) m = { el: prev, id: prev.dataset.id || id, abs: abs, synthetic: false };
        }
      } catch(_){}
    }
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
      try { if (typeof window.__llHostActiveSection === 'function') window.__llHostActiveSection(id); } catch(_){}
      try { window.postMessage({ type:'preview-mark', id, origAbs }, '*'); } catch(_){}
    }
  }

  window.jumpToMarkId = jumpToMarkId;

  // Click section/subsection headings in preview → jump editor to that \\section line
  document.addEventListener('click', (e) => {
    const t = e.target;
    if (!t || !t.closest) return;
    if (t.closest('#zoom-in, #zoom-out, button, a, input, select, textarea')) return;
    const heading = t.closest('h2.ll-section-heading, h3.ll-section-heading, h4.ll-section-heading, h5.ll-section-heading, h2[id], h3[id], h4[id], h5[id]');
    if (!heading || !heading.id) return;
    e.preventDefault();
    e.stopPropagation();
    jumpToMarkId(heading.id);
  }, true);

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
      // Kotlin triple-quoted safety: build markup with + (avoid JS backtick-template interpolation)
      nav.innerHTML = items.map(function(i) {
        var cls = i.lvl === 2 ? 'lvl2' : (i.lvl === 3 ? 'lvl3' : '');
        var escLabel = String(i.label).replace(/</g,'&lt;').replace(/>/g,'&gt;');
        var escId = String(i.id).replace(/"/g,'&quot;');
        return '<a href="#" class="' + cls + '" data-id="' + escId + '">' + escLabel + '</a>';
      }).join('');
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
  const TIMEOUT_MS = 20000;
  let debugEnabled = false;
  try { debugEnabled = localStorage.getItem('ll_show_tikz_debug') === 'true'; } catch(_) {}
  window.__llSetTikzDebug = function(state){
    debugEnabled = !!state;
    try { localStorage.setItem('ll_show_tikz_debug', debugEnabled ? 'true' : 'false'); } catch(_) {}
    if (debugEnabled) {
      document.querySelectorAll('.tikz-lazy[data-tikz-key]').forEach(wrap => {
        const key = wrap.getAttribute('data-tikz-key') || '';
        ensureDebugBadge(wrap, key, 'idle');
      });
    } else {
      document.querySelectorAll('.tikz-debug-badge').forEach(el => el.remove());
    }
  };

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
  function escHtml(s){
    return String(s).replace(/[<>&"]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;'}[c]));
  }
  function ensureDebugBadge(wrap, key, state){
    if (!wrap) return;
    if (!debugEnabled) {
      const existing = wrap.querySelector('.tikz-debug-badge');
      if (existing) existing.remove();
      return;
    }
    wrap.style.position = 'relative';
    let badge = wrap.querySelector('.tikz-debug-badge');
    if (!badge) {
      badge = document.createElement('div');
      badge.className = 'tikz-debug-badge';
      badge.style.cssText = 'position:absolute;top:4px;right:4px;z-index:3;font:11px/1.2 monospace;background:rgba(17,24,39,.82);color:#fff;padding:2px 6px;border-radius:4px;max-width:70%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
      wrap.appendChild(badge);
    }
    badge.innerHTML = 'key: ' + escHtml(key) + (state ? ' | ' + escHtml(state) : '');
    badge.style.display = 'block';
  }

  function install(){
    document.querySelectorAll('.tikz-load').forEach(btn=>{
      if (btn.__llBound) return; btn.__llBound = true;
      btn.addEventListener('click', (e)=>{
        e.preventDefault();
        const key = btn.dataset.tikzKey;
        const wrap = btn.closest('.tikz-lazy');
        ensureDebugBadge(wrap, key, 'queued');
        setStatus(wrap, 'Rendering…');

        // Start render
        callHostAsync(key);

        // Timeout guard
        clearTimeout(btn.__llTimeout);
        btn.__llTimeout = setTimeout(()=>{
          setStatus(wrap, 'Render timed out. Is LaTeX installed? Check logs.');
          ensureDebugBadge(wrap, key, 'timeout');
        }, TIMEOUT_MS);
      });
      if (debugEnabled) {
        const key = btn.dataset.tikzKey;
        const wrap = btn.closest('.tikz-lazy');
        ensureDebugBadge(wrap, key, 'idle');
      }
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
      ensureDebugBadge(wrap, d.key, 'ok');
    } else {
      setStatus(wrap, 'Failed: ' + (d.error || 'unknown error'));
      ensureDebugBadge(wrap, d.key, 'fail');
    }
  }, false);

  document.addEventListener('DOMContentLoaded', install);
  new MutationObserver(install).observe(document.documentElement, {subtree:true, childList:true});
  document.addEventListener('DOMContentLoaded', () => window.__llSetTikzDebug(debugEnabled));
})();
</script>

</body>
</html>
""".trimIndent()