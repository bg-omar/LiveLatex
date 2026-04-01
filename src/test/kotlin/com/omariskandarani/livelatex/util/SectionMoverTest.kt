package com.omariskandarani.livelatex.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionMoverTest {
    @Test
    fun parseTwoSectionsWithLabels() {
        val text = """
            \section{Swirl 1}
            \label{sec:swirl1}
            Line A
            Line B
            \subsection{Inner}
            More text
            \section{Swirl 2}
            \label{sec:swirl2}
            Body 2
        """.trimIndent()
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
        val text = """
            \section{Alpha}
            Body line 1
            Body line 2
        """.trimIndent()
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
