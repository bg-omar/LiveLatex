package com.omariskandarani.livelatex.html

import java.util.LinkedHashMap

/**
 * LaTeX macro extraction and MathJax macro building. Part of LatexHtml multi-file object.
 */

internal data class Macro(val def: String, val nargs: Int)

/** Parse \newcommand and \def from the WHOLE source (pre + body). */
internal fun extractNewcommands(s: String): Map<String, Macro> {
    val out = LinkedHashMap<String, Macro>()

    fun parseCommand(cmd: String) {
        val rx = Regex("""\\$cmd\s*\{\\([A-Za-z@]+)\}(?:\s*\[(\d+)])?(?:\s*\[[^\]]*])?\s*\{""")
        var pos = 0
        while (true) {
            val m = rx.find(s, pos) ?: break
            val name = m.groupValues[1]
            val nargs = m.groupValues[2].ifEmpty { "0" }.toInt()
            val bodyOpen = m.range.last
            val bodyClose = findBalancedBrace(s, bodyOpen)
            if (bodyClose < 0) { pos = bodyOpen + 1; continue }
            val body = s.substring(bodyOpen + 1, bodyClose).trim()
            out[name] = Macro(body, nargs)
            pos = bodyClose + 1
        }
    }
    parseCommand("newcommand")
    parseCommand("renewcommand")
    parseCommand("providecommand")

    run {
        val rx = Regex("""\\def\\([A-Za-z@]+)\s*\{""")
        var pos = 0
        while (true) {
            val m = rx.find(s, pos) ?: break
            val name = m.groupValues[1]
            val open = m.range.last
            val close = findBalancedBrace(s, open)
            if (close < 0) { pos = open + 1; continue }
            val body = s.substring(open + 1, close).trim()
            out.putIfAbsent(name, Macro(body, 0))
            pos = close + 1
        }
    }

    run {
        val rx = Regex("""\\DeclareMathOperator\*?\s*\{\\([A-Za-z@]+)\}\s*\{""")
        var pos = 0
        while (true) {
            val m = rx.find(s, pos) ?: break
            val name = m.groupValues[1]
            val open = m.range.last
            val close = findBalancedBrace(s, open)
            if (close < 0) { pos = open + 1; continue }
            val opText = s.substring(open + 1, close).trim()
            out.putIfAbsent(name, Macro("\\operatorname{$opText}", 0))
            pos = close + 1
        }
    }

    return out
}

/** Build MathJax tex.macros (JSON-like) from user + base shims. */
internal fun buildMathJaxMacros(user: Map<String, Macro>): String {
    val base = linkedMapOf(
        "ae"   to Macro("\\unicode{x00E6}", 0),
        "AE"   to Macro("\\unicode{x00C6}", 0),
        "vb"   to Macro("\\mathbf{#1}", 1),
        "bm"   to Macro("\\boldsymbol{#1}", 1),
        "dv"   to Macro("\\frac{d #1}{d #2}", 2),
        "pdv"  to Macro("\\frac{\\partial #1}{\\partial #2}", 2),
        "abs"  to Macro("\\left|#1\\right|", 1),
        "norm" to Macro("\\left\\lVert #1\\right\\rVert", 1),
        "qty"  to Macro("\\left(#1\\right)", 1),
        "qtyb" to Macro("\\left[#1\\right]", 1),
        "qed"  to Macro("\\square", 0),
        "si"   to Macro("\\mathrm{#1}", 1),
        "num"  to Macro("{#1}", 1),
        "textrm" to Macro("\\mathrm{#1}", 1),
        "Lam"  to Macro("\\Lambda", 0),
        "rc"   to Macro("r_c", 0),
    )

    val merged = LinkedHashMap<String, Macro>()
    merged.putAll(base)
    merged.putAll(user)

    val parts = merged.map { (k, v) ->
        if (v.nargs > 0) "\"$k\": [${jsonEscape(v.def)}, ${v.nargs}]"
        else              "\"$k\": ${jsonEscape(v.def)}"
    }
    return "{${parts.joinToString(",")}}"
}

internal fun jsonEscape(tex: String): String =
    "\"" + tex
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "") + "\""

/** Expand 0-arg \\newcommand macros in body (e.g. \\titlepageOpen -> its definition). */
internal fun expandZeroArgMacros(body: String, macros: Map<String, Macro>): String {
    var s = body
    val zeroArg = macros.filter { it.value.nargs == 0 }
    if (zeroArg.isEmpty()) return s
    var prev: String
    var passes = 0
    do {
        prev = s
        for ((name, macro) in zeroArg) {
            // Replace \name when it's a complete command (not prefix of longer name)
            val re = Regex("""\\(${Regex.escape(name)})(?![A-Za-z@])""")
            s = s.replace(re, macro.def)
        }
        passes++
    } while (s != prev && passes < 10)
    return s
}
