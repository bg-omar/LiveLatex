package com.omariskandarani.livelatex.actions

import kotlin.math.abs

/** Pure helpers for TikZ canvas toolbar (plan 08). */
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
}
