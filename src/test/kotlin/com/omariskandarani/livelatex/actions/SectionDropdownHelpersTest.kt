package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionDropdownHelpersTest {

    @Test
    fun sectionLevel_fromIdPrefix() {
        assertEquals(SectionDropdownHelpers.SectionLevel.SECTION, SectionDropdownHelpers.sectionLevel("section-1"))
        assertEquals(SectionDropdownHelpers.SectionLevel.SECTION, SectionDropdownHelpers.sectionLevel("chapter-1"))
        assertEquals(SectionDropdownHelpers.SectionLevel.SUBSECTION, SectionDropdownHelpers.sectionLevel("subsection-2"))
        assertEquals(SectionDropdownHelpers.SectionLevel.SUBSUBSECTION, SectionDropdownHelpers.sectionLevel("subsubsection-3"))
        assertEquals(SectionDropdownHelpers.SectionLevel.SUBSUBSECTION, SectionDropdownHelpers.sectionLevel("paragraph-4"))
    }

    @Test
    fun indentLabel_nestsByLevel() {
        assertEquals("Intro", SectionDropdownHelpers.indentLabel("section-1", "Intro"))
        assertEquals("  • Details", SectionDropdownHelpers.indentLabel("subsection-1", "Details"))
        assertEquals("    ▹ Note", SectionDropdownHelpers.indentLabel("subsubsection-1", "Note"))
        assertEquals("    ▹ Para", SectionDropdownHelpers.indentLabel("paragraph-1", "Para"))
    }

    @Test
    fun filterSectionsForDropdown_respectsToggles() {
        val sections = listOf(
            "section-1" to "A",
            "subsection-1" to "B",
            "subsubsection-1" to "C",
            "paragraph-1" to "D",
        )
        assertEquals(
            listOf("section-1" to "A"),
            SectionDropdownHelpers.filterSectionsForDropdown(sections, showSubsections = false, showSubsubsections = false),
        )
        assertEquals(
            listOf("section-1" to "A", "subsection-1" to "B"),
            SectionDropdownHelpers.filterSectionsForDropdown(sections, showSubsections = true, showSubsubsections = false),
        )
        assertTrue(SectionDropdownHelpers.includeInDropdown("subsection-1", showSubsections = true, showSubsubsections = false))
        assertFalse(SectionDropdownHelpers.includeInDropdown("subsubsection-1", showSubsections = true, showSubsubsections = false))
    }

    @Test
    fun displaySections_filtersAndIndents() {
        val sections = listOf(
            "section-1" to "A",
            "subsection-1" to "B",
            "subsubsection-1" to "C",
        )
        assertEquals(
            listOf(
                "section-1" to "A",
                "subsection-1" to "  • B",
            ),
            SectionDropdownHelpers.displaySections(sections, showSubsections = true, showSubsubsections = false),
        )
    }
}
