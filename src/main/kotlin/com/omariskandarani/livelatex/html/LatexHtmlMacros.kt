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
internal fun buildMathJaxMacros(user: Map<String, Macro>, fullSource: String = ""): String {
    val usesSiunitx = Regex("""\\usepackage(?:\[[^\]]*])?\{[^}]*siunitx[^}]*\}""")
        .containsMatchIn(fullSource) ||
        Regex("""\\RenewCommandCopy\s*\\qty\s*\\SI""").containsMatchIn(fullSource)

    val base = linkedMapOf(
        "ae"   to Macro("\\unicode{x00E6}", 0),
        "AE"   to Macro("\\unicode{x00C6}", 0),
        "vb"   to Macro("\\mathbf{#1}", 1),
        "bm"   to Macro("\\boldsymbol{#1}", 1),
        "dv"   to Macro("\\frac{d #1}{d #2}", 2),
        "pdv"  to Macro("\\frac{\\partial #1}{\\partial #2}", 2),
        "abs"  to Macro("\\left|#1\\right|", 1),
        "norm" to Macro("\\left\\lVert #1\\right\\rVert", 1),
        "qtyb" to Macro("\\left[#1\\right]", 1),
        "qed"  to Macro("\\square", 0),
        "si"   to Macro("\\mathrm{#1}", 1),
        "num"  to Macro("{#1}", 1),
        "textrm" to Macro("\\mathrm{#1}", 1),
        "Lam"  to Macro("\\Lambda", 0),
        "rc"   to Macro("r_c", 0),
    )
    if (usesSiunitx) {
        base["qty"] = Macro("\\num{#1}\\,\\mathrm{#2}", 2)
    }

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

/**
 * SST papers use `\titlepageOpen` … `\titlepageClose` with abstract/keywords between.
 * Assemble the span into one block (open def + middle + close def) before general macro expansion.
 *
 * Titlepage middle ends at the earliest of `\titlepageClose`, `\section`, or `\begin{figure}` so a
 * misplaced `\titlepageClose` mid-body (e.g. SST-34) does not swallow sections into the title page.
 */
internal fun assembleSplitTitlepageMacros(body: String, macros: Map<String, Macro>): String {
    val openMacro = macros["titlepageOpen"] ?: return body
    val closeMacro = macros["titlepageClose"] ?: return body
    if (openMacro.nargs != 0 || closeMacro.nargs != 0) return body

    val openRe = Regex("""\\titlepageOpen(?![A-Za-z@])""")
    val closeRe = Regex("""\\titlepageClose(?![A-Za-z@])""")
    val sectionRe = Regex("""\\section\*?(?![A-Za-z@])\{""")
    val figureRe = Regex("""\\begin\{figure\}""")

    val openMatch = openRe.find(body) ?: return body
    val afterOpen = openMatch.range.last + 1

    val closeMatch = closeRe.find(body, afterOpen)
    val sectionMatch = sectionRe.find(body, afterOpen)
    val figureMatch = figureRe.find(body, afterOpen)
    val contentEnd = listOfNotNull(
        closeMatch?.range?.first,
        sectionMatch?.range?.first,
        figureMatch?.range?.first,
    ).minOrNull() ?: body.length

    val middle = body.substring(afterOpen, contentEnd)
    val sb = StringBuilder(body.length + 512)
    sb.append(body, 0, openMatch.range.first)
    sb.append(openMacro.def).append(middle).append(closeMacro.def)

    var restStart = contentEnd
    if (closeMatch != null && closeMatch.range.first == contentEnd) {
        restStart = closeMatch.range.last + 1
    }
    var rest = body.substring(restStart)
    rest = closeRe.replace(rest, "")
    sb.append(rest)
    return sb.toString()
}

/** Expand 0-arg \\newcommand macros in body (e.g. \\titlepageOpen -> its definition). */
internal fun expandZeroArgMacros(body: String, macros: Map<String, Macro>): String {
    // Skip empty defs (e.g. \def\swirlarrow{} inside \pdfstringdefDisableCommands) so usages stay intact.
    val zeroArg = macros.filter { it.value.nargs == 0 && it.value.def.isNotBlank() }
    if (zeroArg.isEmpty()) return body

    // Do not expand inside tikzpicture: local \def\KPATH{...} must stay for pdflatex
    // (first-wins extractNewcommands would otherwise freeze every \KPATH to the Unknot circle).
    val (maskedTikz, pictures) = maskTikzpictureEnvironments(body)
    // Do not expand inside math: leave \vswirl etc. for MathJax tex.macros.
    val (masked, mathParts) = maskMathRegionsForMacroExpand(maskedTikz)
    var s = masked

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
    s = unmaskMathRegionsForMacroExpand(s, mathParts)
    return unmaskTikzpictureEnvironments(s, pictures)
}

/**
 * Replace math spans with placeholders so [expandZeroArgMacros] does not expand inside them.
 * Covers `$…$`, `$$…$$`, `\(…\)`, `\[…\]`, and [MATH_ENVS] environments.
 */
internal fun maskMathRegionsForMacroExpand(s: String): Pair<String, List<String>> {
    val parts = mutableListOf<String>()
    val out = StringBuilder()
    var i = 0
    val n = s.length

    fun startsAt(idx: Int, tok: String): Boolean =
        idx >= 0 && idx + tok.length <= n && s.regionMatches(idx, tok, 0, tok.length)

    fun emitMasked(from: Int, to: Int) {
        val token = "__LL_MATH_${parts.size}__"
        parts.add(s.substring(from, to))
        out.append(token)
    }

    while (i < n) {
        val nextDollar = run {
            var j = s.indexOf('$', i)
            while (j >= 0 && j < n && isEscaped(s, j)) j = s.indexOf('$', j + 1)
            j
        }
        val nextBracket = indexOfDisplayMathOpenBracket(s, i)
        val nextParen = s.indexOf("\\(", i)
        val nextBegin = s.indexOf("\\begin{", i)
        val candidates = listOf(nextDollar, nextBracket, nextParen, nextBegin).filter { it >= 0 }
        if (candidates.isEmpty()) {
            out.append(s, i, n)
            break
        }
        val next = candidates.minOrNull()!!
        out.append(s, i, next)

        when (next) {
            nextDollar -> {
                val isDouble = startsAt(next, "$$")
                val closeIdx = if (isDouble) {
                    s.indexOf("$$", next + 2)
                } else {
                    var j = s.indexOf('$', next + 1)
                    while (j >= 0 && j < n && isEscaped(s, j)) j = s.indexOf('$', j + 1)
                    j
                }
                val end = if (closeIdx >= 0) closeIdx + (if (isDouble) 2 else 1) else n
                emitMasked(next, end)
                i = end
            }
            nextBracket -> {
                val closeIdx = s.indexOf("\\]", next + 2)
                val end = if (closeIdx >= 0) closeIdx + 2 else n
                emitMasked(next, end)
                i = end
            }
            nextParen -> {
                val closeIdx = s.indexOf("\\)", next + 2)
                val end = if (closeIdx >= 0) closeIdx + 2 else n
                emitMasked(next, end)
                i = end
            }
            else -> {
                // \begin{...}
                val nameOpen = next + "\\begin{".length
                val nameClose = s.indexOf('}', nameOpen)
                val env = if (nameClose > nameOpen) s.substring(nameOpen, nameClose) else ""
                if (env in MATH_ENVS) {
                    val endAt = when (env) {
                        "tikzpicture" -> {
                            val afterOpts = skipTikzpictureBracketOptions(s, nameClose + 1)
                            val e = findMatchingEndTikzpictureProse(s, afterOpts)
                            if (e < 0) n else e
                        }
                        else -> {
                            val endTok = "\\end{$env}"
                            s.indexOf(endTok, nameClose + 1).let { if (it < 0) n else it + endTok.length }
                        }
                    }
                    emitMasked(next, endAt)
                    i = endAt
                } else {
                    out.append("\\begin{")
                    i = nameOpen
                }
            }
        }
    }
    return out.toString() to parts
}

internal fun unmaskMathRegionsForMacroExpand(s: String, parts: List<String>): String {
    var out = s
    for ((idx, part) in parts.withIndex()) {
        out = out.replace("__LL_MATH_${idx}__", part)
    }
    return out
}

/**
 * Replace each balanced `\\begin{tikzpicture}...\\end{tikzpicture}` with a placeholder token.
 * Nested tikzpictures are treated as one outer block (depth counting).
 */
internal fun maskTikzpictureEnvironments(s: String): Pair<String, List<String>> {
    val beginTok = "\\begin{tikzpicture}"
    val endTok = "\\end{tikzpicture}"
    val pictures = mutableListOf<String>()
    val out = StringBuilder()
    var pos = 0
    while (true) {
        val start = s.indexOf(beginTok, pos)
        if (start < 0) {
            out.append(s, pos, s.length)
            break
        }
        out.append(s, pos, start)
        var depth = 1
        var i = start + beginTok.length
        var end = -1
        while (i < s.length && depth > 0) {
            val nextBegin = s.indexOf(beginTok, i)
            val nextEnd = s.indexOf(endTok, i)
            if (nextEnd < 0) break
            if (nextBegin >= 0 && nextBegin < nextEnd) {
                depth++
                i = nextBegin + beginTok.length
            } else {
                depth--
                if (depth == 0) {
                    end = nextEnd + endTok.length
                    break
                }
                i = nextEnd + endTok.length
            }
        }
        if (end < 0) {
            // Malformed: leave remainder unmasked
            out.append(s, start, s.length)
            break
        }
        val token = "__LL_TIKZPICTURE_${pictures.size}__"
        pictures.add(s.substring(start, end))
        out.append(token)
        pos = end
    }
    return out.toString() to pictures
}

internal fun unmaskTikzpictureEnvironments(s: String, pictures: List<String>): String {
    var out = s
    for ((idx, pic) in pictures.withIndex()) {
        out = out.replace("__LL_TIKZPICTURE_${idx}__", pic)
    }
    return out
}
