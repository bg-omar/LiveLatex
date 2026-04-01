package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertEquals
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
}
