package com.omariskandarani.livelatex.html

object LatexHtml {

    // ------------------------ PUBLIC ENTRY ------------------------

    fun wrap(texSource: String): String {
        val srcNoComments = stripLineComments(texSource)
        val userMacros    = extractNewcommands(srcNoComments)
        val macrosJs      = buildMathJaxMacros(userMacros)

        // Find body & absolute line offset of the first body line
        val beginIdx = texSource.indexOf("\\begin{document}")
        val absOffset = if (beginIdx >= 0)
            texSource.substring(0, beginIdx).count { it == '\n' } + 1
        else
            1

        val body0 = stripPreamble(texSource)
        val body1 = stripLineComments(body0)
        val body2 = applyProseConversions(body1)
        val body3 = sanitizeForMathJaxProse(body2)

        // Escape but keep backslashes for MathJax
        val escaped = escapeHtmlKeepBackslashes(body3)

        // ⬇️ Insert line anchors every Nth line using ABSOLUTE source line numbers
        val withAnchors = injectLineAnchors(escaped, absOffset, everyN = 3)

        return buildHtml(withAnchors, macrosJs)
    }


    // ------------------------ PAGE BUILDER ------------------------

    private fun buildHtml(fullTextHtml: String, macrosJs: String): String = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>LaTeX Preview</title>
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'self' 'unsafe-inline' data: blob: https://cdn.jsdelivr.net;
                 script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net;
                 style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net;
                 img-src * data: blob:;
                 font-src https://cdn.jsdelivr.net data:;">
  <style>
    :root { --bg:#ffffff; --fg:#111827; --muted:#6b7280; --border:#e5e7eb; }
    @media (prefers-color-scheme: dark) { :root { --bg:#0f1115; --fg:#e5e7eb; --muted:#9ca3af; --border:#2d3748; } }
    html, body { height:100%; margin:0; background:var(--bg); color:var(--fg); }
    body { font-family: system-ui, -apple-system, Segoe UI, Roboto, Ubuntu, Cantarell, sans-serif; }
    .wrap { padding:16px 20px 40px; max-width:980px; margin:0 auto; }
    .mj   { font-size:16px; line-height:1.45; }
    .full-text { white-space: pre-wrap; }
    table { border-collapse: collapse; }
    a { color:inherit; }
    /* ⬇️ zero-size line markers that don't affect layout */
    .syncline { display:inline-block; width:0; height:0; overflow:hidden; }
  </style>

  <script>
    // MathJax config
    window.MathJax = {
      tex: {
        tags: 'ams', tagSide: 'right', tagIndent: '0.8em',
        inlineMath: [['\\(','\\)'], ['$', '$']],
        displayMath: [['\\[','\\]'], ['$$','$$']],
        processEscapes: true,
        packages: {'[+]': ['ams','bbox','base']},
        macros: $macrosJs
      },
      options: { skipHtmlTags: ['script','noscript','style','textarea','pre','code'] },
      startup: {
        ready: () => { MathJax.startup.defaultReady(); try { window.sync.init(); } catch(e){} }
      }
    };
  </script>
  <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js"></script>

  <!-- ⬇️ simple scroll API -->
  <script>
    (function(){
      const sync = {
        idx: [],
        init() {
          this.idx = Array.from(document.querySelectorAll('.syncline'))
                          .map(el => ({ el, abs: +el.dataset.abs || 0 }));
        },
        scrollToAbs(line) {
          if (!this.idx.length) this.init();
          const arr = this.idx;
          if (!arr.length) return;
          // binary search: last anchor with abs <= line
          let lo=0, hi=arr.length-1, ans=0;
          while (lo <= hi) {
            const mid = (lo+hi) >> 1;
            if (arr[mid].abs <= line) { ans = mid; lo = mid+1; } else { hi = mid-1; }
          }
          const target = arr[ans] && arr[ans].el;
          if (target) {
            target.scrollIntoView({block:'start', inline:'nearest'});
            // slight nudge
            window.scrollBy(0, -8);
          }
        }
      };
      window.sync = sync;
      document.addEventListener('DOMContentLoaded', () => sync.init());
    })();
  </script>
</head>
<body>
  <div class="wrap mj">
    <div class="full-text">$fullTextHtml</div>
  </div>
</body>
</html>
""".trimIndent()

    // ------------------------ PIPELINE HELPERS ------------------------

    private fun applyProseConversions(s: String): String {
        var t = s
        t = convertSiunitx(t)      // \num, \si
        t = convertHref(t)         // \href{url}{text}
        t = convertSections(t)     // \section, \subsection, \paragraph
        t = convertItemize(t)      // itemize → <ul>
        t = convertEnumerate(t)    // enumerate → <ol>
        t = convertTabulars(t)     // tabular → <table>
        t = stripAuxDirectives(t)  // \addcontentsline, \nocite, \bibliography (placeholder)
        return t
    }

    /** Keep only the document body; MathJax doesn’t understand the preamble. */
    private fun stripPreamble(s: String): String {
        val begin = s.indexOf("\\begin{document}")
        val end   = s.lastIndexOf("\\end{document}")
        return if (begin >= 0 && end > begin) s.substring(begin + "\\begin{document}".length, end) else s
    }

    /** Remove % line comments (safe heuristic for authoring). */
    private fun stripLineComments(s: String): String =
        s.lines().map { line ->
            val i = line.indexOf('%')
            if (i >= 0) line.substring(0, i) else line
        }.joinToString("\n")

    // ------------------------ MACROS ------------------------

    private data class Macro(val def: String, val nargs: Int)

    /** Parse \newcommand and \def from the WHOLE source (pre + body). */
    private fun extractNewcommands(s: String): Map<String, Macro> {
        val out = LinkedHashMap<String, Macro>()

        // \newcommand{\foo}[2]{...}
        val rxNew = Regex("""\\newcommand\{\\([A-Za-z@]+)\}(?:\[(\d+)\])?(?:\[[^\]]*\])?\{(.+?)\}""",
            RegexOption.DOT_MATCHES_ALL)
        rxNew.findAll(s).forEach { m ->
            val name  = m.groupValues[1]
            val nargs = m.groupValues[2].ifEmpty { "0" }.toInt()
            val body  = m.groupValues[3].trim()
            out[name] = Macro(body, nargs)
        }

        // \def\foo{...}
        val rxDef = Regex("""\\def\\([A-Za-z@]+)\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)
        rxDef.findAll(s).forEach { m ->
            out.putIfAbsent(m.groupValues[1], Macro(m.groupValues[2].trim(), 0))
        }

        return out
    }

    /** Build MathJax tex.macros (JSON-like) from user + base shims. */
    private fun buildMathJaxMacros(user: Map<String, Macro>): String {
        // Lightweight shims for common packages (physics, siunitx, etc.)
        val base = linkedMapOf(
            "vb" to Macro("\\mathbf{#1}",1),
            "bm" to Macro("\\boldsymbol{#1}",1),
            "dv" to Macro("\\frac{d #1}{d #2}",2),
            "pdv" to Macro("\\frac{\\partial #1}{\\partial #2}",2),
            "abs" to Macro("\\left|#1\\right|",1),
            "norm" to Macro("\\left\\lVert #1\\right\\rVert",1),
            "qty" to Macro("\\left(#1\\right)",1),
            "qtyb" to Macro("\\left[#1\\right]",1),
            "qed" to Macro("\\square",0),
            // siunitx placeholders (convertSiunitx does formatting)
            "si" to Macro("\\mathrm{#1}",1),
            "num" to Macro("{#1}",1),
            // handy aliases
            "Lam" to Macro("\\Lambda",0),
            "rc"  to Macro("r_c",0),
            // text-ish shims (kept mild)
            "texttt" to Macro("\\mathtt{#1}",1),
            "textbf" to Macro("\\mathbf{#1}",1),
            "emph"   to Macro("\\mathit{#1}",1)
        )

        // Merge with user macros (user wins)
        val merged = LinkedHashMap<String, Macro>()
        merged.putAll(base)
        merged.putAll(user)

        val parts = merged.map { (k,v) ->
            if (v.nargs > 0) "\"$k\": [${jsonEscape(v.def)}, ${v.nargs}]"
            else              "\"$k\": ${jsonEscape(v.def)}"
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun jsonEscape(tex: String): String =
        "\"" + tex
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""

    // ------------------------ PROSE CONVERSIONS ------------------------

    private fun convertSections(s: String): String {
        var t = s
        t = t.replace(Regex("""\\section\*?\{([^}]*)\}""")) {
            "<h2>${escapeHtmlKeepBackslashes(it.groupValues[1])}</h2>"
        }
        t = t.replace(Regex("""\\subsection\*?\{([^}]*)\}""")) {
            "<h3>${escapeHtmlKeepBackslashes(it.groupValues[1])}</h3>"
        }
        t = t.replace(Regex("""\\paragraph\{([^}]*)\}""")) {
            """<h4 style="margin:1em 0 .4em 0;">${escapeHtmlKeepBackslashes(it.groupValues[1])}</h4>"""
        }
        // \texorpdfstring{math}{text} → show the text in HTML
        t = t.replace(Regex("""\\texorpdfstring\{([^}]*)\}\{([^}]*)\}""")) {
            escapeHtmlKeepBackslashes(it.groupValues[2])
        }
        // \appendix → thin rule
        t = t.replace(Regex("""\\appendix"""), """<hr style="border:none;border-top:1px solid var(--border);margin:16px 0;"/>""")
        return t
    }

    private fun convertItemize(s: String): String {
        val rx = Regex("""\\begin\{itemize\}(.+?)\\end\{itemize\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val body = m.groupValues[1]
            val parts = Regex("""(?m)^\s*\\item\s*""").split(body).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@replace ""
            val lis = parts.joinToString("") { "<li>${escapeHtmlKeepBackslashes(it)}</li>" }
            """<ul style="margin:12px 0 12px 24px;">$lis</ul>"""
        }
    }

    private fun convertEnumerate(s: String): String {
        val rx = Regex("""\\begin\{enumerate\}(.+?)\\end\{enumerate\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val body = m.groupValues[1]
            val parts = Regex("""(?m)^\s*\\item\s*""").split(body).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@replace ""
            val lis = parts.joinToString("") { "<li>${escapeHtmlKeepBackslashes(it)}</li>" }
            """<ol style="margin:12px 0 12px 24px;">$lis</ol>"""
        }
    }

    private data class ColSpec(val align: String?, val widthPct: Int?)
    private fun convertTabulars(text: String): String {
        val rx = Regex("""\\begin\{tabular\}\{([^}]*)\}(.+?)\\end\{tabular\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(text) { m ->
            val spec = m.groupValues[1]
            var body = m.groupValues[2].trim()
            // booktabs / hlines cleanup
            body = body
                .replace("\\toprule", "")
                .replace("\\midrule", "")
                .replace("\\bottomrule", "")
                .replace(Regex("""(?m)^\s*\\hline\s*$"""), "")

            val cols = parseColSpec(spec)

            val rowRx = Regex("""(?<!\\)\\\\\s*""")
            val rows = body.split(rowRx).map { it.trim() }.filter { it.isNotEmpty() }

            val trs = rows.joinToString(separator = "") { row ->
                val cells = row.split("&").map { it.trim() }
                val tds = cells.mapIndexed { idx, rawCell ->
                    val (align, widthPct) = colStyle(cols, idx)
                    val style = buildString {
                        if (align != null) append("text-align:$align;")
                        if (widthPct != null) append("width:${widthPct}%;")
                        append("padding:4px 8px;border:1px solid var(--border);vertical-align:top;")
                    }
                    val cellHtml = proseLatexToHtml(escapeHtmlKeepBackslashes(rawCell))
                        .replace(Regex("""\\\\\s*"""), "<br/>")
                    """<td style="$style">$cellHtml</td>"""
                }.joinToString("")
                "<tr>$tds</tr>"
            }

            """<table style="border:1px solid var(--border);margin:12px 0;width:100%;">$trs</table>"""
        }
    }

    private fun parseColSpec(spec: String): List<ColSpec> {
        val cols = mutableListOf<ColSpec>()
        var i = 0
        while (i < spec.length) {
            when (spec[i]) {
                'l' -> { cols += ColSpec("left", null);  i++ }
                'c' -> { cols += ColSpec("center", null); i++ }
                'r' -> { cols += ColSpec("right", null);  i++ }
                'p' -> {
                    val m = Regex("""p\{([^}]*)\}""").find(spec, i)
                    if (m != null && m.range.first == i) {
                        val pct = linewidthToPercent(m.groupValues[1])
                        cols += ColSpec("left", pct)
                        i = m.range.last + 1
                    } else i++
                }
                '|', '!' -> i++ // ignore vertical rules / !{..}
                '@', '>' -> {
                    val m = Regex("""[@>]\{([^}]*)\}""").find(spec, i)
                    i = if (m != null && m.range.first == i) m.range.last + 1 else i + 1
                }
                else -> i++
            }
        }
        return cols
    }

    private fun linewidthToPercent(expr: String): Int? {
        Regex("""^\s*([0-9]*\.?[0-9]+)\s*\\linewidth\s*$""").matchEntire(expr)?.let {
            val f = it.groupValues[1].toDoubleOrNull() ?: return null
            return (f * 100).toInt().coerceIn(1, 100)
        }
        Regex("""^\s*([0-9]{1,3})\s*%\s*$""").matchEntire(expr)?.let {
            return it.groupValues[1].toInt().coerceIn(1, 100)
        }
        return null
    }

    private fun colStyle(cols: List<ColSpec>, idx: Int): Pair<String?, Int?> =
        if (idx < cols.size) cols[idx].align to cols[idx].widthPct else null to null

    /** Very conservative prose text helpers (used inside table/list conversions). */
    private fun proseLatexToHtml(s: String): String {
        var t = s
        t = t.replace(Regex("""\\textbf\{([^{}]*)\}"""), "<strong>$1</strong>")
        t = t.replace(Regex("""\\emph\{([^{}]*)\}"""), "<em>$1</em>")
        t = t.replace(Regex("""\\footnotesize\{([^{}]*)\}"""), "<small>$1</small>")
        return t
    }

    private fun convertHref(s: String): String =
        s.replace(Regex("""\\href\{([^}]*)\}\{([^}]*)\}""")) { m ->
            val url = m.groupValues[1]; val txt = m.groupValues[2]
            """<a href="${escapeHtmlKeepBackslashes(url)}" target="_blank" rel="noopener">${escapeHtmlKeepBackslashes(txt)}</a>"""
        }

    private fun stripAuxDirectives(s: String): String {
        var t = s
        t = t.replace(Regex("""\\addcontentsline\{[^}]*\}\{[^}]*\}\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\nocite\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\bibliographystyle\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\bibliography\{[^}]*\}"""),
            """<div style="opacity:.7;margin:8px 0;">[References: compile in PDF mode]</div>""")
        return t
    }

    // ------------------------ SANITIZER ------------------------

    /** Convert abstract/center/theorem-like to HTML; drop unknown NON-math envs; keep math envs intact. */
    private fun sanitizeForMathJaxProse(bodyText: String): String {
        var s = bodyText

        // Custom titlepage toggles used in your Canon
        s = s.replace("""\\titlepageOpen""".toRegex(), "")
            .replace("""\\titlepageClose""".toRegex(), "")

        // center → HTML
        s = s.replace(
            Regex("""\\begin\{center\}(.+?)\\end\{center\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m -> """<div style="text-align:center;">${escapeHtmlKeepBackslashes(m.groupValues[1].trim())}</div>""" }

        // abstract → HTML card
        s = s.replace(
            Regex("""\\begin\{abstract\}(.+?)\\end\{abstract\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m ->
            """<div style="padding:12px;border-left:3px solid var(--border);background:color-mix(in srgb, var(--bg), #6b7280 10%);margin:12px 0;">
                 <strong>Abstract.</strong> ${escapeHtmlKeepBackslashes(m.groupValues[1].trim())}
               </div>""".trimIndent()
        }

        // theorem-like → HTML card
        val theoremLike = listOf("theorem","lemma","proposition","corollary","definition","remark","identity")
        for (env in theoremLike) {
            s = s.replace(
                Regex("""\\begin\{$env\}(?:\[(.*?)\])?(.+?)\\end\{$env\}""", RegexOption.DOT_MATCHES_ALL)
            ) { m ->
                val ttl = m.groupValues[1].trim()
                val content = m.groupValues[2].trim()
                val head = if (ttl.isNotEmpty()) "$env ($ttl)" else env
                """
                <div style="border:1px solid var(--border);border-radius:8px;padding:12px;margin:12px 0;">
                  <div style="font-weight:600;margin-bottom:6px;text-transform:capitalize;">$head.</div>
                  ${escapeHtmlKeepBackslashes(content)}
                </div>
                """.trimIndent()
            }
        }

        // Drop unknown NON-math wrappers but keep inner text
        val mathEnvs = "(?:equation\\*?|align\\*?|gather\\*?|multline\\*?|flalign\\*?|alignat\\*?)"
        s = s.replace(Regex("""\\begin\{(?!$mathEnvs)\w+\}"""), "")
        s = s.replace(Regex("""\\end\{(?!$mathEnvs)\w+\}"""), "")

        return s
    }

    // ------------------------ SIUNITX SHIMS ------------------------

    private fun convertSiunitx(s: String): String {
        var t = s
        // \num{1.23e-4} → 1.23\times 10^{-4}
        t = t.replace(Regex("""\\num\{([^\}]*)\}""")) { m ->
            val raw = m.groupValues[1].trim()
            val sci = Regex("""^\s*([+-]?\d+(?:\.\d+)?)[eE]([+-]?\d+)\s*$""").matchEntire(raw)
            if (sci != null) {
                val a = sci.groupValues[1]; val b = sci.groupValues[2]
                "$a\\times 10^{${b}}"
            } else raw // fallback; MathJax macro 'num' shows as-is
        }
        // \si{m.s^{-1}} → \mathrm{m\,s^{-1}} (simple and readable)
        t = t.replace(Regex("""\\si\{([^\}]*)\}""")) { m ->
            val u = m.groupValues[1]
                .replace(".", "\\,")     // thin-space as mild separator
                .replace("~", "\\,")
            "\\mathrm{$u}"
        }
        return t
    }

    // ------------------------ UTIL ------------------------

    /** Escape &,<,> but keep backslashes so MathJax sees TeX. */
    private fun escapeHtmlKeepBackslashes(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

private fun injectLineAnchors(escapedBodyHtml: String, absOffset: Int, everyN: Int = 3): String {
    val sb = StringBuilder(escapedBodyHtml.length + 1024)
    val lines = escapedBodyHtml.split("\n")
    for (i in lines.indices) {
        val absLine = absOffset + i // 0-based index → 1-based abs handled below if you prefer; OK as-is
        if (i % everyN == 0) {
            sb.append("""<span class="syncline" data-abs="$absLine"></span>""")
        }
        sb.append(lines[i])
        if (i < lines.lastIndex) sb.append('\n')
    }
    return sb.toString()
}
