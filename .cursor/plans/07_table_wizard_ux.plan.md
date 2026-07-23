---
name: Table Wizard UX
overview: "Generate Table: 1 header vast; body rows; placement dropdown; floating align-rij. TableGeneratorTest uitbreiden + ./gradlew test."
todos:
  - id: test-helpers
    content: "TableGeneratorTest: 1 header + N body; colspec l/c/r/p; placement in table env"
    status: completed
  - id: fixed-one-header
    content: "Header-spinner weg; altijd headerRows=1; Rows=body"
    status: completed
  - id: compact-spinners-row
    content: "Body rows + Cols naast elkaar"
    status: completed
  - id: placement-dropdown
    content: "Placement dropdown + tooltip"
    status: completed
  - id: floating-align-over-preview
    content: "Zwevende align-rij boven tabel-mock"
    status: completed
  - id: test-regress
    content: "./gradlew test groen; handcheck dialog"
    status: completed
isProject: false
---

# Plan 07 — Generate Table wizard UX

Index: [00_execution_order.plan.md](00_execution_order.plan.md) — **07**.

## Tests

- Uitbreiden [`TableGeneratorTest.kt`](c:\workspace\projects\LiveLatex\src\test\kotlin\com\omariskandarani\livelatex\tables\TableGeneratorTest.kt) (generator-contract).
- Dialog-layout: handcheck; geen Swing UI-test verplicht.
- `./gradlew test`.

Primair: [`TableWizardDialog.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\ui\TableWizardDialog.kt) + [`TableGenerator.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\tables\TableGenerator.kt).

## Correctie

- Geen header-input → **altijd 1 header**
- Rows = body only
- Align = zwevende 1-rij boven volledige tabel-mock

```
┌─ Align l ─┬─ align p{width} ─┐
└───────────┴──────────────────┘
┌ Header …  ┬  …               ┐
│ body …                       │
└──────────────────────────────┘
```

## Scope

1. Header vast 1; body-rows + cols compact
2. Placement dropdown + tooltip (`htbp`/`t`/`b`/`h`/`p`/…)
3. Floating align-rij; weg met verticale Columns-JTable; outer-rules via bestaande checkbox
4. Live LaTeX preview synchroon
