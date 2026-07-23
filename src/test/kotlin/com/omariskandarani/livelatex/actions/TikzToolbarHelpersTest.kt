package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikzToolbarHelpersTest {

    @Test
    fun normalizeRotateDeg_wrapsAndZeros() {
        assertEquals(0.0, TikzToolbarHelpers.normalizeRotateDeg(0.0), 1e-12)
        assertEquals(0.0, TikzToolbarHelpers.normalizeRotateDeg(360.0), 1e-12)
        assertEquals(0.0, TikzToolbarHelpers.normalizeRotateDeg(-360.0), 1e-12)
        assertEquals(90.0, TikzToolbarHelpers.normalizeRotateDeg(90.0), 1e-12)
        assertEquals(270.0, TikzToolbarHelpers.normalizeRotateDeg(-90.0), 1e-12)
        assertEquals(45.0, TikzToolbarHelpers.normalizeRotateDeg(405.0), 1e-12)
    }

    @Test
    fun previewCacheKey_differsByContent() {
        val a = TikzToolbarHelpers.previewCacheKey("\\begin{tikzpicture}A\\end{tikzpicture}")
        val b = TikzToolbarHelpers.previewCacheKey("\\begin{tikzpicture}B\\end{tikzpicture}")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("tikz-preview-"))
        assertEquals(a, TikzToolbarHelpers.previewCacheKey("\\begin{tikzpicture}A\\end{tikzpicture}"))
    }

    @Test
    fun clampWidthPercent_bounds() {
        assertEquals(10, TikzToolbarHelpers.clampWidthPercent(0))
        assertEquals(100, TikzToolbarHelpers.clampWidthPercent(200))
        assertEquals(80, TikzToolbarHelpers.clampWidthPercent(80))
    }
}
