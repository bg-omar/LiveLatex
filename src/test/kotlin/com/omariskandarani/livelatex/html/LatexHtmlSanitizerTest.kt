package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexHtmlSanitizerTest {

    @Test
    fun sanitizeForMathJaxProse_keepsTikzAndKnotEnvironments() {
        val s = """
            \begin{tikzpicture}
            \draw (0,0) -- (1,0);
            \end{tikzpicture}
            \begin{knot}
            \strand (0,0) circle (1);
            \end{knot}
        """.trimIndent()
        val out = sanitizeForMathJaxProse(s)
        assertTrue(out.contains("\\begin{tikzpicture}"))
        assertTrue(out.contains("\\begin{knot}"))
    }

    @Test
    fun sanitizeForMathJaxProse_stripsUnknownEnvironmentMarkers() {
        val s = """\begin{foobar}hidden\end{foobar}visible"""
        val out = sanitizeForMathJaxProse(s)
        assertTrue(out.contains("visible"))
        assertFalse(out.contains("foobar"))
    }

    @Test
    fun sanitizeForMathJaxProse_convertsLetterRecipientAndStripsLetterEnv() {
        val s = """
            \begin{letter}{Editors \\ \emph{European Journal}}
            \opening{Dear Editors,}
            Please consider our manuscript.
            \closing{Sincerely,}
            \end{letter}
        """.trimIndent()
        val out = sanitizeForMathJaxProse(s)
        assertTrue(out.contains("ll-letter-to"))
        assertTrue(out.contains("European Journal") || out.contains("emph"))
        assertFalse(out.contains("\\begin{letter}"))
        assertFalse(out.contains("\\end{letter}"))
        assertFalse(out.trimStart().startsWith("{"))
    }

    @Test
    fun convertSiunitx_numScientificToLatexPow() {
        val s = """\num{1e-3}"""
        val out = convertSiunitx(s)
        assertTrue(out.contains("10^{-3}") || out.contains("10^{"))
    }

    @Test
    fun convertSiunitx_siInsertsMathRm() {
        val s = """\si{m/s}"""
        val out = convertSiunitx(s)
        assertTrue(out.contains("\\mathrm"))
    }

    @Test
    fun convertSiunitx_qtyToSI() {
        val s = """r_c=\qty{1.40897017e-15}{m}"""
        val out = convertSiunitx(s)
        assertFalse(out.contains("""\qty{"""))
        assertTrue(out.contains("""\si{m}""") || out.contains("""\mathrm{m}"""))
        assertTrue(out.contains("10^{-15}") || out.contains("1.40897017"))
    }

    @Test
    fun convertSiunitx_textAsciitilde() {
        val s = """\textasciitilde{}"""
        val out = convertSiunitx(s)
        assertTrue(out.contains("~"))
    }

    @Test
    fun sanitizeForMathJaxProse_preservesAlignWithMultipleTags() {
        val tex = """\begin{align} a &= b \tag{F1} \\ c &= d \tag{F2} \end{align}"""
        val out = sanitizeForMathJaxProse(tex)
        assertTrue(out.contains("""\begin{align}"""))
        assertTrue(out.contains("""\tag{F1}"""))
        assertTrue(out.contains("""\tag{F2}"""))
    }

    @Test
    fun parseMinipageWidthPercent_textwidthFraction() {
        assertEquals(32.0, parseMinipageWidthPercent("0.32\\textwidth"), 0.01)
    }

    @Test
    fun convertMinipagesToHtml_threeColumnsProducesFlexRow() {
        val tex = """
            \noindent
            \begin{minipage}[t]{0.32\textwidth}\centering
            \begin{tikzpicture}A\end{tikzpicture}
            \end{minipage}\hfill
            \begin{minipage}[t]{0.32\textwidth}\centering
            \begin{tikzpicture}B\end{tikzpicture}
            \end{minipage}\hfill
            \begin{minipage}[t]{0.32\textwidth}\centering
            \begin{tikzpicture}C\end{tikzpicture}
            \end{minipage}
        """.trimIndent()
        val out = convertMinipagesToHtml(tex)
        assertTrue(out.contains("ll-minipage-row"))
        assertTrue(out.contains("flex"))
        assertTrue(out.contains("tikzpicture"))
    }

    // ── convertTextblockStar ──────────────────────────────────────────────────

    @Test
    fun convertTextblockStar_wrapsInFooterBlock() {
        val out = convertTextblockStar("""\begin{textblock*}{5cm}(0,0)Corner note\end{textblock*}""")
        assertTrue(out.contains("ll-textblock"))
        assertTrue(out.contains("Corner note"))
    }

    // ── stripOuterLatexGroupBraces ────────────────────────────────────────────

    @Test
    fun stripOuterLatexGroupBraces_removesSingleWrapper() {
        assertEquals("Hello", stripOuterLatexGroupBraces("{Hello}"))
    }

    @Test
    fun stripOuterLatexGroupBraces_plainTextUnchanged() {
        assertEquals("plain", stripOuterLatexGroupBraces("plain"))
    }

    @Test
    fun stripOuterLatexGroupBraces_leavesNonEnclosingBracePair() {
        // '{a}{b}' first '{' does not enclose the whole string => returned unchanged.
        assertEquals("{a}{b}", stripOuterLatexGroupBraces("{a}{b}"))
    }

    // ── convertPicturePutBlocks ───────────────────────────────────────────────

    @Test
    fun convertPicturePutBlocks_extractsPutFooterInTitlepageContext() {
        val out = convertPicturePutBlocks(
            """\begin{picture}(100,100)\put(0,0){Footer here}\end{picture}""",
            titlepageContext = true,
        )
        assertTrue(out.contains("ll-titlepage-footer"))
        assertTrue(out.contains("Footer here"))
    }

    @Test
    fun convertPicturePutBlocks_bodyDrawingBecomesPlaceholder() {
        val dollar = "$"
        val out = convertBodyPictureEnvironments("""
            \setlength{\unitlength}{0.8cm}
            \begin{picture}(6,5)
                \thicklines
                \put(1,0.5){\line(2,1){3}}
                \put(0.7,0.3){${dollar}A${dollar}}
            \end{picture}
        """.trimIndent())
        assertTrue(out.contains("ll-picture-omitted"))
        assertFalse(out.contains("ll-titlepage-footer"))
        assertFalse(out.contains("""\line"""))
        assertFalse(out.contains("""\put"""))
        assertFalse(out.contains("""\begin{picture}"""))
    }

    @Test
    fun convertPicturePutBlocks_minipagePutStillFooterOutsideTitlepage() {
        val out = convertBodyPictureEnvironments("""
            \begin{picture}(0,0)
            \put(0,-45){
                \begin{minipage}[b]{0.7\textwidth}
                Footer affiliation text
                \end{minipage}
            }
            \end{picture}
        """.trimIndent())
        assertTrue(out.contains("ll-titlepage-footer"))
        assertTrue(out.contains("Footer affiliation"))
    }

    @Test
    fun stripLegacyPictureCommands_removesUnitlengthAndThicklines() {
        val out = stripLegacyPictureCommands("""\setlength{\unitlength}{0.8cm} \thicklines text""")
        assertTrue(out.contains("text"))
        assertFalse(out.contains("unitlength"))
        assertFalse(out.contains("thicklines"))
    }

    @Test
    fun sanitizeForMathJaxProse_bodyPictureNotTitlepageFooter() {
        val out = sanitizeForMathJaxProse("""
            \section{Picture}
            \setlength{\unitlength}{0.8cm}
            \begin{picture}(6,5)
                \put(1,0.5){\line(2,1){3}}
            \end{picture}
        """.trimIndent())
        assertFalse(out.contains("ll-titlepage-footer"))
        assertTrue(out.contains("ll-picture-omitted"))
        assertFalse(out.contains("""\setlength"""))
        assertFalse(out.contains("""\line"""))
    }

    // ── parseOneMinipageColumn ────────────────────────────────────────────────

    @Test
    fun parseOneMinipageColumn_parsesWidthAndBody() {
        val s = """\begin{minipage}[t]{0.32\textwidth}Body X\end{minipage}"""
        val parsed = parseOneMinipageColumn(s, 0)
        assertTrue(parsed != null)
        assertEquals(32.0, parsed!!.second, 0.01)
        assertTrue(parsed.first.contains("Body X"))
    }
}
