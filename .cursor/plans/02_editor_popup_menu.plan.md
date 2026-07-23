---
name: Editor Popup Menu
overview: "Editor rechtermuis: Preview bovenaan + toggle; Insert+cite; Selected Text. Suite + handcheck; toggle-helper indien zinvol."
todos:
  - id: preview-toggle
    content: "ShowPreviewAction: toggle show/hide; optioneel pure helper + kleine test"
    status: completed
  - id: reorder-preview-top
    content: "plugin.xml: Preview eerste LiveLaTeX EditorPopup-item"
    status: completed
  - id: cite-under-insert
    content: "InsertReference onder InsertGroup; weg uit top-level"
    status: completed
  - id: rename-format
    content: "TextFormatGroup → LiveLaTeX Selected Text"
    status: completed
  - id: test-regress
    content: "./gradlew test groen; handcheck menu-volgorde + Ctrl+Alt+P toggle"
    status: completed
isProject: false
---

# Plan — Editor context-menu LiveLaTeX

Index: [00_execution_order.plan.md](00_execution_order.plan.md) — **02**.

## Tests

- Weinig pure logica: **geen** zware UI-tests verplicht.
- Optioneel: `shouldHidePreview(isVisible)` / toggle-beslissing als one-liner helper.
- `./gradlew test` + handcheck menu.

Primair: [`plugin.xml`](c:\workspace\projects\LiveLatex\src\main\resources\META-INF\plugin.xml) + [`ShowPreviewAction.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\ShowPreviewAction.kt).

## Nu (screenshots)

Top-level in `EditorPopupMenu` (onderaan):

1. LiveLateX insert →
2. Show LaTeX Preview (Ctrl+Alt+P)
3. \* Format →
4. \\Cite{label} or \\ref{label} →

`ShowPreviewAction` doet alleen `tw.show()` — sluit niet.

## Doelstructuur

```
Show LaTeX Preview          Ctrl+Alt+P     ← 1e (toggle open/dicht)
LiveLaTeX insert            →
    New TikZ / Edit TikZ …
    Insert Image …
    …
    Cite / ref …            →   ← was top-level; nu hier
LiveLaTeX Selected Text    →              ← was "* Format"
    bold / italic / …
```

---

## 1. Preview bovenaan

Alle LiveLaTeX-entries gebruiken nu `anchor="last"`; volgorde ≈ declaratievolgorde.

Aanpak in `plugin.xml`:
- **Show Preview** als eerste LiveLaTeX-`add-to-group` op `EditorPopupMenu` (declaratie vóór InsertGroup), of expliciet `anchor="before" relative-to-action="LiveLaTeX.InsertGroup"`
- Daarna InsertGroup, daarna TextFormatGroup
- Geen aparte top-level voor InsertReference

Labels: `Show LaTeX Preview` mag blijven (of `LiveLaTeX Preview` — default: bestaande tekst behouden).

---

## 2. Ctrl+Alt+P togglet venster

In `ShowPreviewAction.actionPerformed`:

```kotlin
val tw = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview") ?: return
if (tw.isVisible) tw.hide() else tw.show()
```

Optioneel in `update`: als toolwindow zichtbaar → presentation text `"Hide LaTeX Preview"`, anders `"Show LaTeX Preview"`. Shortcut blijft `ctrl alt P`.

Description updaten: “Show or hide the LaTeX Preview tool window”.

---

## 3. Insert tweede + cite/ref erin

- `LiveLaTeX.InsertGroup`: text → **`LiveLaTeX insert`** (spelling fix t.o.v. LiveLateX)
- Binnen de group, na de bestaande insert-actions:

```xml
<reference ref="LiveLaTeX.InsertReference"/>
```

- Bij `LiveLaTeX.InsertReference`: **`add-to-group` EditorPopupMenu verwijderen** (alleen nog nested via reference)
- Cite/ref submenu-tekst mag korter: bv. `Cite / ref` of `\cite{} / \ref{}` (default: `Cite or ref`)

---

## 4. Format hernoemen

```xml
<group id="LiveLaTeX.TextFormatGroup" text="LiveLaTeX Selected Text" popup="true">
```

Inhoud ongewijzigd (Bold, Italic, …, Move Section). Blijft derde top-level LiveLaTeX-item.

---

## Verify

- Right-click in `.tex`: volgorde Preview → Insert → Selected Text (geen losse Cite-entry)
- Insert → bevat Cite/ref submenu met labels/citations
- Ctrl+Alt+P: open → dicht → open
- Tools-menu Insert-groep ongewijzigd bruikbaar (alleen spelling)

Niet in scope: TikZ (zie [08](08_tikz_toolbar.plan.md)–[10](10_tikz_canvas_behaviour.plan.md)); InsertReference EDT ([01](01_fix_insert_reference_edt.plan.md)).
