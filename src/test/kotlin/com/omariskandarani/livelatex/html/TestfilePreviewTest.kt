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
    private val testfile = File(repoRoot, "testfile.tex")

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
        assertTrue(
            "picture env should be placeholder or omitted",
            body.contains("ll-picture-omitted") || !body.contains("""\begin{picture}"""),
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
    }

    @Test
    fun testfile_backtickBlockAndVerbDoNotBreakFollowingSections() {
        val html = wrapTestfile()
        assertTrue("Math section should survive backtick block", html.contains("""id="section-math""""))
        assertTrue("Boxes section should survive verb/fbox", html.contains("""id="section-boxes""""))
        assertTrue("Sample box content expected", html.contains("Sample box") || html.contains("<code>"))
    }
}
