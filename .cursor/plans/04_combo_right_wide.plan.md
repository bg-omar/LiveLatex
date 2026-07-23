---
name: Combo Right Wide
overview: "Sections-combo rechts + breed in title-actions. Layout vooral handcheck; ./gradlew test suite."
todos:
  - id: reorder-title-actions
    content: "PreviewChapterComboAction als laatste in setTitleActions"
    status: pending
  - id: combo-grow-width
    content: "Combo/wrapper max+preferred breedte / resize fill-remaining"
    status: pending
  - id: test-regress
    content: "./gradlew test groen; handcheck layout + jump"
    status: pending
isProject: false
---

# Plan 04 — Sections-combo rechts + breed

Index: [00_execution_order.plan.md](00_execution_order.plan.md) — **04**.

## Tests

- Weinig pure logica (Swing sizes) → **geen** verplichte nieuwe unit tests; suite groen + handcheck.
- Indent/filter helpers horen bij plan 05.

## Doel

Geen content-toolbar. Combo rechts van onze knoppen, breed tot beschikbare ruimte.

## Volgorde

In [`LatexPreviewToolWindowFactory`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\ui\LatexPreviewToolWindowFactory.kt):

```
LiveRender | Refresh | Cancel | − | + | Options | [==== Sections combo ====] | ··· | hide
```

`PreviewChapterComboAction` als **laatste** in de list (nu staat die vroeg).

## Breedte

In [`PreviewChapterComboAction`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\PreviewTitleActions.kt) (~nu preferred 200×28):
- `maximumSize` width zeer groot; height ~28
- Parent resize-listener: `preferredSize.width` = beschikbare resterende header-ruimte
- Fallback als title-strip niet stretcht: vaste grotere breedte (360–480+)

## Verify

- Icon-knoppen geclusterd links; brede combo rechts vóór ···/hide
- Resize toolwindow: combo groeit/krimpt; jump werkt nog
