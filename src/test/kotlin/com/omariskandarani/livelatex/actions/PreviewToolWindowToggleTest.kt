package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewToolWindowToggleTest {
    @Test
    fun nextVisible_toggles() {
        assertFalse(PreviewToolWindowToggle.nextVisible(true))
        assertTrue(PreviewToolWindowToggle.nextVisible(false))
    }
}
