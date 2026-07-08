package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMapBuilderTest {

    @Test
    fun extractLatexVisibleText_plainProseAndBold() {
        val body = "Hello \\textbf{world}."
        val segs = SourceMapBuilder.extractLatexVisibleText(body, baseOffset = 0)
        val plain = segs.joinToString("|") { it.plain }
        assertTrue(plain.contains("Hello"))
        assertTrue(plain.contains("world"))
    }

    @Test
    fun extractLatexVisibleText_skipsInlineMath() {
        val body = "Before \$x^2\$ after"
        val segs = SourceMapBuilder.extractLatexVisibleText(body, baseOffset = 0)
        val joined = segs.joinToString("") { it.plain }
        assertTrue(joined.contains("Before"))
        assertTrue(joined.contains("after"))
        assertTrue(!joined.contains("x"))
    }

    @Test
    fun extractLatexVisibleText_sectionTitle() {
        val body = "\\section{Introduction}\nBody text."
        val segs = SourceMapBuilder.extractLatexVisibleText(body, baseOffset = 100)
        assertTrue(segs.any { it.plain.contains("Introduction") })
        assertTrue(segs.any { it.plain.contains("Body") })
    }

    @Test
    fun extractHtmlVisibleText_skipsTags() {
        val html = """<p>Hello <strong>world</strong>.</p>"""
        val segs = SourceMapBuilder.extractHtmlVisibleText(html)
        val plain = segs.joinToString("") { it.plain }
        assertTrue(plain.contains("Hello"))
        assertTrue(plain.contains("world"))
    }

    @Test
    fun extractHtmlVisibleText_decodesEscapedMathEntities() {
        val d = "$"
        val html = "<p>Range ${d}2&lt;x&lt;3${d} and ${d}x &gt; 0${d}</p>"
        val segs = SourceMapBuilder.extractHtmlVisibleText(html)
        val plain = segs.joinToString("") { it.plain }
        assertTrue(plain.contains("2" + "<x<" + "3") || plain.contains("2 < x < 3"))
        assertTrue(plain.contains("x > 0") || plain.contains("x>0"))
    }

    @Test
    fun alignSegments_matchesSequentialTokens() {
        val src = listOf(
            SourceMapBuilder.TextSegment(0, 5, "Hello"),
            SourceMapBuilder.TextSegment(6, 11, "world"),
        )
        val html = listOf(
            SourceMapBuilder.TextSegment(3, 8, "Hello"),
            SourceMapBuilder.TextSegment(17, 22, "world"),
        )
        val mapped = SourceMapBuilder.alignSegments(src, html)
        assertEquals(2, mapped.size)
        assertEquals(0, mapped[0].srcStart)
        assertEquals(3, mapped[0].htmlStart)
    }

    @Test
    fun injectSpans_wrapsMappedRegions() {
        val html = "Hello world"
        val mapped = listOf(
            SourceMapBuilder.MappedSegment(0, 5, 0, 5),
            SourceMapBuilder.MappedSegment(6, 11, 6, 11),
        )
        val out = SourceMapBuilder.injectSpans(html, mapped)
        assertTrue("llsrc span for Hello", out.contains("class=\"llsrc\" data-s=\"0\" data-e=\"5\""))
        assertTrue("llsrc span for world", out.contains("class=\"llsrc\" data-s=\"6\" data-e=\"11\""))
        assertTrue(out.contains(">Hello</span>"))
        assertTrue(out.contains(">world</span>"))
    }

    @Test
    fun applySourceMap_injectsLlsrcForProse() {
        val latex = "\\begin{document}\nHello world.\n\\end{document}"
        val html = "<p>Hello world.</p>"
        val result = SourceMapBuilder.applySourceMap(html, latex)
        assertTrue(result.html.contains("llsrc"))
        assertTrue(result.html.contains("data-s="))
        assertTrue(result.json.contains("\"s\":"))
    }

    @Test
    fun buildOrigToMergedCharMap_identityWithoutInlining() {
        val orig = "alpha\nbeta"
        val marked = "%%LLM1%%alpha\n%%LLM2%%beta"
        val map = SourceMapBuilder.buildOrigToMergedCharMap(orig, marked)
        assertEquals(0, map[0])
        assertEquals(6, map[6]) // start of "beta" after "alpha\n"
    }

    @Test
    fun buildMergedToOrigCharMap_invertsOrigToMerged() {
        val o2m = intArrayOf(0, 1, 2, 10, 11, 12)
        val m2o = SourceMapBuilder.buildMergedToOrigCharMap(o2m, mergedLength = 12)
        assertEquals(0, m2o[0])
        assertEquals(3, m2o[10])
    }

    @Test
    fun charMapToJson_formatsIntArrayAsJsonArray() {
        assertEquals("[0, 1, 2, 10]", SourceMapBuilder.charMapToJson(intArrayOf(0, 1, 2, 10)))
        assertEquals("[]", SourceMapBuilder.charMapToJson(intArrayOf()))
    }
}