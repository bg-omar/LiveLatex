---
name: TikZ Canvas Behaviour
overview: "TikZ canvas behaviour: help-overlay, (0,0) centreren, undo/redo Ctrl+Z / Ctrl+Shift+Z. Helpers + gradle tests."
todos:
  - id: test-lock-undo-origin
    content: "Unit tests undo stack push/pop; origin-center helper math indien geëxtraheerd"
    status: completed
  - id: canvas-help-overlay
    content: "Help-tekst linksboven (grab/add/delete/insert-on-line)"
    status: completed
  - id: origin-centered
    content: "centerOriginInViewport bij open/resize/load/new"
    status: completed
  - id: undo-redo
    content: "Undo/redo snapshots + shortcuts Ctrl+Z / Ctrl+Shift+Z"
    status: completed
  - id: test-regress
    content: "./gradlew test groen"
    status: completed
isProject: false
---

# Plan 10 — TikZ canvas behaviour

Index: [00_execution_order.plan.md](00_execution_order.plan.md). Primair: [`TikzCanvasDialog.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\TikzCanvasDialog.kt).

## Tests

- **New:** pure `UndoStack` (push/undo/redo/clear) op snapshot data-class; geen Swing.
- Origin: helper die viewport-offset berekent — unit-testen indien geëxtraheerd.
- `./gradlew test` groen.

## Scope

1. **Help overlay** linksboven:
   - Click + hold to grab points
   - Click + hold free space to add point
   - Left mouse on point: delete
   - Click + hold on line: add point between
2. **(0,0) midden:** `centerOriginInViewport()` bij open, resize, load, new.
3. **Undo/Redo:** snapshots bij betekenisvolle mutaties (drag = start+commit); Ctrl+Z / Ctrl+Shift+Z (opt. Ctrl+Y).

Niet in scope: toolbar preview/rotate (08); export/menu (09).
