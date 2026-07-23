package com.omariskandarani.livelatex.html

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Editor→preview continuous scroll must use syncline scrollToAbs, not section-mark snaps.
 */
class EditorPreviewScrollPolicyTest {

    @Test
    fun wrappedHtml_continuousSyncLineUsesScrollToAbs() {
        val html = LatexHtml.wrap("\\section{Hello}\nBody line.\n")
        val syncIdx = html.indexOf("d.type === 'sync-line'")
        assertTrue("sync-line handler missing", syncIdx >= 0)
        val continuousIdx = html.indexOf("d.source === 'scroll'", syncIdx)
        assertTrue("continuous source check missing", continuousIdx > syncIdx)
        val scrollToAbsIdx = html.indexOf("scrollToAbs(mergedAbs", continuousIdx)
        assertTrue("continuous path must call scrollToAbs", scrollToAbsIdx > continuousIdx)
        val markPathIdx = html.indexOf("mark-based scroll for discrete", continuousIdx)
        assertTrue("mark path should remain for discrete nav", markPathIdx > scrollToAbsIdx)
    }

    @Test
    fun wrappedHtml_scrollToAbsHasPreambleGuard() {
        val html = LatexHtml.wrap("\\section{Hello}\nBody.\n")
        assertTrue(
            "scrollToAbs must guard line < first syncline abs",
            html.contains("line < arr[0].abs") || html.contains("line < this.idx[0].abs"),
        )
        assertTrue(
            "preamble guard should scroll to top",
            html.contains("scrollTo({ top: 0 })") || html.contains("scrollTo({top:0})"),
        )
    }

    @Test
    fun wrappedHtml_continuousHasMarkFallbackWhenNoSynclines() {
        val html = LatexHtml.wrap("\\section{Hello}\nBody.\n")
        val continuousIdx = html.indexOf("d.source === 'scroll'")
        assertTrue(continuousIdx >= 0)
        val fallbackIdx = html.indexOf("fallback:'mark'", continuousIdx)
        val markFallbackIdx = html.indexOf("no synclines", continuousIdx)
        assertTrue(
            "continuous path must fall back to marks when syncline idx empty",
            fallbackIdx > continuousIdx || markFallbackIdx > continuousIdx ||
                html.indexOf("__scrollToMark", continuousIdx) > continuousIdx,
        )
    }

    @Test
    fun wrappedHtml_stillHasScrollToMarkForJumps() {
        val html = LatexHtml.wrap("\\section{Hello}\nBody.\n")
        assertTrue(html.contains("window.__scrollToMark"))
        assertTrue(html.contains("jumpToMarkId"))
    }

    @Test
    fun wrap_documentBody_hasSynclinesNotOnlyAtEnd() {
        val tex = """
            \documentclass{article}
            \begin{document}
            Intro paragraph one.

            Intro paragraph two.

            \section{Later}
            Body after section.
            \end{document}
        """.trimIndent()
        val html = LatexHtml.wrap(tex)
        val fullTextStart = html.indexOf("class=\"full-text\"")
        assertTrue(fullTextStart >= 0)
        val fullText = html.substring(fullTextStart)
        val synCount = Regex("""class="syncline"""").findAll(fullText).count()
        assertTrue("expected synclines in body, got $synCount", synCount > 0)
        val first = Regex("""class="syncline"[^>]*data-abs="(\d+)"""").find(fullText)
            ?: Regex("""data-abs="(\d+)"[^>]*class="syncline"""").find(fullText)
        assertTrue("first syncline missing", first != null)
        val firstPos = fullText.indexOf(first!!.value)
        val pct = 100.0 * firstPos / fullText.length
        assertTrue(
            "first syncline must not sit in last 50% of body HTML (was ${"%.1f".format(pct)}%)",
            pct < 50.0,
        )
        assertFalse("raw LLA markers must be materialized", fullText.contains("%%LLA{"))
    }
}
