package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Point

class TikzCanvasPathHelpersTest {

    @Test
    fun ensureClosedCanvasRing_appendsCloserOnOpenPath() {
        val open = listOf(Point(0, 0), Point(10, 0), Point(10, 10))
        val closed = TikzCanvasPathHelpers.ensureClosedCanvasRing(open)
        assertEquals(4, closed.size)
        assertEquals(closed.first(), closed.last())
        assertEquals(Point(10, 10), closed[2])
    }

    @Test
    fun ensureClosedCanvasRing_collapsesMultipleClosers() {
        val first = Point(0, 2)
        val pts = listOf(
            first,
            Point(-1, 1),
            Point(1, 1),
            Point(0, 2),
            Point(0, 2),
        )
        val closed = TikzCanvasPathHelpers.ensureClosedCanvasRing(pts)
        assertEquals(4, closed.size)
        assertEquals(first, closed.last())
        assertEquals(1, closed.dropLast(1).count { it == first })
    }

    @Test
    fun ensureClosedUnitRing_sameSemantics() {
        val open = listOf(0.0 to 2.0, -1.0 to 1.0, 1.0 to 1.0)
        val closed = TikzCanvasPathHelpers.ensureClosedUnitRing(open)
        assertEquals(4, closed.size)
        assertEquals(closed.first(), closed.last())
    }

    @Test
    fun knotPreviewHtml_scalesSvg() {
        val html = TikzCanvasPathHelpers.knotPreviewHtml("<svg></svg>")
        assertTrue(html.contains("width:90vw"))
        assertTrue(html.contains("height:90vh"))
        assertTrue(html.contains(".wrap svg{width:100%;height:100%"))
        assertTrue(html.contains("<svg></svg>"))
    }
}
