package com.omariskandarani.livelatex.html

import java.util.Locale
import kotlin.text.RegexOption

import com.omariskandarani.livelatex.html.latexProseToHtmlWithMath
import com.omariskandarani.livelatex.html.renderDate

/**
 * LaTeX sanitization for MathJax compatibility. Part of LatexHtml multi-file object.
 */

/** Convert \\begin{titlepage}...\\end{titlepage} to HTML title block. */
internal fun convertTitlepage(s: String): String {
    val beginTok = "\\begin{titlepage}"
    val endTok = "\\end{titlepage}"
    val sb = StringBuilder(s.length + 256)
    var i = 0
    var guard = 0
    while (i < s.length && guard++ < 5000) {
        val j = s.indexOf(beginTok, i)
        if (j < 0) {
            sb.append(s, i, s.length)
            break
        }
        sb.append(s, i, j)
        val bodyStart = j + beginTok.length
        val endIdx = findMatchingEndTitlepage(s, bodyStart)
        if (endIdx < 0) {
            sb.append(s, j, s.length)
            break
        }
        val body = s.substring(bodyStart, endIdx)
        sb.append(renderTitlepageHtml(body))
        i = endIdx + endTok.length
    }
    return sb.toString()
}

private fun findMatchingEndTitlepage(s: String, bodyStart: Int): Int {
    val beginTok = "\\begin{titlepage}"
    val endTok = "\\end{titlepage}"
    var depth = 1
    var pos = bodyStart
    while (pos < s.length) {
        val nb = s.indexOf(beginTok, pos)
        val ne = s.indexOf(endTok, pos)
        if (ne < 0) return -1
        if (nb >= 0 && nb < ne) {
            depth++
            pos = nb + beginTok.length
        } else {
            depth--
            if (depth == 0) return ne
            pos = ne + endTok.length
        }
    }
    return -1
}

/** Remove a single outer `{...}` wrapper used for TeX grouping/scoping. */
internal fun stripOuterLatexGroupBraces(s: String): String {
    var t = s.trim()
    while (t.isNotEmpty() && t[0] == '{') {
        val close = findBalancedBrace(t, 0)
        if (close == t.length - 1) t = t.substring(1, close).trim()
        else break
    }
    return t
}

/** `picture` + `\\put(...){...}` blocks (common on custom title pages) → footer HTML. */
internal fun convertPicturePutBlocks(s: String): String {
    val beginTok = "\\begin{picture}"
    val endTok = "\\end{picture}"
    val sb = StringBuilder(s.length + 64)
    var i = 0
    var guard = 0
    while (i < s.length && guard++ < 5000) {
        val j = s.indexOf(beginTok, i)
        if (j < 0) {
            sb.append(s, i, s.length)
            break
        }
        sb.append(s, i, j)
        var p = j + beginTok.length
        while (p < s.length && s[p].isWhitespace()) p++
        if (p < s.length && s[p] == '(') {
            val rp = s.indexOf(')', p)
            if (rp >= 0) p = rp + 1
        }
        val endIdx = s.indexOf(endTok, p)
        if (endIdx < 0) {
            sb.append(s, j, s.length)
            break
        }
        val inner = s.substring(p, endIdx)
        sb.append(extractPicturePutFooterHtml(inner))
        i = endIdx + endTok.length
    }
    return sb.toString()
}

private fun extractPicturePutFooterHtml(inner: String): String {
    var footer = inner.trim()
    val putIdx = footer.indexOf("""\put""")
    if (putIdx >= 0) {
        var p = putIdx + 4
        while (p < footer.length && footer[p].isWhitespace()) p++
        if (p < footer.length && footer[p] == '(') {
            val rp = footer.indexOf(')', p)
            if (rp >= 0) p = rp + 1
        }
        while (p < footer.length && footer[p].isWhitespace()) p++
        if (p < footer.length && footer[p] == '{') {
            val close = findBalancedBrace(footer, p)
            if (close >= 0) footer = footer.substring(p + 1, close).trim()
        }
    }
    footer = footer.replace(Regex("""\\renewcommand\s*\{[^}]*\}\s*\{[^}]*\}"""), "")
    footer = convertMinipagesToHtml(footer)
    footer = convertHref(footer)
    val html = latexProseToHtmlWithMath(footer.trim())
    return """<div class="ll-titlepage-footer" style="margin-top:2em;padding-top:0.75em;border-top:1px solid var(--border);font-size:0.88em;line-height:1.4;text-align:left;">$html</div>"""
}

private fun applyTitlepageVspace(body: String): String =
    Regex("""\\vspace\*?\s*\{([^}]*)\}""").replace(body) { m ->
        val arg = m.groupValues[1].trim().replace(',', '.')
        val numMatch = Regex("""([\d.]+)\s*(cm|mm|pt|em|ex|in)?""").find(arg)
        if (numMatch != null) {
            val num = numMatch.groupValues[1]
            val unit = numMatch.groupValues[2].ifEmpty { "em" }
            """<div style="height:${num}$unit"></div>"""
        } else "\n"
    }

private fun preprocessTitlepageBody(body: String): String {
    var t = body
    t = t.replace(Regex("""\\thispagestyle\{[^}]*\}"""), "")
    t = t.replace(Regex("""\\centering\b"""), "")
    t = t.replace(Regex("""\\raggedright\b"""), "")
    t = t.replace(Regex("""\\null\b"""), "")
    t = t.replace(Regex("""\\vfill\b"""), "\n")
    t = t.replace(Regex("""\\par\b"""), " ")
    t = applyTitlepageVspace(t)
    t = t.replace("""\today""", renderDate("""\today""") ?: "")
    t = t.replace(Regex("""\\(tiny|scriptsize|footnotesize|small|normalsize|large|Large|LARGE|huge|Huge)\b"""), "")
    t = t.replace(Regex("""\\bfseries\b"""), "")
    t = t.replace(Regex("""\\itshape\b"""), "")
    t = convertPicturePutBlocks(t)
    t = t.replace(
        Regex("""\\begin\{abstract\}(.+?)\\end\{abstract\}""", RegexOption.DOT_MATCHES_ALL),
    ) { m ->
        val raw = m.groupValues[1].trim()
        val collapsedSingles = raw.replace(Regex("""(?<!\n)\n(?!\n)"""), " ")
        val html = proseNoBr(collapsedSingles)
        """<div class="abstract-block" style="padding:12px;border-left:3px solid var(--border); background:#6b728022; margin:12px 0;"><strong>Abstract.</strong>&nbsp;$html</div>"""
    }
    t = Regex("""\\paragraph\s*\{keyword\}\s*(.+)""", RegexOption.MULTILINE).replace(t) { m ->
        val kw = latexProseToHtmlWithMath(m.groupValues[1].trim())
        """<div class="ll-keywords" style="margin:0.75em 0;font-size:0.95em;opacity:0.9;text-align:left;"><strong>Keywords:</strong> $kw</div>"""
    }
    t = replaceCmd1ArgBalanced(t, "paragraph") { label ->
        val htmlLabel = latexProseToHtmlWithMath(label)
        "<p style=\"margin:0.75em 0;text-align:left;\"><strong>$htmlLabel</strong> "
    }
    return t
}

private fun renderTitlepageHtml(rawBody: String): String {
    val body = preprocessTitlepageBody(rawBody)
    val parts = body.split(Regex("""\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
    val styled = parts.mapIndexed { i, block ->
        if (block.startsWith("<div") || block.startsWith("<p")) return@mapIndexed block
        val stripped = stripOuterLatexGroupBraces(block)
        val html = latexProseToHtmlWithMath(stripped)
        if (html.isBlank()) return@mapIndexed ""
        val style = when (i) {
            0 -> "font-size:1.5em; font-weight:bold; margin:0 0 1em 0;"
            1 -> "font-size:1.1em; font-style:italic; margin:0.5em 0;"
            2 -> "font-size:1em; margin:0.5em 0; opacity:0.9;"
            else -> "font-size:1em; margin:0.5em 0; opacity:0.9; text-align:left;"
        }
        "<div style=\"$style\">$html</div>"
    }.filter { it.isNotEmpty() }.joinToString("")
    return """
        <div class="ll-titlepage" style="text-align:center; padding:2em 1em; margin:0 0 1.5em 0; border-bottom:1px solid var(--border);">
          $styled
        </div>
        """.trimIndent()
}

/**
 * Standard / KOMA `letter`: strip `\\begin{letter}...\\end{letter}`, optional `[...]`,
 * first braced recipient (if any), and render recipient as HTML so it is not left as a stray `{...}` fragment.
 * Inner body (with `\\opening`, `\\closing`, etc.) stays as LaTeX for later prose conversion.
 */
internal fun convertLetterEnvironment(s: String): String {
    val beginTok = "\\begin{letter}"
    val endTok = "\\end{letter}"
    val sb = StringBuilder(s.length + 64)
    var i = 0
    var guard = 0
    while (i < s.length && guard++ < 5000) {
        val j = s.indexOf(beginTok, i)
        if (j < 0) {
            sb.append(s, i, s.length)
            break
        }
        sb.append(s, i, j)
        var p = j + beginTok.length
        while (p < s.length && s[p].isWhitespace()) p++
        if (p < s.length && s[p] == '[') {
            var depth = 1
            p++
            while (p < s.length && depth > 0) {
                when (s[p]) {
                    '[' -> depth++
                    ']' -> depth--
                }
                p++
            }
        }
        while (p < s.length && s[p].isWhitespace()) p++
        var recipientBlock = ""
        if (p < s.length && s[p] == '{') {
            val close = findBalancedBrace(s, p)
            if (close > p) {
                val raw = s.substring(p + 1, close).trim()
                if (raw.isNotEmpty()) {
                    recipientBlock =
                        """<div class="ll-letter-to" style="margin:0 0 0.85em 0;opacity:.95;">${latexProseToHtmlWithMath(raw)}</div>"""
                }
                p = close + 1
            }
        }
        val endIdx = s.indexOf(endTok, p)
        if (endIdx < 0) {
            sb.append(s, j, s.length)
            break
        }
        val inner = s.substring(p, endIdx)
        sb.append(recipientBlock).append(inner.trimStart())
        i = endIdx + endTok.length
    }
    return sb.toString()
}

/** Convert abstract/center/theorem-like to HTML; drop unknown NON-math envs; keep math envs intact. */
internal fun sanitizeForMathJaxProse(bodyText: String): String {
        var s = bodyText

        s = convertTitlepage(s)
        s = convertPicturePutBlocks(s)
        s = convertLetterEnvironment(s)

        s = s.replace(
            Regex("""\\begin\{center\}(.+?)\\end\{center\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m -> """<div style="text-align:center;">${latexProseToHtmlWithMath(m.groupValues[1].trim())}</div>""" }

        s = s.replace(
            Regex("""\\begin\{abstract\}(.+?)\\end\{abstract\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m ->
            val raw = m.groupValues[1].trim()
            val collapsedSingles = raw.replace(Regex("""(?<!\n)\n(?!\n)"""), " ")
            val html = proseNoBr(collapsedSingles)

            val merged =
                if (Regex("""<p\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                    Regex("""(?i)(<p\b[^>]*>)""").replaceFirst(html, "${'$'}1<strong>Abstract.</strong>&nbsp;")
                } else {
                    "<strong>Abstract.</strong>&nbsp;$html"
                }

            """
    <div class="abstract-block" style="padding:12px;border-left:3px solid var(--border); background:#6b728022; margin:12px 0;">
      $merged
    </div>
    """.trimIndent()
        }

        val theoremLike = listOf("theorem","lemma","proposition","corollary","definition","remark","identity")
        for (env in theoremLike) {
            s = s.replace(
                Regex("""\\begin\{$env\}(?:\[(.*?)\])?(.+?)\\end\{$env\}""", RegexOption.DOT_MATCHES_ALL)
            ) { m ->
                val ttl = m.groupValues[1].trim()
                val content = m.groupValues[2].trim()
                val head = if (ttl.isNotEmpty()) "$env ($ttl)" else env
                """
      <div style="font-weight:600;margin-bottom:6px;text-transform:capitalize;">$head.</div>
      ${latexProseToHtmlWithMath(content)}
    """.trimIndent()
            }
        }

        // No convertAlignWithMultipleTagsToBlocks: that helper split multi-\\tag align into
        // \\[...\\begin{aligned}...\\]; MathJax 3 rejects \\tag inside nested aligned and preview broke.

        s = convertMinipagesToHtml(s)

        val mathEnvs =
            "(?:equation\\*?|align\\*?|aligned\\*?|aligned|gather\\*?|multline\\*?|flalign\\*?|alignat\\*?|bmatrix|pmatrix|vmatrix|Bmatrix|Vmatrix|smallmatrix|matrix|cases|split)"
        val keepEnvs =
            "(?:$mathEnvs|tabular|table|longtable|figure|center|tikzpicture|knot|tcolorbox|thebibliography|itemize|enumerate|description|multicols)"

        s = s.replace(Regex("""\\begin\{(?!$keepEnvs)\w+\}"""), "")
        s = s.replace(Regex("""\\end\{(?!$keepEnvs)\w+\}"""), "")

        return s
    }

/** `textpos` package: map `\\begin{textblock*}...\\end{textblock*}` to a footer-style block in HTML preview. */
internal fun convertTextblockStar(s: String): String {
    val rx = Regex(
        """\\begin\{textblock\*\}\s*\{[^}]*\}\s*\([^)]*\)([\s\S]*?)\\end\{textblock\*\}""",
        RegexOption.DOT_MATCHES_ALL,
    )
    return rx.replace(s) { m ->
        val inner = m.groupValues[1].trim()
        """<div class="ll-textblock" style="margin-top:14px;padding-top:10px;border-top:1px solid var(--border);font-size:0.88em;line-height:1.35;opacity:0.95;">${latexProseToHtmlWithMath(inner)}</div>"""
    }
}

internal fun convertSiunitx(s: String): String {
        var t = s
        t = t.replace(Regex("""\\num\{([^}]*)\}""")) { m ->
            val raw = m.groupValues[1].trim()
            val sci = Regex("""^\s*([+-]?\d+(?:\.\d+)?)[eE]([+-]?\d+)\s*$""").matchEntire(raw)
            if (sci != null) {
                val a = sci.groupValues[1]
                val b = sci.groupValues[2]
                "$a\\times 10^{${b}}"
            } else raw
        }
        t = t.replace(Regex("""\\si\{([^}]*)\}""")) { m ->
            val u = m.groupValues[1].replace(".", "\\,").replace("~", "\\,")
            "\\mathrm{$u}"
        }
        t = t.replace(Regex("""\\SI\{([^}]*)\}\{([^}]*)\}""")) { m ->
            val num  = m.groupValues[1]
            val unit = m.groupValues[2]
            "\\num{$num}\\,\\si{$unit}"
        }
        t = t.replace(Regex("""\\textasciitilde\{\}"""), "~")
            .replace(Regex("""\\textasciitilde"""), "~")
            .replace(Regex("""\\&"""), "&")
        return t
    }

/** Width inside `\\begin{minipage}{...}` → approximate % for HTML (e.g. `0.32\\textwidth` → 32). */
internal fun parseMinipageWidthPercent(widthTex: String): Double {
    val t = widthTex.trim()
    val fracText = Regex("""([\d.]+)\s*\\textwidth\b""").find(t)
    if (fracText != null) {
        return (fracText.groupValues[1].toDoubleOrNull() ?: 0.32) * 100.0
    }
    val fracLine = Regex("""([\d.]+)\s*\\linewidth\b""").find(t)
    if (fracLine != null) {
        return (fracLine.groupValues[1].toDoubleOrNull() ?: 0.32) * 100.0
    }
    val fracCol = Regex("""([\d.]+)\s*\\columnwidth\b""").find(t)
    if (fracCol != null) {
        return (fracCol.groupValues[1].toDoubleOrNull() ?: 0.32) * 100.0
    }
    return 33.0
}

private fun findMatchingEndMinipage(s: String, bodyStart: Int): Int {
    val beginTok = "\\begin{minipage}"
    val endTok = "\\end{minipage}"
    var depth = 1
    var pos = bodyStart
    while (pos < s.length) {
        val nb = s.indexOf(beginTok, pos)
        val ne = s.indexOf(endTok, pos)
        if (ne < 0) return -1
        if (nb >= 0 && nb < ne) {
            depth++
            pos = nb + beginTok.length
        } else {
            depth--
            if (depth == 0) return ne
            pos = ne + endTok.length
        }
    }
    return -1
}

/**
 * Parse `\\begin{minipage}[opt]{width}...\\end{minipage}` at index [j].
 * Returns (inner HTML via [latexProseToHtmlWithMath], width %, index after closing) or null.
 */
internal fun parseOneMinipageColumn(s: String, j: Int): Triple<String, Double, Int>? {
    val beginTok = "\\begin{minipage}"
    val endTok = "\\end{minipage}"
    if (j < 0 || j + beginTok.length > s.length || !s.regionMatches(j, beginTok, 0, beginTok.length)) {
        return null
    }
    var k = j + beginTok.length
    if (k < s.length && s[k] == '[') {
        val rb = s.indexOf(']', k)
        if (rb < 0) return null
        k = rb + 1
    }
    while (k < s.length && s[k].isWhitespace()) k++
    if (k >= s.length || s[k] != '{') return null
    val wClose = findBalancedBrace(s, k)
    if (wClose < 0) return null
    val widthTex = s.substring(k + 1, wClose).trim()
    val pct = parseMinipageWidthPercent(widthTex).coerceIn(10.0, 100.0)
    val bodyStart = wClose + 1
    val endIdx = findMatchingEndMinipage(s, bodyStart)
    if (endIdx < 0) return null
    var inner = s.substring(bodyStart, endIdx).trim()
    inner = Regex("""^\\centering\b\s*""").replace(inner, "")
    val html = latexProseToHtmlWithMath(inner)
    return Triple(html, pct, endIdx + endTok.length)
}

private fun fmtPct(p: Double): String = String.format(Locale.US, "%.2f", p)

/**
 * Turn `minipage` rows (often `0.32\\textwidth` + `\\hfill`) into HTML flex or inline-block
 * so multiple TikZ figures sit side by side. Raw `minipage` is otherwise stripped by [sanitizeForMathJaxProse].
 */
internal fun convertMinipagesToHtml(s: String): String {
    val beginTok = "\\begin{minipage}"
    val out = StringBuilder(s.length + 256)
    val row = mutableListOf<Pair<String, Double>>()
    val betweenRowOk = Regex("""\s*(\\hfill\s*)*""")

    fun flushRow() {
        if (row.isEmpty()) return
        if (row.size == 1) {
            val (h, pct) = row[0]
            out.append(
                """<div class="ll-minipage" style="display:inline-block;vertical-align:top;width:${fmtPct(pct)}%;max-width:100%;box-sizing:border-box;text-align:center;padding:4px;">$h</div>""",
            )
        } else {
            out.append(
                """<div class="ll-minipage-row" style="display:flex;flex-wrap:wrap;gap:8px;justify-content:space-between;align-items:flex-start;width:100%;margin:10px 0;box-sizing:border-box;">""",
            )
            for ((h, pct) in row) {
                out.append(
                    """<div style="flex:1 1 ${fmtPct(pct)}%;min-width:min(100%,140px);max-width:100%;text-align:center;box-sizing:border-box;padding:2px;">$h</div>""",
                )
            }
            out.append("</div>")
        }
        row.clear()
    }

    var i = 0
    while (i < s.length) {
        val j = s.indexOf(beginTok, i)
        if (j < 0) {
            flushRow()
            out.append(s, i, s.length)
            break
        }
        val between = s.substring(i, j)
        if (row.isNotEmpty()) {
            if (!betweenRowOk.matches(between)) {
                flushRow()
                out.append(between)
            }
        } else {
            out.append(between)
        }
        val parsed = parseOneMinipageColumn(s, j)
        if (parsed == null) {
            flushRow()
            out.append(beginTok)
            i = j + beginTok.length
            continue
        }
        val (html, pct, nextI) = parsed
        row.add(html to pct)
        i = nextI
    }
    flushRow()
    var t = out.toString()
    t = Regex("""\\noindent\s*""").replace(t, "")
    return t
}