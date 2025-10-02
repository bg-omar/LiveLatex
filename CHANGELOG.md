# Changelog

## Unreleased


## [0.0.6] - 2025-09-30

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

---

## [0.0.5] - 2025-09-22

### Added
- LaTeX migration XML files
- LaTeX text formatting actions and enhanced image handling in HTML preview

### Changed
- Updated plugin version to 0.0.5

### Enhanced
- Enhanced TikZ support in LaTeX rendering by adding balanced extraction for nested environments, improving SVG generation
- Enhanced LaTeX HTML rendering by adding support for TikZ and longtable environments, improving macro handling, and refining prose conversion

---

## [0.0.4] - 2025-09-19

### Added
- LaTeX logo

### Changed
- Updated plugin version to 0.0.4

### Enhanced
- Enhanced HTML rendering with title metadata and floating toolbar

---

## [0.0.3] - 2025-09-13

### Added
- Gradle run configuration for publishing

### Changed
- Bumped plugin version to 0.0.3

---

## [0.0.2] - 2025-09-13

### Changed
- Bumped plugin version to 0.0.2 in gradle.properties

---

## [0.0.1] - 2025-09-12

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

---