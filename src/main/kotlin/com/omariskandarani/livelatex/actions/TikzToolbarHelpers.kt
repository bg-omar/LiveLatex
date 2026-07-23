package com.omariskandarani.livelatex.actions

import kotlin.math.abs

/** Pure helpers for TikZ canvas toolbar / export (plans 08–09). */
object TikzToolbarHelpers {

    /** Degrees in [0, 360); 0 means no rotation (matches TikZ `rotate`). */
    fun normalizeRotateDeg(degrees: Double): Double {
        var d = degrees % 360.0
        if (d < 0) d += 360.0
        if (abs(d) < 1e-9 || abs(d - 360.0) < 1e-9) return 0.0
        return d
    }

    /** Cache key that changes when preview TeX content changes (avoids sticky Borromean SVG). */
    fun previewCacheKey(tex: String): String =
        "tikz-preview-" + Integer.toHexString(tex.hashCode())

    fun clampWidthPercent(pct: Int): Int = pct.coerceIn(10, 100)

    /** e.g. 80 → `0.8`, 85 → `0.85`, 100 → `1`. */
    fun linewidthFraction(widthPercent: Int): String {
        val p = clampWidthPercent(widthPercent)
        return when {
            p == 100 -> "1"
            p % 10 == 0 -> "0.${p / 10}"
            else -> "0.${p.toString().padStart(2, '0')}"
        }
    }

    /**
     * Wrap a tikzpicture (or knot export) for editor insert.
     * Preview standalone docs should keep the unwrapped body (linewidth is invalid there).
     */
    fun wrapInLinewidthResizebox(body: String, widthPercent: Int): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("%")) return trimmed
        if (trimmed.contains("\\resizebox{") && trimmed.contains("\\linewidth")) return trimmed
        val frac = linewidthFraction(widthPercent)
        return "\\resizebox{$frac\\linewidth}{!}{%\n$trimmed\n}"
    }
}
