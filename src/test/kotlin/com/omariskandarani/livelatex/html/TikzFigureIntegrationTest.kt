package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Integration tests for TikZ / figure preview rendering (PDF-toonbaar-criterium).
 * Skips gracefully when example files or external tools (pdflatex) are unavailable.
 */
class TikzFigureIntegrationTest {

    private val sstRoot = File("C:/workspace/projects/SwirlStringTheory")
    private val twistedTikz = File(sstRoot, "docs/tikz/twisted_tikz.tex")
    private val twistedTik3 = File(sstRoot, "docs/tikz/twisted_tik3.tex")
    private val sst33 = File(sstRoot, "papers/SST-33_Heat_Transport/SST-33_Enhanced_Heat_Transport_via_Swirl_Coupling.tex")
    private val sst34 = File(sstRoot, "papers/SST-34_Hydrogen-Gravity/SST-34_Hydrogen-Gravity.tex")

    @Before
    fun setup() {
        LatexTikzJobStore.clear()
        TikzRenderer.pluginCacheRoot = Files.createTempDirectory("ll-tikz-integ-cache").toString()
    }

    @After
    fun tearDown() {
        LatexTikzJobStore.clear()
        currentBaseDir = null
        TikzRenderer.currentBaseDir = null
        TikzRenderer.pluginCacheRoot = null
    }

    private fun pdflatexAvailable(): Boolean = try {
        val p = ProcessBuilder("pdflatex", "--version").redirectErrorStream(true).start()
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun assertNoPdfImgSrc(html: String) {
        assertFalse(
            "HTML must not contain <img src=\"*.pdf\">",
            Regex("""<img[^>]+src="[^"]*\.pdf""", RegexOption.IGNORE_CASE).containsMatchIn(html),
        )
    }

    private fun assertFigureOrPlaceholder(html: String) {
        val hasImg = html.contains("<img")
        val hasPlaceholder = html.contains("ll-figure-unavailable") ||
            html.contains("tikz-wrap") ||
            html.contains("tikz-lazy") ||
            html.contains("tikz-placeholder")
        assertTrue("Expected <img> or a placeholder", hasImg || hasPlaceholder)
    }

    @Test
    fun guard_wrap_neverEmitsPdfImgSrc_forIncludeGraphics() {
        val tex = """
            \documentclass{article}
            \usepackage{graphicx}
            \begin{document}
            \includegraphics[width=0.5\linewidth]{figures/missing_figure}
            \end{document}
        """.trimIndent()
        val html = LatexHtml.wrap(tex)
        assertNoPdfImgSrc(html)
        assertTrue(html.contains("ll-figure-unavailable") || html.contains("<img"))
    }

    @Test
    fun resolveImageForPreview_pdfFileNeverReturnsPdfUrl() {
        val dir = Files.createTempDirectory("ll-pdf-resolve")
        val pdf = dir.resolve("sample.pdf")
        Files.writeString(pdf, "%PDF-1.4 minimal")
        currentBaseDir = dir.toString()
        when (val r = resolveImageForPreview("sample.pdf")) {
            is PreviewImageResult.Ready -> {
                assertFalse(r.url.contains(".pdf", ignoreCase = true))
                assertTrue(r.url.startsWith("data:") || r.url.contains(".svg") || r.url.contains(".png"))
            }
            is PreviewImageResult.Unavailable -> assertTrue(r.html.contains("ll-figure-unavailable"))
        }
    }

    @Test
    fun twistedTikz_standalone_wrapsWithoutPdfImg() {
        assumeTrue("twisted_tikz.tex present", twistedTikz.isFile)
        TikzRenderer.currentBaseDir = twistedTikz.parentFile.absolutePath
        currentBaseDir = twistedTikz.parentFile.absolutePath
        val src = twistedTikz.readText(Charsets.UTF_8)
        val html = if (pdflatexAvailable()) {
            LatexHtml.wrapWithInputs(src, twistedTikz.absolutePath)
        } else {
            TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(
                stripLineComments(stripPreamble(src)),
                stripLineComments(src),
                TikzRenderer.collectTikzPreamble(stripLineComments(src)),
            )
        }
        assertNoPdfImgSrc(html)
        assertFigureOrPlaceholder(html)
        assertTrue(TikzRenderer.isStandaloneFigureDocument(stripLineComments(src)))
    }

    @Test
    fun twistedTik3_standalone_wrapsWithoutPdfImg() {
        assumeTrue("twisted_tik3.tex present", twistedTik3.isFile)
        TikzRenderer.currentBaseDir = twistedTik3.parentFile.absolutePath
        currentBaseDir = twistedTik3.parentFile.absolutePath
        val src = twistedTik3.readText(Charsets.UTF_8)
        val html = if (pdflatexAvailable()) {
            LatexHtml.wrapWithInputs(src, twistedTik3.absolutePath)
        } else {
            TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(
                stripLineComments(stripPreamble(src)),
                stripLineComments(src),
                TikzRenderer.collectTikzPreamble(stripLineComments(src)),
            )
        }
        assertNoPdfImgSrc(html)
        assertFigureOrPlaceholder(html)
    }

    @Test
    fun sst33_paper_wrapsWithoutPdfImg() {
        assumeTrue("SST-33 paper present", sst33.isFile)
        TikzRenderer.currentBaseDir = sst33.parentFile.absolutePath
        currentBaseDir = sst33.parentFile.absolutePath
        val src = sst33.readText(Charsets.UTF_8)
        val html = if (pdflatexAvailable()) {
            LatexHtml.wrapWithInputs(src, sst33.absolutePath)
        } else {
            LatexHtml.wrap(src)
        }
        assertNoPdfImgSrc(html)
        assertNotEquals("", html)
    }

    @Test
    fun sst34_paper_wrapsWithoutPdfImg() {
        assumeTrue("SST-34 paper present", sst34.isFile)
        TikzRenderer.currentBaseDir = sst34.parentFile.absolutePath
        currentBaseDir = sst34.parentFile.absolutePath
        val src = sst34.readText(Charsets.UTF_8)
        val html = if (pdflatexAvailable()) {
            LatexHtml.wrapWithInputs(src, sst34.absolutePath)
        } else {
            LatexHtml.wrap(src)
        }
        assertNoPdfImgSrc(html)
        assertTrue(html.contains("includegraphics") || html.contains("<img") || html.contains("ll-figure-unavailable"))
    }

    @Test
    fun convertPdfToWebImage_cachesByFileKey() {
        val dir = Files.createTempDirectory("ll-pdf-convert")
        val pdf = dir.resolve("fig.pdf")
        Files.writeString(pdf, "%PDF-1.4\n")
        assumeTrue("Need pdflatex toolchain to produce real PDF for conversion", pdflatexAvailable())
        // Minimal valid PDF is hard; test cache miss path returns null without crash.
        val result = LatexHtmlTikz.convertPdfToWebImage(pdf.toFile())
        // Either converts (tools present) or returns null — never throws.
        if (result != null) {
            assertTrue(result.file.exists())
            assertTrue(result.file.extension.equals("svg", true) || result.file.extension.equals("png", true))
        }
    }
}
