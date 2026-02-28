package com.omariskandarani.livelatex.html

/**
 * LaTeX parsing helpers. Part of LatexHtml multi-file object.
 */

/** Keep only the document body; MathJax doesn't understand the preamble. */
internal fun stripPreamble(s: String): String {
    val begin = s.indexOf(BEGIN_DOCUMENT)
    val end   = s.lastIndexOf(END_DOCUMENT)
    return if (begin >= 0 && end > begin) s.substring(begin + BEGIN_DOCUMENT.length, end) else s
}

/**
 * Remove % line comments (safe heuristic):
 * cuts at the first unescaped % per line (so \% is preserved).
 */
internal fun stripLineComments(s: String): String =
    s.lines().joinToString("\n") { line ->
        val cut = firstUnescapedPercent(line)
        if (cut >= 0) line.substring(0, cut) else line
    }

internal fun firstUnescapedPercent(line: String): Int {
    var i = 0
    while (true) {
        val j = line.indexOf('%', i)
        if (j < 0) return -1
        var bs = 0
        var k = j - 1
        while (k >= 0 && line[k] == '\\') { bs++; k-- }
        if (bs % 2 == 0) return j  // even backslashes → % is not escaped
        i = j + 1                   // odd backslashes → escaped, keep searching
    }
}

internal fun findBalancedBrace(s: String, open: Int): Int {
    if (open < 0 || open >= s.length || s[open] != '{') return -1
    var depth = 0
    var i = open
    while (i < s.length) {
        when (s[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return i }
            '\\' -> if (i + 1 < s.length) i++ // skip next char
        }
        i++
    }
    return -1
}

internal fun replaceCmd1ArgBalanced(s: String, cmd: String, wrap: (String) -> String): String {
    val rx = Regex("""\\$cmd\s*\{""")
    val sb = StringBuilder(s.length)
    var pos = 0
    while (true) {
        val m = rx.find(s, pos) ?: break
        val start = m.range.first
        val braceOpen = m.range.last
        val braceClose = findBalancedBrace(s, braceOpen)
        if (braceClose < 0) {
            sb.append(s, pos, start + 1)
            pos = start + 1
            continue
        }
        sb.append(s, pos, start)
        val inner = s.substring(braceOpen + 1, braceClose)
        sb.append(wrap(inner))
        pos = braceClose + 1
    }
    sb.append(s, pos, s.length)
    return sb.toString()
}
