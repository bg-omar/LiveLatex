package com.omariskandarani.livelatex.html

import java.io.File
import java.nio.file.Paths

/**
 * Minimal LaTeX → HTML previewer for prose + MathJax math.
 * - Parses user \newcommand / \def into MathJax macros
 * - Converts common prose constructs (sections, lists, tables, theorems, etc.)
 * - Leaves math regions intact ($...$, \[...\], \(...\), equation/align/...)
 * - Inserts invisible line anchors to sync scroll with editor
 */
object LatexHtml {

    // ─────────────────────────── PUBLIC ENTRY ───────────────────────────

    private const val BEGIN_DOCUMENT = "\\begin{document}"
    private const val END_DOCUMENT = "\\end{document}"
    private const val LABEL_REGEX =  "\\\\label\\{[^}]*\\}"
    private const val EM_HTML = "<em>$1</em>"
    val rxNew = Regex(
        """\\newcommand\{\\([A-Za-z@]+)\}(?:\[(\d+)])?(?:\[[^\]]*])?\{(.+?)\}""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun wrap(texSource: String): String {
        val srcNoComments = stripLineComments(texSource)
        val userMacros    = extractNewcommands(srcNoComments)
        val macrosJs      = buildMathJaxMacros(userMacros)

        // Find body & absolute line offset of the first body line
        val beginIdx  = texSource.indexOf(BEGIN_DOCUMENT)
        val absOffset = if (beginIdx >= 0)
            texSource.substring(0, beginIdx).count { it == '\n' } + 1
        else
            1

        val body0 = stripPreamble(texSource)
        val body1 = stripLineComments(body0)
        val body2 = sanitizeForMathJaxProse(body1)
        val body3 = applyProseConversions(body2)
        val body4 = applyInlineFormattingOutsideTags(body3)

        // Insert anchors (no blanket escaping here; we preserve math)
        val withAnchors = injectLineAnchors(body4, absOffset, everyN = 1)

        return buildHtml(withAnchors, macrosJs)
    }

    // Helper to extract package names from \usepackage
    private fun extractUsepackageNames(s: String): List<String> {
        val rx = Regex("""\\usepackage(?:\[[^\]]*])?\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        val pkgs = mutableSetOf<String>()
        rx.findAll(s).forEach { m ->
            m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { pkgs += it }
        }
        // Only include supported MathJax packages
        val supported = setOf("ams", "base", "bbox", "textmacros", "color", "boldsymbol", "mhchem", "noundefined")
        return pkgs.filter { it in supported }.toList()
    }

    // ─────────────────────────── PAGE BUILDER ───────────────────────────

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
    a { color: inherit; }
    /* zero-size line markers that don't affect layout */
    .syncline { display:inline-block; width:0; height:0; overflow:hidden; }
    html, body { height: 100%; margin: 0; }
    body { overflow-y: auto; }
    .wrap { min-height: 100vh; }

  </style>

  <script>
    // MathJax config
    window.MathJax = {
      tex: {
        tags: 'ams', tagSide: 'right', tagIndent: '0.8em',
        inlineMath: [['\\(','\\)'], ['$', '$']],
        displayMath: [['\\[','\\]'], ['$$','$$']],
        processEscapes: true,
        packages: {'[+]': ['ams','bbox','base','textmacros']},
        macros: $macrosJs
      },
      options: { skipHtmlTags: ['script','noscript','style','textarea','pre','code'] },
      startup: {
        ready: () => { MathJax.startup.defaultReady(); try { window.sync.init(); } catch(e){} }
      }
    };
  </script>
  <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-chtml.js"></script>

<script>
  (function () {
    const sync = {
      idx: [],
      init() {
        this.idx = Array.from(document.querySelectorAll('.syncline'))
                        .map(el => ({ el, abs: +el.dataset.abs || 0 }));
      },
      scrollToAbs(line, mode = 'center') {
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
        if (!target) return;
        if (mode === 'center') {
          const r = target.getBoundingClientRect();
          const y = window.scrollY + r.top - (window.innerHeight/2);
          window.scrollTo({ top: Math.max(0, y) });
        } else {
          target.scrollIntoView({block:'start', inline:'nearest'});
          window.scrollBy(0, -8);
        }
      }
    };
    window.sync = sync;

    // Re-index after layout (MathJax ready calls init too)
    document.addEventListener('DOMContentLoaded', () => sync.init());

    // Accept postMessage from host
    window.addEventListener('message', (ev) => {
      const d = ev.data || {};
      if (d && d.type === 'sync-line' && Number.isFinite(d.abs)) {
        sync.scrollToAbs(d.abs, d.mode || 'center');
      }
    }, false);
  })();
</script>
<script>
  (function () {
    const sync = {
      idx: [],
      init() {
        this.idx = Array.from(document.querySelectorAll('.syncline'))
                        .map(el => ({ el, abs: +el.dataset.abs || 0 }));
      },
      scrollToAbs(line, mode = 'center') {
        if (!this.idx.length) this.init();
        const arr = this.idx;
        if (!arr.length) return;

        // last anchor with abs <= line
        let lo=0, hi=arr.length-1, ans=0;
        while (lo <= hi) {
          const mid = (lo+hi) >> 1;
          if (arr[mid].abs <= line) { ans = mid; lo = mid+1; } else { hi = mid-1; }
        }
        const target = arr[ans] && arr[ans].el;
        if (!target) return;

        // center the target
        const r = target.getBoundingClientRect();
        const y = window.scrollY + r.top - (window.innerHeight / 2);
        window.scrollTo({ top: Math.max(0, y) });
      }
    };
    window.sync = sync;

    document.addEventListener('DOMContentLoaded', () => sync.init());
    window.addEventListener('message', (ev) => {
      const d = ev.data || {};
      if (d.type === 'sync-line' && Number.isFinite(d.abs)) {
        sync.scrollToAbs(d.abs, d.mode || 'center');
      }
    }, false);
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


    // ──────────────────────── PIPELINE HELPERS ────────────────────────

    private fun applyProseConversions(s: String): String {
        var t = s
        t = convertSiunitx(t)
        t = convertHref(t)
        t = convertSections(t)
        t = convertFigureEnvs(t)      // figures first
        t = convertIncludeGraphics(t) // standalone \includegraphics
        t = convertTableEnvs(t)       // wrap table envs (keeps inner tabular)
        t = convertItemize(t)
        t = convertEnumerate(t)
        t = convertTabulars(t)        // finally convert tabular -> <table>
        t = convertTheBibliography(t)
        t = stripAuxDirectives(t)
        t = t.replace(Regex("""\\label\{[^}]*\}"""), "") // belt-and-suspenders
        return t
    }

    /** Keep only the document body; MathJax doesn’t understand the preamble. */
    private fun stripPreamble(s: String): String {
        val begin = s.indexOf(BEGIN_DOCUMENT)
        val end   = s.lastIndexOf(END_DOCUMENT)
        return if (begin >= 0 && end > begin) s.substring(begin + BEGIN_DOCUMENT.length, end) else s
    }

    /**
     * Remove % line comments (safe heuristic):
     * cuts at the first unescaped % per line (so \% is preserved).
     */
    private fun stripLineComments(s: String): String =
        s.lines().joinToString("\n") { line ->
            val cut = firstUnescapedPercent(line)
            if (cut >= 0) line.substring(0, cut) else line
        }

    private fun firstUnescapedPercent(line: String): Int {
        var i = 0
        while (true) {
            val j = line.indexOf('%', i)
            if (j < 0) return -1
            var bs = 0
            var k = j - 1
            while (k >= 0 && line[k] == '\\') { bs++; k-- }
            if (bs % 2 == 0) return j  // even backslashes → % is not escaped
            i = j + 1                   // odd backslashes → escaped, keep searching
        }
    }

    // Balanced-arg helpers (unchanged from before, keep them if you already added)
    private fun findBalancedBrace(s: String, open: Int): Int {
        if (open < 0 || open >= s.length || s[open] != '{') return -1
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
                '\\' -> if (i + 1 < s.length) i++ // skip next char
            }
            i++
        }
        return -1
    }

    private fun replaceCmd1ArgBalanced(s: String, cmd: String, wrap: (String) -> String): String {
        val rx = Regex("""\\$cmd\\s*\{""")
        val sb = StringBuilder(s.length)
        var pos = 0
        while (true) {
            val m = rx.find(s, pos) ?: break
            val start = m.range.first
            val braceOpen = m.range.last
            val braceClose = findBalancedBrace(s, braceOpen)
            if (braceClose < 0) {
                // Malformed command: skip this match and continue
                sb.append(s, pos, start + 1)
                pos = start + 1
                continue
            }
            sb.append(s, pos, start)
            val inner = s.substring(braceOpen + 1, braceClose)
            sb.append(wrap(inner))
            pos = braceClose + 1
        }
        sb.append(s, pos, s.length)
        return sb.toString()
    }

    private fun replaceCmd2ArgsBalanced(
        s: String, cmd: String, render: (String, String) -> String
    ): String {
        val rx = Regex("""\\$cmd\s*\{""")
        val sb = StringBuilder(s.length)
        var pos = 0
        while (true) {
            val m = rx.find(s, pos) ?: break
            val start = m.range.first
            val aOpen = m.range.last
            val aClose = findBalancedBrace(s, aOpen); if (aClose < 0) break
            val bOpen = s.indexOf('{', aClose + 1);   if (bOpen < 0) break
            val bClose = findBalancedBrace(s, bOpen); if (bClose < 0) break
            val a = s.substring(aOpen + 1, aClose)
            val b = s.substring(bOpen + 1, bClose)
            sb.append(s, pos, start)
            sb.append(render(a, b))
            pos = bClose + 1
        }
        sb.append(s, pos, s.length)
        return sb.toString()
    }

    // Escape just once, then inject tags; do NOT escape again afterwards.
    private fun formatInlineProseNonMath(s0: String): String {
        fun apply(t0: String, alreadyEscaped: Boolean): String {
            var t = t0
            if (!alreadyEscaped) {
                // only convert LaTeX line breaks (double backslash), leave single "\" alone
                t = t.replace(Regex("""(?<!\\)\\\\\s*"""), "<br/>")
                // escape HTML special chars (keep backslashes so TeX commands remain)
                t = t.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            }
            val rec: (String) -> String = { inner -> apply(inner, true) }

            t = replaceCmd1ArgBalanced(t, "textbf")      { "<strong>${rec(it)}</strong>" }
            t = replaceCmd1ArgBalanced(t, "emph")        { "<em>${rec(it)}</em>" }
            t = replaceCmd1ArgBalanced(t, "textit")      { "<em>${rec(it)}</em>" }
            t = replaceCmd1ArgBalanced(t, "underline")   { "<u>${rec(it)}</u>" }
            t = replaceCmd1ArgBalanced(t, "footnotesize"){ "<small>${rec(it)}</small>" }
            // IMPORTANT: do NOT do a final global escape here (that caused &lt;strong&gt;)
            return t
        }
        return apply(s0, false)
    }


    // ───────────────────────────── MACROS ─────────────────────────────

    private data class Macro(val def: String, val nargs: Int)

    /** Parse \newcommand and \def from the WHOLE source (pre + body). */
    private fun extractNewcommands(s: String): Map<String, Macro> {
        val out = LinkedHashMap<String, Macro>()

        // --- Improved \newcommand parser ---
        val rxNewStart = Regex("""\\newcommand\{\\([A-Za-z@]+)\}(?:\[(\d+)])?(?:\[[^\]]*])?\{""")
        var pos = 0
        while (true) {
            val m = rxNewStart.find(s, pos) ?: break
            val name = m.groupValues[1]
            val nargs = m.groupValues[2].ifEmpty { "0" }.toInt()
            val bodyOpen = m.range.last
            val bodyClose = findBalancedBrace(s, bodyOpen)
            if (bodyClose < 0) {
                pos = m.range.last + 1
                continue // skip malformed
            }
            val body = s.substring(bodyOpen + 1, bodyClose).trim()
            out[name] = Macro(body, nargs)
            pos = bodyClose + 1
        }

        // \def\foo{...} (unchanged)
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

            // siunitx placeholders (convertSiunitx does the formatting)
            "si"   to Macro("\\mathrm{#1}", 1),
            "num"  to Macro("{#1}", 1),

            // handy aliases
            "Lam"  to Macro("\\Lambda", 0),
            "rc"   to Macro("r_c", 0),

            // text-ish shims (kept mild)
            "texttt" to Macro("\\mathtt{#1}", 1),
            "textbf" to Macro("\\mathbf{#1}", 1),
            "emph"   to Macro("\\mathit{#1}", 1)
        )

        // Merge with user macros (user wins)
        val merged = LinkedHashMap<String, Macro>()
        merged.putAll(base)
        merged.putAll(user)

        val parts = merged.map { (k, v) ->
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


    // ──────────────────────── PROSE CONVERSIONS ────────────────────────

    private fun convertSections(s: String): String {
        var t = s
        // \section, \subsection, \subsubsection (starred or not)
        t = t.replace(Regex("""\\section\*?\{([^}]*)\}""")) {
            "<h2>${latexProseToHtmlWithMath(it.groupValues[1])}</h2>"
        }
        t = t.replace(Regex("""\\subsection\*?\{([^}]*)\}""")) {
            "<h3>${latexProseToHtmlWithMath(it.groupValues[1])}</h3>"
        }
        t = t.replace(Regex("""\\subsubsection\*?\{([^}]*)\}""")) {
            """<h4 style="margin:1em 0 .4em 0;">${latexProseToHtmlWithMath(it.groupValues[1])}</h4>"""
        }
        t = t.replace(Regex("""\\paragraph\{([^}]*)\}""")) {
            """<h5 style="margin:1em 0 .3em 0;">${latexProseToHtmlWithMath(it.groupValues[1])}</h5>"""
        }


// \texorpdfstring{math}{text} → use the *text* argument, formatted like prose
        t = t.replace(Regex("""\\texorpdfstring\{([^}]*)\}\{([^}]*)\}""")) {
            latexProseToHtmlWithMath(it.groupValues[2])
        }

        // \appendix divider
        t = t.replace(
            Regex("""\\appendix"""),
            """<hr style="border:none;border-top:1px solid var(--border);margin:16px 0;"/>"""
        )
        return t
    }

    /**
     * Convert LaTeX prose to HTML, preserving math regions ($...$, \[...\], \(...\)).
     * Only escapes HTML and converts text formatting in non-math regions.
     */
// Keep math regions intact; only run the formatter on non-math spans.
    private fun latexProseToHtmlWithMath(s: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = s.length
        while (i < n) {
            val dollarIdx  = s.indexOf('$', i)
            val bracketIdx = s.indexOf("\\[", i)
            val parenIdx   = s.indexOf("\\(", i)
            val nextIdx = listOf(dollarIdx, bracketIdx, parenIdx).filter { it >= 0 }.minOrNull() ?: n

            // Non-math chunk
            sb.append(formatInlineProseNonMath(s.substring(i, nextIdx)))
            if (nextIdx == n) break

            // Math chunk (preserve verbatim so MathJax can parse it)
            if (nextIdx == dollarIdx) {
                val isDouble = s.startsWith("$$", dollarIdx)
                val closeIdx = if (isDouble) s.indexOf("$$", dollarIdx + 2) else s.indexOf('$', dollarIdx + 1)
                val end = if (closeIdx >= 0) closeIdx + if (isDouble) 2 else 1 else n
                sb.append(s.substring(dollarIdx, end))
                i = end
            } else if (nextIdx == bracketIdx) {
                val closeIdx = s.indexOf("\\]", bracketIdx + 2)
                val end = if (closeIdx >= 0) closeIdx + 2 else n
                sb.append(s.substring(bracketIdx, end))
                i = end
            } else { // paren
                val closeIdx = s.indexOf("\\)", parenIdx + 2)
                val end = if (closeIdx >= 0) closeIdx + 2 else n
                sb.append(s.substring(parenIdx, end))
                i = end
            }
        }
        return sb.toString()
    }


    private fun convertItemize(s: String): String {
        val rx = Regex("""\\begin\{itemize\}(.+?)\\end\{itemize\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val body  = m.groupValues[1]
            val parts = Regex("""(?m)^\s*\\item\s*""")
                .split(body)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@replace ""
            val lis = parts.joinToString("") { item ->
                // Use latexProseToHtmlWithMath to handle both \textbf and math
                val html = latexProseToHtmlWithMath(item)
                "<li>$html</li>"
            }
            """<ul style="margin:12px 0 12px 24px;">$lis</ul>"""
        }
    }

    private fun convertEnumerate(s: String): String {
        val rx = Regex("""\\begin\{enumerate\}(.+?)\\end\{enumerate\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            val body  = m.groupValues[1]
            // FIX: Use correct regex for splitting items
            val parts = Regex("""(?m)^\s*\\item\s*""")
                .split(body)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@replace ""
            val lis = parts.joinToString("") { item ->
                // Use latexProseToHtmlWithMath to handle both \textbf and math
                val html = latexProseToHtmlWithMath(item)
                "<li>$html</li>"
            }
            """<ol style=\"margin:12px 0 12px 24px;\">$lis</ol>"""
        }
    }

    private data class ColSpec(val align: String?, val widthPct: Int?)

    // --- Tables ---------------------------------------------------------------

    private fun convertTabulars(text: String): String {
        // We can’t rely on a single regex because colspec may nest (p{...}).
        val out = StringBuilder(text.length + 512)
        var i = 0
        while (true) {
            val start = text.indexOf("\\begin{tabular}{", i)
            if (start < 0) { out.append(text.substring(i)); break }
            out.append(text.substring(i, start))

            // Find balanced colspec: starts at the '{' after \begin{tabular}
            val colOpen = text.indexOf('{', start + "\\begin{tabular}".length)
            val colClose = findBalancedBrace(text, colOpen)
            if (colOpen < 0 || colClose < 0) { out.append(text.substring(start)); break }

            val spec = text.substring(colOpen + 1, colClose)
            val cols = parseColSpecBalanced(spec)

            // Body runs until matching \end{tabular}
            val endTag = text.indexOf("\\end{tabular}", colClose + 1)
            if (endTag < 0) { out.append(text.substring(start)); break }
            var body = text.substring(colClose + 1, endTag).trim()

            // Cleanups: booktabs, hlines, and row spacing \\[6pt]
            body = body
                .replace("\\toprule", "")
                .replace("\\midrule", "")
                .replace("\\bottomrule", "")
                .replace(Regex("""(?m)^\s*\\hline\s*$"""), "")
                .replace(Regex("""(?<!\\)\\\\\s*\[[^\]]*]"""), "\\\\") // turn \\[6pt] into \\

            // Split rows on unescaped \\  (allow trailing spaces)
            val rows = Regex("""(?<!\\)\\\\\s*""").split(body)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val trs = rows.joinToString("") { row ->
                val cells = row.split('&').map { it.trim() }
                var cellIdx = 0
                val tds = cols.joinToString("") { col ->
                    if (col.align == "space") {
                        "<td style=\"width:1em;border:none;\"></td>"
                    } else {
                        val raw = if (cellIdx < cells.size) cells[cellIdx] else ""
                        cellIdx++
                        val style = buildString {
                            if (col.align != null) append("text-align:${col.align};")
                            if (col.widthPct != null) append("width:${col.widthPct}%;")
                            append("padding:4px 8px;border:1px solid var(--border);vertical-align:top;")
                        }
                        val cellHtml = latexProseToHtmlWithMath(raw)
                        "<td style=\"$style\">$cellHtml</td>"
                    }
                }
                "<tr>$tds</tr>"
            }

            out.append("""<table style="border:1px solid var(--border);margin:12px 0;width:100%;">$trs</table>""")
            i = endTag + "\\end{tabular}".length
        }
        return out.toString()
    }

    /**
     * Parse a LaTeX tabular column spec (l, c, r, p{...}, |, @{}, !{}, >{}, etc.)
     * into a list of ColSpec (align, widthPct).
     * Ignores vertical rules and other decorations.
     */

    private fun parseColSpecBalanced(spec: String): List<ColSpec> {
        // Handle tokens: l c r | !{...} @{...} >{...} p{...}
        val cols = mutableListOf<ColSpec>()
        var i = 0
        fun skipGroup(openAt: Int): Int = findBalancedBrace(spec, openAt).coerceAtLeast(openAt)
        while (i < spec.length) {
            when (spec[i]) {
                'l' -> { cols += ColSpec("left", null);  i++ }
                'c' -> { cols += ColSpec("center", null); i++ }
                'r' -> { cols += ColSpec("right", null);  i++ }
                'p' -> {
                    val o = spec.indexOf('{', i + 1)
                    if (o > 0) {
                        val c = findBalancedBrace(spec, o)
                        val widthExpr = if (c > o) spec.substring(o + 1, c) else ""
                        cols += ColSpec("left", linewidthToPercent(widthExpr))
                        i = if (c > o) c + 1 else i + 1
                    } else i++
                }
                '|', ' ' -> i++
                '@', '!' , '>' -> {
                    val o = spec.indexOf('{', i + 1)
                    i = if (o > 0) skipGroup(o) + 1 else i + 1
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
        // DEPRECATED: Use latexProseToHtmlWithMath for all prose conversions to handle \textbf, \emph, etc. with balanced braces and math preservation.
        return latexProseToHtmlWithMath(s)
    }

    private fun convertHref(s: String): String =
        s.replace(Regex("""\\href\{([^}]*)\}\{([^}]*)\}""")) { m ->
            val url = m.groupValues[1]
            val txt = m.groupValues[2]
            """<a href="${escapeHtmlKeepBackslashes(url)}" target="_blank" rel="noopener">${escapeHtmlKeepBackslashes(txt)}</a>"""
        }

    private fun stripAuxDirectives(s: String): String {
        var t = s
        t = t.replace(Regex("""\\addcontentsline\{[^}]*\}\{[^}]*\}\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\nocite\{[^}]*\}"""), "")
        t = t.replace(Regex("""\\bibliographystyle\{[^}]*\}"""), "")
        t = t.replace(
            Regex("""\\bibliography\{[^}]*\}"""),
            """<div style="opacity:.7;margin:8px 0;">[References: compile in PDF mode]</div>"""
        )
        return t
    }
    // ─────────────────────────── SANITIZER ───────────────────────────

    /** Convert abstract/center/theorem-like to HTML; drop unknown NON-math envs; keep math envs intact. */
    private fun sanitizeForMathJaxProse(bodyText: String): String {
        var s = bodyText

        // Custom titlepage toggles used in your Canon
        s = s.replace("""\\titlepageOpen""".toRegex(), "")
            .replace("""\\titlepageClose""".toRegex(), "")

        // center → HTML
        // center
        s = s.replace(
            Regex("""\\begin\{center\}(.+?)\\end\{center\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m -> """<div style="text-align:center;">${latexProseToHtmlWithMath(m.groupValues[1].trim())}</div>""" }

        // abstract
        s = s.replace(
            Regex("""\\begin\{abstract\}(.+?)\\end\{abstract\}""", RegexOption.DOT_MATCHES_ALL)
        ) { m ->
            """
  <div style="padding:12px;border-left:3px solid var(--border); background:#6b728022; margin:12px 0;">
    <strong>Abstract.</strong> ${latexProseToHtmlWithMath(m.groupValues[1].trim())}
  </div>
  """.trimIndent()
        }

        // theorem-like
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

        // Math environments to preserve verbatim (add bmatrix, pmatrix, etc.)
        val mathEnvs = "(?:equation\\*?|align\\*?|gather\\*?|multline\\*?|flalign\\*?|alignat\\*?|bmatrix|pmatrix|vmatrix|Bmatrix|Vmatrix|smallmatrix)"
        s = s.replace(Regex("""\\begin\{(?!$mathEnvs)\\w+\}"""), "")
        s = s.replace(Regex("""\\end\{(?!$mathEnvs)\\w+\}"""), "")

        return s
    }


    // ───────────────────────── SIUNITX SHIMS ─────────────────────────

    private fun convertSiunitx(s: String): String {
        var t = s
        // \num{1.23e-4} → 1.23\times 10^{-4}
        t = t.replace(Regex("""\\num\{([^}]*)\}""")) { m ->
            val raw = m.groupValues[1].trim()
            val sci = Regex("""^\s*([+-]?\d+(?:\.\d+)?)[eE]([+-]?\d+)\s*$""").matchEntire(raw)
            if (sci != null) {
                val a = sci.groupValues[1]
                val b = sci.groupValues[2]
                "$a\\times 10^{${b}}"
            } else raw
        }
        // \si{m.s^{-1}} → \mathrm{m\,s^{-1}}
        t = t.replace(Regex("""\\si\{([^}]*)\}""")) { m ->
            val u = m.groupValues[1].replace(".", "\\,").replace("~", "\\,")
            "\\mathrm{$u}"
        }
        // \SI{<num>}{<unit>} → \num{...}\,\si{...}
        t = t.replace(Regex("""\\SI\{([^}]*)\}\{([^}]*)\}""")) { m ->
            val num  = m.groupValues[1]
            val unit = m.groupValues[2]
            "\\num{$num}\\,\\si{$unit}"
        }
        // common text encodings
        t = t.replace(Regex("""\\textasciitilde\{\}"""), "~")
            .replace(Regex("""\\textasciitilde"""), "~")
            .replace(Regex("""\\&"""), "&")
        return t
    }


    // ───────────────────────────── UTIL ─────────────────────────────

    /** Escape &,<,> but keep backslashes so MathJax sees TeX. */
    private fun escapeHtmlKeepBackslashes(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // After all conversions & before injectLineAnchors(...)
    private fun applyInlineFormattingOutsideTags(html: String): String {
        // Split into [text, <tag>, text, <tag>, ...]
        val rx = Regex("(<[^>]+>)")
        val parts = rx.split(html)
        val tags  = rx.findAll(html).map { it.value }.toList()

        val out = StringBuilder(html.length + 256)
        for (i in parts.indices) {
            // Format only non-HTML text chunks, preserving math
            out.append(latexProseToHtmlWithMath(parts[i]))
            if (i < tags.size) out.append(tags[i]) // reinsert the tag that followed
        }
        return out.toString()
    }


    /**
     * Insert invisible line anchors every Nth source line, but never *inside*
     * math ($...$, $$...$$, \[...\], \(...\)) or math environments.
     * Handles math regions spanning multiple lines robustly.
     */
    private fun injectLineAnchors(s: String, absOffset: Int, everyN: Int = 3): String {
        val mathEnvs = setOf(
            "equation","equation*","align","align*","gather","gather*",
            "multline","multline*","flalign","flalign*","alignat","alignat*"
        )
        var i = 0
        var line = 0
        var inDollar = false
        var inDoubleDollar = false
        var inBracket = false   // \[...\]
        var inParen = false     // \(...\)
        var envDepth = 0

        fun startsAt(idx: Int, tok: String) =
            idx + tok.length <= s.length && s.regionMatches(idx, tok, 0, tok.length)

        val sb = StringBuilder(s.length + 1024)
        while (i < s.length) {
            // toggle $$ first (so we don't flip single $ inside $$...$$)
            if (!inBracket && !inParen) {
                if (startsAt(i, "$$")) {
                    inDoubleDollar = !inDoubleDollar
                    sb.append("$$"); i += 2; continue
                }
                if (!inDoubleDollar && s[i] == '$') {
                    // Only toggle if not escaped
                    val prev = if (i > 0) s[i-1] else ' '
                    if (prev != '\\') {
                        inDollar = !inDollar
                        sb.append('$'); i += 1; continue
                    }
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
                val safeSpot = !inDollar && !inDoubleDollar && !inBracket && !inParen && envDepth == 0
                // Only insert anchor if the *previous* line ended outside math
                if (safeSpot && (line % everyN == 0)) {
                    val absLine = absOffset + line
                    sb.append("<span class=\"syncline\" data-abs=\"$absLine\"></span>")
                }
                i++; continue
            }

            sb.append(ch); i++
        }
        return sb.toString()
    }

    private fun convertTableEnvs(s: String): String {
        val rx = Regex("""\\begin\{table\}(?:\[[^\]]*])?(.+?)\\end\{table\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            var body = m.groupValues[1]
            // extract caption
            var captionHtml = ""
            val capRx = Regex("""\\caption\{([^}]*)\}""")
            val cap = capRx.find(body)
            if (cap != null) {
                captionHtml = """<figcaption style=\"opacity:.8;margin:6px 0 10px;\">${escapeHtmlKeepBackslashes(cap.groupValues[1])}</figcaption>"""
                body = body.replace(cap.value, "")
            }
            // drop \centering, labels
            body = body.replace(Regex("""\\centering"""), "")
                .replace(Regex("""\\label\{[^}]*\}"""), "")
            // wrap; tabular will be converted later
            """<figure style=\"margin:14px 0;\">$body$captionHtml</figure>"""
        }
    }

    private fun convertTheBibliography(s: String): String {
        val rx = Regex(
            """\\begin\{thebibliography\}\{[^}]*\}(.+?)\\end\{thebibliography\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        return rx.replace(s) { m ->
            val body = m.groupValues[1]
            // split on \bibitem{...}
            val entries = Regex("""\\bibitem\{[^}]*\}""")
                .split(body)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (entries.isEmpty()) return@replace ""
            val lis = entries.joinToString("") { "<li>${escapeHtmlKeepBackslashes(it)}</li>" }
            """<h4>References</h4><ol style="margin:12px 0 12px 24px;">$lis</ol>"""
        }
    }

    // --- Figures / includegraphics -------------------------------------------

    private fun convertFigureEnvs(s: String): String {
        val rx = Regex("""\\begin\{figure\}(?:\[[^\]]*])?(.+?)\\end\{figure\}""", RegexOption.DOT_MATCHES_ALL)
        return rx.replace(s) { m ->
            var body = m.groupValues[1]

            // Capture first \includegraphics in the figure
            var imgHtml = ""
            val inc = Regex("""\\includegraphics(?:\[[^\]]*])?\{([^}]*)\}""").find(body)
            if (inc != null) {
                val opts = Regex("""\\includegraphics(?:\[([^\]]*)])?\{([^}]*)\}""").find(inc.value)
                val (optStr, path) = if (opts != null) opts.groupValues[1] to opts.groupValues[2] else "" to inc.groupValues[1]
                val style = includeGraphicsStyle(optStr)
                imgHtml = """<img src="${escapeHtmlKeepBackslashes(path)}" alt="" style="$style">"""
                body = body.replace(inc.value, "")
            }

            // Caption
            var captionHtml = ""
            Regex("""\\caption\{([^}]*)\}""").find(body)?.let { c ->
                captionHtml = """<figcaption style="opacity:.8;margin:6px 0 10px;">${escapeHtmlKeepBackslashes(c.groupValues[1])}</figcaption>"""
                body = body.replace(c.value, "")
            }

            // Drop common LaTeX-only bits
            body = body.replace(Regex("""\\centering"""), "")
                .replace(Regex("""\\label\{[^}]*\}"""), "")
                .trim()

            // Whatever remains (rare) → prose
            val rest = if (body.isNotEmpty()) "<div>${latexProseToHtmlWithMath(body)}</div>" else ""
            """<figure style="margin:14px 0;text-align:center;">$imgHtml$captionHtml$rest</figure>"""
        }
    }

    private fun convertIncludeGraphics(s: String): String {
        // For standalone \includegraphics outside figure
        return s.replace(Regex("""\\includegraphics(?:\[([^\]]*)])?\{([^}]*)\}""")) { m ->
            val style = includeGraphicsStyle(m.groupValues[1])
            val path  = m.groupValues[2]
            """<img src="${escapeHtmlKeepBackslashes(path)}" alt="" style="$style">"""
        }
    }

    private fun includeGraphicsStyle(options: String): String {
        // Parse width=..., height=..., scale=... (simple mapping)
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
        // default
        return "max-width:100%;height:auto;"
    }

    /** Recursively inline all \input{...} and \include{...} files. */
    fun inlineInputs(source: String, baseDir: String, seen: MutableSet<String> = mutableSetOf()): String {
        val rx = Regex("""\\(input|include)\{([^}]+)\}""")

        var result: String = source

        rx.findAll(source).forEach { m ->
            val cmd = m.groupValues[1]
            val rawPath = m.groupValues[2]
            // Try .tex, .sty, or no extension
            val candidates = listOf(rawPath, "$rawPath.tex", "$rawPath.sty")
            val filePath = candidates
                .map { Paths.get(baseDir, it).toFile() }
                .firstOrNull { it.exists() && it.isFile }
            val absPath = filePath?.absolutePath
            if (absPath != null && absPath !in seen) {
                seen += absPath
                val fileText = filePath.readText()
                val inlined = inlineInputs(fileText, filePath.parent ?: baseDir, seen)
                result = result.replace(m.value, inlined)
            } else if (absPath != null && absPath in seen) {
                result = result.replace(m.value, "% Circular input: $rawPath %")
            } else {
                result = result.replace(m.value, "% Missing input: $rawPath %")
            }
        }
        return result
    }

    fun wrapWithInputs(texSource: String, mainFilePath: String): String {
        val baseDir = File(mainFilePath).parent ?: ""
        val fullSource = inlineInputs(texSource, baseDir)
        return wrap(fullSource)
    }
}

