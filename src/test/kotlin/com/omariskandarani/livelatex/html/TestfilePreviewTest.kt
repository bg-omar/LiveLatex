package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Characterization / regression for [testfile.tex] preview rendering.
 */
class TestfilePreviewTest {

    private val repoRoot = File(System.getProperty("user.dir"))
    private val testfile = File(repoRoot, "LiveLatex_full_probes/testfile.tex")

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

    private fun wrapTestfile(): String {
        assertTrue("testfile.tex missing at ${testfile.absolutePath}", testfile.isFile)
        currentBaseDir = testfile.parentFile.absolutePath
        return LatexHtml.wrap(testfile.readText())
    }

    /** Extract the body content between full-text div and spacer for easier assertions. */
    private fun bodyChunk(html: String): String {
        val start = html.indexOf("""class="full-text"""")
        val end = html.indexOf("""id="ll-spacer"""")
        return if (start >= 0 && end > start) html.substring(start, end) else html
    }

    @Test
    fun testfile_maketitle_hasWellFormedH1() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        assertTrue("maketitle block expected", body.contains("""class="maketitle""""))
        assertTrue("h1 open expected", body.contains("<h1"))
        assertTrue("h1 close expected", body.contains("</h1>"))
        assertTrue("title should mention Æther", body.contains("Æther"))
        assertTrue("title should mention Superfluid", body.contains("Superfluid"))
        assertTrue("author expected", body.contains("Omar"))
        // Title uses \\ line break in \title{...}
        assertTrue("title line break expected", body.contains("<br") || body.contains("Based on Vortex"))
        // Must not merge title h1 with figure img alt attribute
        assertFalse("malformed h1/img merge", Regex("""<h1[^>]*>\s*"\s*alt="figure"""").containsMatchIn(body))
        // Figure must appear after maketitle, not inside it
        val maketitleEnd = body.indexOf("""class="maketitle"""")
        val figureIdx = body.indexOf("""alt="figure"""")
        if (maketitleEnd >= 0 && figureIdx >= 0) {
            assertTrue("figure should follow maketitle", figureIdx > maketitleEnd)
        }
    }

    @Test
    fun testfile_pictureCommandsDoNotLeak() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        assertFalse("setlength should not leak", body.contains("""\setlength{\unitlength}"""))
        assertFalse("raw \\line( should not leak", body.contains("""\line("""))
        assertFalse("raw \\put( should not leak", body.contains("""\put("""))
        assertFalse("raw \\begin{picture} must not leak", body.contains("""\begin{picture}"""))
        assertFalse("omit placeholder should not appear", body.contains("ll-picture-omitted"))
        assertTrue(
            "body picture must be lazy LiveRender placeholder (default LiveRender off)",
            body.contains("tikz-lazy") && body.contains("LiveRender"),
        )
        val pictureIdx = body.indexOf("""id="section-picture"""")
        assertTrue("Picture section expected", pictureIdx >= 0)
        val tail = body.substring(pictureIdx)
        assertFalse(
            "picture section must not become titlepage footer",
            tail.contains("ll-titlepage-footer"),
        )
    }

    @Test
    fun testfile_allSectionsPresent() {
        val html = wrapTestfile()
        val sectionIds = Regex("""id="(section-[^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        val expectedIds = listOf(
            "section-text",
            "section-characters",
            "section-math",
            "section-multicolumn",
            "section-boxes",
            "section-symbols",
            "section-new-section",
            "section-picture",
        )
        for (id in expectedIds) {
            assertTrue("missing section id: $id (found: $sectionIds)", html.contains("""id="$id""""))
        }
        // Real tags — escaped blob from Characters `\$` used to contain the same substring
        assertTrue("real Math h2 expected", html.contains("""<h2 id="section-math""""))
        assertFalse("Math h2 must not be entity-escaped", html.contains("""&lt;h2 id="section-math""""))
    }

    @Test
    fun testfile_charactersDollarDoesNotSwallowFollowingHtml() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        val charsIdx = body.indexOf("Sample characters")
        assertTrue("Characters probe text expected", charsIdx >= 0)
        val mathH2 = body.indexOf("""<h2 id="section-math"""", charsIdx)
        assertTrue("real Math section heading after Characters", mathH2 > charsIdx)
        val escapedLlmark = body.indexOf("""&lt;span class="llmark""", charsIdx)
        assertTrue(
            "Characters `\\$` must not HTML-escape following llmark/sections",
            escapedLlmark < 0 || escapedLlmark > mathH2,
        )
        // Literal dollar is wrapped so it cannot open math in pass 2 / MathJax
        val between = body.substring(charsIdx, mathH2)
        assertTrue(
            "expected tex2jax_ignore dollar or preserved escape in Characters",
            between.contains("""tex2jax_ignore""") || between.contains("""\$""") || between.contains("&#36;"),
        )
    }

    @Test
    fun testfile_mathjaxDoesNotIgnoreLlsrc() {
        val html = wrapTestfile()
        assertTrue("ignoreHtmlClass expected", html.contains("ignoreHtmlClass"))
        assertTrue(
            "tex2jax_ignore must remain ignored",
            html.contains("ignoreHtmlClass: 'tex2jax_ignore'") ||
                html.contains("""ignoreHtmlClass: "tex2jax_ignore""""),
        )
        assertFalse(
            "llsrc must not be in ignoreHtmlClass (breaks abstract equations)",
            Regex("""ignoreHtmlClass:\s*['"][^'"]*llsrc""").containsMatchIn(html),
        )
    }

    @Test
    fun testfile_abstractKeepsMathMacrosForMathJax() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        val absStart = body.indexOf("abstract-block")
        assertTrue("abstract block expected", absStart >= 0)
        val absEnd = body.indexOf("section-text", absStart).let { if (it < 0) body.length else it }
        val abstract = body.substring(absStart, absEnd)
        assertTrue("equation env in abstract", abstract.contains("""\begin{equation}"""))
        assertTrue("vswirl left for MathJax", abstract.contains("""\vswirl"""))
        assertFalse(
            "should not pre-expand vswirl inside math",
            abstract.contains("""{v_{\mkern-2mu\scriptscriptstyle\boldsymbol{\circlearrowleft}}}"""),
        )
    }

    @Test
    fun testfile_figureImgSurvivesAndResolves() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        assertTrue("real img tag expected", body.contains("""<img"""))
        assertFalse("img must not be entity-escaped", body.contains("""&lt;img"""))
        assertTrue("figure alt expected", body.contains("""alt="figure""""))
        val img = Regex("""<img[^>]*alt="figure"[^>]*>""").find(body)?.value
        assertTrue("figure img element expected", img != null)
        assertTrue(
            "figure src should be data URL or non-empty",
            img!!.contains("""src="data:image""") || Regex("""src="[^"]+"""").containsMatchIn(img),
        )
    }

    @Test
    fun testfile_multicolAndSymbolsAndNewSectionPresent() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        assertTrue("multicol expected", body.contains("""class="multicol""") || body.contains("column-count"))
        assertTrue("symbols section", body.contains("""id="section-symbols""""))
        assertTrue("new section heading", body.contains("""id="section-new-section""""))
        assertTrue("subsection heading", body.contains("""id="subsection-new-subsection""""))
        // Symbols should include at least one converted glyph (not only raw \textbullet)
        assertTrue(
            "symbol glyphs expected",
            body.contains("•") || body.contains("…") || body.contains("—") || body.contains("§"),
        )
    }

    @Test
    fun testfile_tableMathCellsNotEscaped() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        // Header Value 3 cell must not swallow the next row's $\alpha$
        val value3Td = Regex(
            """<td[^>]*>\s*<strong>Value 3</strong>\s*</td>""",
            RegexOption.IGNORE_CASE,
        ).find(body)
        assertTrue("Value 3 must be alone in its header <td>", value3Td != null)
        assertFalse(
            "Value 3 cell must not contain tex2jax_ignore (\\$ glue bug)",
            value3Td!!.value.contains("tex2jax_ignore"),
        )
        // Alpha row is its own <td> with intact math
        assertTrue(
            "alpha in its own table cell",
            Regex("""<td[^>]*>\s*\$\\alpha\$\s*</td>""").containsMatchIn(body) ||
                Regex("""<td[^>]*>\s*\$\s*\\alpha\s*\$\s*</td>""").containsMatchIn(body),
        )
        assertFalse(
            "table cells must not be HTML-escaped after alpha",
            Regex("""\\alpha\$?\s*&lt;/td&gt;""").containsMatchIn(body),
        )
        assertTrue("real td close expected", body.contains("""</td>"""))
    }

    @Test
    fun testfile_boxesRendersFboxNotRawCommand() {
        val html = wrapTestfile()
        val body = bodyChunk(html)
        val boxesIdx = body.indexOf("""id="section-boxes"""")
        assertTrue("Boxes section expected", boxesIdx >= 0)
        val symbolsIdx = body.indexOf("""id="section-symbols"""", boxesIdx).let { if (it < 0) body.length else it }
        val boxes = body.substring(boxesIdx, symbolsIdx)
        assertTrue("Sample box text expected", boxes.contains("Sample box"))
        assertFalse("raw \\fbox{ must not leak", boxes.contains("""\fbox{"""))
        assertTrue(
            "fbox should render as bordered span",
            boxes.contains("border:1px solid") || boxes.contains("<em>Sample box</em>"),
        )
    }

    @Test
    fun testfile_backtickBlockAndVerbDoNotBreakFollowingSections() {
        val html = wrapTestfile()
        assertTrue("Math section should survive backtick block", html.contains("""<h2 id="section-math""""))
        assertTrue("Boxes section should survive verb/fbox", html.contains("""<h2 id="section-boxes""""))
        assertTrue("Sample box content expected", html.contains("Sample box") || html.contains("<code>"))
    }

    /** Write fresh probe HTML next to testfile.tex (same path as TikZ-debug dump). */
    @Test
    fun testfile_regenerateProbeHtml() {
        val html = wrapTestfile()
        val out = File(testfile.parentFile, "testfile.html")
        out.writeText(html)
        assertTrue(out.isFile && out.length() > 1000)
        val body = bodyChunk(html)
        assertTrue(body.contains("""<h2 id="section-math""""))
        assertFalse(body.contains("""&lt;h2 id="section-math""""))
        assertTrue(body.contains("""tex2jax_ignore"""))
        assertTrue(body.contains("""\vswirl"""))
        assertTrue(body.contains("""<img"""))
        assertFalse(body.contains("""&lt;img"""))
    }
}
