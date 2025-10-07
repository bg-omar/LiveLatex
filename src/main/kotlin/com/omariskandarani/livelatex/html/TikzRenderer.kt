package com.omariskandarani.livelatex.html

import java.io.File
import java.security.MessageDigest

/**
 * Extracted TikZ renderer — functions kept intact (no refactors).
 * Note: LatexHtml should set TikzRenderer.currentBaseDir in wrapWithInputs().
 */
object TikzRenderer {

    // —— Config / cache ————————————————————————————————————————————————
    /** Base dir of the main .tex file (set by caller; mirrors LatexHtml’s). */
    var currentBaseDir: String? = null

    // --- path where we cache compiled SVGs (project-local)
    private fun tikzCacheDir(): File {
        val base = currentBaseDir?.let(::File) ?: File(".")
        val dir  = File(base, ".livelatex-cache/tikz")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // --- Home cache (used by renderTikzToSvg for SST macros)
    private val tikzCacheDirHome: File by lazy {
        val dir = File(System.getProperty("user.home"), ".livelatex/tikz-cache")
        dir.mkdirs(); dir
    }

    // Small utils (unchanged)
    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val b  = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }
    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun fileUrl(f: File) = f.toURI().toString()

    // ───────────────────────── TikZ preamble collector ─────────────────────────

    // helper: collect commands with a single balanced-brace argument
    private fun collectBalanced(cmd: String, s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val j = s.indexOf(cmd + "{", startIndex = i)
            if (j < 0) break
            val open = s.indexOf('{', j)
            val close = findBalancedBrace(s, open)
            if (open >= 0 && close > open) {
                out += s.substring(j, close + 1)
                i = close + 1
            } else {
                i = j + cmd.length
            }
        }
        return out
    }

    fun collectTikzPreamble(srcNoComments: String): String {
        val preamble = srcNoComments.substringBefore("\\begin{document}")

        // grab any \usepackage lines the user had (e.g., siunitx)
        val pkgs = Regex("""\\usepackage(?:\[[^\]]*])?\{[^}]+}""")
            .findAll(preamble).joinToString("\n") { it.value }

        // robust \tikzset{...} with balanced braces
        val tikzsets = collectBalanced("\\tikzset", preamble).joinToString("\n")

        // gather all libraries the user asked for anywhere in the preamble
        val libsSet = collectUsetikzlibsFromSource(preamble).toSortedSet()
        val libsLine = if (libsSet.isNotEmpty()) "\\usetikzlibrary{${libsSet.joinToString(",")}}\n" else ""

        val needsTikzCd = Regex("""\\begin\{tikzcd}|\\usepackage\{tikz-cd}""").containsMatchIn(preamble)

        return buildString {
            appendLine("\\usepackage{tikz}")
            if (needsTikzCd) appendLine("\\usepackage{tikz-cd}")
            if (pkgs.isNotBlank()) appendLine(pkgs)          // keep user’s other packages (e.g., siunitx)
            append(libsLine)                                 // only if non-empty
            if (tikzsets.isNotBlank()) appendLine(tikzsets)  // full, balanced \tikzset blocks
        }
    }


    // ───────────────────────── TikZ picture renderer ─────────────────────────

    fun convertTikzPictures(htmlLike: String, fullSourceNoComments: String, tikzPreamble: String): String {
        val rx = Regex(
            """\\begin\{tikzpicture}(\[[^\]]*])?(.+?)\\end\{tikzpicture}""",
            RegexOption.DOT_MATCHES_ALL
        )

        val userMacros = extractNewcommands(fullSourceNoComments)
        val texMacroDefs = buildTexNewcommands(userMacros)
        val srcLibs = collectUsetikzlibsFromSource(fullSourceNoComments)

        return rx.replace(htmlLike) { m ->
            val opts = m.groupValues[1]                 // includes the surrounding [ ]
            val body = m.groupValues[2].trim()

            // also look at options when deciding libs
            val hay = opts + "\n" + body

            val autoLibs = buildSet {
                // arrows.meta if -{Latex...} OR >=Latex in options
                if (Regex("""-\{?Latex""").containsMatchIn(hay) ||
                    Regex(""">=\s*Latex""").containsMatchIn(hay)) {
                    add("arrows.meta")
                }
                // positioning if "left/right/above/below=of"
                if (Regex("""\b(left|right|above|below)\s*=\s*|[^=]\bof\b""").containsMatchIn(hay)) {
                    add("positioning")
                }
                // hobby/topaths if hobby shortcuts or blanks appear (in opts or body)
                if (Regex("""use\s+Hobby\s+shortcut|invert\s+soft\s+blanks|\[blank=""").containsMatchIn(hay)) {
                    addAll(listOf("hobby","topaths"))
                }
                // knots stack (same as before, kept intact, just look in hay)
                if (Regex("""\\begin\{knot}|\bflip crossing/""").containsMatchIn(hay)) {
                    addAll(listOf("knots","hobby","intersections","decorations.pathreplacing","shapes.geometric","spath3","topaths"))
                }
            }

            val allLibs = (srcLibs + autoLibs).toSortedSet()
            val libsLine = if (allLibs.isNotEmpty()) "\\usetikzlibrary{${allLibs.joinToString(",")}}\n" else ""

            val texDoc = """
\documentclass[tikz,border=1pt]{standalone}
\usepackage{amsmath,amssymb,bm}
\usepackage{tikz}
$libsLine
$texMacroDefs
$tikzPreamble
\usetikzlibrary{
    spath3,
    intersections,
    arrows,
    knots,
    calc,
    hobby,
    decorations.pathreplacing,
    shapes.geometric,
}

% ---------------- Global styles (safe for 'knots') ----------------
\tikzset{
    knot diagram/every strand/.append style={
        line cap=round,
        line join=round,
        ultra thick,
        black
    },
    every knot/.style={line cap=round,line join=round,very thick},
    strand/.style={line cap=round,line join=round,line width=3pt,draw=black},
    over/.style={preaction={draw=white,line width=6.5pt}},
% DO NOT set a global "every path" here; it breaks internal clip paths.
}
% ------- TikZ Guide Lines -------
\newcommand{\SSTGuidesPoints}[2]{% #1=basename (e.g. P), #2=last index
    \foreach \i in {1,...,#2}{
      \fill[blue] (#1\i) circle (1.2pt);
      \node[blue,font=\scriptsize,above] at (#1\i) {\i};
    }
    \draw[gray!40, dashed]
    \foreach \i [remember=\i as \lasti (initially 1)] in {2,...,#2,1} { (#1\lasti)--(#1\i) };
}
\providecommand{\swirlarrow}{\rightsquigarrow}
\begin{document}
\begin{tikzpicture}$opts
$body
\end{tikzpicture}
\end{document}
        """.trimIndent()

            val key = sha1(texDoc)
            val cache = tikzCacheDir()
            val svg = File(cache, "$key.svg")
            if (svg.exists()) {
                return@replace """<span class="tikz-wrap" style="display:block;margin:12px 0;">${svg.readText()}</span>"""
            }

            val work = File(cache, key).apply { mkdirs() }
            val tex = File(work, "fig.tex").apply { writeText(texDoc) }

            ensureLocalTikzLibs(srcLibs, work)

            fun runLocal(cmd: List<String>): Pair<Int,String> {
                val pb = ProcessBuilder(cmd).directory(work).redirectErrorStream(true)
                TikzRenderer.currentBaseDir?.let { base ->
                    val sep = if (System.getProperty("os.name").contains("win", true)) ";" else ":"
                    val path = File(base).absolutePath
                    pb.environment()["TEXINPUTS"] = path + sep + File(path, "tex").absolutePath + sep
                }
                val p = pb.start()
                val log = p.inputStream.bufferedReader().readText()
                return p.waitFor() to log
            }

            val (pcode, plog) = runLocal(listOf("pdflatex","-interaction=nonstopmode","-halt-on-error","fig.tex"))
            File(work, "build.log").writeText(plog)
            if (pcode != 0 || !File(work,"fig.pdf").exists()) {
                val msg = htmlEscapeAll(plog.takeLast(4000))
                return@replace """<div class="tikz-error" style="color:#b91c1c;">[TikZ compile failed]<pre>$msg

[tip] Open: ${work.absolutePath.replace("\\","/")}/build.log</pre></div>"""
            }

            val (scode, slog) = runLocal(listOf("dvisvgm","--pdf","--no-fonts","-n","-o","fig.svg","fig.pdf"))
            File(work, "convert.log").writeText(slog)
            if (scode != 0 || !File(work,"fig.svg").exists()) {
                val msg = htmlEscapeAll(slog.takeLast(4000))
                return@replace """<div class="tikz-error" style="color:#b91c1c;">[dvisvgm failed]<pre>$msg

[tip] Open: ${work.absolutePath.replace("\\","/")}/convert.log</pre></div>"""
            }

            val svgText = File(work,"fig.svg").readText()
            svg.writeText(svgText)
            """<span class="tikz-wrap" style="display:block;margin:12px 0;">$svgText</span>"""
        }
    }

    // Detect and render macro calls like \SSTdown, \SSTHopfLink[...]{...}{...}, etc.
    fun convertSstTikzMacros(s: String, srcNoComments: String): String {
        // If user didn’t \usetikzlibrary{sstknots} but uses \SST* macros, add it.
        val preSeen = collectTikzPreamble(srcNoComments)
        val needsSst = Regex("""\\SST[A-Za-z]""").containsMatchIn(s) &&
                !Regex("""\\usetikzlibrary\{[^}]*\bsstknots\b""").containsMatchIn(preSeen)
        val preamble = if (needsSst) preSeen + "\n\\usetikzlibrary{sstknots}\n" else preSeen

        // Heuristic: match a \SSTName with optional [..] and up to 3 braces (your macros use ≤3)
        val rx = Regex("""\\SST[A-Za-z]+(?:\[[^\]]*])?(?:\{[^{}]*}){0,3}""")
        return rx.replace(s) { m ->
            val svg = renderTikzToSvg(preamble, m.value)
            if (svg != null)
                """<img src="${fileUrl(svg)}" alt="tikz" style="max-width:100%;height:auto;display:block;margin:10px auto;"/>"""
            else
                """<pre style="background:#0001;border:1px solid var(--border);padding:8px;overflow:auto;">[TikZ render failed; see cache logs]\n${escapeHtmlKeepBackslashes(m.value)}</pre>"""
        }
    }

    // Minimal standalone compiler used by convertSstTikzMacros (kept intact)
    private fun renderTikzToSvg(preamble: String, tikzEnvBlock: String): File? {
        val texDoc = """
        \documentclass[tikz,border=2pt]{standalone}
        \usepackage{amsmath,amssymb}
        $preamble
        \begin{document}
        $tikzEnvBlock
        \end{document}
    """.trimIndent()

        val h = sha256Hex(texDoc)
        val work = File(tikzCacheDirHome, h).apply { mkdirs() }
        val tex = File(work, "$h.tex")
        val pdf = File(work, "$h.pdf")
        val svg = File(work, "$h.svg")

        // Cache hit
        if (svg.exists()) return svg

        tex.writeText(texDoc)

        // Compile → PDF
        val (ok1, log1) = run(
            listOf("pdflatex", "-interaction=nonstopmode", "-halt-on-error",
                "-output-directory", work.absolutePath, tex.absolutePath),
            work
        )
        if (!ok1 || !pdf.exists()) {
            File(work, "build.log").writeText(log1)
            if (log1.contains("tikzlibrarysstknots.code.tex") && log1.contains("not found", true)) {
                return null
            }
            return null
        }

        // PDF → SVG
        val tools = findTikzTools()
        val (ok2, log2) =
            if (tools.dvisvgm != null)
                run(listOf(tools.dvisvgm, "--pdf", "--no-fonts", "--exact", "-n", pdf.absolutePath, "-o", svg.absolutePath), work)
            else if (tools.pdf2svg != null)
                run(listOf(tools.pdf2svg, pdf.absolutePath, svg.absolutePath), work)
            else false to "Neither dvisvgm nor pdf2svg is available."

        if (!ok2 || !svg.exists()) {
            File(work, "convert.log").writeText(log2)
            return null
        }
        return svg
    }

    // Try to find tools once and cache the result
    private data class TikzTools(val dvisvgm: String?, val pdf2svg: String?)
    private var _tikzTools: TikzTools? = null
    private fun findTikzTools(): TikzTools {
        _tikzTools?.let { return it }
        fun which(cmd: String): String? {
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val proc = ProcessBuilder(if (isWin) listOf("where", cmd) else listOf("which", cmd))
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val ok = proc.waitFor() == 0 && out.isNotBlank()
            return if (ok) out.lineSequence().firstOrNull()?.trim() else null
        }
        val tools = TikzTools(
            dvisvgm = which("dvisvgm"),
            pdf2svg = which("pdf2svg")
        )
        _tikzTools = tools
        return tools
    }

    private fun run(cmd: List<String>, cwd: File, timeoutMs: Long = 60_000): Pair<Boolean,String> {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)

        // >>> Add TEXINPUTS so pdflatex finds local *.tex/ TikZ libs in your project
        currentBaseDir?.let { base ->
            val sep = if (System.getProperty("os.name").contains("win", true)) ";" else ":"
            val path = File(base).absolutePath
            pb.environment()["TEXINPUTS"] = path + sep + File(path, "tex").absolutePath + sep
        }
        // <<<

        val p = pb.start()
        val out = StringBuilder()
        val t = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        t.start()
        val ok = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) { p.destroyForcibly(); return false to "Timeout running: $cmd\n$out" }
        return (p.exitValue() == 0) to out.toString()
    }

    // ───────────────────────── Support helpers (copied intact) ─────────────────

    // Balanced {…} used by several parsers
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

    // Build \newcommand lines for a standalone doc
    private data class Macro(val def: String, val nargs: Int)
    private fun buildTexNewcommands(macros: Map<String, Macro>): String {
        if (macros.isEmpty()) return ""
        val sb = StringBuilder()
        for ((name, m) in macros) {
            val nargs = m.nargs.coerceAtLeast(0)
            if (nargs == 0) sb.append("\\newcommand{\\$name}{${m.def}}\n")
            else            sb.append("\\newcommand{\\$name}[$nargs]{${m.def}}\n")
        }
        return sb.toString()
    }

    // Parse all \usetikzlibrary{...} occurrences
    private fun collectUsetikzlibsFromSource(src: String): Set<String> =
        Regex("""\\usetikzlibrary\{([^}]*)}""")
            .findAll(src)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    // Copy local custom libraries (e.g., tikzlibrarysstknots.code.tex) to the build dir
    private fun ensureLocalTikzLibs(libs: Set<String>, workDir: File) {
        val base = currentBaseDir?.let(::File) ?: return
        for (lib in libs) {
            if (lib.matches(Regex("""[a-zA-Z][a-zA-Z0-9\-]*"""))) {
                val fname = "tikzlibrary${lib}.code.tex"
                val candidates = listOf(
                    File(base, fname),
                    File(base, "tikz/$fname"),
                    File(base, "tex/$fname")
                )
                val srcFile = candidates.firstOrNull { it.exists() } ?: continue
                srcFile.copyTo(File(workDir, fname), overwrite = true)
            }
        }
    }

    // From LatexHtml: used in errors to make logs safe for HTML
    private fun htmlEscapeAll(s: String): String =
        s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

    // Parse \newcommand and \def (whole source, copied intact)
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

        // \def\foo{...}
        val rxDef = Regex("""\\def\\([A-Za-z@]+)\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)
        rxDef.findAll(s).forEach { m ->
            out.putIfAbsent(m.groupValues[1], Macro(m.groupValues[2].trim(), 0))
        }

        return out
    }
}

private fun TikzRenderer.escapeHtmlKeepBackslashes(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
