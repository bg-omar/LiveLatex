package com.omariskandarani.livelatex.html

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollEditorRemovedTest {

    @Test
    fun wrappedHtml_hasNoPreviewToEditorAutoScrollPaths() {
        val html = LatexHtml.wrap("\\section{Hello}\nBody.\n")
        assertFalse(html.contains("onPreviewScroll"))
        assertFalse(html.contains("__llAutoScrollEditor"))
        assertFalse(html.contains("ll_auto_scroll_editor"))
        assertFalse(html.contains("ll-auto-scroll-editor"))
        // Editor → preview auto-scroll remains.
        assertTrue(html.contains("__llAutoScroll"))
        assertTrue(html.contains("ll_auto_scroll"))
    }
}
