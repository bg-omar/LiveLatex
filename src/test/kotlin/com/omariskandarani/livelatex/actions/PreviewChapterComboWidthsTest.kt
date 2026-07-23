package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewChapterComboWidthsTest {

    @Test
    fun clampFromToolWidth_usesMinWhenRemainingIsSmall() {
        assertEquals(
            PreviewChapterComboWidths.MIN_WIDTH,
            PreviewChapterComboWidths.clampFromToolWidth(0),
        )
        // toolWidth 200 - chrome 320 → remaining 0 → min
        assertEquals(
            PreviewChapterComboWidths.MIN_WIDTH,
            PreviewChapterComboWidths.clampFromToolWidth(200),
        )
    }

    @Test
    fun clampFromToolWidth_uses75PercentOfRemaining() {
        // 800 - 320 = 480; * 0.75 = 360
        assertEquals(360, PreviewChapterComboWidths.clampFromToolWidth(800))
        // 1000 - 320 = 680; * 0.75 = 510
        assertEquals(510, PreviewChapterComboWidths.clampFromToolWidth(1000))
    }

    @Test
    fun clampFromToolWidth_respectsCustomChromeAndFraction() {
        // 1000 - 200 = 800; * 0.5 = 400
        assertEquals(400, PreviewChapterComboWidths.clampFromToolWidth(1000, chromeReserve = 200, fraction = 0.5))
    }
}
