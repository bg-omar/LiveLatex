---
name: Fix InsertReference EDT
overview: "Fix PluginException: InsertReferenceActionGroup EDT walkTopDown. BGT + snelle bib-lookup. Helpers + ./gradlew test; geen bug-lock."
todos:
  - id: test-helpers
    content: "Extract/test resolveBibFile + label parse (gewenst: geen full-tree walk)"
    status: pending
  - id: declare-bgt
    content: "InsertReferenceActionGroup (+ nested): getActionUpdateThread = BGT"
    status: pending
  - id: replace-walk
    content: "walkTopDown weg; direct/sibling/FilenameIndex"
    status: pending
  - id: test-regress
    content: "./gradlew test groen; right-click popup handcheck"
    status: pending
isProject: false
---

# Fix InsertReferenceActionGroup EDT freeze

Index: [00_execution_order.plan.md](00_execution_order.plan.md) — **01**.

## Tests

- **Lock:** label/`\\bibliography` parse uit fixture-doc.
- **Fix:** resolve vindt `.bib` zonder project-wide walk (niet: walkTopDown is OK).
- Helper + `./gradlew test`; handcheck context-menu.

Los van toolbar/scroll. Branch: **WIP-Frozen-scroll-broke**.

## Symptoom

```
PluginException: 3148 ms to call on EDT InsertReferenceActionGroup#children@EditorPopup
Revise AnAction.getActionUpdateThread property
```

Stack: `getChildren` → `findBibFile` → `File(projectBasePath).walkTopDown()` → `File.isDirectory` op EDT.

## Oorzaak

[`InsertReferenceActionGroup.kt`](c:\workspace\projects\LiveLatex\src\main\kotlin\com\omariskandarani\livelatex\actions\InsertReferenceActionGroup.kt):

1. Geen `getActionUpdateThread()` → default **EDT**
2. Bij missende `$projectBase/$name.bib`: **hele project** `walkTopDown()` (regels 47–51)
3. Plus `readText()` + regex over elke gevonden `.bib` — ook zwaar op EDT

## Fix

### 1. Threading
```kotlin
override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
```
Op de outer group; nested `ActionGroup`s/AnActions voor citations/labels ook BGT (of parent-policy volgen waar mogelijk).

### 2. Geen project-wide walk
Vervang `walkTopDown` door snelle, begrensde resolutie, in volgorde:
1. `File(projectBasePath, "$name.bib")` (bestaat al)
2. Relatief t.o.v. huidige editor-file parent (en eventueel parents omhoog tot project root) — typische LaTeX-layout
3. IntelliJ **VFS / `FilenameIndex.getVirtualFilesByName(project, "$name.bib", …)`** (of `FilenameIndex.getFilesByName`) — index lookup i.p.v. disk walk
4. Geen recursive `java.io.File` tree walk meer

### 3. Licht houden
- Alleen eerste match gebruiken (zoals nu)
- `update()` blijft goedkoop (extension-check); al geschikt voor BGT

## Verify

- Right-click in `.tex` → menu opent snel, geen PluginException
- Labels-submenus + Citations werken nog als `\bibliography{…}` + `.bib` aanwezig
- Project zonder `.bib` / met `.bib` diep in subdir: nog steeds gevonden via index of relative path
