package com.omariskandarani.livelatex.html

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end regression for [testfile.tex]: sections, maketitle, and body picture handling.
 */
class TestfileRenderTest {

    private fun wrapTestfile(): String {
        val f = File("testfile.tex")
        assumeTrue("testfile.tex in project root (run Gradle from LiveLatex)", f.isFile)
        currentBaseDir = f.parentFile?.absolutePath ?: ""
        TikzRenderer.currentBaseDir = currentBaseDir
        return LatexHtml.wrap(f.readText(Charsets.UTF_8))
    }

    @Test
    fun testfile_allMainSectionsPresent() {
        val html = wrapTestfile()
        val sections = listOf(
            "section-text",
            "section-characters",
            "section-math",
            "section-multicolumn",
            "section-boxes",
            "section-symbols",
            "section-new-section",
            "section-picture",
        )
        for (id in sections) {
            assertTrue("missing section id=$id", html.contains("""id="$id""""))
        }
    }

    @Test
    fun testfile_pictureSectionNotTitlepageFooter() {
        val html = wrapTestfile()
        val pictureIdx = html.indexOf("""id="section-picture"""")
        assumeTrue(pictureIdx >= 0)
        val tail = html.substring(pictureIdx, (pictureIdx + 2500).coerceAtMost(html.length))
        assertFalse("picture section must not become titlepage footer", tail.contains("ll-titlepage-footer"))
        assertTrue(tail.contains("ll-picture-omitted") || !tail.contains("""\line"""))
    }

    @Test
    fun testfile_noRawPictureDrawingLeaks() {
        val html = wrapTestfile()
        assertFalse(html.contains("""\setlength{\unitlength}"""))
        assertFalse(html.contains("""\put(1,0.5)"""))
        assertFalse(html.contains("""\line(2,1)"""))
    }

    @Test
    fun testfile_maketitleHasTitleWithoutStrayFigureMarkup() {
        val html = wrapTestfile()
        val maketitleStart = html.indexOf("class=\"maketitle\"")
        assumeTrue(maketitleStart >= 0)
        val blockEnd = html.indexOf("</div>", maketitleStart + 20)
        assumeTrue(blockEnd > maketitleStart)
        val block = html.substring(maketitleStart, blockEnd)
        assertTrue(block.contains("Time Dilation"))
        assertTrue(block.contains("<h1"))
        assertFalse("maketitle h1 must not contain figure img", block.contains("alt=\"figure\""))
    }

    @Test
    fun testfile_collectedSectionsIncludeMathAndPicture() {
        wrapTestfile()
        val ids = LatexHtml.lastCollectedSections.map { it.first }
        assertTrue(ids.contains("section-math"))
        assertTrue(ids.contains("section-picture"))
        assertTrue(ids.contains("section-text"))
        assertTrue(ids.contains("section-new-section"))
    }
}
