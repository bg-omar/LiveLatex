package com.omariskandarani.livelatex.actions

/**
 * Pure helpers for the preview Sections dropdown (indent + level filters).
 */
object SectionDropdownHelpers {

    enum class SectionLevel {
        SECTION,
        SUBSECTION,
        SUBSUBSECTION,
    }

    fun sectionLevel(id: String): SectionLevel = when {
        id.startsWith("subsubsection-") || id.startsWith("paragraph-") -> SectionLevel.SUBSUBSECTION
        id.startsWith("subsection-") -> SectionLevel.SUBSECTION
        else -> SectionLevel.SECTION
    }

    /** Display-only indent; ids stay unchanged for jump. */
    fun indentLabel(id: String, label: String): String = when (sectionLevel(id)) {
        SectionLevel.SECTION -> label
        SectionLevel.SUBSECTION -> "  • $label"
        SectionLevel.SUBSUBSECTION -> "    ▹ $label"
    }

    fun includeInDropdown(
        id: String,
        showSubsections: Boolean,
        showSubsubsections: Boolean,
    ): Boolean = when (sectionLevel(id)) {
        SectionLevel.SUBSUBSECTION -> showSubsubsections
        SectionLevel.SUBSECTION -> showSubsections
        SectionLevel.SECTION -> true
    }

    fun filterSectionsForDropdown(
        sections: List<Pair<String, String>>,
        showSubsections: Boolean,
        showSubsubsections: Boolean,
    ): List<Pair<String, String>> =
        sections.filter { (id, _) -> includeInDropdown(id, showSubsections, showSubsubsections) }

    fun displaySections(
        sections: List<Pair<String, String>>,
        showSubsections: Boolean,
        showSubsubsections: Boolean,
    ): List<Pair<String, String>> =
        filterSectionsForDropdown(sections, showSubsections, showSubsubsections)
            .map { (id, label) -> id to indentLabel(id, label) }
}
