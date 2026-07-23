package com.omariskandarani.livelatex.html

import com.omariskandarani.livelatex.core.LiveLatexSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugScrollLogTest {

    @Test
    fun settings_debugModeDefaultsOff() {
        val s = LiveLatexSettings()
        assertFalse(s.debugScrollLog)
        s.debugScrollLog = true
        assertTrue(s.debugScrollLog)
    }

    @Test
    fun wrappedHtml_updateDebugGatedByLlDebugScroll() {
        val html = LatexHtml.wrap("\\section{Hello}\nBody.\n")
        assertTrue(html.contains("ll_debug_scroll"))
        assertTrue(html.contains("llDebugScrollEnabled"))
        assertTrue(html.contains("window.__llApplyDebugScroll"))
        val updateIdx = html.indexOf("function updateDebug")
        assertTrue(updateIdx >= 0)
        val gateIdx = html.indexOf("llDebugScrollEnabled()", updateIdx)
        assertTrue("updateDebug must gate on llDebugScrollEnabled", gateIdx > updateIdx)
        assertTrue(gateIdx < updateIdx + 200)
        assertTrue(html.contains("Debug Mode"))
    }

    @Test
    fun wrappedHtml_debugHudHiddenByDefaultCss() {
        val html = LatexHtml.wrap("\\section{Hello}\nBody.\n")
        assertTrue(html.contains("#ll-debug"))
        assertTrue(html.contains("display: none"))
    }
}
