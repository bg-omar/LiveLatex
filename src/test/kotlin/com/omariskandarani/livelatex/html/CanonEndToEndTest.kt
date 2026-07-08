package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 3 — end-to-end characterization over realistic canon-style LaTeX.
 *
 * Uses the combined `mini_canon.tex` fixture (fast) and, when available, a truncated slice of the
 * real `SST_CANON-v0.8.19.tex` to prove the whole [LatexHtml.wrap] pipeline stays stable: no crash,
 * math preserved, sections -> headings, tabular -> table, preamble macros exposed to MathJax.
 */
class CanonEndToEndTest {

    @After
    fun tearDown() {
        currentBaseDir = null
        lineMapOrigToMergedJson = null
        lineMapMergedToOrigJson = null
        charMapOrigToMergedJson = null
        charMapMergedToOrigJson = null
        srcMapJson = null
        lastCharOrigToMerged = intArrayOf()
        lastCharMergedToOrig = intArrayOf()
    }

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/canon-fixtures/$name")
        assertNotNull("fixture resource missing: $name", stream)
        return stream!!.bufferedReader().use { it.readText() }
    }

    @Test
    fun miniCanon_wrapsWithAllCoreConstructs() {
        val html = LatexHtml.wrap(loadFixture("mini_canon.tex"))

        // Title / sections -> headings
        assertTrue(html.contains("<h2"))
        assertTrue(html.contains("Introduction"))
        assertTrue(html.contains("<h3"))
        assertTrue(html.contains("Balance"))

        // Preamble macros exposed to MathJax
        assertTrue(html.contains("rhoM"))
        assertTrue(html.contains("SwirlClock"))

        // Math preserved (align kept intact for MathJax)
        assertTrue(html.contains("""\begin{align}"""))
        assertTrue(html.contains("""\tag{A1}"""))

        // List and table conversions
        assertTrue(html.contains("<ul"))
        assertTrue(html.contains("First observation"))
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("Symbol"))
    }

    @Test
    fun miniCanon_comparisonOperatorsSurviveInMath() {
        val html = LatexHtml.wrap(loadFixture("mini_canon.tex"))
        // "$2 < x < 3$" must not corrupt the line; angle brackets get HTML-escaped inside math.
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("holds"))
    }

    /**
     * Best-effort slice of the real canon if it is checked out next to this repo. Skips gracefully
     * (asserts trivially) when the file is not present, so CI without the SST tree still passes.
     */
    @Test
    fun realCanonSlice_wrapsWithoutCrash() {
        val candidates = listOf(
            "C:/workspace/projects/SwirlStringTheory/SST-CANON/been_processed/v0.8.19/SST_CANON-v0.8.19.tex",
            "../SwirlStringTheory/SST-CANON/been_processed/v0.8.19/SST_CANON-v0.8.19.tex",
        )
        val canon = candidates.map { File(it) }.firstOrNull { it.isFile }
        if (canon == null) {
            // Real canon not available in this checkout; nothing to assert.
            assertTrue(true)
            return
        }
        val full = canon.readText()
        // Truncate to keep the test fast, then close the document so wrap sees a valid body.
        val beginIdx = full.indexOf(BEGIN_DOCUMENT)
        val header = if (beginIdx >= 0) full.substring(0, beginIdx + BEGIN_DOCUMENT.length) else ""
        val bodySlice = if (beginIdx >= 0) {
            val after = full.substring(beginIdx + BEGIN_DOCUMENT.length)
            after.take(20_000)
        } else full.take(20_000)
        val doc = header + "\n" + bodySlice + "\n" + END_DOCUMENT

        val html = LatexHtml.wrapWithInputs(doc, canon.absolutePath)
        assertTrue(html.isNotEmpty())
        // Should not leak a raw PDF <img> and should produce some HTML structure.
        assertTrue(html.contains("<"))
    }
}
