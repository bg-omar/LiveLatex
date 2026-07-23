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

    @Test
    fun wrapInLinewidthResizebox_usesPercentOfLinewidth() {
        val body = "\\begin{tikzpicture}\n\\draw (0,0)--(1,1);\n\\end{tikzpicture}"
        val wrapped = TikzToolbarHelpers.wrapInLinewidthResizebox(body, 80)
        assertTrue(wrapped.startsWith("\\resizebox{0.8\\linewidth}{!}{%"))
        assertTrue(wrapped.contains(body))
        assertTrue(wrapped.trimEnd().endsWith("}"))
        // Already wrapped → leave as-is (do not nest another resizebox).
        assertEquals(wrapped, TikzToolbarHelpers.wrapInLinewidthResizebox(wrapped, 50))
        assertEquals("% No content.", TikzToolbarHelpers.wrapInLinewidthResizebox("% No content.", 80))
    }

    @Test
    fun linewidthFraction_formatsCommonPercents() {
        assertEquals("0.8", TikzToolbarHelpers.linewidthFraction(80))
        assertEquals("0.85", TikzToolbarHelpers.linewidthFraction(85))
        assertEquals("1", TikzToolbarHelpers.linewidthFraction(100))
    }
}
