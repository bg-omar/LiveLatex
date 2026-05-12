package com.omariskandarani.livelatex.html

import java.util.LinkedHashMap

/**
 * LaTeX macro extraction and MathJax macro building. Part of LatexHtml multi-file object.
 */

internal data class Macro(val def: String, val nargs: Int)

private fun normalizeZeroArgNewcommandBody(body: String, nargs: Int): String {
    if (nargs != 0) return body
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.startsWith("{") && findBalancedBrace(trimmed, 0) == trimmed.lastIndex) return trimmed

    // Keep a single control-sequence body unchanged (\foo), but group richer bodies
    // (e.g. S_{(t)}^{...}, r_c, \rho_{\!f}) so expansion behaves as one math atom.
    val singleControlSequence = Regex("""\\[A-Za-z@]+$""")
    return if (singleControlSequence.matches(trimmed)) trimmed else "{$trimmed}"
}

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
            val rawBody = s.substring(bodyOpen + 1, bodyClose).trim()
            val body = normalizeZeroArgNewcommandBody(rawBody, nargs)
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

    // Protect macro definition heads from replacement, e.g.
    //   \def\Amp{...}, \newcommand{\Amp}{...}
    // so expansions do not corrupt them into invalid forms.
    val protectedHeads = linkedMapOf<String, String>()
    var protectIdx = 0
    for ((name, _) in zeroArg) {
        val token = "__LL_DEF_HEAD_${protectIdx++}__"
        val defHead = Regex("""\\def\s*\\${Regex.escape(name)}(?![A-Za-z@])""")
        if (defHead.containsMatchIn(s)) {
            protectedHeads[token] = "\\def\\$name"
            s = s.replace(defHead, token)
        }
        val cmds = listOf("newcommand", "renewcommand", "providecommand")
        for (cmd in cmds) {
            val tokenCmd = "__LL_DEF_HEAD_${protectIdx++}__"
            val cmdHead = Regex("""\\$cmd\s*\{\s*\\${Regex.escape(name)}\s*}""")
            if (cmdHead.containsMatchIn(s)) {
                protectedHeads[tokenCmd] = "\\$cmd{\\$name}"
                s = s.replace(cmdHead, tokenCmd)
            }
        }
    }

    var prev: String
    var passes = 0
    do {
        prev = s
        for ((name, macro) in zeroArg) {
            // Replace \name when it's a complete command (not prefix of longer name)
            val re = Regex("""\\(${Regex.escape(name)})(?![A-Za-z@])""")
            // Must use transform overload: replace(String) treats \ and $ as special (Matcher.replaceAll),
            // which eats TeX backslashes inside macro.def (e.g. \mkern, \boldsymbol).
            s = s.replace(re) { macro.def }
        }
        passes++
    } while (s != prev && passes < 10)

    for ((token, original) in protectedHeads) {
        s = s.replace(token, original)
    }
    return s
}
