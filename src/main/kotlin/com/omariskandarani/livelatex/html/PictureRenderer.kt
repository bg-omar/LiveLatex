package com.omariskandarani.livelatex.html

import java.io.File
import java.security.MessageDigest

/**
 * Classic LaTeX `picture` environment → SVG/PNG (same LiveRender gate and job store as TikZ).
 * Title-page footer pictures are converted to HTML in the sanitizer and never reach here.
 */
object PictureRenderer {

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }

    fun countPictureStarts(s: String): Int {
        var count = 0
        var pos = 0
        val tok = "\\begin{picture}"
        while (true) {
            val i = s.indexOf(tok, pos)
            if (i < 0) break
            count++
            pos = i + tok.length
        }
        return count
    }

    /**
     * Scan up to three non-empty lines before [pictureStart] for `\setlength{\unitlength}{…}`.
     */
    internal fun lookbehindUnitlength(src: String, pictureStart: Int): String? {
        if (pictureStart <= 0) return null
        val before = src.substring(0, pictureStart)
        val lines = before.lines()
        var checked = 0
        for (i in lines.lastIndex downTo 0) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            checked++
            val m = Regex("""\\setlength\s*\{\\unitlength\}\s*\{([^}]*)\}""").find(line)
            if (m != null) return m.groupValues[1].trim()
            if (checked >= 3) break
        }
        return null
    }

    /**
     * Build a standalone document for one classic `picture` block.
     * Returns (contentSha1Key with `picture-` prefix, texDoc).
     */
    internal fun buildPictureBlockDoc(
        sizeOpts: String,
        body: String,
        unitlength: String? = null,
    ): Pair<String, String> {
        val size = sizeOpts.trim().ifEmpty { "(1,1)" }
        val unitLine = if (!unitlength.isNullOrBlank()) {
            "\\setlength{\\unitlength}{$unitlength}\n"
        } else {
            ""
        }
        val texDoc = """
\documentclass{standalone}
\usepackage{pict2e}
\usepackage{amsmath}
\begin{document}
$unitLine\begin{picture}$size
${body.trim()}
\end{picture}
\end{document}
""".trimIndent()
        return "picture-${sha1(texDoc)}" to texDoc
    }

    private fun parsePictureBlock(src: String, beginIdx: Int): ParsedPicture? {
        val beginTok = "\\begin{picture}"
        var p = beginIdx + beginTok.length
        while (p < src.length && src[p].isWhitespace()) p++
        var sizeOpts = ""
        if (p < src.length && src[p] == '(') {
            val rp = src.indexOf(')', p)
            if (rp < 0) return null
            sizeOpts = src.substring(p, rp + 1)
            p = rp + 1
        }
        val endTok = "\\end{picture}"
        val endIdx = src.indexOf(endTok, p)
        if (endIdx < 0) return null
        val body = src.substring(p, endIdx)
        val endExclusive = endIdx + endTok.length
        return ParsedPicture(sizeOpts, body, endExclusive)
    }

    private data class ParsedPicture(val sizeOpts: String, val body: String, val endExclusive: Int)

    private fun wrapWebImageHtml(result: LatexHtmlTikz.WebImageResult): String {
        val url = webImageResultToUrl(result)
        return """<span class="tikz-wrap" style="display:block;margin:12px 0;"><img src="$url" alt="picture" style="max-width:100%;height:auto;display:block;"/></span>"""
    }

    private fun pictureCompileFailureHtml(workHint: String, logPath: String?): String =
        figureUnavailablePlaceholder(
            "[picture compile failed]",
            "pdflatex or PDF→SVG/PNG conversion failed.",
            workHint,
            logPath,
        )

    private fun lazyPlaceholderHtml(key: String): String =
        """<span class="tikz-lazy" data-tikz-key="$key" style="display:block;margin:8px 0;padding:8px 12px;background:#f0f0f0;color:#666;font-size:12px;border-radius:4px;"><button type="button" class="tikz-load" data-tikz-key="$key" style="margin-right:8px;padding:4px 10px;cursor:pointer;border:1px solid #ccc;border-radius:4px;background:#fff;">LiveRender</button><span class="tikz-status"></span></span>"""

    /** Eager: compile each classic `picture` to SVG/PNG. */
    fun convertPictures(htmlLike: String): String {
        val beginTok = "\\begin{picture}"
        val result = StringBuilder(htmlLike.length)
        var pos = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Preview build cancelled")
            val start = htmlLike.indexOf(beginTok, pos)
            if (start < 0) break
            result.append(htmlLike, pos, start)
            TikzRenderer.bumpLiveRenderProgress("picture")
            val parsed = parsePictureBlock(htmlLike, start)
            if (parsed == null) {
                result.append(htmlLike, start, (start + beginTok.length).coerceAtMost(htmlLike.length))
                pos = start + beginTok.length
                continue
            }
            val unit = lookbehindUnitlength(htmlLike, start)
            val (key, texDoc) = buildPictureBlockDoc(parsed.sizeOpts, parsed.body, unit)
            val cache = LatexHtmlTikz.tikzCacheDir()
            val svg = File(cache, "$key.svg")
            val png = File(cache, "$key.png")
            when {
                svg.exists() -> result.append(wrapWebImageHtml(LatexHtmlTikz.WebImageResult(svg, "image/svg+xml")))
                png.exists() -> result.append(wrapWebImageHtml(LatexHtmlTikz.WebImageResult(png, "image/png")))
                else -> {
                    val rendered = LatexHtmlTikz.renderTexDocumentToWebImage(texDoc, key)
                    if (rendered != null) {
                        result.append(wrapWebImageHtml(rendered))
                    } else {
                        val work = File(cache, key)
                        result.append(pictureCompileFailureHtml(work.absolutePath, LatexHtmlTikz.latestLogPath(work)))
                    }
                }
            }
            pos = parsed.endExclusive
        }
        result.append(htmlLike, pos, htmlLike.length)
        return result.toString()
    }

    /**
     * Lazy: replace each classic `picture` with a LiveRender button (same job store / JS bridge as TikZ).
     */
    fun replacePicturesWithLazyPlaceholder(htmlLike: String): String {
        val beginTok = "\\begin{picture}"
        val result = StringBuilder(htmlLike.length)
        var pos = 0
        while (true) {
            val start = htmlLike.indexOf(beginTok, pos)
            if (start < 0) break
            result.append(htmlLike, pos, start)
            val parsed = parsePictureBlock(htmlLike, start)
            if (parsed == null) {
                result.append(htmlLike, start, (start + beginTok.length).coerceAtMost(htmlLike.length))
                pos = start + beginTok.length
                continue
            }
            val unit = lookbehindUnitlength(htmlLike, start)
            val (docKey, texDoc) = buildPictureBlockDoc(parsed.sizeOpts, parsed.body, unit)
            val key = "$docKey-$start"
            LatexTikzJobStore.put(key, texDoc)
            result.append(lazyPlaceholderHtml(key))
            pos = parsed.endExclusive
        }
        result.append(htmlLike, pos, htmlLike.length)
        return result.toString()
    }
}
