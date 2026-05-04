package com.omariskandarani.livelatex.html

import java.util.regex.Matcher
import kotlin.collections.ArrayDeque
import kotlin.text.RegexOption

/**
 * LaTeX prose-to-HTML conversions. Part of LatexHtml multi-file object.
 */

/** Replace `\\texorpdfstring{PDF}{bookmark}` with the first argument (balanced braces, math-safe). */
internal fun replaceTexorpdfstringBalanced(s: String): String {
    val cmd = "\\texorpdfstring"
    var t = s
    var guard = 0
    while (guard++ < 200000) {
        val k = t.indexOf(cmd)
        if (k < 0) break
        var p = k + cmd.length
        while (p < t.length && t[p].isWhitespace()) p++
        if (p >= t.length || t[p] != '{') {
            t = t.removeRange(k, k + cmd.length)
            continue
        }
        val c1 = findBalancedBraceAllowMath(t, p)
        if (c1 < 0) break
        var q = c1 + 1
        while (q < t.length && t[q].isWhitespace()) q++
        if (q >= t.length || t[q] != '{') break
        val c2 = findBalancedBraceAllowMath(t, q)
        if (c2 < 0) break
        val first = t.substring(p + 1, c1)
        t = t.substring(0, k) + first + t.substring(c2 + 1)
    }
    return t
}

private fun stripTexorpdfstringForPlainTitle(raw: String): String =
    replaceTexorpdfstringBalanced(raw).trim()

private data class SectionCmdMatch(val kind: String, val cmdEnd: Int)

/** Longest command name first (subsubsection before subsection). */
private val SECTION_CMD_NAMES = listOf("subsubsection", "subsection", "section", "paragraph")

private fun matchSectionCommandAt(s: String, i: Int): SectionCmdMatch? {
    if (i >= s.length || s[i] != '\\') return null
    for (name in SECTION_CMD_NAMES) {
        val pref = "\\$name"
        if (i + pref.length > s.length || !s.regionMatches(i, pref, 0, pref.length)) continue
        var j = i + pref.length
        if (j < s.length && s[j] == '*') j++
        val kind = if (name == "paragraph") "paragraph" else name
        return SectionCmdMatch(kind, j)
    }
    return null
}

/**
 * After `\\section`, `\\section*`, etc.: optional `[short title]`, then `{title}` with balanced braces.
 */
internal fun extractSectionHeadingTitle(s: String, cmdEnd: Int): Pair<String, Int>? {
    var j = cmdEnd
    while (j < s.length && s[j].isWhitespace()) j++
    if (j < s.length && s[j] == '*') {
        j++
        while (j < s.length && s[j].isWhitespace()) j++
    }
    if (j < s.length && s[j] == '[') {
        var depth = 1
        var q = j + 1
        while (q < s.length && depth > 0) {
            when (s[q]) {
                '[' -> depth++
                ']' -> depth--
            }
            q++
        }
        if (depth != 0) return null
        j = q
        while (j < s.length && s[j].isWhitespace()) j++
    }
    if (j >= s.length || s[j] != '{') return null
    val close = findBalancedBraceAllowMath(s, j)
    if (close < 0) return null
    val raw = s.substring(j + 1, close)
    return Pair(raw, close + 1)
}

/** Collect (id, label) for all \\section, \\subsection, \\subsubsection, \\paragraph in document order (for IDE dropdown without JS bridge). */
internal fun collectSectionsList(s: String, _absOffset: Int): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < s.length) {
        val m = matchSectionCommandAt(s, i)
        if (m == null) {
            i++
            continue
        }
        val ext = extractSectionHeadingTitle(s, m.cmdEnd)
        if (ext == null) {
            i++
            continue
        }
        val (rawTitle, after) = ext
        val title = stripTexorpdfstringForPlainTitle(rawTitle)
        if (title.isNotEmpty()) {
            val id = if (m.kind == "paragraph") "paragraph-${slugify(title)}" else "${m.kind}-${slugify(title)}"
            out.add(id to title)
        }
        i = after
    }
    return out
}

internal fun convertSections(s: String, absOffset: Int): String {
    val sb = StringBuilder(s.length + 64)
    var i = 0
    while (i < s.length) {
        val m = matchSectionCommandAt(s, i)
        if (m == null) {
            sb.append(s[i])
            i++
            continue
        }
        val ext = extractSectionHeadingTitle(s, m.cmdEnd)
        if (ext == null) {
            sb.append(s[i])
            i++
            continue
        }
        val (rawTitle, after) = ext
        val titlePlain = stripTexorpdfstringForPlainTitle(rawTitle)
        if (titlePlain.isEmpty()) {
            i = after
            continue
        }
        val tag = when (m.kind) {
            "section" -> "h2"
            "subsection" -> "h3"
            "subsubsection" -> "h4"
            "paragraph" -> "h5"
            else -> "div"
        }
        val id = if (m.kind == "paragraph") "paragraph-${slugify(titlePlain)}" else "${m.kind}-${slugify(titlePlain)}"
        val abs = absOffset + s.substring(0, i).count { it == '\n' } + 1
        val htm = latexProseToHtmlWithMath(titlePlain)
        if (m.kind == "paragraph") {
            sb.append("""<span class="llmark" data-id="$id" data-abs="$abs"></span><h5 id="$id" style="margin:1em 0 .3em 0;">$htm</h5>""")
        } else {
            sb.append("""<span class="llmark" data-id="$id" data-abs="$abs"></span><$tag id="$id">$htm</$tag>""")
        }
        i = after
    }
    var t = sb.toString()
    t = replaceTexorpdfstringBalanced(t)
    val appendixHr = "<hr style=\"border:none;border-top:1px solid var(--border);margin:16px 0;\"/>"
    t = t.replace(Regex("\\\\appendix"), appendixHr)
    return t
}

/** Skip optional `[...]` after `\\begin{env}` (supports nested brackets). */
internal fun skipBeginEnvBracketOptions(s: String, afterBeginNameClose: Int): Int {
    var p = afterBeginNameClose
    while (p < s.length && s[p].isWhitespace()) p++
    if (p >= s.length || s[p] != '[') return p
    var depth = 1
    var j = p + 1
    while (j < s.length && depth > 0) {
        when (s[j]) {
            '[' -> depth++
            ']' -> depth--
        }
        j++
    }
    return if (depth == 0) j else p
}

private const val BEGIN_ITEMIZE = "\\begin{itemize}"
private const val BEGIN_ENUMERATE = "\\begin{enumerate}"
private const val END_ITEMIZE = "\\end{itemize}"
private const val END_ENUMERATE = "\\end{enumerate}"

/** Next list-related token from `fromIdx`, or null if none. */
private fun findNextListToken(s: String, fromIdx: Int): Pair<Int, String>? {
    val candidates = listOf(
        BEGIN_ITEMIZE to "begin_itemize",
        BEGIN_ENUMERATE to "begin_enumerate",
        END_ITEMIZE to "end_itemize",
        END_ENUMERATE to "end_enumerate"
    ).mapNotNull { (tok, id) ->
        val p = s.indexOf(tok, fromIdx)
        if (p < 0) null else Triple(p, tok, id)
    }
    if (candidates.isEmpty()) return null
    val min = candidates.minBy { it.first }
    return min.first to min.third
}

/**
 * Matching `\\end{env}` for `\\begin{env}` whose body starts at [bodyStart],
 * with correct nesting of `itemize` / `enumerate`.
 */
internal fun findMatchingEndListEnvironment(s: String, env: String, bodyStart: Int): Int {
    val stack = ArrayDeque<String>()
    stack.addLast(env)
    var idx = bodyStart
    while (idx < s.length && stack.isNotEmpty()) {
        val found = findNextListToken(s, idx) ?: return -1
        val (p, kind) = found
        when (kind) {
            "begin_itemize" -> {
                stack.addLast("itemize")
                idx = skipBeginEnvBracketOptions(s, p + BEGIN_ITEMIZE.length)
            }
            "begin_enumerate" -> {
                stack.addLast("enumerate")
                idx = skipBeginEnvBracketOptions(s, p + BEGIN_ENUMERATE.length)
            }
            "end_itemize" -> {
                if (stack.last() != "itemize") return -1
                stack.removeLast()
                if (stack.isEmpty()) return p
                idx = p + END_ITEMIZE.length
            }
            "end_enumerate" -> {
                if (stack.last() != "enumerate") return -1
                stack.removeLast()
                if (stack.isEmpty()) return p
                idx = p + END_ENUMERATE.length
            }
            else -> return -1
        }
    }
    return -1
}

/** Convert nested itemize/enumerate: inner environments first (recursive). */
internal fun convertListEnvironmentsNested(s: String): String {
    fun findFirstBegin(s0: String): Pair<Int, String>? {
        val ti = s0.indexOf("\\begin{itemize}")
        val te = s0.indexOf("\\begin{enumerate}")
        if (ti < 0 && te < 0) return null
        if (ti < 0) return Pair(te, "enumerate")
        if (te < 0) return Pair(ti, "itemize")
        return if (ti <= te) Pair(ti, "itemize") else Pair(te, "enumerate")
    }

    var t = s
    var iter = 0
    while (iter++ < 50000) {
        val first = findFirstBegin(t) ?: return t
        val (bi, env) = first
        val beginTok = "\\begin{$env}"
        val bodyStart = skipBeginEnvBracketOptions(t, bi + beginTok.length)
        val endIdx = findMatchingEndListEnvironment(t, env, bodyStart)
        if (endIdx < 0) return t
        val body = t.substring(bodyStart, endIdx)
        val endTok = "\\end{$env}"
        val afterEnd = endIdx + endTok.length
        val innerConverted = convertListEnvironmentsNested(body)
        val parts = Regex("""(?m)^\s*\\item\s*""").split(innerConverted).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            t = t.substring(0, bi) + t.substring(afterEnd)
            continue
        }
        val lis = parts.joinToString("") { item -> "<li>${proseNoBr(item)}</li>" }
        val wrap = if (env == "itemize")
            """<ul style="margin:12px 0 12px 24px;">$lis</ul>"""
        else
            """<ol style="margin:12px 0 12px 24px;">$lis</ol>"""
        t = t.substring(0, bi) + wrap + t.substring(afterEnd)
    }
    return t
}

internal fun convertLlmark(s: String, absOffset: Int): String {
    val needle = "\\llmark"
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val k = s.indexOf(needle, i)
        if (k < 0) {
            sb.append(s, i, s.length)
            break
        }
        sb.append(s, i, k)
        var p = k + needle.length
        var titleOpt = ""
        if (p < s.length && s[p] == '[') {
            var depth = 1
            val startBracket = p
            var j = p + 1
            while (j < s.length && depth > 0) {
                when (s[j]) {
                    '[' -> depth++
                    ']' -> depth--
                }
                j++
            }
            if (depth == 0) {
                titleOpt = s.substring(startBracket + 1, j - 1)
                p = j
            }
        }
        while (p < s.length && s[p].isWhitespace()) p++
        if (p >= s.length || s[p] != '{') {
            sb.append(s[k])
            i = k + 1
            continue
        }
        val c = findBalancedBraceAllowMath(s, p)
        if (c < 0) {
            sb.append(s[k])
            i = k + 1
            continue
        }
        val keyRaw = s.substring(p + 1, c).trim()
        val key = keyRaw.ifBlank { "mark" }
        val id = "mark-${slugify(key)}"
        val absLine = absOffset + s.substring(0, k).count { it == '\n' } + 1
        val capHtml = if (titleOpt.isNotBlank())
            """<div style="opacity:.7;margin:.2em 0;">${latexProseToHtmlWithMath(titleOpt)}</div>"""
        else
            ""
        sb.append("""<span class="llmark" data-id="$id" data-abs="$absLine"></span>$capHtml""")
        i = c + 1
    }
    return sb.toString()
}

internal fun unescapeLatexSpecials(t0: String): String {
    var t = t0
    t = Regex("""\\\$""").replace(t, Matcher.quoteReplacement("$"))
    t = Regex("""\\&""").replace(t, "&")
    t = Regex("""\\%""").replace(t, "%")
    t = Regex("""\\#""").replace(t, "#")
    t = Regex("""\\_""").replace(t, "_")
    t = Regex("""\\\{""").replace(t, "{")
    t = Regex("""\\\}""").replace(t, "}")
    t = Regex("""\\~\{\}""").replace(t, "~")
    t = Regex("""\\\^\{\}""").replace(t, "^")
    return t
}

internal val MATH_ENVS = setOf(
    "equation","equation*","align","align*","aligned","gather","gather*",
    "multline","multline*","flalign","flalign*","alignat","alignat*",
    "bmatrix","pmatrix","vmatrix","Bmatrix","Vmatrix","smallmatrix",
    "matrix","cases","split",
    // Preserve whole env so minipage/center wrappers and sanitize don’t break TikZ bodies.
    "tikzpicture","knot",
)

/** After `\\begin{tikzpicture}`, skip optional `[...]` (e.g. `[use Hobby shortcut]`). */
internal fun skipTikzpictureBracketOptions(s: String, afterNameClose: Int): Int {
    var p = afterNameClose
    while (p < s.length && s[p].isWhitespace()) p++
    if (p < s.length && s[p] == '[') {
        val rb = s.indexOf(']', p)
        if (rb >= 0) return rb + 1
    }
    return afterNameClose
}

/** Match `\\end{tikzpicture}` with nested `\\begin{tikzpicture}` depth (same idea as TikzRenderer). */
internal fun findMatchingEndTikzpictureProse(s: String, bodyStart: Int): Int {
    val beginTok = "\\begin{tikzpicture}"
    val endTok = "\\end{tikzpicture}"
    var depth = 1
    var i = bodyStart
    while (i < s.length && depth > 0) {
        val nextBegin = s.indexOf(beginTok, i)
        val nextEnd = s.indexOf(endTok, i)
        if (nextEnd < 0) return -1
        if (nextBegin >= 0 && nextBegin < nextEnd) {
            depth++
            i = nextBegin + beginTok.length
        } else {
            depth--
            if (depth == 0) return nextEnd + endTok.length
            i = nextEnd + endTok.length
        }
    }
    return -1
}

/**
 * Find `\\[` starting a display-math region, but not the second backslash of a TeX line break `\\\\[dim]`.
 * Otherwise `\\\\[0.5em]` is mis-read as `\\[` + `0.5em]` and corrupts the preview.
 */
internal fun indexOfDisplayMathOpenBracket(s: String, from: Int): Int {
    var j = from
    while (true) {
        val k = s.indexOf("\\[", j)
        if (k < 0) return -1
        if (k >= 1 && s[k - 1] == '\\') {
            j = k + 2
            continue
        }
        return k
    }
}

/**
 * Convert LaTeX prose to HTML, preserving math regions ($...$, \[...\], \(...\)).
 */
internal fun latexProseToHtmlWithMath(s: String): String {
    fun tryWrap(cmd: String, openIdx: Int): String? {
        if (!s.regionMatches(openIdx, "\\$cmd", 0, cmd.length + 1)) return null
        var j = openIdx + cmd.length + 1
        while (j < s.length && s[j].isWhitespace()) j++
        if (j >= s.length || s[j] != '{') return null
        val close = findBalancedBraceAllowMath(s, j)
        if (close < 0) return null
        val inner = s.substring(j + 1, close)
        val before = s.substring(0, openIdx)
        val after  = s.substring(close + 1)
        val tag = when (cmd) {
            "textbf"       -> "strong"
            "emph", "textit", "itshape" -> "em"
            "underline", "uline" -> "u"
            "small", "footnotesize" -> "small"
            "texttt"       -> "code"
            else -> return null
        }
        return before + "<$tag>" + latexProseToHtmlWithMath(inner) + "</$tag>" + latexProseToHtmlWithMath(after)
    }

    run {
        var i = s.indexOf('\\')
        while (i >= 0) {
            for (cmd in arrayOf("textbf","emph","textit","itshape","underline","uline","small","footnotesize","texttt")) {
                val rep = tryWrap(cmd, i)
                if (rep != null) return rep
            }
            i = s.indexOf('\\', i + 1)
        }
    }

    val sb = StringBuilder()
    var i = 0
    val n = s.length

    fun startsAt(idx: Int, tok: String): Boolean =
        idx >= 0 && idx + tok.length <= n && s.regionMatches(idx, tok, 0, tok.length)

    while (i < n) {
        val nextDollar = run {
            var j = s.indexOf('$', i)
            while (j >= 0 && j < n && isEscaped(s, j)) j = s.indexOf('$', j + 1)
            j
        }
        val nextBracket = indexOfDisplayMathOpenBracket(s, i)
        val nextParen   = s.indexOf("\\(", i)
        val nextBegin   = s.indexOf("\\begin{", i)

        val candidates = listOf(nextDollar, nextBracket, nextParen, nextBegin).filter { it >= 0 }
        val next = if (candidates.isEmpty()) n else candidates.minOrNull()!!

        sb.append(formatInlineProseNonMath(s.substring(i, next)))

        if (next == n) break

        if (next == nextDollar) {
            val isDouble = startsAt(next, "$$")
            val closeIdx = if (isDouble) s.indexOf("$$", next + 2) else s.indexOf('$', next + 1)
            val end = if (closeIdx >= 0) closeIdx + (if (isDouble) 2 else 1) else n
            sb.append(s.substring(next, end)); i = end; continue
        }
        if (next == nextBracket) {
            val closeIdx = s.indexOf("\\]", next + 2)
            val end = if (closeIdx >= 0) closeIdx + 2 else n
            sb.append(s.substring(next, end)); i = end; continue
        }
        if (next == nextParen) {
            val closeIdx = s.indexOf("\\)", next + 2)
            val end = if (closeIdx >= 0) closeIdx + 2 else n
            sb.append(s.substring(next, end)); i = end; continue
        }
        if (next == nextBegin) {
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
                sb.append(s.substring(next, endAt)); i = endAt; continue
            }
            sb.append("\\begin{"); i = nameOpen
        }
    }
    return sb.toString()
}

internal fun formatInlineProseNonMath(s0: String): String {
    fun applyFmt(t0: String, alreadyEscaped: Boolean): String {
        var t = t0
        t = t.replace(Regex("""\\thispagestyle\s*\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\centering\b"""), "")
        t = t.replace(Regex("""\\raggedright\b"""), "")
        t = t.replace(Regex("""\\raggedleft\b"""), "")
        t = t.replace(Regex("""\\bfseries\b"""), "")
        t = t.replace(Regex("""\\mdseries\b"""), "")
        t = t.replace(Regex("""\\upshape\b"""), "")
        t = t.replace(Regex("""\\(tiny|scriptsize|footnotesize|small|normalsize|large|Large|LARGE|huge|Huge)(?!\s*\{)\b"""), "")
        t = t.replace(Regex("""\\par\b"""), "<br/>")
        // `\\[dim]` optional spacing after a line break (must run before generic `\\` → `<br/>`)
        t = t.replace(Regex("""(?<!\\)\\\\\s*\[[^\]]*]"""), "<br/>")
        t = t.replace(Regex("""(?<!\\)\\\\\s*"""), "<br/>")

        if (!alreadyEscaped) {
            t = unescapeLatexSpecials(t)
            t = t.replace("\\&","&amp;")
        }

        t = Regex("""\\verb\*?(.)(.+?)\1""", RegexOption.DOT_MATCHES_ALL)
            .replace(t) { m ->
                val code = htmlEscapeAll(m.groupValues[2])
                "<code>$code</code>"
            }
        t = t.replace(Regex("""\\noindent\b"""), "")
        t = t.replace(Regex("""\\quad\b"""), """<span style="display:inline-block;width:1em;"></span>""")
        t = t.replace(Regex("""\\qquad\b"""), """<span style="display:inline-block;width:2em;"></span>""")
        t = Regex("""\\vspace\*?\s*\{([^}]*)\}""").replace(t) { m ->
            val arg = m.groupValues[1].trim().replace(',', '.')
            val numMatch = Regex("""([\d.]+)\s*(cm|mm|pt|em|ex|in)?""").find(arg)
            if (numMatch != null) {
                val num = numMatch.groupValues[1]
                val unit = numMatch.groupValues[2].ifEmpty { "em" }
                """<div style="height:${num}$unit"></div>"""
            } else """<div style="height:1em"></div>"""
        }
        t = replaceCmd1ArgBalanced(t, "small") { "<small>${applyFmt(it, true)}</small>" }
        t = t.replace(Regex("""\\small\b(?!\s*\{)"""), "")  // standalone \small: strip (scope would need paragraph end)
        t = t.replace(Regex("""\\smallbreak\b"""), """<div style="height:.5em"></div>""")
            .replace(Regex("""\\medbreak\b"""),   """<div style="height:1em"></div>""")
            .replace(Regex("""\\bigbreak\b"""),   """<div style="height:1.5em"></div>""")

        t = replaceCmd1ArgBalanced(t, "textbf")    { "<strong>${applyFmt(it, true)}</strong>" }
        t = replaceCmd1ArgBalanced(t, "emph")      { "<em>${applyFmt(it, true)}</em>" }
        t = replaceCmd1ArgBalanced(t, "textit")    { "<em>${applyFmt(it, true)}</em>" }
        t = replaceCmd1ArgBalanced(t, "itshape")   { "<em>${applyFmt(it, true)}</em>" }
        t = replaceCmd1ArgBalanced(t, "textsuperscript") { "<sup>${applyFmt(it, true)}</sup>" }
        t = t.replace(Regex("""\\itshape\b"""), "")
        t = replaceCmd1ArgBalanced(t, "underline") { "<u>${applyFmt(it, true)}</u>" }
        t = replaceCmd1ArgBalanced(t, "uline")     { "<u>${applyFmt(it, true)}</u>" }
        t = replaceCmd1ArgBalanced(t, "footnotesize"){ "<small>${applyFmt(it, true)}</small>" }

        t = replaceCmd1ArgBalanced(t, "texttt") { """<code>${applyFmt(it, true)}</code>""" }
        t = replaceCmd1ArgBalanced(t, "mbox") { """<span style="white-space:nowrap;">${applyFmt(it, true)}</span>""" }
        t = Regex("""\\rule(?:\[[^\]]*])?\s*\{([^}]*)\}\s*\{([^}]*)\}""").replace(t) { _ ->
            """<hr style="border:none;border-top:1px solid var(--border);margin:0.35em 0;width:100%;"/>"""
        }
        t = replaceCmd1ArgBalanced(t, "fbox") {
            """<span style="display:inline-block;border:1px solid var(--fg);padding:0 .25em;">${applyFmt(it, true)}</span>"""
        }

        // \cite{key1, key2} -> HTML links to bibliography anchors
        t = replaceCmd1ArgBalanced(t, "cite") { keys ->
            keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(", ") { k -> val e = htmlEscapeAll(k); """<a href="#$e" class="ll-cite" title="Go to reference">[$e]</a>""" }
        }
        t = replaceCmd1ArgBalanced(t, "citep") { keys ->
            keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(", ") { k -> val e = htmlEscapeAll(k); """<a href="#$e" class="ll-cite" title="Go to reference">[$e]</a>""" }
        }
        t = replaceCmd1ArgBalanced(t, "citet") { keys ->
            keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(", ") { k -> val e = htmlEscapeAll(k); """<a href="#$e" class="ll-cite" title="Go to reference">$e</a>""" }
        }
        // \ref{label} and \eqref{label} -> HTML links to label anchors
        t = replaceCmd1ArgBalanced(t, "ref") { label ->
            val e = htmlEscapeAll(label)
            """<a href="#$e" class="ll-ref" title="Go to $e">$e</a>"""
        }
        t = replaceCmd1ArgBalanced(t, "eqref") { label ->
            val e = htmlEscapeAll(label)
            """<a href="#$e" class="ll-eqref" title="Go to equation $e">($e)</a>"""
        }
        // \label{id} -> invisible anchor for \ref and \eqref
        t = replaceCmd1ArgBalanced(t, "label") { id ->
            """<span id="${htmlEscapeAll(id)}" class="ll-label"></span>"""
        }
        // letter class / scrlttr2
        t = replaceCmd1ArgBalanced(t, "opening") { "<p>${applyFmt(it, true)}</p>" }
        t = replaceCmd1ArgBalanced(t, "closing") { "<p style=\"margin-top:1.2em;\">${applyFmt(it, true)}</p>" }

        t = replaceTextSymbols(t)
        return t
    }
    return applyFmt(s0, false)
}

internal fun convertParagraphsOutsideTags(html: String): String {
    val rxTag = Regex("(<[^>]+>)")
    val parts = rxTag.split(html)
    val tags  = rxTag.findAll(html).map { it.value }.toList()

    val out = StringBuilder(html.length + 256)
    for (i in parts.indices) {
        val chunkRaw = parts[i]
        if (!chunkRaw.contains('<') && !chunkRaw.contains('>')) {
            val chunk = chunkRaw.trim()
            if (chunk.isNotEmpty()) {
                if (Regex("""\n{2,}""").containsMatchIn(chunk)) {
                    val paras = chunk.split(Regex("""\n{2,}"""))
                        .map { it.trim() }.filter { it.isNotEmpty() }
                        .joinToString("") { p -> "<p>${latexProseToHtmlWithMath(p)}</p>" }
                    out.append(paras)
                } else {
                    out.append(latexProseToHtmlWithMath(chunk))
                }
            }
        } else {
            out.append(chunkRaw)
        }
        if (i < tags.size) out.append(tags[i])
    }

    val step1 = out.toString()
        .replace(Regex("""<(li|dd|dt)>\s*<p>(.*?)</p>\s*</\1>""", RegexOption.DOT_MATCHES_ALL)) { m ->
            "<${m.groupValues[1]}>${m.groupValues[2]}</${m.groupValues[1]}>"
        }
    return step1.replace(
        Regex("""(<figcaption[^>]*>)\s*<p>(.*?)</p>\s*(</figcaption>)""", RegexOption.DOT_MATCHES_ALL),
        "\$1\$2\$3"
    )
}

internal fun convertMulticols(s: String): String {
    val rx = Regex("\\\\begin\\{multicols\\}\\{(\\d+)\\}(.+?)\\\\end\\{multicols\\}",
        RegexOption.DOT_MATCHES_ALL)
    return rx.replace(s) { m ->
        val n = (m.groupValues[1].toIntOrNull() ?: 2).coerceIn(1, 8)
        val body = latexProseToHtmlWithMath(m.groupValues[2].trim())
        """<div class="multicol" style="-webkit-column-count:$n;column-count:$n;-webkit-column-gap:1.2em;column-gap:1.2em;">$body</div>"""
    }
}

internal fun convertItemize(s: String): String {
    val rx = Regex("\\\\begin\\{itemize\\}(?:\\[[^\\]]*])?(.+?)\\\\end\\{itemize\\}", RegexOption.DOT_MATCHES_ALL)
    return rx.replace(s) { m ->
        val body = m.groupValues[1]
        val parts = Regex("""(?m)^\s*\\item\s*""")
            .split(body).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@replace ""
        val lis = parts.joinToString("") { item -> "<li>${proseNoBr(item)}</li>" }
        """<ul style="margin:12px 0 12px 24px;">$lis</ul>"""
    }
}

internal fun convertEnumerate(s: String): String {
    val rx = Regex("\\\\begin\\{enumerate\\}(?:\\[[^\\]]*])?(.+?)\\\\end\\{enumerate\\}", RegexOption.DOT_MATCHES_ALL)
    return rx.replace(s) { m ->
        val body = m.groupValues[1]
        val parts = Regex("""(?m)^\s*\\item\s*""")
            .split(body).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@replace ""
        val lis = parts.joinToString("") { item -> "<li>${proseNoBr(item)}</li>" }
        """<ol style="margin:12px 0 12px 24px;">$lis</ol>"""
    }
}

internal fun convertDescription(s: String): String {
    val rxEnv = Regex("\\\\begin\\{description\\}(?:\\[[^\\]]*])?(.+?)\\\\end\\{description\\}", RegexOption.DOT_MATCHES_ALL)
    return rxEnv.replace(s) { envMatch ->
        val body = envMatch.groupValues[1]

        val rxItem = Regex("""(?ms)^\s*\\item(?:\s*\[([^\]]*)])?\s*(.*?)\s*(?=^\s*\\item|\z)""")

        val items = rxItem.findAll(body).map { m ->
            val rawLabel   = m.groupValues[1]
            val rawContent = m.groupValues[2]

            val (peeled, tag) = peelTopLevelTextWrapper(rawLabel)
            val labelHtmlInner = latexProseToHtmlWithMath(peeled)
            val labelHtml = when (tag) {
                "strong" ->
                    if (labelHtmlInner.contains("<strong>", ignoreCase = true)) labelHtmlInner
                    else "<strong>$labelHtmlInner</strong>"
                "em" ->
                    if (labelHtmlInner.contains("<em>", ignoreCase = true)) labelHtmlInner
                    else "<em>$labelHtmlInner</em>"
                else -> labelHtmlInner
            }

            val contentHtml = latexProseToHtmlWithMath(rawContent)

            val termHtml = when {
                labelHtml.isBlank() -> ""
                labelHtml.contains("<strong>", ignoreCase = true) -> labelHtml
                else -> "<strong>$labelHtml</strong>"
            }

            "<dt>$termHtml</dt><dd>$contentHtml</dd>"
        }.joinToString("")

        """<dl style="margin:12px 0 12px 24px;">$items</dl>"""
    }
}
