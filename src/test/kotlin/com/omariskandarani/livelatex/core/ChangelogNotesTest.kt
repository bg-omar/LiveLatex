package com.omariskandarani.livelatex.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogNotesTest {

    private val sample = """
        ## 0.0.10

        Thank you for supporting LiveLatex — a few upgrades 🎉

        ### New 💪
        - **LaTeX Preview** toggle
        - TikZ `\resizebox` export

        ### Fixed 🪲
        - Insert Reference EDT freeze & more

        ## 0.0.9

        ### New 💪
        - Render TikZ checkbox
    """.trimIndent()

    @Test
    fun shouldAnnounce_whenNeverSeen() {
        assertTrue(ChangelogNotes.shouldAnnounce(null, "0.0.10"))
        assertTrue(ChangelogNotes.shouldAnnounce("", "0.0.10"))
        assertTrue(ChangelogNotes.shouldAnnounce("   ", "0.0.10"))
    }

    @Test
    fun shouldAnnounce_whenVersionChanged() {
        assertTrue(ChangelogNotes.shouldAnnounce("0.0.9", "0.0.10"))
    }

    @Test
    fun shouldAnnounce_falseWhenSameOrBlankCurrent() {
        assertFalse(ChangelogNotes.shouldAnnounce("0.0.10", "0.0.10"))
        assertFalse(ChangelogNotes.shouldAnnounce(null, ""))
        assertFalse(ChangelogNotes.shouldAnnounce("0.0.9", "  "))
    }

    @Test
    fun sectionForVersion_extractsBodyUntilNextHeader() {
        val section = ChangelogNotes.sectionForVersion(sample, "0.0.10")!!
        assertTrue(section.contains("LaTeX Preview"))
        assertTrue(section.contains("Insert Reference"))
        assertFalse(section.contains("Render TikZ checkbox"))
        assertFalse(section.contains("## 0.0.10"))
    }

    @Test
    fun sectionForVersion_missingReturnsNull() {
        assertNull(ChangelogNotes.sectionForVersion(sample, "9.9.9"))
        assertNull(ChangelogNotes.sectionForVersion(sample, ""))
    }

    @Test
    fun toNotificationHtml_usesBrAndBoldAndEscapes() {
        val section = ChangelogNotes.sectionForVersion(sample, "0.0.10")!!
        val html = ChangelogNotes.toNotificationHtml(section)
        assertTrue(html.startsWith("<html>"))
        assertTrue(html.endsWith("</html>"))
        assertTrue(html.contains("<br>"))
        assertTrue(html.contains("Thank you for supporting LiveLatex"))
        assertTrue(html.contains("<b>New 💪</b>"))
        assertTrue(html.contains("<b>Fixed 🪲</b>"))
        assertTrue(html.contains("<b>LaTeX Preview</b>"))
        assertTrue(html.contains("resizebox"))
        assertTrue(html.contains("&amp;"))
        assertFalse(html.contains("**"))
        assertFalse(html.contains("###"))
    }

    @Test
    fun inlineMd_escapesAngles() {
        assertTrue(ChangelogNotes.inlineMd("a < b > c").contains("&lt;"))
        assertTrue(ChangelogNotes.inlineMd("a < b > c").contains("&gt;"))
    }
}