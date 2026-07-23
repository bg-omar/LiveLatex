package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Handcheck stand-in: full probes must get early source-line synclines (plan 11).
 */
class ProbeScrollAnchorSmokeTest {

    @After
    fun tearDown() {
        currentBaseDir = null
        TikzRenderer.currentBaseDir = null
    }

    @Test
    fun sst05_hasEarlySynclines() {
        assertProbeEarlySynclines(
            File("LiveLatex_full_probes/SST-05_Einstein_to_SST/SST-05_Einstein_to_SST.tex"),
            "SST-05",
        )
    }

    @Test
    fun sst34_hasEarlySynclines() {
        assertProbeEarlySynclines(
            File("LiveLatex_full_probes/SST-34/SST-34_Hydrogen-Gravity.tex"),
            "SST-34",
        )
    }

    private fun assertProbeEarlySynclines(file: File, label: String) {
        assertTrue("$label missing at ${file.absolutePath}", file.isFile)
        currentBaseDir = file.parentFile.absolutePath
        TikzRenderer.currentBaseDir = file.parentFile.absolutePath
        val html = LatexHtml.wrap(file.readText())
        val fullStart = html.indexOf("class=\"full-text\"")
        val full = if (fullStart >= 0) html.substring(fullStart) else html
        val n = Regex("""class="syncline"""").findAll(full).count()
        assertTrue("$label expected synclines, got $n", n > 0)
        val first = Regex("""class="syncline"[^>]*data-abs="(\d+)"""").find(full)
        assertTrue("$label first syncline missing", first != null)
        val pct = 100.0 * full.indexOf(first!!.value) / full.length
        assertTrue("$label first syncline too late (${"%.1f".format(pct)}%)", pct < 50.0)
    }
}
