package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LatexHtmlWrapTest {

    @After
    fun tearDown() {
        lineMapOrigToMergedJson = null
        lineMapMergedToOrigJson = null
        charMapOrigToMergedJson = null
        charMapMergedToOrigJson = null
        srcMapJson = null
        lastCharOrigToMerged = intArrayOf()
        lastCharMergedToOrig = intArrayOf()
    }

    @Test
    fun wrap_htmlOnlyPreviewClearsStaleSourceMaps() {
        lineMapOrigToMergedJson = "[9]"
        lineMapMergedToOrigJson = "[8]"
        charMapOrigToMergedJson = "[7]"
        charMapMergedToOrigJson = "[6]"
        srcMapJson = """[{"s":1,"e":2,"h0":3,"h1":4}]"""
        lastCharOrigToMerged = intArrayOf(0, 10)
        lastCharMergedToOrig = intArrayOf(0, 11)

        val html = LatexHtml.wrap("<p style='opacity:.66'>Open a <code>.tex</code> file to preview.</p>")

        assertFalse(html.contains("class=\"llsrc\""))
        assertTrue(html.contains("window.__llSrcMap = [];"))
        assertTrue(html.contains("window.__llCharO2M = [];"))
        assertTrue(html.contains("window.__llCharM2O = [];"))
        assertEquals(0, lastCharOrigToMerged.size)
        assertEquals(0, lastCharMergedToOrig.size)
    }

    @Test
    fun wrap_afterWrapWithInputsInstallsFreshIdentityMaps() {
        val tempDir = Files.createTempDirectory("livelatex-wrap-test")
        Files.writeString(tempDir.resolve("child.tex"), "Included child text.")
        val mainPath = tempDir.resolve("main.tex").toString()

        LatexHtml.wrapWithInputs(
            "\\begin{document}\nBefore input.\n\\input{child}\nAfter input.\n\\end{document}",
            mainPath,
        )

        val directSource = "\\begin{document}\nDirect text.\n\\end{document}"
        LatexHtml.wrap(directSource)

        val expectedIdentity = IntArray(directSource.length + 1) { it }
        assertEquals(
            SourceMapBuilder.charMapToJson(expectedIdentity),
            charMapOrigToMergedJson,
        )
        assertEquals(
            SourceMapBuilder.charMapToJson(expectedIdentity),
            charMapMergedToOrigJson,
        )
    }

    @Test
    fun wrapWithInputs_preambleMacroAndChildBodyEndToEnd() {
        val tempDir = Files.createTempDirectory("livelatex-e2e")
        Files.writeString(tempDir.resolve("prelude.tex"), """\providecommand{\rhoM}{\rho_{\!m}}""")
        Files.writeString(tempDir.resolve("body.tex"), "\\section{Included}\nDensity is \$\\rhoM\$ here.")
        val mainPath = tempDir.resolve("main.tex")
        val src = "\\documentclass{article}\n\\input{prelude}\n\\begin{document}\n\\input{body}\n\\end{document}"
        Files.writeString(mainPath, src)

        val html = LatexHtml.wrapWithInputs(src, mainPath.toString())

        // Preamble macro from included file reaches MathJax config.
        assertTrue(html.contains("rhoM"))
        // Child body is inlined, converted, and section becomes a heading.
        assertTrue(html.contains("<h2"))
        assertTrue(html.contains("Included"))
        assertTrue(html.contains("Density is"))
    }

  @Test
  fun wrap_subsectionWithTightChainComparisonInMath() {
    val tex = """
      \begin{document}
      \subsection{Range}
      Valid when ${'$'}2<x<3${'$'} holds.
      \subsection{Next}
      Next section body.
      \end{document}
    """.trimIndent()
    val html = LatexHtml.wrap(tex)
    assertTrue(html.contains("&lt;"))
    assertFalse(html.contains("<x<"))
    assertTrue(html.contains("holds"))
    assertTrue(html.contains("Next section body"))
  }

  @Test
  fun wrap_subsectionWithGreaterThanInMathPreservesLineBreaks() {
        val tex = """
            \begin{document}
            \subsection{First}
            Value ${'$'}x > 0${'$'} \\
            More in first.
            \subsection{Second}
            Second body.
            \end{document}
        """.trimIndent()
        val html = LatexHtml.wrap(tex)
        assertTrue(html.contains("&gt;"))
        assertTrue(html.contains("<br"))
        assertTrue(html.contains("Second body"))
        assertTrue(html.contains("More in first"))
    }
}