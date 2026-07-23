---
name: Remove AutoScroll Editor
overview: "autoScrollEditor weg; editor→preview + jumps blijven. Template/settings asserts + ./gradlew test; geen bug-lock op preview→editor."
todos:
  - id: test-helpers
    content: "Assert template/settings: geen ll_auto_scroll_editor paths (gewenst na fix); pipeline suite blijft"
    status: pending
  - id: remove-setting-toggles
    content: "autoScrollEditor setting + toggles + localStorage push weg"
    status: pending
  - id: remove-template-paths
    content: "onPreviewScroll, __llAutoScrollEditor, emitIfStable moveCaret-tak weg"
    status: pending
  - id: snapback-guards
    content: "jsMoveCaret align-echo weg; syncingFromPreview langer"
    status: pending
  - id: test-regress
    content: "./gradlew test groen; handcheck scroll-policy"
    status: pending
isProject: false
---

# Plan 06 — Auto scroll editor weg

Index: [00_execution_order.plan.md](00_execution_order.plan.md) — **06**.

## Tests

- **Geen** test “preview-scroll verplaatst caret”.
- Wel: na change template bevat geen `onPreviewScroll` / `__llAutoScrollEditor`; bestaande HTML pipeline-tests groen.
- `./gradlew test` + handcheck.

## Doel

- **Weg:** preview → editor (`autoScrollEditor`)
- **Blijft:** editor → preview; jumps via `__jbcefMoveCaret`

## Verwijderen

**Settings/UI**
- [`LiveLatexSettings.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\core\LiveLatexSettings.kt): `autoScrollEditor`
- [`PreviewTitleActions.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\PreviewTitleActions.kt): Options-toggle
- [`PreviewToolbarPanel.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\ui\PreviewToolbarPanel.kt): checkbox
- `syncAutoScrollSettingsToPage`: alleen `_editor` push weg

**Template** [`LatexHtmlTemplate.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\html\LatexHtmlTemplate.kt)
- `onPreviewScroll` IIFE; `__llAutoScrollEditor`; `emitIfStable` moveCaret-tak

## Snap-back

- `jsMoveCaret`: geen `sync-line` align-echo
- Langere `syncingFromPreview` na jump
