package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewChapterComboWidthsTest {
    @Test
    fun clampPreferredWidth_usesFallbackFloor() {
        assertEquals(PreviewChapterComboWidths.MIN_WIDTH, PreviewChapterComboWidths.clampPreferredWidth(0))
        assertEquals(PreviewChapterComboWidths.MIN_WIDTH, PreviewChapterComboWidths.clampPreferredWidth(80))
    }

    @Test
    fun clampPreferredWidth_keepsLargerAvailable() {
        assertEquals(420, PreviewChapterComboWidths.clampPreferredWidth(420))
        assertEquals(640, PreviewChapterComboWidths.clampPreferredWidth(640))
    }
}
