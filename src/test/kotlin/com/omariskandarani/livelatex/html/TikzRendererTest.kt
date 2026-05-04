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
}
