package com.omariskandarani.livelatex.html

/**
 * Builds a source map between merged LaTeX body text and rendered HTML prose,
 * then injects `<span class="llsrc" data-s="…" data-e="…">` wrappers for selection sync.
 *
 * Limitations (by design): math/TikZ regions are skipped; repeated prose tokens align sequentially.
 */
object SourceMapBuilder {

    data class TextSegment(val start: Int, val end: Int, val plain: String)

    data class MappedSegment(
        val srcStart: Int,
        val srcEnd: Int,
        val htmlStart: Int,
        val htmlEnd: Int,
    )

    /** Result of [applySourceMap]: HTML with spans + compact JSON for the preview page. */
    data class SourceMapResult(val html: String, val json: String)

    private val PROSE_ARG_CMDS = setOf(
        "textbf", "emph", "textit", "itshape", "underline", "uline", "texttt", "textsf", "textrm",
        "small", "footnotesize", "textsuperscript", "textsubscript", "mbox", "fbox",
        "section", "subsection", "subsubsection", "paragraph", "chapter",
        "caption", "emph", "href", "texorpdfstring",
        "title", "author", "date",
        "item", "bibitem",
    )

    private val REF_CITE_CMDS = setOf("ref", "eqref", "cite", "citep", "citet", "pageref", "autoref")

    /**
     * Align visible prose, inject `.llsrc` spans, return JSON array:
     * `[{s:srcStart,e:srcEnd,h0:htmlStart,h1:htmlEnd}, …]`
     */
    fun applySourceMap(html: String, mergedLatex: String): SourceMapResult {
        val bodyStart = bodyStartOffset(mergedLatex)
        val bodyEnd = bodyEndOffset(mergedLatex)
        val body = if (bodyStart in 0 until bodyEnd) mergedLatex.substring(bodyStart, bodyEnd) else mergedLatex

        val srcSegments = extractLatexVisibleText(body, baseOffset = bodyStart)
        val htmlSegments = extractHtmlVisibleText(html)
        val mapped = alignSegments(srcSegments, htmlSegments)

        val injected = injectSpans(html, mapped)
        val json = mapped.joinToString(prefix = "[", postfix = "]") { m ->
            """{"s":${m.srcStart},"e":${m.srcEnd},"h0":${m.htmlStart},"h1":${m.htmlEnd}}"""
        }
        return SourceMapResult(injected, json)
    }

    fun bodyStartOffset(mergedLatex: String): Int {
        val begin = mergedLatex.indexOf(BEGIN_DOCUMENT)
        return if (begin >= 0) begin + BEGIN_DOCUMENT.length else 0
    }

    private fun bodyEndOffset(mergedLatex: String): Int {
        val end = mergedLatex.lastIndexOf(END_DOCUMENT)
        return if (end >= 0) end else mergedLatex.length
    }

    /** Extract contiguous visible prose runs from LaTeX body (math/TikZ skipped). */
    internal fun extractLatexVisibleText(body: String, baseOffset: Int = 0): List<TextSegment> {
        val out = mutableListOf<TextSegment>()
        walkLatexRange(body, 0, body.length, baseOffset, out)
        return out
    }

    private fun walkLatexRange(
        body: String,
        start: Int,
        end: Int,
        baseOffset: Int,
        out: MutableList<TextSegment>,
    ) {
        var j = start
        var runStart = -1

        fun flushRun(runEnd: Int) {
            if (runStart < 0 || runEnd <= runStart) {
                runStart = -1
                return
            }
            val plain = normalizePlain(body.substring(runStart, runEnd))
            if (plain.isNotEmpty()) {
                out += TextSegment(baseOffset + runStart, baseOffset + runEnd, plain)
            }
            runStart = -1
        }

        while (j < end) {
            val ch = body[j]
            if (ch == '%') {
                flushRun(j)
                val nl = body.indexOf('\n', j)
                j = if (nl >= 0) nl + 1 else end
                continue
            }
            if (ch == '\\') {
                flushRun(j)
                val consumed = skipLatexControl(body, j, end) { s0, e0 ->
                    walkLatexRange(body, s0, e0.coerceAtMost(end), baseOffset, out)
                }
                j = when {
                    consumed > j -> consumed.coerceAtMost(end)
                    else -> (j + 1).coerceAtMost(end)
                }
                continue
            }
            if (ch == '$') {
                flushRun(j)
                val isDouble = j + 1 < end && body[j + 1] == '$'
                val close = if (isDouble) body.indexOf("$$", j + 2) else {
                    var k = j + 1
                    while (k < end) {
                        if (body[k] == '$' && !isEscaped(body, k)) break
                        k++
                    }
                    k
                }
                j = if (close in 0 until end) close + (if (isDouble) 2 else 1) else end
                continue
            }
            if (ch.isWhitespace()) {
                flushRun(j)
                j++
                continue
            }
            if (runStart < 0) runStart = j
            j++
        }
        flushRun(end)
    }

    /** @return index after consumed control sequence, or -1 if not handled */
    private fun skipLatexControl(
        s: String,
        i: Int,
        limit: Int,
        recurse: (Int, Int) -> Unit,
    ): Int {
        if (i >= limit || s[i] != '\\') return -1
        if (startsAt(s, i, "\\[")) {
            val close = s.indexOf("\\]", i + 2)
            return if (close >= 0) close + 2 else limit
        }
        if (startsAt(s, i, "\\(")) {
            val close = s.indexOf("\\)", i + 2)
            return if (close >= 0) close + 2 else limit
        }
        if (startsAt(s, i, "\\begin{")) {
            val nameOpen = i + "\\begin{".length
            val nameClose = s.indexOf('}', nameOpen)
            if (nameClose < 0) return i + 1
            val env = s.substring(nameOpen, nameClose)
            val endTok = "\\end{$env}"
            val endIdx = s.indexOf(endTok, nameClose + 1)
            if (endIdx < 0) return limit
            if (env in MATH_ENVS || env == "tikzpicture" || env == "knot") {
                return if (env == "tikzpicture") {
                    val afterOpts = skipTikzpictureBracketOptions(s, nameClose + 1)
                    findMatchingEndTikzpictureProse(s, afterOpts).let { if (it < 0) limit else it }
                } else {
                    endIdx + endTok.length
                }
            }
            recurse(nameClose + 1, endIdx)
            return endIdx + endTok.length
        }

        var j = i + 1
        if (j < limit && !s[j].isLetter()) return j + 1
        while (j < limit && s[j].isLetter()) j++
        val cmd = s.substring(i + 1, j)
        while (j < limit && s[j].isWhitespace()) j++
        if (j < limit && s[j] == '{') {
            val close = findBalancedBraceAllowMath(s, j)
            if (close >= 0) {
                val innerEnd = close.coerceAtMost(limit - 1)
                if (cmd in PROSE_ARG_CMDS || cmd in REF_CITE_CMDS) {
                    if (cmd == "href") {
                        var p = close + 1
                        while (p < limit && s[p].isWhitespace()) p++
                        if (p < limit && s[p] == '{') {
                            val close2 = findBalancedBraceAllowMath(s, p)
                            if (close2 >= 0) recurse(p + 1, close2.coerceAtMost(limit))
                        }
                    } else {
                        recurse(j + 1, innerEnd + 1)
                    }
                }
                return (close + 1).coerceAtMost(limit)
            }
        }
        return j.coerceAtMost(limit)
    }

    private fun startsAt(s: String, i: Int, tok: String): Boolean =
        i >= 0 && i + tok.length <= s.length && s.regionMatches(i, tok, 0, tok.length)

    /** Extract visible text runs from rendered HTML (tags/math/syncline skipped). */
    internal fun extractHtmlVisibleText(html: String): List<TextSegment> {
        val out = mutableListOf<TextSegment>()
        var i = 0
        val n = html.length
        var runStart = -1

        fun flushRun(end: Int) {
            if (runStart < 0 || end <= runStart) {
                runStart = -1
                return
            }
            val raw = html.substring(runStart, end)
            val plain = normalizePlain(decodeHtmlEntities(raw))
            if (plain.isNotEmpty()) {
                out += TextSegment(runStart, end, plain)
            }
            runStart = -1
        }

        while (i < n) {
            if (html[i] == '<') {
                flushRun(i)
                val close = html.indexOf('>', i)
                if (close < 0) break
                val tag = html.substring(i, close + 1)
                if (isSkipOpenTag(tag)) {
                    val tagName = tagNameOf(tag) ?: ""
                    i = close + 1
                    if (tagName.isNotEmpty() && !tag.endsWith("/>")) {
                        i = skipUntilCloseTag(html, i, tagName)
                    }
                    continue
                }
                i = close + 1
                continue
            }
            if (runStart < 0) runStart = i
            i++
        }
        flushRun(n)
        return out
    }

    private fun isSkipOpenTag(tag: String): Boolean {
        val lower = tag.lowercase()
        return lower.startsWith("<span class=\"syncline") ||
            lower.startsWith("<span class=\"llmark") ||
            lower.startsWith("<span class=\"ll-label") ||
            lower.contains("class=\"mjx-container") ||
            lower.startsWith("<script") ||
            lower.startsWith("<style") ||
            lower.startsWith("<svg") ||
            lower.contains("tikz-lazy") ||
            lower.contains("tikz-status")
    }

    private fun tagNameOf(tag: String): String? {
        val m = Regex("""^<\s*([a-zA-Z0-9]+)""").find(tag) ?: return null
        return m.groupValues[1].lowercase()
    }

    private fun skipUntilCloseTag(html: String, start: Int, tagName: String): Int {
        val closeTag = "</$tagName>"
        var depth = 1
        var i = start
        val openRx = Regex("""<\s*$tagName\b""", RegexOption.IGNORE_CASE)
        while (i < html.length && depth > 0) {
            val nextClose = html.indexOf(closeTag, i, ignoreCase = true)
            if (nextClose < 0) return html.length
            val nextOpen = openRx.find(html, i)?.range?.first ?: html.length
            if (nextOpen < nextClose) {
                depth++
                i = nextOpen + 1
            } else {
                depth--
                i = nextClose + closeTag.length
            }
        }
        return i
    }

    internal fun alignSegments(
        src: List<TextSegment>,
        html: List<TextSegment>,
    ): List<MappedSegment> {
        val out = mutableListOf<MappedSegment>()
        var si = 0
        var hi = 0
        while (si < src.size && hi < html.size) {
            val s = src[si]
            val h = html[hi]
            if (plainEqual(s.plain, h.plain)) {
                out += MappedSegment(s.start, s.end, h.start, h.end)
                si++
                hi++
                continue
            }
            if (s.plain.contains(h.plain) || h.plain.contains(s.plain)) {
                out += MappedSegment(s.start, s.end, h.start, h.end)
                si++
                hi++
                continue
            }
            // Prefer advancing the side with the shorter token (cite/ref mismatches, etc.)
            if (s.plain.length <= h.plain.length) si++ else hi++
        }
        return out
    }

    internal fun injectSpans(html: String, mapped: List<MappedSegment>): String {
        if (mapped.isEmpty()) return html
        val sb = StringBuilder(html.length + mapped.size * 48)
        var cursor = 0
        for (m in mapped.sortedBy { it.htmlStart }) {
            if (m.htmlStart < cursor || m.htmlEnd <= m.htmlStart) continue
            sb.append(html, cursor, m.htmlStart)
            sb.append("""<span class="llsrc" data-s="${m.srcStart}" data-e="${m.srcEnd}">""")
            sb.append(html, m.htmlStart, m.htmlEnd)
            sb.append("</span>")
            cursor = m.htmlEnd
        }
        sb.append(html, cursor, html.length)
        return sb.toString()
    }

    private fun normalizePlain(s: String): String =
        s.replace(Regex("""\s+"""), " ")

    private fun plainEqual(a: String, b: String): Boolean =
        normalizePlain(a).trim() == normalizePlain(b).trim()

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")

    /** Build orig char offset → merged char offset map (length = origLen + 1). */
    fun buildOrigToMergedCharMap(
        origText: String,
        inlinedMarked: String,
        markerPrefix: String = "%%LLM",
    ): IntArray {
        val map = IntArray(origText.length + 1)
        val markerRx = Regex("""${Regex.escape(markerPrefix)}\d+%%""")
        val origLines = origText.split('\n')
        var origOffset = 0
        var searchFrom = 0

        fun strippedLenBefore(pos: Int): Int =
            markerRx.replace(inlinedMarked.substring(0, pos.coerceIn(0, inlinedMarked.length)), "").length

        for (lineIdx in origLines.indices) {
            val line = origLines[lineIdx]
            val token = "$markerPrefix${lineIdx + 1}%%"
            val tokenPos = inlinedMarked.indexOf(token, searchFrom).let {
                if (it >= 0) it else inlinedMarked.indexOf(token)
            }
            val mergedLineStart = if (tokenPos >= 0) {
                searchFrom = tokenPos + token.length
                strippedLenBefore(tokenPos + token.length)
            } else {
                if (origOffset > 0) map[origOffset - 1] else 0
            }
            for (j in 0..line.length) {
                map[origOffset + j] = mergedLineStart + j
            }
            origOffset += line.length + 1
        }
        if (origText.isEmpty()) map[0] = 0
        return map
    }

    /** Inverse: merged char offset → orig char offset (length = mergedLen + 1). */
    fun buildMergedToOrigCharMap(origToMerged: IntArray, mergedLength: Int): IntArray {
        val m2o = IntArray(mergedLength + 1)
        for (m in 0..mergedLength) {
            var o = 0
            while (o + 1 < origToMerged.size && origToMerged[o + 1] <= m) o++
            m2o[m] = o.coerceAtMost(origToMerged.size - 1)
        }
        return m2o
    }

    fun charMapToJson(arr: IntArray): String =
        arr.joinToString(prefix = "[", postfix = "]") { it.toString() }
}