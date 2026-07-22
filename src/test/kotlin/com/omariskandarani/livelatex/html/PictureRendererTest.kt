package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PictureRendererTest {

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
    fun buildPictureBlockDoc_includesPict2ePictureAndUnitlength() {
        val (key, doc) = PictureRenderer.buildPictureBlockDoc(
            sizeOpts = "(6,5)",
            body = """\put(1,0.5){\line(2,1){3}}""",
            unitlength = "0.8cm",
        )
        assertTrue(key.startsWith("picture-"))
        assertTrue(doc.contains("""\usepackage{pict2e}"""))
        assertTrue(doc.contains("""\usepackage{amsmath}"""))
        assertTrue(doc.contains("""\setlength{\unitlength}{0.8cm}"""))
        assertTrue(doc.contains("""\begin{picture}(6,5)"""))
        assertTrue(doc.contains("""\put(1,0.5){\line(2,1){3}}"""))
        assertTrue(doc.contains("""\end{picture}"""))
        assertFalse(doc.contains("tikzpicture"))
        assertFalse(doc.contains("usetikzlibrary"))
    }

    @Test
    fun lookbehindUnitlength_findsNearbySetlength() {
        val src = """
            \setlength{\unitlength}{0.8cm}
            \begin{picture}(6,5)
            \put(0,0){\line(1,0){1}}
            \end{picture}
        """.trimIndent()
        val start = src.indexOf("""\begin{picture}""")
        val unit = PictureRenderer.lookbehindUnitlength(src, start)
        assertTrue(unit == "0.8cm")
    }

    @Test
    fun replacePicturesWithLazyPlaceholder_registersJobAndEmitsButton() {
        val html = """
            Before
            \setlength{\unitlength}{0.8cm}
            \begin{picture}(6,5)
            \put(1,0.5){\line(2,1){3}}
            \end{picture}
            After
        """.trimIndent()
        val out = PictureRenderer.replacePicturesWithLazyPlaceholder(html)
        assertTrue(out.contains("tikz-lazy"))
        assertTrue(out.contains("LiveRender"))
        assertFalse(out.contains("""\begin{picture}"""))
        assertFalse(out.contains("""\line("""))
        val keyMatch = Regex("""data-tikz-key="([^"]+)"""").find(out)
        assertTrue(keyMatch != null)
        val texDoc = LatexTikzJobStore.get(keyMatch!!.groupValues[1])
        assertTrue(texDoc != null && texDoc.contains("pict2e"))
        assertTrue(texDoc!!.contains("""\setlength{\unitlength}{0.8cm}"""))
    }
}
