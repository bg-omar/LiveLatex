package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-function tests for fragile LaTeX->HTML pipeline pieces affected by raw `<`/`>` in math.
 * Uses the shared fixture table from the "Fix math greater-than" plan.
 */
class LatexHtmlPipelineTest {

    private val dollar = "$"

    // ── splitHtmlTagsRespectingMath ───────────────────────────────────────────

    @Test
    fun splitHtmlTagsRespectingMath_cmpGt_doesNotTreatGreaterThanAsTagEnd() {
        val html = "<h3>T</h3>Before ${dollar}x > 0${dollar} after"
        val texts = splitHtmlTagsRespectingMath(html).filterIsInstance<HtmlMathSplitPiece.Text>().map { it.value }
        assertTrue(texts.any { it.contains("${dollar}x > 0${dollar}") })
    }

    @Test
    fun splitHtmlTagsRespectingMath_cmpLt_doesNotTreatLessThanAsTagStart() {
        val html = "<h3>T</h3>${dollar}a < b${dollar} tail"
        val texts = splitHtmlTagsRespectingMath(html).filterIsInstance<HtmlMathSplitPiece.Text>().map { it.value }
        assertTrue(texts.any { it.contains("${dollar}a < b${dollar}") })
    }

    @Test
    fun splitHtmlTagsRespectingMath_cmpChainTight() {
        val html = "Range ${dollar}2<x<3${dollar} ok"
        val texts = splitHtmlTagsRespectingMath(html).filterIsInstance<HtmlMathSplitPiece.Text>().map { it.value }
        assertTrue(texts.any { it.contains("${dollar}2<x<3${dollar}") })
    }

    @Test
    fun splitHtmlTagsRespectingMath_realHtmlTag_splitsCorrectly() {
        val html = "<strong>bold</strong> ${dollar}x>0${dollar}"
        val tags = splitHtmlTagsRespectingMath(html).filterIsInstance<HtmlMathSplitPiece.Tag>().map { it.value }
        assertEquals(listOf("<strong>", "</strong>"), tags)
    }

    @Test
    fun convertParagraphsOutsideTags_proseComparisonWithoutMathStillProcessed() {
        // Without math delimiters, raw '<' may confuse the HTML tag splitter, but the old
        // contains('>') guard must not skip the entire subsection chunk.
        val html = """<h3>T</h3>a less than b"""
        val out = convertParagraphsOutsideTags(html)
        assertTrue(out.contains("less than"))
    }

    // ── escapeAngleBracketsInMathFragment ─────────────────────────────────────

    @Test
    fun escapeAngleBrackets_cmpGt() {
        assertEquals("${dollar}x &gt; 0${dollar}", escapeAngleBracketsInMathFragment("${dollar}x > 0${dollar}"))
    }

    @Test
    fun escapeAngleBrackets_cmpLt() {
        assertEquals("${dollar}x &lt; 0${dollar}", escapeAngleBracketsInMathFragment("${dollar}x < 0${dollar}"))
    }

    @Test
    fun escapeAngleBrackets_cmpChainTight() {
        assertEquals("${dollar}2&lt;x&lt;3${dollar}", escapeAngleBracketsInMathFragment("${dollar}2<x<3${dollar}"))
    }

    @Test
    fun escapeAngleBrackets_cmpChainSpaced() {
        assertEquals("${dollar}2 &lt; x &lt; 3${dollar}", escapeAngleBracketsInMathFragment("${dollar}2 < x < 3${dollar}"))
    }

    @Test
    fun escapeAngleBrackets_displayMath() {
        assertEquals("""\[a &gt; b\]""", escapeAngleBracketsInMathFragment("""\[a > b\]"""))
    }

    @Test
    fun escapeAngleBrackets_alignEnv() {
        val env = """\begin{align} a &> b \\ c &< d \end{align}"""
        val out = escapeAngleBracketsInMathFragment(env)
        assertTrue(out.contains("&gt;"))
        assertTrue(out.contains("&lt;"))
        assertFalse(out.contains(" &> "))
    }

    // ── latexProseToHtmlWithMath ──────────────────────────────────────────────

    @Test
    fun latexProse_cmpLatexCmds_unchanged() {
        val out = latexProseToHtmlWithMath("""${dollar}a \leq b \geq c${dollar}""")
        assertTrue(out.contains("""\leq"""))
        assertTrue(out.contains("""\geq"""))
    }

    @Test
    fun latexProse_alignEnv_preservedWithEscapedOperators() {
        val tex = """\begin{align} a &> b \\ c &< d \end{align}"""
        val out = latexProseToHtmlWithMath(tex)
        assertTrue(out.contains("""\begin{align}"""))
        assertTrue(out.contains("&gt;"))
        assertTrue(out.contains("&lt;"))
    }

    // ── convertSections ───────────────────────────────────────────────────────

    @Test
    fun convertSections_titleWithMathInHeading() {
        val out = convertSections("""\section{Bound ${dollar}x > 0${dollar}}""", absOffset = 1)
        assertTrue(out.contains("<h2"))
        assertTrue(out.contains("&gt;"))
    }

    // ── proseNoBr ─────────────────────────────────────────────────────────────

    @Test
    fun proseNoBr_listItemWithMathGreaterThan() {
        val out = proseNoBr("""Item ${dollar}x > 0${dollar} here""")
        assertTrue(out.contains("&gt;"))
        assertFalse(out.contains("<br"))
    }

    // ── sanitizeForMathJaxProse ───────────────────────────────────────────────

    @Test
    fun sanitize_alignWithComparisonOperatorsInMath() {
        val tex = """\begin{align} a &> b \tag{F1} \\ c &< d \tag{F2} \end{align}"""
        val out = sanitizeForMathJaxProse(tex)
        assertTrue(out.contains("""\begin{align}"""))
        assertTrue(out.contains("""\tag{F1}"""))
        assertTrue(out.contains("&>") || out.contains(">"))
    }

    // ── convertTabulars / parseColSpecBalanced ────────────────────────────────

    @Test
    fun parseColSpecBalanced_prefixGreaterThanNotConfusedWithMath() {
        val cols = parseColSpecBalanced("""@{}>{\raggedleft\arraybackslash}p{0.21\textwidth}@{}l""")
        assertEquals(2, cols.size)
        assertEquals("left", cols[0].align)
        assertEquals("left", cols[1].align)
    }

    // ── findBalancedBraceAllowMath ────────────────────────────────────────────

    @Test
    fun findBalancedBraceAllowMath_bracesInsideInlineMath() {
        val s = """{outer ${dollar}x_{i} > 0${dollar} end}"""
        assertEquals(s.lastIndex, findBalancedBraceAllowMath(s, 0))
    }

    // ── applyInlineFormattingOutsideTags (table wrapper) ──────────────────────

    @Test
    fun applyInlineFormattingOutsideTags_tablePreservedProseOutsideFormatted() {
        // Table segments are kept verbatim; prose after the table is still formatted.
        val d = "$"
        val html = """<table><tr><td>cell</td></tr></table> ${d}x > 0${d} after"""
        val out = applyInlineFormattingOutsideTags(html)
        assertTrue(out.contains("<table"))
        assertTrue(out.contains("cell"))
        assertTrue(out.contains("&gt;"))
        assertTrue(out.contains("after"))
    }
}
