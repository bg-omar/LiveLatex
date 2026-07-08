package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexHtmlUtilsTest {

    @After
    fun tearDown() {
        currentBaseDir = null
    }

    @Test
    fun htmlEscapeAll_escapesXmlSpecials() {
        // Order: & first, then < and >, then "; < becomes &lt; so the leading & is not double-encoded.
        assertEquals(
            "&amp;&lt;tag&gt;&amp;&quot;",
            htmlEscapeAll("&<tag>&\"")
        )
    }

    @Test
    fun findLastCmdArg_returnsLastOccurrence() {
        val s = """\title{First}\title{Second}"""
        assertEquals("Second", findLastCmdArg(s, "title"))
    }

    @Test
    fun extractTitleMeta_collectsTitleAuthorAffil() {
        val s = """
            \title{My Paper}
            \author{Alice}
            \affil{Lab A}
            \date{Today}
        """.trimIndent()
        val m = extractTitleMeta(s)
        assertEquals("My Paper", m.title)
        assertEquals("Alice", m.authors)
        assertEquals(listOf("Lab A"), m.affiliations)
        assertEquals("Today", m.dateRaw)
    }

    @Test
    fun replaceTextSymbols_commonTextSymbols() {
        val t = """\textemdash{} \textbullet{} \texttimes{}"""
        val out = replaceTextSymbols(t)
        assertTrue(out.contains("—"))
        assertTrue(out.contains("•"))
        assertTrue(out.contains("×"))
    }

    @Test
    fun includeGraphicsStyle_linewidthToPercent() {
        val s = includeGraphicsStyle("width=0.5\\linewidth")
        assertTrue(s.contains("max-width:50"))
        assertTrue(s.contains("%"))
    }

    @Test
    fun splitAuthors_splitsOnAnd() {
        val parts = splitAuthors("""Alice \and Bob""")
        assertEquals(listOf("Alice", "Bob"), parts)
    }

    @Test
    fun fixInlineBoundarySpaces_insertsSpaceAfterClosingInlineTag() {
        val out = fixInlineBoundarySpaces("</strong>word")
        assertEquals("</strong> word", out)
    }

    @Test
    fun injectLineAnchors_addsSynclineOnSafeNewlines() {
        val plain = "line1\nline2\nline3\n"
        val out = injectLineAnchors(plain, absOffset = 10, everyN = 1)
        assertTrue(out.contains("syncline"))
        assertTrue(out.contains("data-abs="))
    }

    @Test
    fun injectLineAnchors_mathWithAngleBracketsDoesNotCorruptTagState() {
        val html = "Before \$x &gt; 0\$ and \$2&lt;x&lt;3\$\nafter"
        val out = injectLineAnchors(html, absOffset = 1, everyN = 1)
        assertTrue(out.contains("syncline"))
        assertTrue(out.contains("&gt;"))
        assertTrue(out.contains("&lt;"))
        assertTrue(out.contains("after"))
    }

    // ── findAllCmdArgs ────────────────────────────────────────────────────────

    @Test
    fun findAllCmdArgs_returnsAllOccurrences() {
        val args = findAllCmdArgs("""\affil{A}\affil{B}""", "affil")
        assertEquals(listOf("A", "B"), args)
    }

    // ── renderDate ────────────────────────────────────────────────────────────

    @Test
    fun renderDate_nullAndEmptyAndLiteral() {
        assertEquals(null, renderDate(null))
        assertEquals("", renderDate(""))
        assertTrue(renderDate("2020")!!.contains("2020"))
    }

    // ── processThanksWithin ───────────────────────────────────────────────────

    @Test
    fun processThanksWithin_replacesThanksWithSup() {
        val notes = mutableListOf<String>()
        val out = processThanksWithin("""Author\thanks{A note}""", notes)
        assertEquals("Author<sup>1</sup>", out)
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("A note"))
    }

    // ── buildMakTitleHtml / convertMakeTitle ──────────────────────────────────

    @Test
    fun buildMakTitleHtml_rendersTitleAndAuthor() {
        val meta = TitleMeta(title = "Great Paper", authors = "Alice", affiliations = emptyList(), dateRaw = null)
        val out = buildMakTitleHtml(meta)
        assertTrue(out.contains("<h1"))
        assertTrue(out.contains("Great Paper"))
        assertTrue(out.contains("Alice"))
    }

    @Test
    fun convertMakeTitle_replacesMaketitleCommand() {
        val meta = TitleMeta(title = "T", authors = null, affiliations = emptyList(), dateRaw = null)
        val out = convertMakeTitle("""before \maketitle after""", meta)
        assertTrue(out.contains("maketitle"))
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after"))
        assertFalse(out.contains("""\maketitle"""))
    }

    // ── proseNoBr ─────────────────────────────────────────────────────────────

    @Test
    fun proseNoBr_stripsLineBreaks() {
        val out = proseNoBr("""line \\ break""")
        assertTrue(out.contains("line"))
        assertTrue(out.contains("break"))
        assertFalse(out.contains("<br"))
    }

    // ── resolveImagePath ──────────────────────────────────────────────────────

    @Test
    fun resolveImagePath_emptyAndHttpAndMissingNeverPdf() {
        assertEquals("", resolveImagePath(""))
        assertEquals("https://example.com/x.png", resolveImagePath("https://example.com/x.png"))
        assertEquals("", resolveImagePath("no_such_image"))
        val html = convertIncludeGraphics("""\includegraphics{no_such_image}""")
        assertTrue(html.contains("ll-figure-unavailable"))
        assertFalse(html.contains(".pdf"))
    }

    // ── applyInlineFormattingOutsideTags ──────────────────────────────────────

    @Test
    fun applyInlineFormattingOutsideTags_formatsTextOutsideTags() {
        val out = applyInlineFormattingOutsideTags("""<p>\textbf{bold}</p>""")
        assertTrue(out.contains("<strong>bold</strong>"))
        assertTrue(out.contains("<p>"))
    }
}
