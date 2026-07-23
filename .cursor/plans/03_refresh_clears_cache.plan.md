---
name: Refresh Clears Cache
overview: "Refresh = clear doc-cache + reload; Options clear-doc weg. Helper/service test + ./gradlew test."
todos:
  - id: test-helpers
    content: "Temp-dir test clearCacheForPaper contract indien testbaar zonder volle IDE"
    status: completed
  - id: wire-refresh-clear
    content: "PreviewRefreshAction → requestClearCache + tooltip"
    status: completed
  - id: remove-options-clear-doc
    content: "Options Clear cache for this document weg; Clear all blijft"
    status: completed
  - id: test-regress
    content: "./gradlew test groen; handcheck Refresh"
    status: completed
isProject: false
---

# Plan 03 — Refresh = clear document cache

Index: [00_execution_order.plan.md](00_execution_order.plan.md) — **03**.

## Tests

- Geen test die “Refresh = alleen soft refresh” vastzet (dat is juist wat we veranderen).
- Wel: clear-dir + refresh contract op temp folder als service-methode isoleerbaar is.
- `./gradlew test` + handcheck.

## Doel

Title-bar Refresh doet wat Options “Clear cache for this document” nu doet; die Options-entry weg.

## Wijzigingen

- [`PreviewRefreshAction`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\PreviewTitleActions.kt): `requestClearCache()` i.p.v. `requestRefresh()`; tooltip bv. “Clear cache for this document and refresh preview”
- Options: **“Clear cache for this document”** weg; **“Clear all cache”** blijft
- Optioneel consistentie in dode [`PreviewToolbarPanel`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\ui\PreviewToolbarPanel.kt)

API bestaat al: `requestClearCache()` → `clearCacheForPaper()` (delete `currentDocumentCacheDir` + `scheduleRefresh`).

## Verify

- Refresh wist TikZ/doc-cache voor huidig document en herlaadt preview
- “Clear cache for this document” niet meer in Options; “Clear all cache” wel
