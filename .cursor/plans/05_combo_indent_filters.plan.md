---
name: Combo Indent Filters
overview: "Combo indent + Show subsections/subsubsections. Pure helpers + ./gradlew test."
todos:
  - id: test-helpers
    content: "Unit tests sectionLevel / indentLabel / filterSectionsForDropdown"
    status: pending
  - id: indent-labels
    content: "Display-labels indenten op id-prefix"
    status: pending
  - id: settings-filter-toggles
    content: "Settings + Options toggles + UI refresh"
    status: pending
  - id: test-regress
    content: "./gradlew test groen; handcheck dropdown"
    status: pending
isProject: false
---

# Plan 05 — Combo indentatie + niveau-filters

Index: [00_execution_order.plan.md](00_execution_order.plan.md) — **05** (ná 04).

## Tests

- Extract pure helpers → unit tests (level, indent string, filter).
- Geen bug-lock relevant; `./gradlew test`.

## Indentatie

Niveau uit id-prefix (zelfde als in-page `labelFromMark`):
- `section-` / top: geen prefix
- `subsection-`: indent + `•`
- `subsubsection-` / `paragraph-`: diepere indent + `▹`

In [`PreviewChapterComboAction`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\PreviewTitleActions.kt): display-label alleen; id ongewijzigd.

## Options-filters

[`LiveLatexSettings.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\core\LiveLatexSettings.kt) (default beide `true`):
- `showDropdownSubsections`
- `showDropdownSubsubsections`

Toggles in Options; bij toggle `notifySectionsUiListeners()`.

```kotlin
when {
  id.startsWith("subsubsection-") || id.startsWith("paragraph-") -> settings.showDropdownSubsubsections
  id.startsWith("subsection-") -> settings.showDropdownSubsections
  else -> true
}
```

## Verify

- Sub-/subsubsection genest; Options uit → weg; jump werkt
