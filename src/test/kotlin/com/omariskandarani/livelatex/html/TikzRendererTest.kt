package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class TikzRendererTest {

    @Before
    fun setup() {
        LatexTikzJobStore.clear()
        TikzRenderer.currentBaseDir = null
        TikzRenderer.pluginCacheRoot = null
    }

    @After
    fun tearDown() {
        LatexTikzJobStore.clear()
        TikzRenderer.currentBaseDir = null
        TikzRenderer.pluginCacheRoot = null
    }

    @Test
    fun collectTikzPreamble_fixesUsePackageTypo() {
        val src = """
            \documentclass{article}
            \usePackage{tikz}
            \begin{document}
        """.trimIndent()
        val pre = TikzRenderer.collectTikzPreamble(src)
        assertTrue(pre.contains("\\usepackage{tikz}"))
        assertFalse(pre.contains("\\usePackage{"))
    }

    @Test
    fun collectTikzPreamble_trimsUsetikzlibraryTrailingComma() {
        val src = """
            \documentclass{article}
            \usepackage{tikz}
            \usetikzlibrary{knots,hobby,}
            \begin{document}
        """.trimIndent()
        val pre = TikzRenderer.collectTikzPreamble(src)
        assertTrue(pre.contains("\\usetikzlibrary{knots,hobby}"))
    }

    @Test
    fun collectTikzPreamble_dropsTextcompWhenStixPresent() {
        val src = """
            \documentclass{article}
            \usepackage{stix}
            \usepackage{textcomp}
            \usepackage{tikz}
            \begin{document}
        """.trimIndent()
        val pre = TikzRenderer.collectTikzPreamble(src)
        assertFalse(Regex("""\\usepackage\{[^}]*textcomp""").containsMatchIn(pre))
    }

    @Test
    fun collectTikzPreamble_stripsJournalMetadataCommand() {
        val src = """
            \documentclass{article}
            \usepackage{tikz}
            \Title{Hello}
            \begin{document}
        """.trimIndent()
        val pre = TikzRenderer.collectTikzPreamble(src)
        assertFalse(pre.contains("\\Title{"))
    }

    @Test
    fun isStandaloneFigureDocument_detectsStandaloneClass() {
        val src = """\documentclass[tikz]{standalone}\begin{document}\begin{tikzpicture}\end{tikzpicture}\end{document}"""
        assertTrue(TikzRenderer.isStandaloneFigureDocument(src))
    }

    @Test
    fun collectTikzPreamble_keepsNewifAndNewcommandFromStandalone() {
        val src = """
            \documentclass[tikz]{standalone}
            \newif\ifsstguides
            \newcommand{\doubletwist}[7]{#1}
            \begin{document}
        """.trimIndent()
        val pre = TikzRenderer.collectTikzPreamble(src)
        assertTrue(pre.contains("\\newif\\ifsstguides"))
        assertTrue(pre.contains("\\newcommand{\\doubletwist}"))
    }

    @Test
    fun collectTikzPreamble_fragmentDoesNotIncludeTikzpicture() {
        val src = """
            \begin{tikzpicture}[
                node distance=0.5 and 0.5,
                arrow/.style={-{Latex[length=2]}, thick}
            ]
            \node(Faraday){$\mathbf{b}_{\swirlarrow}$};
            \node[left=of Faraday](E){$\bm{\eta}$};
            \end{tikzpicture}
        """.trimIndent()
        val pre = TikzRenderer.collectTikzPreamble(src)
        assertFalse(
            "fragment preamble must not contain the picture body",
            pre.contains("\\begin{tikzpicture}"),
        )
        assertFalse(pre.contains("\\swirlarrow"))
        assertTrue(pre.contains("\\usepackage{tikz}"))
        assertTrue(pre.contains("\\usepackage{amsmath}"))
    }

    @Test
    fun buildTikzBlockDoc_fragmentSwirlHasSinglePictureAndMathDeps() {
        val body = """
            \node(Faraday){$\nabla \times \mathbf{E} = -\partial_t \mathbf{B} - \mathbf{b}_{\swirlarrow}$};
            \node[left=of Faraday](E){$\mathbf{E}$};
            \node[right=of Faraday](b){$\bm{\varrho}_{\swirlarrow}$};
        """.trimIndent()
        val opts = "[node distance=0.5 and 0.5, arrow/.style={-{Latex[length=2]}, thick}]"
        val tikzPreamble = TikzRenderer.collectTikzPreamble(
            """
            \begin{tikzpicture}$opts
            $body
            \end{tikzpicture}
            """.trimIndent(),
        )
        val result = TikzRenderer.buildTikzBlockDoc(
            body = body,
            opts = opts,
            tikzPreamble = tikzPreamble,
            texMacroDefs = "",
            tikzsetDefs = "",
            srcLibs = emptySet(),
            injectedMacroNames = emptySet(),
        )
        assertNotNull(result)
        val texDoc = result!!.second
        val beginDoc = texDoc.indexOf("\\begin{document}")
        assertTrue(beginDoc >= 0)
        assertFalse(
            "no tikzpicture before \\begin{document}",
            texDoc.substring(0, beginDoc).contains("\\begin{tikzpicture}"),
        )
        assertEquals(1, Regex("""\\begin\{tikzpicture}""").findAll(texDoc).count())
        val swirl = texDoc.indexOf("\\providecommand{\\swirlarrow}")
        assertTrue("swirl fallback required", swirl >= 0)
        assertTrue("swirl fallback must precede \\begin{document}", swirl < beginDoc)
        assertTrue(texDoc.contains("\\usepackage{amssymb}"))
        assertTrue(texDoc.contains("\\usepackage{bm}"))
    }

    @Test
    fun skipTikzpictureBracketOptions_handlesNestedBracketsInArrowStyle() {
        val s = """
            \begin{tikzpicture}[
                node distance=0.5 and 0.5,
                arrow/.style={-{Latex[length=2]}, thick},
            ]
            \node {A};
            \end{tikzpicture}
        """.trimIndent()
        val beginTok = "\\begin{tikzpicture}"
        val start = s.indexOf(beginTok)
        val afterBegin = start + beginTok.length
        val afterOpts = skipTikzpictureBracketOptions(s, afterBegin)
        val opts = s.substring(afterBegin, afterOpts).trim()
        assertTrue(opts.startsWith("["))
        assertTrue(opts.endsWith("]"))
        assertTrue(opts.contains("Latex[length=2]"))
        assertTrue(opts.contains("arrow/.style="))
        assertTrue(s.substring(afterOpts).trimStart().startsWith("\\node"))
    }

    @Test
    fun replaceTikzPicturesWithLazyPlaceholder_preservesNestedBracketOptions() {
        val full = """
            \documentclass{article}
            \usepackage{tikz}
            \usetikzlibrary{arrows.meta}
            \begin{document}
        """.trimIndent()
        val tikzPreamble = TikzRenderer.collectTikzPreamble(full)
        val html = """
            \begin{tikzpicture}[
                arrow/.style={-{Latex[length=2]}, thick},
            ]
            \node {A};
            \end{tikzpicture}
        """.trimIndent()
        val out = TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(html, full, tikzPreamble)
        assertTrue(out.contains("tikz-lazy"))
        val m = Regex("""data-tikz-key="([^"]+)"""").find(out)
        assertNotNull(m)
        val tex = LatexTikzJobStore.get(m!!.groupValues[1])
        assertNotNull(tex)
        assertTrue(
            "standalone fig.tex must keep nested Latex[length=…] inside tikzpicture options",
            tex!!.contains("Latex[length=2]"),
        )
        assertTrue(tex.contains("arrow/.style="))
        assertFalse(
            "must not truncate options at first ] inside Latex[length=2]",
            Regex("""\\begin\{tikzpicture\}\[\{-|\\begin\{tikzpicture\}\[arrow""").containsMatchIn(tex) &&
                !tex.contains("Latex[length=2]"),
        )
    }

    @Test
    fun lazyTikzJob_prefersDeclareRobustCommandOverEmptyPdfstringDef() {
        val full = """
            \documentclass{article}
            \usepackage{tikz}
            \pdfstringdefDisableCommands{%
                \def\swirlarrow{}%
            }
            \DeclareRobustCommand{\swirlarrow}{\rightsquigarrow}
            \begin{document}
        """.trimIndent()
        val tikzPreamble = TikzRenderer.collectTikzPreamble(full)
        val html = """
            \begin{tikzpicture}
            \node {$\mathbf{b}_{\swirlarrow}$};
            \end{tikzpicture}
        """.trimIndent()
        val out = TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(html, full, tikzPreamble)
        val m = Regex("""data-tikz-key="([^"]+)"""").find(out)
        assertNotNull(m)
        val tex = LatexTikzJobStore.get(m!!.groupValues[1])
        assertNotNull(tex)
        assertFalse(
            "must not emit broken \\providecommand{\\swirlarrow}{}}",
            tex!!.contains("\\providecommand{\\swirlarrow}{}}"),
        )
        assertTrue(
            "must keep a usable swirlarrow definition",
            tex.contains("\\DeclareRobustCommand{\\swirlarrow}") ||
                tex.contains("\\providecommand{\\swirlarrow}{\\rightsquigarrow}") ||
                Regex("""\\providecommand\{\\swirlarrow\}\{[^}]+\}""").containsMatchIn(tex),
        )
        assertFalse("must not inject knots preamble for non-knot figures", tex.contains("every knot/.style"))
    }

    @Test
    fun replaceTikzPicturesWithLazyPlaceholder_twoFiguresProduceTwoPlaceholders() {
        val full = """
            \documentclass{article}
            \usepackage{tikz}
            \begin{document}
        """.trimIndent()
        val tikzPreamble = TikzRenderer.collectTikzPreamble(full)
        val html = """
            \begin{tikzpicture}\node{A};\end{tikzpicture}
            mid
            \begin{tikzpicture}\node{B};\end{tikzpicture}
        """.trimIndent()
        val out = TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(html, full, tikzPreamble)
        assertEquals(2, Regex("""class="tikz-lazy"""").findAll(out).count())
    }

    @Test
    fun replaceTikzPicturesWithPlaceholder_handlesNestedTikzpicture() {
        val html = """
            outer
            \begin{tikzpicture}
            inner
            \begin{tikzpicture}
            deep
            \end{tikzpicture}
            mid
            \end{tikzpicture}
            tail
        """.trimIndent()
        val out = TikzRenderer.replaceTikzPicturesWithPlaceholder(html)
        assertEquals(1, Pattern.compile("tikz-placeholder").matcher(out).results().count())
        assertTrue(out.contains("outer"))
        assertTrue(out.contains("tail"))
    }

    @Test
    fun replaceTikzPicturesWithLazyPlaceholder_collapsesKnotOptionalAcrossLines() {
        val full = """
            \documentclass{article}
            \usepackage{tikz}
            \begin{document}
        """.trimIndent()
        val tikzPreamble = TikzRenderer.collectTikzPreamble(full)
        val html = """
            \begin{tikzpicture}
            \begin{knot}[
              consider self intersections,
              flip crossing/.list={2,4},
            ]
            \strand (0,0) -- (1,0);
            \end{knot}
            \end{tikzpicture}
        """.trimIndent()
        val out = TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(html, full, tikzPreamble)
        assertTrue(out.contains("tikz-lazy"))
        val m = Regex("""data-tikz-key="([^"]+)"""").find(out)
        assertNotNull(m)
        val key = m!!.groupValues[1]
        val tex = LatexTikzJobStore.get(key)
        assertNotNull(tex)
        assertFalse(
            "newline right after \\begin{knot}[ breaks optional-arg scan in standalone TeX",
            Regex("""\\begin\{knot\}\[\s*\n""").containsMatchIn(tex!!),
        )
        assertTrue(tex.contains("consider self intersections"))
        assertTrue(tex.contains("flip crossing/.list={2,4}"))
    }

    @Test
    fun replaceSstTikzMacrosWithPlaceholder_replacesSstMacroButNotSSTGuidesPoints() {
        val s = """\SSTdown and \SSTGuidesPoints{P}{3} done"""
        val out = TikzRenderer.replaceSstTikzMacrosWithPlaceholder(s)
        assertTrue(out.contains("tikz-placeholder"))
        assertTrue(out.contains("\\SSTGuidesPoints{P}{3}"))
    }

    @Test
    fun twistKnotsFixture_lazyJobCountMatchesTikzPictures() {
        val f = File("TwistKnots.tex")
        org.junit.Assume.assumeTrue("TwistKnots.tex in project root (run Gradle from LiveLatex)", f.isFile)
        val raw = f.readText(Charsets.UTF_8)
        val src = stripLineComments(raw)
        val tikzPreamble = TikzRenderer.collectTikzPreamble(src)
        val body = stripPreamble(raw)
        val bodyNc = stripLineComments(body)
        val out = TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(bodyNc, src, tikzPreamble)
        val expected = Regex("""\\begin\{tikzpicture\}""").findAll(bodyNc).count()
        val lazyCount = Regex("""class="tikz-lazy"""").findAll(out).count()
        assertEquals(expected, lazyCount)
        assertEquals(8, expected)
        Regex("""data-tikz-key="([^"]+)"""").findAll(out).forEach { m ->
            val doc = LatexTikzJobStore.get(m.groupValues[1])
            assertNotNull(m.groupValues[1], doc)
            assertTrue(doc!!.contains("\\begin{document}"))
            assertTrue(doc.contains("\\end{document}"))
        }
    }

    @Test
    fun torusKnotsFixture_lazyJobCountMatchesTikzPictures() {
        val f = File("TorusKnots.tex")
        org.junit.Assume.assumeTrue("TorusKnots.tex in project root", f.isFile)
        val raw = f.readText(Charsets.UTF_8)
        val src = stripLineComments(raw)
        val tikzPreamble = TikzRenderer.collectTikzPreamble(src)
        val body = stripPreamble(raw)
        val bodyNc = stripLineComments(body)
        val out = TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(bodyNc, src, tikzPreamble)
        val expected = Regex("""\\begin\{tikzpicture\}""").findAll(bodyNc).count()
        val lazyCount = Regex("""class="tikz-lazy"""").findAll(out).count()
        assertEquals(expected, lazyCount)
        assertEquals(20, expected)
    }

    @Test
    fun buildTikzBlockDoc_stripsUsetikzlibraryTrailingBackslash() {
        val preamble = """
            \usepackage{tikz}
            \newcommand{\SSTGuidesPoints}[2]{\ifsstguides\fi}
            \usetikzlibrary{knots,hobby,spath3}\
        """.trimIndent()
        val body = """
            \begin{knot}[consider self intersections]
            \strand (0,0) circle (1cm);
            \end{knot}
        """.trimIndent()
        val result = TikzRenderer.buildTikzBlockDoc(
            body = body,
            opts = "",
            tikzPreamble = preamble,
            texMacroDefs = "",
            tikzsetDefs = "",
            srcLibs = setOf("knots", "hobby", "spath3"),
            injectedMacroNames = emptySet(),
        )
        assertNotNull(result)
        val texDoc = result!!.second
        assertFalse(
            "must not leave an orphan backslash line",
            texDoc.lines().any { it.trim() == "\\" },
        )
        assertTrue(texDoc.contains("\\newif\\ifsstguides"))
        assertTrue("must keep \\newcommand after strip", texDoc.contains("\\newcommand{\\SSTGuidesPoints}"))
        assertEquals(1, Regex("""\\usetikzlibrary\{[^}]*\}""").findAll(texDoc).count())
    }

    @Test
    fun buildTikzBlockDoc_stripUsetikzlibraryDoesNotEatNextCommandBackslash() {
        val preamble = """
            \usepackage{tikz}
            \usetikzlibrary{knots,hobby,spath3}
            \newcommand{\SSTGuidesPoints}[2]{\ifsstguides\fi}
            \usepackage{amsmath}
        """.trimIndent()
        val body = """
            \begin{knot}[consider self intersections]
            \strand (0,0) circle (1cm);
            \end{knot}
        """.trimIndent()
        val result = TikzRenderer.buildTikzBlockDoc(
            body = body,
            opts = "",
            tikzPreamble = preamble,
            texMacroDefs = "",
            tikzsetDefs = "",
            srcLibs = setOf("knots", "hobby", "spath3"),
            injectedMacroNames = emptySet(),
        )
        assertNotNull(result)
        val texDoc = result!!.second
        assertTrue(
            "must not steal \\ from \\newcommand",
            texDoc.contains("\\newcommand{\\SSTGuidesPoints}"),
        )
        assertFalse(
            "bare newcommand means leading \\ was eaten",
            Regex("""(?m)^newcommand\{""").containsMatchIn(texDoc),
        )
        assertTrue(
            "must not steal \\ from \\usepackage",
            texDoc.contains("\\usepackage{amsmath}"),
        )
        assertFalse(
            "bare usepackage means leading \\ was eaten",
            Regex("""(?m)^usepackage\{""").containsMatchIn(texDoc),
        )
        assertFalse(
            "must not leave an orphan backslash line",
            texDoc.lines().any { it.trim() == "\\" },
        )
    }

    @Test
    fun buildTikzBlockDoc_emitsSingleUsetikzlibraryForSpath3Knots() {
        val preamble = """
            \usepackage{amsmath,amssymb,amsfonts,bm}
            \usepackage{tikz}
            \usetikzlibrary{knots, hobby, calc, intersections, decorations.pathreplacing, decorations.markings, shapes.geometric, spath3}
        """.trimIndent()
        val body = """
            \begin{knot}[consider self intersections]
            \strand (0,0) circle (1cm);
            \end{knot}
        """.trimIndent()
        val srcLibs = setOf(
            "knots", "hobby", "calc", "intersections",
            "decorations.pathreplacing", "decorations.markings",
            "shapes.geometric", "spath3",
        )
        val result = TikzRenderer.buildTikzBlockDoc(
            body = body,
            opts = "",
            tikzPreamble = preamble,
            texMacroDefs = "",
            tikzsetDefs = "",
            srcLibs = srcLibs,
            injectedMacroNames = emptySet(),
        )
        assertNotNull(result)
        val texDoc = result!!.second
        val usetikz = Regex("""\\usetikzlibrary\{[^}]*\}""").findAll(texDoc).map { it.value }.toList()
        assertEquals("expected exactly one \\usetikzlibrary, got: $usetikz", 1, usetikz.size)
        assertTrue("knots library required", usetikz.single().contains("knots"))
        assertFalse(
            "spath3 should not be listed alongside knots (knots loads it)",
            Regex("""\\usetikzlibrary\{[^}]*\bspath3\b""").containsMatchIn(texDoc),
        )
        assertTrue(
            "expl3 variant shim required for current spath3",
            texDoc.contains("ll_orig_cs_generate_variant:Nn"),
        )
        assertEquals(
            "must not keep a second \\usetikzlibrary",
            texDoc.indexOf("\\usetikzlibrary"),
            texDoc.lastIndexOf("\\usetikzlibrary"),
        )
    }

    @Test
    fun compositeTubeFixture_compilesWhenPdflatexAvailable() {
        val fixture = File("src/test/resources/probe-fixtures/composite_tube.tex")
        org.junit.Assume.assumeTrue(fixture.isFile)
        org.junit.Assume.assumeTrue(pdflatexAvailable())
        val body = fixture.readText(Charsets.UTF_8)
        val src = """
            \documentclass{article}
            \usepackage{tikz}
            \usetikzlibrary{hobby,topaths}
            \usepackage{xcolor}
            \begin{document}
            $body
            \end{document}
        """.trimIndent()
        val out = LatexHtmlTikz.renderTexToSvg(src, "composite-tube-probe")
        assertNotNull("composite tube TikZ should compile to SVG when pdflatex is available", out)
        assertTrue(out!!.exists())
    }

    private fun pdflatexAvailable(): Boolean = try {
        val p = ProcessBuilder("pdflatex", "--version").redirectErrorStream(true).start()
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
