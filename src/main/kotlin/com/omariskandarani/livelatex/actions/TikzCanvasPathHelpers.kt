package com.omariskandarani.livelatex.actions

import java.awt.Point
import kotlin.math.abs

/** Canvas path helpers: closed rings for editing vs TeX `[closed]`. */
object TikzCanvasPathHelpers {

    /**
     * Ensure exactly one closing point equal to the first (for canvas polyline).
     * Strips trailing duplicates of the start, then appends a copy of the first point.
     * Empty / single-point lists are unchanged.
     */
    fun ensureClosedCanvasRing(pts: List<Point>): List<Point> {
        if (pts.size < 2) return pts.map { Point(it) }
        val out = pts.map { Point(it) }.toMutableList()
        val first = out.first()
        while (out.size >= 2 && out.last() == first) {
            out.removeAt(out.lastIndex)
        }
        if (out.size >= 2) {
            out += Point(first)
        }
        return out
    }

    /** Same for unit-space presets (double pairs). */
    fun ensureClosedUnitRing(
        pts: List<Pair<Double, Double>>,
        eps: Double = 1e-9,
    ): List<Pair<Double, Double>> {
        if (pts.size < 2) return pts
        fun same(a: Pair<Double, Double>, b: Pair<Double, Double>) =
            abs(a.first - b.first) < eps && abs(a.second - b.second) < eps
        val out = pts.toMutableList()
        val first = out.first()
        while (out.size >= 2 && same(out.last(), first)) {
            out.removeAt(out.lastIndex)
        }
        if (out.size >= 2) {
            out += first
        }
        return out
    }

    /** HTML shell so Knot Preview SVG fills ~90% of the dialog and scales on resize. */
    fun knotPreviewHtml(svgText: String): String = """
<!DOCTYPE html><html><head><meta charset="utf-8"/>
<style>
html,body{margin:0;height:100%;width:100%;overflow:hidden;background:#f5f5f5;}
body{display:flex;align-items:center;justify-content:center;}
.wrap{width:90vw;height:90vh;display:flex;align-items:center;justify-content:center;
  background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.1);padding:8px;box-sizing:border-box;}
.wrap svg{width:100%;height:100%;display:block;}
</style></head>
<body><div class="wrap">$svgText</div></body></html>
    """.trimIndent()
}
