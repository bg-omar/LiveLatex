package com.omariskandarani.livelatex.util

import org.junit.Test
import org.junit.Assert.*

class SectionMoverTest {
    @Test
    fun parseTwoSectionsWithLabels() {
        val text = """\n            \\section{Swirl 1}\n            \\label{sec:swirl1}\n            Line A\n            Line B\n            \\subsection{Inner}\n            More text\n            \\section{Swirl 2}\n            \\label{sec:swirl2}\n            Body 2\n        """.trimIndent()
        val sections = SectionMover.extractSectionsForTesting(text)
        assertEquals(2, sections.size)
        val s1 = sections[0]
        val s2 = sections[1]
        assertEquals("Swirl 1", s1.title)
        assertEquals(1, s1.ordinal)
        assertNotNull(s1.labelLineIdx)
        assertTrue(s1.contentStartLineIdx > s1.headerLineIdx)
        assertEquals("Swirl 2", s2.title)
        assertEquals(2, s2.ordinal)
        assertTrue(s1.contentEndLineIdxExclusive == s2.headerLineIdx)
    }

    @Test
    fun parseSectionWithoutLabel() {
        val text = """\n            \\section{Alpha}\n            Body line 1\n            Body line 2\n        """.trimIndent()
        val sections = SectionMover.extractSectionsForTesting(text)
        assertEquals(1, sections.size)
        val s = sections[0]
        assertEquals("Alpha", s.title)
        assertNull(s.labelLineIdx)
        assertEquals(s.headerLineIdx + 1, s.contentStartLineIdx)
    }

    @Test
    fun parseEmptyReturnsNoSections() {
        val sections = SectionMover.extractSectionsForTesting("")
        assertTrue(sections.isEmpty())
    }
}
