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
}