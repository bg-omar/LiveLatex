package com.omariskandarani.livelatex.html

import kotlin.text.RegexOption

import com.omariskandarani.livelatex.html.latexProseToHtmlWithMath
import com.omariskandarani.livelatex.html.renderDate

/**
 * LaTeX sanitization for MathJax compatibility. Part of LatexHtml multi-file object.
 */

/** Convert \\begin{titlepage}...\\end{titlepage} to HTML title block. */
internal fun convertTitlepage(s: String): String {
    val rx = Regex("""\\begin\{titlepage\}(.+?)\\end\{titlepage\}""", RegexOption.DOT_MATCHES_ALL)
    return rx.replace(s) { m ->
        var body = m.groupValues[1]
        body = body.replace(Regex("""\\thispagestyle\{[^}]*\}"""), "")
        body = body.replace(Regex("""\\centering\b"""), "")
        body = body.replace(Regex("""\\par\b"""), "\n")
        body = Regex("""\\vspace\*?\s*\{([^}]*)\}""").replace(body) { m ->
            val arg = m.groupValues[1].trim().replace(',', '.')
            val numMatch = Regex("""([\d.]+)\s*(cm|mm|pt|em|ex|in)?""").find(arg)
            if (numMatch != null) {
                val num = numMatch.groupValues[1]
                val unit = numMatch.groupValues[2].ifEmpty { "em" }
                """<div style="height:${num}$unit"></div>"""
            } else "<br/>"
        }
        body = body.replace("""\today""", renderDate("""\today""") ?: "")
        body = body.replace(Regex("""\\(Large|normalsize|small)\b"""), "")
        body = body.replace(Regex("""\\bfseries\b"""), "")
        body = body.replace(Regex("""\\itshape\b"""), "")
        val html = latexProseToHtmlWithMath(body)
        val blocks = html.split(Regex("""\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val styled = blocks.mapIndexed { i, block ->
            val style = when (i) {
                0 -> "font-size:1.5em; font-weight:bold; margin:0 0 1em 0;"
                1 -> "font-size:1.1em; font-style:italic; margin:0.5em 0;"
                else -> "font-size:1em; margin:0.5em 0; opacity:0.9;"
            }
            "<div style=\"$style\">$block</div>"
        }.joinToString("")
        """
        <div class="ll-titlepage" style="text-align:center; padding:2em 1em; margin:0 0 1.5em 0; border-bottom:1px solid var(--border);">
          $styled
        </div>
        """.trimIndent()
    }
}

/** Convert abstract/center/theorem-like to HTML; drop unknown NON-math envs; keep math envs intact. */
internal fun sanitizeForMathJaxProse(bodyText: String): String {
        var s = bodyText

        s = convertTitlepage(s)

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

        s = convertAlignWithMultipleTagsToBlocks(s)

        val mathEnvs =
            "(?:equation\\*?|align\\*?|aligned\\*?|aligned|gather\\*?|multline\\*?|flalign\\*?|alignat\\*?|bmatrix|pmatrix|vmatrix|Bmatrix|Vmatrix|smallmatrix|matrix|cases|split)"
        val keepEnvs =
            "(?:$mathEnvs|tabular|table|longtable|figure|center|tikzpicture|tcolorbox|thebibliography|itemize|enumerate|description|multicols)"

        s = s.replace(Regex("""\\begin\{(?!$keepEnvs)\w+\}"""), "")
        s = s.replace(Regex("""\\end\{(?!$keepEnvs)\w+\}"""), "")

        return s
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
