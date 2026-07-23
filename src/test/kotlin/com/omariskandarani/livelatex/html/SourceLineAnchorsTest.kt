package com.omariskandarani.livelatex.html

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-line syncline markers: plant on TeX body newlines, materialize to .syncline spans.
 */
class SourceLineAnchorsTest {

    @Test
    fun plant_usesAbsOffsetPlusBodyLine() {
        val body = "\nFirst\nSecond\n"
        val planted = plantSourceLineAnchors(body, absOffset = 10, everyN = 1)
        assertTrue(planted.contains("%%LLA{11}%%"))
        assertTrue(planted.contains("%%LLA{12}%%"))
        assertTrue(planted.contains("%%LLA{13}%%"))
        assertFalse("must not use HTML-newline abs alone", planted.contains("data-abs"))
    }

    @Test
    fun plant_skipsMathEnvironments() {
        val body = "\nbefore\n\\begin{equation}\na=1\n\\end{equation}\nafter\n"
        val planted = plantSourceLineAnchors(body, absOffset = 1, everyN = 1)
        assertTrue(planted.contains("%%LLA{2}%%")) // after "before"
        // newlines inside equation must not get markers
        val eqStart = planted.indexOf("\\begin{equation}")
        val eqEnd = planted.indexOf("\\end{equation}")
        assertTrue(eqStart >= 0 && eqEnd > eqStart)
        val inside = planted.substring(eqStart, eqEnd)
        assertFalse(inside.contains("%%LLA{"))
        assertTrue(planted.contains("%%LLA{")) // after still marked
    }

    @Test
    fun plant_skipsTabularEnvironments() {
        val body = "\nbefore\n\\begin{tabular}{cc}\nA & B\\\\\n\$\\alpha\$ & C\\\\\n\\end{tabular}\nafter\n"
        val planted = plantSourceLineAnchors(body, absOffset = 1, everyN = 1)
        val tabStart = planted.indexOf("\\begin{tabular}")
        val tabEnd = planted.indexOf("\\end{tabular}")
        assertTrue(tabStart >= 0 && tabEnd > tabStart)
        assertFalse(planted.substring(tabStart, tabEnd).contains("%%LLA{"))
        assertTrue(planted.contains("%%LLA{"))
    }

    @Test
    fun materialize_convertsMarkersToSynclineSpans() {
        val html = "Hello %%LLA{42}%% world %%LLA{43}%%"
        val out = materializeSourceLineAnchors(html)
        assertTrue(out.contains("""<span class="syncline" data-abs="42"></span>"""))
        assertTrue(out.contains("""<span class="syncline" data-abs="43"></span>"""))
        assertFalse(out.contains("%%LLA{"))
    }

    @Test
    fun plantThenMaterialize_roundTrip() {
        val body = "\nAlpha\nBeta\n"
        val planted = plantSourceLineAnchors(body, absOffset = 100, everyN = 1)
        val out = materializeSourceLineAnchors(planted)
        assertTrue(out.contains("""data-abs="101""""))
        assertTrue(out.contains("""data-abs="102""""))
        assertTrue(out.contains("syncline"))
        assertTrue(out.contains("Alpha"))
        assertTrue(out.contains("Beta"))
    }

    @Test
    fun plant_everyNSkipsIntermediateLines() {
        val body = "\nA\nB\nC\nD\n"
        val planted = plantSourceLineAnchors(body, absOffset = 0, everyN = 2)
        assertTrue(planted.contains("%%LLA{2}%%"))
        assertTrue(planted.contains("%%LLA{4}%%"))
        assertFalse(planted.contains("%%LLA{1}%%"))
        assertFalse(planted.contains("%%LLA{3}%%"))
    }
}
