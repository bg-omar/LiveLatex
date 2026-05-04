# Changelog

## Unreleased 0.0.9 - 2026-04-19

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## 0.0.8 - 2026-03-31

### Added

- LaTeX Preview tool window **title actions**: jump to section (from parsed `\section` list), zoom in/out, **options** menu (auto-scroll preview, auto-scroll editor, clear preview cache), and **LiveRender** toggle for TikZ compilation in the preview
- **In-preview toolbar** (`PreviewToolbarPanel`): hamburger options, section combo, zoom controls, LiveRender checkbox, and a progress indicator when compiling many TikZ figures
- **Application settings** (`LiveLatexSettings`): default LiveRender for TikZ, auto-scroll preview/editor, optional TikZ debug overlay; synced with the HTML preview where applicable
- `**RenderTikzToggleAction`** and `**ShowPreviewAction**` visibility extended to `.sty` and `.tikz` files (in addition to `.tex`)
- **Unit tests**: `LatexHtmlParsingTest`, `LatexHtmlSanitizerTest`, `LatexHtmlUtilsTest`, `LatexTikzJobStoreTest`, `TikzRendererTest`, `TableGeneratorTest`; updates to `SectionMoverTest`
- Example document `**TorusKnots.tex`** (replaces removed `knots-tester.tex`)
- **README**: troubleshooting for Gradle **PKIX / SSL certificate** issues behind proxies
- **LiveLatex browser extension**: Save / Open / Preview controls and tab mode; error dialog and stored template placeholders; “render everywhere” toggle and clearer badge text; optional **dynamic CSS** for ChatGPT and Gemini with cache-aware loading; **Gemini tree panel** (UI, drag-and-drop, resizing); chat tree panel with sidebar sorting, auto-scroll, and popup preferences; widescreen CSS overrides

### Changed

- IntelliJ Platform compatibility: `**pluginUntilBuild` extended to `261.*`**
- **TikZ canvas** (`TikzCanvasDialog`), **new TikZ figure** action, and **knot presets** / IDE knot store data (refined flip lists and presets)
- **LaTeX → HTML preview pipeline**: `LatexHtmlTemplate`, `LatexHtmlProse`, `LatexHtmlMacros`, `LatexHtmlTikz`, `LatexHtmlSanitizer`, `LatexHtml`, and `**TikzRenderer`** — improved TikZ job handling, rendering, and sanitization; `**LatexPreviewService**` coordinates refresh, browser bridge, and deferred TikZ work
- Gradle / wrapper and IntelliJ **project metadata** (`.idea`, platform self-update lock); **plugin manifest** and branding assets updated
- `**testfile.tex`** refreshed for current features

### Removed

- Redundant helpers in `**LatexHtmlBlocks**` (logic consolidated in the rest of the HTML stack)
- Legacy `**knots-tester.tex**`

## 0.0.7

- Make compatible with 2026.1

## 0.0.6 - 2025-09-30

### Added

- TikZ example document and updated knot presets with descriptive names
- Two-strand export settings and enhanced TikZ figure handling
- Knot presets for common knots and grid coordinate display in TikZ tool window

### Changed

- Updated README to use PNG images for logo and LaTeX icons

### Refactored

- Refactored knot presets and grid coordinate handling for improved precision and clarity
- Refactored InsertReferenceActionGroup to enhance bibliography file handling and improve citation key extraction

### Enhanced

- Enhanced TikZ interaction by adding long-press functionality for knot addition and improving state management for user interactions
- Enhanced TikZ rendering by adding lazy loading support, deferred compilation of TikZ jobs, render buttons, and status messages
- Enhanced mark handling in LaTeX HTML rendering by filtering out synthetic marks, improving scroll behavior, and adding synthetic mark creation for documents without marks

## 0.0.5 - 2025-09-22

### Added

- LaTeX migration XML files
- LaTeX text formatting actions and enhanced image handling in HTML preview

### Changed

- Updated plugin version to 0.0.5

### Enhanced

- Enhanced TikZ support in LaTeX rendering by adding balanced extraction for nested environments, improving SVG generation
- Enhanced LaTeX HTML rendering by adding support for TikZ and longtable environments, improving macro handling, and refining prose conversion

## 0.0.4 - 2025-09-19

### Added

- LaTeX logo

### Changed

- Updated plugin version to 0.0.4

### Enhanced

- Enhanced HTML rendering with title metadata and floating toolbar

## 0.0.3 - 2025-09-13

### Added

- Gradle run configuration for publishing

### Changed

- Bumped plugin version to 0.0.3

## 0.0.2 - 2025-09-13

### Changed

- Bumped plugin version to 0.0.2 in gradle.properties

## 0.0.1 - 2025-09-12

### Added

- Project code style configuration for Kotlin
- LaTeX utilities and actions for table generation and TikZ figures
- Initial README and LaTeX example
- Configuration files and enhanced LaTeX preview tool window behavior

### Changed

- Updated project configuration

### Fixed

- Fixed regex for label stripping in LaTeX HTML conversion
- Improved line anchor injection for multi-line math and updated tool window behavior

### Refactored

- Refactored file handling in image insertion and updated action visibility for LaTeX files
- Refactored LaTeX HTML rendering and improved synchronization with editor
- Refactored LiveLatex plugin configuration and improved scroll synchronization in LaTeX preview

### Enhanced

- Enhanced LaTeX action visibility based on file type and improved HTML rendering for lists and tables
- Enhanced LaTeX figure handling