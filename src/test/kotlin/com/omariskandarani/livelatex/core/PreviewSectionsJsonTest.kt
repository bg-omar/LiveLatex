package com.omariskandarani.livelatex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSectionsJsonTest {
    @Test
    fun parse_typicalJsStringifyArray() {
        val json = """[{"id":"section-1","abs":10,"label":"Intro"},{"id":"subsection-2","abs":20,"label":"Details"}]"""
        assertEquals(
            listOf("section-1" to "Intro", "subsection-2" to "Details"),
            PreviewSectionsJson.parse(json),
        )
    }

    @Test
    fun parse_emptyAndBlank() {
        assertTrue(PreviewSectionsJson.parse("").isEmpty())
        assertTrue(PreviewSectionsJson.parse("[]").isEmpty())
    }
}
