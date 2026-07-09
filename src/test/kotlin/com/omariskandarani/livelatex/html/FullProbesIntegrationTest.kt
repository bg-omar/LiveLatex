package com.omariskandarani.livelatex.html

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FullProbesIntegrationTest {

    private val repoRoot = File(System.getProperty("user.dir"))
    private val probesDir = File(repoRoot, "LiveLatex_full_probes")

    private fun probeFile(vararg parts: String): File {
        val nested = File(probesDir, parts.joinToString(File.separator))
        if (nested.isFile) return nested
        if (parts.size >= 2) {
            val flat = File(probesDir, parts.last())
            if (flat.isFile) return flat
        }
        return nested
    }

    private fun wrapProbe(path: File): String {
        assumeTrue("${path.name} missing at ${path.absolutePath}", path.isFile)
        currentBaseDir = path.parentFile?.absolutePath ?: ""
        TikzRenderer.currentBaseDir = currentBaseDir
        return LatexHtml.wrapWithInputs(path.readText(Charsets.UTF_8), path.absolutePath)
    }

    @Test
    fun sst05_noResizeboxLeakage() {
        val tex = probeFile("SST-05_Einstein_to_SST", "SST-05_Einstein_to_SST.tex")
        val html = wrapProbe(tex)
        val body = html.substringAfter("""class="full-text"""", html)
        assertFalse("raw resizebox should be unwrapped", body.contains("""\resizebox{"""))
        assertTrue(body.contains("ll-titlepage-footer") || body.contains("orcid.org"))
        assertFalse("bibliography url should be linked", body.contains("""\url{https://"""))
    }

    @Test
    fun sst34_noSubfloatOrQtyLeakage() {
        val tex = probeFile("SST-34", "SST-34_Hydrogen-Gravity.tex")
        val html = wrapProbe(tex)
        val body = html.substringAfter("""class="full-text"""", html)
        assertFalse("subfloat macro should be converted", body.contains("""\subfloat["""))
        assertFalse("textsuperscript should be converted in body", body.contains("""\textsuperscript{"""))
        assertFalse("raw qty should be converted", body.contains("""\qty{"""))
        assertFalse("raw footnote macro should be converted", body.contains("""\footnote{"""))
        assertTrue("titlepage footer expected", body.contains("ll-titlepage-footer") || body.contains("orcid.org"))
        assertTrue("section should not be inside titlepage only", body.contains("""id="section-""") || body.contains("Chiral Swirling Knots"))
        assertFalse("section content should not be trapped in raw titlepageOpen", body.contains("""\titlepageOpen"""))
    }

    @Test
    fun probeFixtures_resizeboxUnwrapped() {
        val fixture = File("src/test/resources/probe-fixtures/resizebox_tikz.tex")
        assumeTrue(fixture.isFile)
        val html = LatexHtml.wrap(fixture.readText())
        assertFalse(html.contains("""\resizebox{"""))
        assertTrue(html.contains("Timeline") || html.contains("tikz"))
    }

    @Test
    fun probeFixtures_subfloatConverted() {
        val fixture = File("src/test/resources/probe-fixtures/subfloat_gallery.tex")
        assumeTrue(fixture.isFile)
        val html = LatexHtml.wrap(fixture.readText())
        assertFalse(html.contains("""\subfloat["""))
        assertTrue(html.contains("subfloat"))
    }

    @Test
    fun probeFixtures_qtyConverted() {
        val fixture = File("src/test/resources/probe-fixtures/qty_siunitx.tex")
        assumeTrue(fixture.isFile)
        val html = LatexHtml.wrap(convertSiunitx(fixture.readText()))
        assertFalse(html.contains("""\qty{"""))
        assertTrue(html.contains("""\SI{""") || html.contains("mathrm"))
    }
}
