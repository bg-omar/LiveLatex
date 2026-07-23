---
name: TikZ Export TeX
overview: "TikZ export/apply/place in editor: resizebox linewidth-wrap; New/Edit/Load Last menu; session store. Helpers + gradle tests."
todos:
  - id: test-lock-export-helpers
    content: "Unit tests wrapResizebox / export body contract (gewenst gedrag)"
    status: completed
  - id: export-resizebox
    content: "Export wrap \\resizebox{W\\linewidth}{!}{tikzpicture}; preview-doc zonder broken linewidth"
    status: completed
  - id: editor-menu-tikz
    content: "New/Edit TikZ + Load Last; descriptions; plugin.xml labels; TikzSessionStore uitbreiden"
    status: completed
  - id: session-on-ok
    content: "doOKAction / Add to TeX schrijft session voor Load Last"
    status: completed
  - id: test-regress
    content: "./gradlew test groen"
    status: completed
isProject: false
---

# Plan 09 — TikZ export / apply / place in TeX

Index: [00_execution_order.plan.md](00_execution_order.plan.md). Files: [`TikzCanvasDialog.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\TikzCanvasDialog.kt), [`NewTikzFigureAction.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\NewTikzFigureAction.kt), [`TikzSessionStore.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\TikzSessionStore.kt), [`plugin.xml`](c:\workspace\projects\LiveLatex\src\main\resources\META-INF\plugin.xml).

## Tests

- **Lock:** bestaande insert/figure-paden waar relevant.
- **New:** `wrapInLinewidthResizebox(body, pct)` → `\resizebox{0.8\linewidth}{!}{...}`; geen “scale= om linewidth te faken”.
- **Menu:** geen zware UI-test; labels/descriptions handcheck + suite.
- `./gradlew test` groen.

## Scope

1. **Export wrap:** Width % uit toolbar → `\resizebox{W\linewidth}{!}{…tikzpicture…}` bij Add to TeX / export body. Interne `scale=1.05` mag blijven. Preview-standalone: geen kapotte `\linewidth` (textwidth of skip wrap in preview-doc).
2. **Editor menu:** **New TikZ** / **Edit TikZ** (caret in `tikzpicture`) + **Load Last TikZ**; description: right-click in picture om te editen; plugin.xml opschonen.
3. **Session:** na OK/export `TikzSessionStore` vullen; Load Last heropent dialog met die state.

Niet in scope: Preview-venster/live (08); undo/origin/help (10).
