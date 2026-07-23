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

    /**
     * Start index for replacing a tikzpicture: include same-line leading spaces/tabs before
     * `\begin{tikzpicture}` so re-exports do not stack indent on top of leftover whitespace.
     */
    fun includeLeadingWhitespace(text: String, blockStart: Int): Int {
        var i = blockStart.coerceIn(0, text.length)
        while (i > 0) {
            val c = text[i - 1]
            if (c != ' ' && c != '\t') break
            i--
        }
        return i
    }

    /**
     * Replace-range payload for an existing tikzpicture: eat prior leading whitespace and
     * re-apply it once to the new body (after stripping a common indent from the export).
     */
    fun tikzEditorReplace(
        documentText: String,
        blockStart: Int,
        blockEnd: Int,
        newBody: String,
    ): Triple<Int, Int, String> {
        val start = includeLeadingWhitespace(documentText, blockStart)
        val ambient = documentText.substring(start, blockStart.coerceAtLeast(start))
        val stripped = stripCommonIndent(newBody.trimEnd('\n', '\r'))
        val payload = if (ambient.isEmpty()) {
            stripped
        } else {
            stripped.split("\n").joinToString("\n") { line ->
                if (line.isEmpty()) line else ambient + line
            }
        }
        return Triple(start, blockEnd.coerceIn(start, documentText.length), payload)
    }

    /** Remove the largest indent shared by all non-blank lines (spaces/tabs). */
    fun stripCommonIndent(text: String): String {
        val lines = text.split("\n")
        val indents = lines.mapNotNull { line ->
            if (line.isBlank()) null
            else line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line.length else it }
        }
        val min = indents.minOrNull() ?: return text
        if (min <= 0) return text
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) ""
            else if (line.length >= min && line.take(min).all { it == ' ' || it == '\t' }) line.drop(min)
            else line.trimStart(' ', '\t')
        }
    }
}
