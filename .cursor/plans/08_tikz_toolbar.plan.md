---
name: TikZ Toolbar
overview: "TikZ dialog toolbar: Preview cache-key + herbruikbaar venster; Live preview; rotate ±; Autosave; Width % UI; New-confirm. Helpers + gradle tests; geen bug-lock."
todos:
  - id: test-lock-rotate-hash
    content: "Helpers: rotate normalize + previewCacheKey(tex) unit tests (gewenst: content-hash, niet vaste key)"
    status: pending
  - id: fix-preview-cache-reuse
    content: "doPreview: content-hash key; één Knot Preview dialog hergebruiken"
    status: pending
  - id: fix-live-preview
    content: "Live preview deelt refresh-pad met Preview; updates na markDirty"
    status: pending
  - id: rotate-negative
    content: "Spinner -360..360; TikZ rotate suffix"
    status: pending
  - id: autosave-checkbox
    content: "Autosave checkbox + debounced maybeAutoSave"
    status: pending
  - id: width-pct-ui
    content: "Width % spinner in toolbar (export-wrap in plan 09)"
    status: pending
  - id: new-confirm
    content: "doNew confirm bij content/dirty"
    status: pending
  - id: test-regress
    content: "./gradlew test groen"
    status: pending
isProject: false
---

# Plan 08 — TikZ toolbar

Index: [00_execution_order.plan.md](00_execution_order.plan.md). Primair: [`TikzCanvasDialog.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\TikzCanvasDialog.kt).

## Tests

- **Lock:** bestaande TikZ renderer/figure-tests blijven groen.
- **Fix (niet vastzetten):** geen test “vaste key `tikz-knot-preview`”. Wel `previewCacheKey(tex)` verschilt per content.
- **New:** `normalizeRotateDeg`, autosave debounce niet unit-testen tenzij geëxtraheerd.
- Afronden: `./gradlew test`.

## Scope

1. **Preview:** content-hash cache key; één modeless dialog hergebruiken (geen stapel Borromean).
2. **Live preview:** zelfde render-refresh als Preview na `markDirty` (debounced).
3. **Rotate ±:** spinner `-360…360`; export-suffix blijft consistent met canvas.
4. **Autosave** checkbox + debounced save via bestaande store.
5. **Width %** UI-control (waarde doorgeven; `\resizebox` wrap = plan 09).
6. **New** met confirm als dirty/niet-leeg.

Niet in scope: undo/origin/help (10); editor menu / resizebox insert (09).
