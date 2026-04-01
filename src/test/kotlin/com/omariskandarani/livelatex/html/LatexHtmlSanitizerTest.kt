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
}
