package com.omariskandarani.livelatex.html

import java.io.File
import java.util.Base64
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.text.RegexOption

import com.omariskandarani.livelatex.html.latexProseToHtmlWithMath

/**
 * LaTeX→HTML utility functions. Part of LatexHtml multi-file object.
 */

internal fun fixInlineBoundarySpaces(html: String): String =
        Regex(
            """</(?:strong|em|u|small|code|span)>(?=(?:<(?!/)|[A-Za-z0-9(]))""",
            RegexOption.IGNORE_CASE
        ).replace(html) { it.value + " " }

internal data class TitleMeta(
        val title: String?,
        val authors: String?,        // raw \author{...} content
        val affiliations: List<String>,  // raw \affil, \affiliation, \institute content
        val dateRaw: String?         // raw \date{...} content
    )

internal fun findLastCmdArg(src: String, cmd: String): String? {
        val rx = Regex("""\\$cmd\s*\{""")
        var pos = 0
        var last: String? = null
        while (true) {
            val m = rx.find(src, pos) ?: break
            val open = m.range.last
            val close = findBalancedBrace(src, open)
            if (close < 0) break
            last = src.substring(open + 1, close)
            pos = close + 1
        }
        return last
    }

internal fun findAllCmdArgs(src: String, cmd: String): List<String> {
        val rx = Regex("""\\$cmd\s*\{""")
        val results = mutableListOf<String>()
        var pos = 0
        while (true) {
            val m = rx.find(src, pos) ?: break
            val open = m.range.last
            val close = findBalancedBrace(src, open)
            if (close < 0) break
            results += src.substring(open + 1, close).trim()
            pos = close + 1
        }
        return results
    }

internal fun extractTitleMeta(srcNoComments: String): TitleMeta {
        val ttl = findLastCmdArg(srcNoComments, "title")
        val aut = findLastCmdArg(srcNoComments, "author")
        val dat = findLastCmdArg(srcNoComments, "date")
        val affils = mutableListOf<String>()
        affils += findAllCmdArgs(srcNoComments, "affil")
        affils += findAllCmdArgs(srcNoComments, "affiliation")
        affils += findAllCmdArgs(srcNoComments, "institute")
        return TitleMeta(ttl, aut, affils.distinct().filter { it.isNotEmpty() }, dat)
    }

internal fun renderDate(dateRaw: String?): String? {
        if (dateRaw == null) return null
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
        val trimmed = dateRaw.trim()
        if (trimmed.isEmpty()) return ""
        val replaced = trimmed.replace("""\today""", today)
        return latexProseToHtmlWithMath(replaced)
    }

internal fun splitAuthors(raw: String): List<String> =
        Regex("""\\and""").split(raw).map { it.trim() }.filter { it.isNotEmpty() }

internal fun processThanksWithin(text: String, notes: MutableList<String>): String {
        var s = text
        while (true) {
            val i = s.indexOf("""\thanks{"""); if (i < 0) break
            val open = s.indexOf('{', i + 1); if (open < 0) break
            val close = findBalancedBrace(s, open); if (close < 0) break
            val content = s.substring(open + 1, close)
            notes += latexProseToHtmlWithMath(content)
            val n = notes.size
            s = s.substring(0, i) + "<sup>$n</sup>" + s.substring(close + 1)
        }
        return s
    }

internal fun buildMakTitleHtml(meta: TitleMeta): String {
        val notes = mutableListOf<String>()

        val titleHtml = meta.title?.let { latexProseToHtmlWithMath(processThanksWithin(it, notes)) } ?: ""

        val authorsHtml = meta.authors?.let { raw ->
            val parts = splitAuthors(raw).map { p ->
                val withMarks = processThanksWithin(p, notes)
                """<span class="author">${latexProseToHtmlWithMath(withMarks)}</span>"""
            }
            parts.joinToString("""<span class="author-sep" style="padding:0 .6em;opacity:.5;">·</span>""")
        } ?: ""

        val affiliationsHtml = if (meta.affiliations.isEmpty()) "" else {
            val items = meta.affiliations.map { latexProseToHtmlWithMath(it) }
                .joinToString("""<span style="padding:0 .4em;opacity:.6;">|</span>""")
            """<div class="affiliations" style="opacity:.85;margin:.15em 0 0 0;font-size:.95em;">$items</div>"""
        }

        val dateHtml = renderDate(meta.dateRaw) ?: ""

        val notesHtml = if (notes.isEmpty()) "" else {
            val lis = notes.mapIndexed { idx, txt -> """<li value="${idx+1}">$txt</li>""" }.joinToString("")
            """<ol class="title-notes" style="margin:.6em 0 0 1.2em;font-size:.95em;">$lis</ol>"""
        }

        return """
<div class="maketitle" style="margin:8px 0 16px;border-bottom:1px solid var(--border);padding-bottom:8px;">
  ${if (titleHtml.isNotEmpty()) """<h1 style="margin:0 0 .25em 0;">$titleHtml</h1>""" else ""}
  ${if (authorsHtml.isNotEmpty()) """<div class="authors" style="margin:.2em 0;">$authorsHtml</div>""" else ""}
  $affiliationsHtml
  ${if (dateHtml.isNotEmpty()) """<div class="date" style="opacity:.8;margin-top:.15em;">$dateHtml</div>""" else ""}
  $notesHtml
</div>
""".trim()
    }

internal fun convertMakeTitle(body: String, meta: TitleMeta): String =
        body.replace(Regex("""\\maketitle\b""")) { buildMakTitleHtml(meta) }

/** Text chunk or HTML tag from [splitHtmlTagsRespectingMath]. */
internal sealed class HtmlMathSplitPiece {
    data class Text(val value: String) : HtmlMathSplitPiece()
    data class Tag(val value: String) : HtmlMathSplitPiece()
}

/**
 * Split HTML into alternating text and tag segments, ignoring `<`/`>` inside math
 * (`$…$`, `$$…$$`, `\[…\]`, `\(…\)`, and [MATH_ENVS] blocks).
 */
internal fun splitHtmlTagsRespectingMath(html: String): List<HtmlMathSplitPiece> {
    val out = mutableListOf<HtmlMathSplitPiece>()
    val textSb = StringBuilder()
    val tagSb = StringBuilder()

    var inHtmlTag = false
    var attrQuote: Char? = null

    var inDollar = false
    var inDoubleDollar = false
    var inBracket = false
    var inParen = false
    var envDepth = 0

    fun startsAt(idx: Int, tok: String): Boolean =
        idx + tok.length <= html.length && html.regionMatches(idx, tok, 0, tok.length)

    fun inAnyMath(): Boolean =
        inDollar || inDoubleDollar || inBracket || inParen || envDepth > 0

    fun flushText() {
        if (textSb.isNotEmpty()) {
            out.add(HtmlMathSplitPiece.Text(textSb.toString()))
            textSb.clear()
        }
    }

    fun flushTag() {
        if (tagSb.isNotEmpty()) {
            out.add(HtmlMathSplitPiece.Tag(tagSb.toString()))
            tagSb.clear()
        }
    }

    var i = 0
    while (i < html.length) {
        if (!inAnyMath() && !inHtmlTag && startsAt(i, "<!--")) {
            flushText()
            val end = html.indexOf("-->", i + 4)
            val commentEnd = if (end >= 0) end + 3 else html.length
            out.add(HtmlMathSplitPiece.Tag(html.substring(i, commentEnd)))
            i = commentEnd
            continue
        }

        if (!inAnyMath() && !inHtmlTag && html[i] == '<') {
            flushText()
            inHtmlTag = true
            attrQuote = null
            tagSb.append('<')
            i++
            continue
        }

        if (inHtmlTag) {
            val c = html[i]
            tagSb.append(c)
            i++
            if (attrQuote == null) {
                if (c == '"' || c == '\'') attrQuote = c
                else if (c == '>') {
                    inHtmlTag = false
                    flushTag()
                }
            } else if (c == attrQuote) {
                attrQuote = null
            }
            continue
        }

        if (!inBracket && !inParen) {
            if (startsAt(i, "$$")) {
                inDoubleDollar = !inDoubleDollar
                textSb.append("$$")
                i += 2
                continue
            }
            if (!inDoubleDollar && html[i] == '$' && !isEscaped(html, i)) {
                inDollar = !inDollar
                textSb.append('$')
                i++
                continue
            }
        }
        if (!inDollar && !inDoubleDollar) {
            if (startsAt(i, "\\[")) {
                inBracket = true
                textSb.append("\\[")
                i += 2
                continue
            }
            if (startsAt(i, "\\]") && inBracket) {
                inBracket = false
                textSb.append("\\]")
                i += 2
                continue
            }
            if (startsAt(i, "\\(")) {
                inParen = true
                textSb.append("\\(")
                i += 2
                continue
            }
            if (startsAt(i, "\\)") && inParen) {
                inParen = false
                textSb.append("\\)")
                i += 2
                continue
            }
            if (startsAt(i, "\\begin{")) {
                val nameClose = html.indexOf('}', i + 7)
                val name = if (nameClose > i + 7) html.substring(i + 7, nameClose) else ""
                if (name in MATH_ENVS) {
                    envDepth++
                    textSb.append(html, i, nameClose + 1)
                    i = nameClose + 1
                    continue
                }
            }
            if (startsAt(i, "\\end{") && envDepth > 0) {
                val nameClose = html.indexOf('}', i + 5)
                val name = if (nameClose > i + 5) html.substring(i + 5, nameClose) else ""
                if (name in MATH_ENVS) {
                    envDepth--
                    textSb.append(html, i, nameClose + 1)
                    i = nameClose + 1
                    continue
                }
            }
        }

        textSb.append(html[i])
        i++
    }

    if (inHtmlTag) flushTag() else flushText()
    return out
}

/** Escape raw `<`/`>` inside a preserved math fragment for safe HTML embedding. */
internal fun escapeAngleBracketsInMathFragment(fragment: String): String {
    val sb = StringBuilder(fragment.length + 8)
    var i = 0
    while (i < fragment.length) {
        when {
            fragment.startsWith("&lt;", i) -> { sb.append("&lt;"); i += 4 }
            fragment.startsWith("&gt;", i) -> { sb.append("&gt;"); i += 4 }
            fragment.startsWith("&amp;", i) -> { sb.append("&amp;"); i += 5 }
            fragment[i] == '<' -> { sb.append("&lt;"); i++ }
            fragment[i] == '>' -> { sb.append("&gt;"); i++ }
            else -> { sb.append(fragment[i]); i++ }
        }
    }
    return sb.toString()
}

internal fun applyInlineFormattingOutsideTags(html: String): String {
        // Protect tables and figures from math-aware rewrites (unpaired `$` must not escape them).
        val protectRx = Regex("(?is)(<table\\b.*?</table>|<figure\\b.*?</figure>)")
        val segments = protectRx.split(html)
        val protectedBlocks = protectRx.findAll(html).map { it.value }.toList()

        val out = StringBuilder(html.length + 256)
        for (i in segments.indices) {
            out.append(applyInlineFormattingOutsideTags_NoTables(segments[i]))
            if (i < protectedBlocks.size) out.append(protectedBlocks[i])
        }
        return out.toString()
    }

internal fun applyInlineFormattingOutsideTags_NoTables(html: String): String {
        val out = StringBuilder(html.length + 256)
        for (piece in splitHtmlTagsRespectingMath(html)) {
            when (piece) {
                is HtmlMathSplitPiece.Text -> out.append(latexProseToHtmlWithMath(piece.value))
                is HtmlMathSplitPiece.Tag -> out.append(piece.value)
            }
        }
        return out.toString()
    }

internal fun proseNoBr(s: String): String =
        latexProseToHtmlWithMath(s).replace(Regex("(?i)<br\\s*/?>\\s*"), " ")

internal fun htmlEscapeAll(s: String): String =
        s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

internal fun replaceTextSymbols(t0: String): String {
        var t = t0
        t = t.replace(Regex("""\\textellipsis\b"""),     "…")
            .replace(Regex("""\\textquotedblleft\b"""), "\u201C")
            .replace(Regex("""\\textquotedblright\b"""), "\u201D")
            .replace(Regex("""\\textquoteleft\b"""),    "'")
            .replace(Regex("""\\textquoteright\b"""),   "'")
            .replace(Regex("""\\textemdash\b"""),       "—")
            .replace(Regex("""\\textendash\b"""),       "–")
        t = t.replace(Regex("""\\textfractionsolidus\b"""), "⁄")
            .replace(Regex("""\\textdiv\b"""),              "÷")
            .replace(Regex("""\\texttimes\b"""),            "×")
            .replace(Regex("""\\textminus\b"""),            "−")
            .replace(Regex("""\\textpm\b"""),               "±")
            .replace(Regex("""\\textsurd\b"""),             "√")
            .replace(Regex("""\\textlnot\b"""),             "¬")
            .replace(Regex("""\\textasteriskcentered\b"""), "∗")
            .replace(Regex("""\\textbullet\b"""),           "•")
            .replace(Regex("""\\textperiodcentered\b"""),   "·")
            .replace(Regex("""\\textdaggerdbl\b"""),        "‡")
            .replace(Regex("""\\textdagger\b"""),           "†")
            .replace(Regex("""\\textsection\b"""),          "§")
            .replace(Regex("""\\textparagraph\b"""),        "¶")
            .replace(Regex("""\\textbardbl\b"""),           "‖")
            .replace(Regex("""\\textbackslash\b"""),        "&#92;")
        return t
}

/** Token planted on TeX body newlines; [materializeSourceLineAnchors] turns these into `.syncline` spans. */
internal const val SOURCE_LINE_ANCHOR_RE = """%%LLA\{(\d+)\}%%"""

/**
 * Plant `%%LLA{N}%%` markers on safe TeX newlines in the document body (before HTML collapse).
 * [N] = [absOffset] + body newline index so abs matches editor 1-based lines.
 */
internal fun plantSourceLineAnchors(s: String, absOffset: Int, everyN: Int = 1): String {
        // Skip markers inside math and row/list envs so & / \item / \\ parsers keep working.
        val skipEnvs = setOf(
            "equation","equation*","align","align*","aligned","aligned*",
            "gather","gather*","multline","multline*","flalign","flalign*",
            "alignat","alignat*","bmatrix","pmatrix","vmatrix","Bmatrix","Vmatrix",
            "smallmatrix","matrix","cases","split",
            "tabular","tabular*","array",
            "table","table*","longtable",
            "itemize","enumerate","description",
        )

        var inDollar = false
        var inDoubleDollar = false
        var inBracket = false
        var inParen = false
        var envDepth = 0

        fun startsAt(idx: Int, tok: String) =
            idx + tok.length <= s.length && s.regionMatches(idx, tok, 0, tok.length)

        var i = 0
        var line = 0
        val sb = StringBuilder(s.length + 1024)

        while (i < s.length) {
            if (!inBracket && !inParen) {
                if (startsAt(i, "$$")) { inDoubleDollar = !inDoubleDollar; sb.append("$$"); i += 2; continue }
                if (!inDoubleDollar && s[i] == '$') {
                    val prev = if (i > 0) s[i - 1] else ' '
                    if (prev != '\\') { inDollar = !inDollar; sb.append('$'); i += 1; continue }
                }
            }
            if (!inDollar && !inDoubleDollar) {
                if (startsAt(i, "\\[")) { inBracket = true;  sb.append("\\["); i += 2; continue }
                if (startsAt(i, "\\]") && inBracket) { inBracket = false; sb.append("\\]"); i += 2; continue }
                if (startsAt(i, "\\(")) { inParen = true;   sb.append("\\("); i += 2; continue }
                if (startsAt(i, "\\)") && inParen) { inParen = false;  sb.append("\\)"); i += 2; continue }
                if (startsAt(i, "\\begin{")) {
                    val end  = s.indexOf('}', i + 7)
                    val name = if (end > 0) s.substring(i + 7, end) else ""
                    if (name in skipEnvs) envDepth++
                    sb.append(s, i, (end + 1).coerceAtMost(s.length))
                    i = (end + 1).coerceAtMost(s.length)
                    continue
                }
                if (startsAt(i, "\\end{")) {
                    val end  = s.indexOf('}', i + 5)
                    val name = if (end > 0) s.substring(i + 5, end) else ""
                    if (name in skipEnvs && envDepth > 0) envDepth--
                    sb.append(s, i, (end + 1).coerceAtMost(s.length))
                    i = (end + 1).coerceAtMost(s.length)
                    continue
                }
            }

            val ch = s[i]
            if (ch == '\n') {
                line++
                sb.append('\n')
                val safeSpot = !inDollar && !inDoubleDollar && !inBracket && !inParen && envDepth == 0
                if (safeSpot && everyN > 0 && (line % everyN == 0)) {
                    // Own line so converters that match ^\s*\\item / cell starts keep working.
                    sb.append("%%LLA{${absOffset + line}}%%\n")
                }
                i++
                continue
            }
            sb.append(ch); i++
        }
        return sb.toString()
}

/** Replace surviving `%%LLA{N}%%` markers with invisible `.syncline` spans. */
internal fun materializeSourceLineAnchors(html: String): String =
    html.replace(Regex(SOURCE_LINE_ANCHOR_RE)) { m ->
        """<span class="syncline" data-abs="${m.groupValues[1]}"></span>"""
    }

/**
 * Insert invisible line anchors every Nth source line (HTML-stage fallback).
 */
internal fun injectLineAnchors(s: String, absOffset: Int, everyN: Int = 3): String {
        val mathEnvs = setOf(
            "equation","equation*","align","align*","aligned","aligned*",
            "gather","gather*","multline","multline*","flalign","flalign*",
            "alignat","alignat*","bmatrix","pmatrix","vmatrix","Bmatrix","Vmatrix",
            "smallmatrix","matrix","cases","split"
        )

        var inHtmlTag = false
        var attrQuote: Char? = null
        var inHtmlComment = false
        var inVerbatimTag = false
        var verbatimTagName = ""

        fun isVerbatimOpenTag(name: String) = when (name.lowercase()) {
            "script","style","pre","code","textarea" -> true
            else -> false
        }

        var inDollar = false
        var inDoubleDollar = false
        var inBracket = false
        var inParen = false
        var envDepth = 0

        fun startsAt(idx: Int, tok: String) =
            idx + tok.length <= s.length && s.regionMatches(idx, tok, 0, tok.length)

        fun readHtmlTagName(from: Int): Pair<String, Int> {
            var i = from
            if (i < s.length && s[i] == '<') i++
            if (i < s.length && s[i] == '/') i++
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace() || c == '>' || c == '/' ) break
                i++
            }
            return s.substring(start, i).lowercase() to i
        }

        var i = 0
        var line = 0
        val sb = StringBuilder(s.length + 1024)

        while (i < s.length) {
            if (!inHtmlComment) {
                if (startsAt(i, "<!--")) {
                    inHtmlComment = true
                    sb.append("<!--"); i += 4
                    continue
                }
            } else {
                val end = s.indexOf("-->", i)
                if (end >= 0) {
                    sb.append(s, i, end + 3)
                    i = end + 3
                } else {
                    sb.append(s.substring(i))
                    i = s.length
                }
                continue
            }

            if (!inHtmlTag && startsAt(i, "<")) {
                val (tag, afterName) = readHtmlTagName(i)
                inHtmlTag = true
                attrQuote = null
                if (tag.isNotEmpty() && isVerbatimOpenTag(tag)) {
                    inVerbatimTag = true
                    verbatimTagName = tag
                }
                sb.append('<'); i += 1
                continue
            }

            if (inHtmlTag) {
                val c = s[i]
                sb.append(c); i++
                if (attrQuote == null) {
                    if (c == '"' || c == '\'') attrQuote = c
                    else if (c == '>') {
                        inHtmlTag = false
                        val lookBack = 64.coerceAtMost(sb.length)
                        val tail = sb.substring(sb.length - lookBack)
                        val closeTag = Regex("</\\s*([a-zA-Z0-9:-]+)\\s*>").find(tail)?.groups?.get(1)?.value?.lowercase()
                        if (inVerbatimTag && closeTag == verbatimTagName) {
                            inVerbatimTag = false
                            verbatimTagName = ""
                        }
                    }
                } else {
                    if (c == attrQuote) attrQuote = null
                }
                continue
            }

            if (inVerbatimTag) {
                val needle = "</$verbatimTagName>"
                val at = s.indexOf(needle, i, ignoreCase = true)
                if (at < 0) {
                    sb.append(s.substring(i))
                    i = s.length
                } else {
                    sb.append(s, i, at)
                    i = at
                }
                continue
            }

            if (!inBracket && !inParen) {
                if (startsAt(i, "$$")) { inDoubleDollar = !inDoubleDollar; sb.append("$$"); i += 2; continue }
                if (!inDoubleDollar && s[i] == '$') {
                    val prev = if (i > 0) s[i - 1] else ' '
                    if (prev != '\\') { inDollar = !inDollar; sb.append('$'); i += 1; continue }
                }
            }
            if (!inDollar && !inDoubleDollar) {
                if (startsAt(i, "\\[")) { inBracket = true;  sb.append("\\["); i += 2; continue }
                if (startsAt(i, "\\]") && inBracket) { inBracket = false; sb.append("\\]"); i += 2; continue }
                if (startsAt(i, "\\(")) { inParen = true;   sb.append("\\("); i += 2; continue }
                if (startsAt(i, "\\)") && inParen) { inParen = false;  sb.append("\\)"); i += 2; continue }
                if (startsAt(i, "\\begin{")) {
                    val end  = s.indexOf('}', i + 7)
                    val name = if (end > 0) s.substring(i + 7, end) else ""
                    if (name in mathEnvs) envDepth++
                    sb.append(s, i, (end + 1).coerceAtMost(s.length))
                    i = (end + 1).coerceAtMost(s.length)
                    continue
                }
                if (startsAt(i, "\\end{")) {
                    val end  = s.indexOf('}', i + 5)
                    val name = if (end > 0) s.substring(i + 5, end) else ""
                    if (name in mathEnvs && envDepth > 0) envDepth--
                    sb.append(s, i, (end + 1).coerceAtMost(s.length))
                    i = (end + 1).coerceAtMost(s.length)
                    continue
                }
            }

            val ch = s[i]
            if (ch == '\n') {
                line++
                sb.append('\n')
                val safeSpot = !inDollar && !inDoubleDollar && !inBracket && !inParen &&
                    envDepth == 0 && !inHtmlTag && !inHtmlComment && !inVerbatimTag && attrQuote == null
                if (safeSpot && (line % everyN == 0)) {
                    val absLine = absOffset + line
                    sb.append("<span class=\"syncline\" data-abs=\"$absLine\"></span>")
                }
                i++
                continue
            }
            sb.append(ch); i++
        }
        return sb.toString()
    }

internal fun toFileUrl(f: File): String = f.toURI().toString()

/** Max size (bytes) for embedding images as data: URL so they load in CEF preview (file: can be blocked). */
private const val MAX_DATA_URL_IMAGE_BYTES = 512 * 1024

private fun mimeForImagePath(path: String): String = when {
    path.endsWith(".png", true) -> "image/png"
    path.endsWith(".jpg", true) -> "image/jpeg"
    path.endsWith(".jpeg", true) -> "image/jpeg"
    path.endsWith(".gif", true) -> "image/gif"
    path.endsWith(".webp", true) -> "image/webp"
    path.endsWith(".svg", true) -> "image/svg+xml"
    else -> "image/png"
}

/** Result of resolving an image path for HTML preview (never a raw PDF/EPS URL). */
internal sealed class PreviewImageResult {
    data class Ready(val url: String) : PreviewImageResult()
    data class Unavailable(val html: String) : PreviewImageResult()
}

/** Consistent placeholder when a figure/TikZ/PDF cannot be shown in the browser preview. */
internal fun figureUnavailablePlaceholder(
    label: String,
    reason: String,
    sourcePath: String,
    logPath: String? = null,
): String {
    val logLine = if (!logPath.isNullOrBlank()) {
        """<div style="opacity:.85;margin-top:4px;font-size:11px;">Log: ${htmlEscapeAll(logPath.replace("\\", "/"))}</div>"""
    } else ""
    return """
<span class="ll-figure-unavailable" style="display:block;margin:8px 0;padding:8px 12px;background:#fef2f2;color:#991b1b;font-size:12px;border-radius:4px;border:1px solid #fecaca;">
  <strong>${htmlEscapeAll(label)}</strong> ${htmlEscapeAll(reason)}
  <div style="opacity:.85;margin-top:4px;font-size:11px;">Source: ${htmlEscapeAll(sourcePath.replace("\\", "/"))}</div>
  $logLine
</span>""".trimIndent()
}

internal fun webImageResultToUrl(result: LatexHtmlTikz.WebImageResult): String =
    imageUrlForRasterOrVectorFile(result.file)

/** Resolve LaTeX figure path to a preview-safe URL or placeholder HTML. */
internal fun resolveImageForPreview(path: String, baseDirFallback: String = "figures"): PreviewImageResult {
    val p = path.trim().replace('\\', '/')
    if (p.isEmpty()) {
        return PreviewImageResult.Unavailable(
            figureUnavailablePlaceholder("[Figure unavailable]", "Empty image path.", path),
        )
    }
    if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("data:")) {
        return PreviewImageResult.Ready(p)
    }

    val baseDir = currentBaseDir?.let { File(it) } ?: File("")
    val abs = File(p)
    if (abs.isAbsolute && abs.exists()) return resolveExistingImageFile(abs, p)

    val hasExt = p.contains('.')
    val rasterExts = listOf(".png", ".jpg", ".jpeg", ".svg", ".gif", ".webp")
    val vectorExts = listOf(".pdf", ".eps")

    fun existingWithExt(f: File): File? {
        if (f.exists()) return f
        if (hasExt) return null
        for (e in rasterExts + vectorExts) {
            val c = File(f.parentFile ?: baseDir, f.name + e)
            if (c.exists()) return c
        }
        return null
    }

    var found: File? = existingWithExt(File(baseDir, p))
        ?: existingWithExt(File(baseDir, "figures${File.separator}$p"))
    if (found == null) {
        var ancestor = baseDir.parentFile
        var depth = 0
        while (ancestor != null && depth < 15) {
            found = existingWithExt(File(ancestor, p))
                ?: existingWithExt(File(ancestor, "figures${File.separator}$p"))
            if (found != null) break
            ancestor = ancestor.parentFile
            depth++
        }
    }

    if (found != null) return resolveExistingImageFile(found, path)

    val fallback = if (hasExt) File(baseDir, p) else File(baseDir, p + rasterExts.first())
    return PreviewImageResult.Unavailable(
        figureUnavailablePlaceholder(
            "[Figure not found]",
            "Could not locate image on disk.",
            fallback.absolutePath,
        ),
    )
}

private fun resolveExistingImageFile(file: File, originalPath: String): PreviewImageResult {
    val ext = file.extension.lowercase()
    if (ext in setOf("pdf", "eps")) {
        val converted = LatexHtmlTikz.convertPdfToWebImage(file)
        if (converted != null) {
            return PreviewImageResult.Ready(webImageResultToUrl(converted))
        }
        return PreviewImageResult.Unavailable(
            figureUnavailablePlaceholder(
                "[PDF figure unavailable]",
                "PDF found but could not be converted to SVG/PNG (need dvisvgm/pdf2svg or pdftoppm/magick).",
                file.absolutePath,
                LatexHtmlTikz.tikzCacheDir().absolutePath,
            ),
        )
    }
    return PreviewImageResult.Ready(imageUrlForRasterOrVectorFile(file))
}

/** Prefer data: URL for small local raster/SVG files so CEF preview shows them (file: often blocked). */
private fun imageUrlForRasterOrVectorFile(f: File): String {
    if (!f.exists() || !f.isFile) return toFileUrl(f)
    if (f.length() > MAX_DATA_URL_IMAGE_BYTES) return toFileUrl(f)
    val ext = f.extension.lowercase()
    if (ext !in setOf("png", "jpg", "jpeg", "svg", "gif", "webp")) return toFileUrl(f)
    return try {
        val bytes = f.readBytes()
        val mime = mimeForImagePath(f.name)
        "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    } catch (_: Exception) {
        toFileUrl(f)
    }
}

/** @deprecated Prefer [resolveImageForPreview]; kept for callers that only need a URL string. */
internal fun resolveImagePath(path: String, baseDirFallback: String = "figures"): String =
    when (val r = resolveImageForPreview(path, baseDirFallback)) {
        is PreviewImageResult.Ready -> r.url
        is PreviewImageResult.Unavailable -> ""
    }

/** Legacy name used internally — delegates to [imageUrlForRasterOrVectorFile]. */
private fun imageUrlForFile(f: File): String = imageUrlForRasterOrVectorFile(f)

internal fun convertIncludeGraphics(latex: String): String {
        val rx = Regex("""\\includegraphics(\[.*?\])?\{([^}]+)\}""")
        return rx.replace(latex) { match ->
            val opts = match.groups[1]?.value ?: ""
            val path = match.groups[2]?.value ?: ""
            when (val resolved = resolveImageForPreview(path)) {
                is PreviewImageResult.Ready -> {
                    val widthMatch = Regex("width=([0-9.]+)\\\\?\\w*").find(opts)
                    val width = widthMatch?.groups?.get(1)?.value ?: ""
                    val style = if (width.isNotEmpty()) {
                        " style=\"max-width:${(width.toFloatOrNull()?.let { it * 100 } ?: 70).toInt()}%\""
                    } else {
                        " style=\"max-width:70%\""
                    }
                    """<img src="${resolved.url}" alt="figure"$style>"""
                }
                is PreviewImageResult.Unavailable -> resolved.html
            }
        }
    }

internal fun includeGraphicsStyle(options: String): String {
        val mWidth  = Regex("""width\s*=\s*([0-9]*\.?[0-9]+)\\linewidth""").find(options)
        if (mWidth != null) {
            val pct = (mWidth.groupValues[1].toDoubleOrNull() ?: 1.0) * 100.0
            return "max-width:${pct.coerceIn(1.0,100.0)}%;height:auto;"
        }
        val absW = Regex("""width\s*=\s*([0-9]*\.?[0-9]+)(cm|mm|pt|px)""").find(options)
        if (absW != null) {
            val w = absW.groupValues[1]
            val unit = absW.groupValues[2]
            return "width:${w}${unit};height:auto;max-width:100%;"
        }
        val scale = Regex("""scale\s*=\s*([0-9]*\.?[0-9]+)""").find(options)?.groupValues?.get(1)?.toDoubleOrNull()
        if (scale != null) {
            val pct = (scale * 100.0).coerceIn(1.0, 500.0)
            return "max-width:${pct}%;height:auto;"
        }
        return "max-width:100%;height:auto;"
    }
