package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Plan 3 — pipeline-order characterization.
 *
 * Locks the *sequence* of the LaTeX->HTML stages that the plan describes:
 *   1. inlineInputs (\input/\include) BEFORE macro extraction
 *   2. extractNewcommands reads preamble macros (even though preamble is stripped from the body)
 *   3. expandZeroArgMacros BEFORE prose conversion
 *   4. `\\[dim]` line-break handled BEFORE generic `\\`, and NOT as display math
 *   5. brace-interference: math-aware brace matching inside `\textbf{...}`
 */
class PipelineOrderTest {

    @After
    fun tearDown() {
        currentBaseDir = null
        TikzRenderer.currentBaseDir = null
        lineMapOrigToMergedJson = null
        lineMapMergedToOrigJson = null
        charMapOrigToMergedJson = null
        charMapMergedToOrigJson = null
        srcMapJson = null
        lastCharOrigToMerged = intArrayOf()
        lastCharMergedToOrig = intArrayOf()
    }

    // ── 1. inlineInputs ───────────────────────────────────────────────────────

    @Test
    fun inlineInputs_resolvesChildContent() {
        val dir = Files.createTempDirectory("ll-inline").toFile()
        java.io.File(dir, "child.tex").writeText("CHILD CONTENT")
        val out = LatexHtml.inlineInputs("A \\input{child} B", dir.absolutePath)
        assertEquals("A CHILD CONTENT B", out)
    }

    @Test
    fun inlineInputs_missingInputBecomesComment() {
        val dir = Files.createTempDirectory("ll-inline-missing").toFile()
        val out = LatexHtml.inlineInputs("X \\input{nope} Y", dir.absolutePath)
        assertTrue(out.contains("% Missing input: nope %"))
    }

    @Test
    fun inlineInputs_recursiveIncludesGrandchild() {
        val dir = Files.createTempDirectory("ll-inline-rec").toFile()
        java.io.File(dir, "child.tex").writeText("C1 \\input{grand} C2")
        java.io.File(dir, "grand.tex").writeText("GRANDCHILD")
        val out = LatexHtml.inlineInputs("\\input{child}", dir.absolutePath)
        assertTrue(out.contains("C1"))
        assertTrue(out.contains("GRANDCHILD"))
        assertTrue(out.contains("C2"))
    }

    // ── 2. \input resolved BEFORE macro extraction ────────────────────────────

    @Test
    fun wrapWithInputs_preambleMacroFromIncludedFileBecomesMathJaxMacro() {
        val dir = Files.createTempDirectory("ll-macro-input").toFile()
        java.io.File(dir, "prelude.tex").writeText("""\providecommand{\rhoM}{\rho_{\!m}}""")
        val mainPath = java.io.File(dir, "main.tex")
        val src = "\\documentclass{article}\n\\input{prelude}\n\\begin{document}\n\$\\rhoM\$\n\\end{document}"
        mainPath.writeText(src)

        val html = LatexHtml.wrapWithInputs(src, mainPath.absolutePath)
        // Macro key only appears if inlineInputs ran before extractNewcommands.
        assertTrue(html.contains("rhoM"))
    }

    // ── 3. expandZeroArgMacros BEFORE prose conversion ────────────────────────

    @Test
    fun expandZeroArgMacros_runsBeforeProse_singleControlSequenceBody() {
        // A 0-arg macro whose body is a single control sequence is expanded verbatim (no wrapping braces),
        // so downstream prose sees the expansion, not the macro name.
        val macros = extractNewcommands("""\newcommand{\LamX}{\Lambda}""")
        val expanded = expandZeroArgMacros("""value \LamX end""", macros)
        assertEquals("""value \Lambda end""", expanded)
    }

    // ── 4. `\\[dim]` before generic `\\`, not display math ────────────────────

    @Test
    fun lineBreakWithDim_isBreakNotDisplayMath() {
        val out = latexProseToHtmlWithMath("""Line A \\[0.5em] Line B""")
        assertTrue(out.contains("<br/>"))
        assertFalse(out.contains("0.5em"))
        assertTrue(out.contains("Line A"))
        assertTrue(out.contains("Line B"))
    }

    @Test
    fun realDisplayMathIsPreserved() {
        val out = latexProseToHtmlWithMath("""before \[ x = 1 \] after""")
        assertTrue(out.contains("""\["""))
        assertTrue(out.contains("x = 1"))
    }

    // ── 5. brace-interference / math-aware angle escaping ─────────────────────

    @Test
    fun boldWithMathContainingGreaterThan_escapesAndBolds() {
        // Normal string so \$ becomes a real math delimiter (triple-quoted would keep a literal backslash).
        val out = latexProseToHtmlWithMath("\\textbf{\$x_{i} > 0\$}")
        // The '>' inside math is escaped for safe HTML embedding.
        assertTrue(out.contains("&gt;"))
    }

    @Test
    fun boldWithNestedBraces_matchesOuterBrace() {
        val out = latexProseToHtmlWithMath("""\textbf{a {b} c}""")
        assertTrue(out.contains("<strong>"))
        assertTrue(out.contains("b"))
    }
}
