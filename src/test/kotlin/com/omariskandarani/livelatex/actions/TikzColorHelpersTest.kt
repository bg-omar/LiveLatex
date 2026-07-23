package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikzColorHelpersTest {

    @Test
    fun allPresets_includesNamedAndMixes() {
        val p = TikzColorHelpers.allPresets()
        assertTrue(p.isNotEmpty())
        assertTrue(p.contains("teal"))
        assertTrue(p.contains("black!60!black"))
        assertTrue(p.contains("red!70!green"))
        assertEquals(p.size, p.distinct().size)
    }
}
