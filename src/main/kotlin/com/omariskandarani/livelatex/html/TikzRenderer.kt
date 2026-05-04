package com.omariskandarani.livelatex.html

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extracted TikZ renderer — functions kept intact (no refactors).
 * Note: LatexHtml should set TikzRenderer.currentBaseDir in wrapWithInputs().
 * When [pluginCacheRoot] is set (e.g. by the IDE plugin), cache is stored there instead of next to the .tex file.
 */
object TikzRenderer {

    // —— Config / cache ————————————————————————————————————————————————
    /** Base dir of the main .tex file (set by caller; mirrors LatexHtml's). Used for TEXINPUTS and \input resolution. */
    var currentBaseDir: String? = null

    /** When set (e.g. by JetBrains IDE), TikZ cache is stored here instead of .livelatex-cache next to the .tex file. */
    var pluginCacheRoot: String? = null

    // —— LiveRender progress (preview build thread → EDT via [setLiveRenderProgressHandler]) ——
    private val liveRenderStep = AtomicInteger(0)
    @Volatile private var liveRenderTotal: Int = 0
    @Volatile private var liveRenderHandler: ((current: Int, total: Int, detail: String) -> Unit)? = null

    fun setLiveRenderProgressHandler(handler: ((current: Int, total: Int, detail: String) -> Unit)?) {
        liveRenderHandler = handler
    }

    fun initLiveRenderProgress(totalSteps: Int) {
        liveRenderTotal = totalSteps.coerceAtLeast(0)
        liveRenderStep.set(0)
    }

    private fun bumpLiveRenderProgress(detail: String) {
        val tot = liveRenderTotal
        val h = liveRenderHandler ?: return
        if (tot <= 0) return
        val c = liveRenderStep.incrementAndGet().coerceAtMost(tot)
        h.invoke(c, tot, detail)
    }

    fun finishLiveRenderProgressPhase() {
        liveRenderTotal = 0
        liveRenderStep.set(0)
    }

    // --- path where we cache compiled SVGs (plugin cache or project-local)
    private fun tikzCacheDir(): File {
        val dir = if (pluginCacheRoot != null) {
            File(pluginCacheRoot, "tikz")
        } else {
            val base = currentBaseDir?.let(::File) ?: File(".")
            File(base, ".livelatex-cache/tikz")
        }
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

    private fun buildTexInputs(base: String): String {
        val sep = if (System.getProperty("os.name").contains("win", true)) ";" else ":"
        val dirs = linkedSetOf<String>()
        var cur: File? = File(base).absoluteFile
        var depth = 0
        while (cur != null && depth < 4) {
            dirs += cur.absolutePath
            dirs += File(cur, "tex").absolutePath
            cur = cur.parentFile
            depth++
        }
        // Trailing separator preserves default TeX search path.
        return dirs.joinToString(sep) + sep
    }

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

    /** Full preamble from the .tex (before \\begin{document}) for TikZ; strips \\documentclass, ensures tikz/ams present. */
    fun collectTikzPreamble(srcNoComments: String): String {
        var preamble = srcNoComments.substringBefore("\\begin{document}").trim()
        preamble = Regex("""\\documentclass\s*(?:\[[^\]]*])?\s*\{[^}]*\}""").replace(preamble, "").trim()
        // Common typo: \usePackage is undefined; LaTeX expects \usepackage
        preamble = preamble.replace("\\usePackage{", "\\usepackage{")
        // Cache builds run in a temp dir; normalize parent-relative package imports.
        preamble = Regex("""\\usepackage(\[[^\]]*])?\{\.{2}/([^}]+)}""").replace(preamble, "\\usepackage$1{$2}")
        // Normalize accidental escaped command form (\\pgfplotsset -> \pgfplotsset)
        // often produced by copied markdown/HTML snippets.
        preamble = preamble.replace("\\\\pgfplotsset", "\\pgfplotsset")
        // Strip document-level journal metadata commands that can appear in article preambles
        // but are undefined in standalone figure compilation.
        val metadataCommands = listOf(
            "firstpage", "pubvolume", "issuenum", "articlenumber", "pubyear", "copyrightyear",
            "datereceived", "daterevised", "dateaccepted", "datepublished", "hreflink",
            "Title", "TitleCitation", "Author", "AuthorNames", "AuthorCitation",
            "address", "corres", "abstract", "keyword"
        )
        for (cmd in metadataCommands) {
            collectBalanced("\\$cmd", preamble).forEach { snippet ->
                preamble = preamble.replace(snippet, "")
            }
        }
        // If first-page metadata was present, this helper line can linger and break standalone builds.
        preamble = Regex("""\\setcounter\{page}\{\s*\\@firstpage\s*}""").replace(preamble, "")
        // Trailing comma only inside \usetikzlibrary{...} (never globally: that corrupts nested \tikzset).
        preamble = Regex("""\\usetikzlibrary\{([^}]*)\}""").replace(preamble) { m ->
            val inner = m.groupValues[1].trimEnd().trimEnd(',')
            "\\usetikzlibrary{$inner}"
        }

        // Standalone fig.tex emits \tikzset blocks again via [tikzsetDefs]; strip here to avoid duplicates.
        // (Duplicate global \tikzset can confuse pgf/spath3 and trigger spurious errors.)
        preamble = removeTikzsetBlocks(preamble).trim()

        // stix loads textcomp with its own options; a separate \usepackage{textcomp,...} causes option clash.
        if (Regex("""\\usepackage(?:\[[^\]]*])?\{[^}]*\bstix\b[^}]*\}""").containsMatchIn(preamble)) {
            preamble = Regex("""\\usepackage(\[[^\]]*])?\{([^}]*)\}""").replace(preamble) { m ->
                val opt = m.groupValues[1]
                val pkgs = m.groupValues[2].split(',').map { it.trim() }.filter {
                    it.isNotEmpty() && !it.equals("textcomp", ignoreCase = true)
                }
                if (pkgs.isEmpty()) "" else "\\usepackage$opt{${pkgs.joinToString(",")}}"
            }.trim()
        }

        val hasTikz = Regex("""\\usepackage\s*(?:\[[^\]]*])?\{[^}]*tikz[^}]*\}""").containsMatchIn(preamble)
        val hasAms = Regex("""\\usepackage\s*(?:\[[^\]]*])?\{[^}]*amsmath[^}]*\}""").containsMatchIn(preamble)
        val needsTikzCd = Regex("""\\begin\{tikzcd}|\\usepackage\{tikz-cd}""").containsMatchIn(preamble)
        val prefix = buildString {
            if (!hasTikz) appendLine("\\usepackage{tikz}")
            if (needsTikzCd) appendLine("\\usepackage{tikz-cd}")
            // amssymb/bm with stix (or other full math fonts) can exceed the symbol-font limit; amsmath alone is enough for most figures.
            if (!hasAms) appendLine("\\usepackage{amsmath}")
        }
        return if (prefix.isBlank()) preamble else prefix + "\n" + preamble
    }

    // ───────────────────────── TikZ picture renderer ─────────────────────────

    /** Find the end of a tikzpicture block, accounting for nested \begin{tikzpicture}...\end{tikzpicture}. */
    private fun findMatchingEndTikzpicture(s: String, bodyStart: Int): Int {
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
     * Matches preset knot macros rendered standalone (\\SSTdown, \\SSTHopfLink{...}, …).
     * Excludes \\SSTGuidesPoints: it is a tikz-internals helper, not a full figure.
     */
    private val sstStandaloneMacroRegex =
        Regex("""\\SST(?!GuidesPoints)[A-Za-z]+(?:\[[^\]]*])?(?:\{[^{}]*}){0,3}""")

    /** Count of `\\begin{tikzpicture}` occurrences (used for LiveRender progress totals). */
    fun countTikzPictureStarts(s: String): Int {
        var c = 0
        var i = 0
        val tok = "\\begin{tikzpicture}"
        while (i < s.length) {
            val j = s.indexOf(tok, i)
            if (j < 0) break
            c++
            i = j + tok.length
        }
        return c
    }

    fun countSstStandaloneMacros(s: String): Int = sstStandaloneMacroRegex.findAll(s).count()

    /** When TikZ rendering is disabled: replace each tikzpicture block with a placeholder span (no compilation). */
    fun replaceTikzPicturesWithPlaceholder(htmlLike: String): String {
        val beginTok = "\\begin{tikzpicture}"
        val placeholder = """<span class="tikz-placeholder" style="display:block;margin:8px 0;padding:6px 10px;background:#f0f0f0;color:#666;font-size:12px;border-radius:4px;">[TikZ uit – zet LiveRender aan in de preview-balk om te renderen]</span>"""
        val result = StringBuilder(htmlLike.length)
        var pos = 0
        while (true) {
            val start = htmlLike.indexOf(beginTok, pos)
            if (start < 0) break
            result.append(htmlLike, pos, start)
            var bodyStart = start + beginTok.length
            if (bodyStart < htmlLike.length && htmlLike[bodyStart] == '[') {
                val closeBracket = htmlLike.indexOf(']', bodyStart)
                if (closeBracket >= 0) bodyStart = closeBracket + 1
            }
            val bodyEnd = findMatchingEndTikzpicture(htmlLike, bodyStart)
            if (bodyEnd >= 0) {
                result.append(placeholder)
                pos = bodyEnd
            } else {
                result.append(htmlLike, start, bodyStart.coerceAtMost(htmlLike.length))
                pos = bodyStart
            }
        }
        result.append(htmlLike, pos, htmlLike.length)
        return result.toString()
    }

    /**
     * Lazy TikZ: replace each tikzpicture with a placeholder that has a "Render" button.
     * Registers each block in LatexTikzJobStore so the host can compile on demand (lighter for IDE/Android).
     */
    fun replaceTikzPicturesWithLazyPlaceholder(htmlLike: String, fullSourceNoComments: String, tikzPreamble: String): String {
        val userMacros = extractNewcommands(fullSourceNoComments)
        val texMacroDefs = buildTexNewcommands(userMacros)
        val preambleOnly = fullSourceNoComments.substringBefore("\\begin{document}")
        val tikzsetDefs = collectTikzsetBlocksFromSource(preambleOnly)
        val srcLibs = collectUsetikzlibsFromSource(fullSourceNoComments)
        val beginTok = "\\begin{tikzpicture}"
        val result = StringBuilder(htmlLike.length)
        var pos = 0
        while (true) {
            val start = htmlLike.indexOf(beginTok, pos)
            if (start < 0) break
            result.append(htmlLike, pos, start)
            var opts = ""
            var bodyStart = start + beginTok.length
            if (bodyStart < htmlLike.length && htmlLike[bodyStart] == '[') {
                val closeBracket = htmlLike.indexOf(']', bodyStart)
                if (closeBracket >= 0) {
                    opts = htmlLike.substring(bodyStart, closeBracket + 1)
                    bodyStart = closeBracket + 1
                }
            }
            val bodyEnd = findMatchingEndTikzpicture(htmlLike, bodyStart)
            if (bodyEnd < 0) {
                result.append(htmlLike, start, (start + beginTok.length).coerceAtMost(htmlLike.length))
                pos = bodyStart
                continue
            }
            val body = htmlLike.substring(bodyStart, bodyEnd - "\\end{tikzpicture}".length).trim()
            val blockDoc = buildTikzBlockDoc(body, opts, tikzPreamble, texMacroDefs, tikzsetDefs, srcLibs, userMacros.keys)
            if (blockDoc == null) {
                result.append(htmlLike, start, bodyEnd)
                pos = bodyEnd
                continue
            }
            val (docKey, texDoc) = blockDoc
            val key = "$docKey-$start"
            LatexTikzJobStore.put(key, texDoc)
            val lazyHtml = """<span class="tikz-lazy" data-tikz-key="$key" style="display:block;margin:8px 0;padding:8px 12px;background:#f0f0f0;color:#666;font-size:12px;border-radius:4px;"><button type="button" class="tikz-load" data-tikz-key="$key" style="margin-right:8px;padding:4px 10px;cursor:pointer;border:1px solid #ccc;border-radius:4px;background:#fff;">LiveRender</button><span class="tikz-status"></span></span>"""
            result.append(lazyHtml)
            pos = bodyEnd
        }
        result.append(htmlLike, pos, htmlLike.length)
        return result.toString()
    }

    /**
     * Remove \\newcommand/\\renewcommand/\\providecommand{...} blocks from a tikzpicture body when those
     * names are already emitted in [texMacroDefs] as \\providecommand. Otherwise LaTeX errors with
     * "Command \\X already defined" (extractNewcommands lifts macros into the standalone preamble).
     */
    private fun stripTikzBodyMacroRedeclarations(body: String, injectedMacroNames: Set<String>): String {
        if (injectedMacroNames.isEmpty()) return body
        var s = body
        val cmds = listOf("newcommand", "renewcommand", "providecommand")
        for (name in injectedMacroNames) {
            for (cmd in cmds) {
                s = stripLatexCmdDefinition(s, cmd, name)
            }
        }
        return s
    }

    private fun stripLatexCmdDefinition(s: String, cmd: String, name: String): String {
        val escapedName = Regex.escape(name)
        val pattern = Regex("""\\$cmd\{\\$escapedName}(?:\[\d+])?(?:\[[^\]]*])?\{""")
        val sb = StringBuilder()
        var pos = 0
        while (true) {
            val m = pattern.find(s, pos) ?: break
            sb.append(s, pos, m.range.first)
            val bodyOpen = m.range.last
            val bodyClose = findBalancedBrace(s, bodyOpen)
            if (bodyClose < 0) {
                sb.append(s, m.range.first, s.length)
                return sb.toString()
            }
            pos = bodyClose + 1
        }
        sb.append(s, pos, s.length)
        return sb.toString()
    }

    /**
     * Blank lines inside `\begin{knot}[ ... ]` become `\par` in TeX and break the optional-argument
     * scan ("Paragraph ended before \environment knot was complete"). Collapse the bracket list to
     * one line and strip comments so standalone TikZ jobs match normal LaTeX behavior.
     */
    private fun normalizeKnotOptionalArguments(body: String): String {
        return try {
            val marker = "\\begin{knot}"
            val sb = StringBuilder(body.length)
            var i = 0
            while (i < body.length) {
                val j = body.indexOf(marker, i)
                if (j < 0) {
                    sb.append(body, i, body.length)
                    break
                }
                sb.append(body, i, j)
                sb.append(marker)
                val afterMarker = j + marker.length
                var k = afterMarker
                while (k < body.length && body[k].isWhitespace()) k++
                if (k >= body.length || body[k] != '[') {
                    sb.append(body, afterMarker, k.coerceAtMost(body.length))
                    i = k
                    continue
                }
                // Include '[' (end index is exclusive; was `k` before and dropped the bracket).
                sb.append(body, afterMarker, k + 1)
                val innerStart = k + 1
                var p = innerStart
                var braceDepth = 0
                var closeIdx = -1
                while (p < body.length) {
                    when (body[p]) {
                        '{' -> {
                            braceDepth++
                            p++
                        }
                        '}' -> {
                            braceDepth--
                            p++
                        }
                        ']' -> if (braceDepth == 0) {
                            closeIdx = p
                            break
                        } else p++
                        '%' -> if (braceDepth == 0) {
                            while (p < body.length && body[p] != '\n' && body[p] != '\r') p++
                            while (p < body.length && (body[p] == '\n' || body[p] == '\r')) p++
                        } else p++
                        else -> p++
                    }
                }
                if (closeIdx < 0) {
                    sb.append(body, (innerStart - 1).coerceAtLeast(0), body.length)
                    break
                }
                val inner = body.substring(innerStart, closeIdx)
                var collapsed = inner
                    .replace(Regex("""\\par\b"""), " ")
                    .replace(Regex("""%[^\n\r]*"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                // Draft crossing mode is for TeX editor debugging; it suppresses normal over/under and
                // crossing labels often vanish in PDF→SVG. "Only when rendering" is unreliable in standalone.
                collapsed = collapsed
                    .replace(Regex("""draft\s+mode\s*=\s*crossings\s*,?"""), "")
                    .replace(Regex(""",\s*draft\s+mode\s*=\s*crossings"""), "")
                    .replace(Regex("""only\s+when\s+rendering\s*/\s*\.style\s*=\s*\{[^{}]*\}\s*,?"""), "")
                    .replace(Regex(""",\s*only\s+when\s+rendering\s*/\s*\.style\s*=\s*\{[^{}]*\}"""), "")
                    .replace(Regex(""",\s*,+"""), ",")
                    .trim()
                    .removePrefix(",")
                    .removeSuffix(",")
                    .trim()
                sb.append(collapsed)
                sb.append(']')
                i = closeIdx + 1
            }
            sb.toString()
        } catch (_: Throwable) {
            // Fail-open: never crash preview build on normalization edge cases.
            body
        }
    }

    /** Build full standalone tex document for one tikzpicture block; returns (sha1Key, texDoc) or null. */
    private fun buildTikzBlockDoc(
        body: String,
        opts: String,
        tikzPreamble: String,
        texMacroDefs: String,
        tikzsetDefs: String,
        srcLibs: Set<String>,
        injectedMacroNames: Set<String>,
    ): Pair<String, String>? {
        val scrubbedBody = normalizeKnotOptionalArguments(
            stripTikzBodyMacroRedeclarations(body.trim(), injectedMacroNames),
        )
        val hay = opts + "\n" + scrubbedBody
        val autoLibs = buildSet {
            if (Regex("""-\{?Latex""").containsMatchIn(hay) || Regex(""">=\s*Latex""").containsMatchIn(hay)) add("arrows.meta")
            if (Regex("""\b(left|right|above|below)\s*=\s*|[^=]\bof\b""").containsMatchIn(hay)) add("positioning")
            if (Regex("""use\s+Hobby\s+shortcut|invert\s+soft\s+blanks|\[blank=""").containsMatchIn(hay)) addAll(listOf("hobby","topaths"))
            if (Regex("""\\begin\{knot}|\bflip crossing/""").containsMatchIn(hay)) addAll(listOf("knots","hobby","intersections","decorations.pathreplacing","shapes.geometric","spath3","topaths"))
        }
        val allLibs = (srcLibs + autoLibs).toSortedSet()
        val libsLine = if (allLibs.isNotEmpty()) "\\usetikzlibrary{${allLibs.joinToString(",")}}\n" else ""
        val needsPgfplots = Regex("""\\begin\{axis\}|\\addplot|\\pgfplot|xlabel\s*=|ylabel\s*=""").containsMatchIn(hay)
        val hasPgfplotsInPreamble = Regex("""\\usepackage\s*(?:\[[^\]]*])?\s*\{[^}]*pgfplots[^}]*\}""").containsMatchIn(tikzPreamble)
        val (docClass, preambleBlock) = if (needsPgfplots && !hasPgfplotsInPreamble) {
            // Figure uses axis/xlabel/ylabel but preamble has no pgfplots: add it
            "\\documentclass[border=1pt]{standalone}" to """
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
$tikzPreamble
"""
        } else if (needsPgfplots) {
            "\\documentclass[border=1pt]{standalone}" to """
$tikzPreamble
\pgfplotsset{compat=1.18}
"""
        } else {
            "\\documentclass[tikz,border=1pt]{standalone}" to """
$tikzPreamble
"""
        }
        val texDoc = """
$docClass
$preambleBlock
$libsLine
$texMacroDefs
\usetikzlibrary{
    spath3,
    intersections,
    arrows,
    arrows.meta,
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
    every knot/.style={line cap=round,line join=round,very thick,clip width=6pt,clip radius=3pt},
    strand/.style={line cap=round,line join=round,line width=3pt,draw=black},
    over/.style={preaction={draw=white,line width=8pt}},
% DO NOT set a global "every path" here; it breaks internal clip paths.
}
\providecommand{\swirlarrow}{\rightsquigarrow}
\begin{document}
$tikzsetDefs
\begin{tikzpicture}$opts
$scrubbedBody
\end{tikzpicture}
\end{document}
        """.trimIndent()
        return sha1(texDoc) to texDoc
    }

    fun convertTikzPictures(htmlLike: String, fullSourceNoComments: String, tikzPreamble: String): String {
        val userMacros = extractNewcommands(fullSourceNoComments)
        val texMacroDefs = buildTexNewcommands(userMacros)
        val preambleOnly = fullSourceNoComments.substringBefore("\\begin{document}")
        val tikzsetDefs = collectTikzsetBlocksFromSource(preambleOnly)
        val srcLibs = collectUsetikzlibsFromSource(fullSourceNoComments)
        val beginTok = "\\begin{tikzpicture}"
        val result = StringBuilder(htmlLike.length)
        var pos = 0

        while (true) {
            val start = htmlLike.indexOf(beginTok, pos)
            if (start < 0) break
            result.append(htmlLike, pos, start)
            bumpLiveRenderProgress("tikzpicture")

            var opts = ""
            var bodyStart = start + beginTok.length
            if (bodyStart < htmlLike.length && htmlLike[bodyStart] == '[') {
                val closeBracket = htmlLike.indexOf(']', bodyStart)
                if (closeBracket >= 0) {
                    opts = htmlLike.substring(bodyStart, closeBracket + 1)
                    bodyStart = closeBracket + 1
                }
            }
            val bodyEnd = findMatchingEndTikzpicture(htmlLike, bodyStart)
            if (bodyEnd < 0) {
                result.append(htmlLike, start, (start + beginTok.length).coerceAtMost(htmlLike.length))
                pos = bodyStart
                continue
            }
            val body = htmlLike.substring(bodyStart, bodyEnd - "\\end{tikzpicture}".length).trim()
            val blockDoc = buildTikzBlockDoc(body, opts, tikzPreamble, texMacroDefs, tikzsetDefs, srcLibs, userMacros.keys)
            if (blockDoc == null) {
                result.append(htmlLike, start, bodyEnd)
                pos = bodyEnd
                continue
            }
            val (key, texDoc) = blockDoc
            val cache = tikzCacheDir()
            val svg = File(cache, "$key.svg")
            if (svg.exists()) {
                result.append(
                    """<span class="tikz-wrap" style="display:block;margin:12px 0;"><img src="${fileUrl(svg)}" alt="tikz" style="max-width:100%;height:auto;display:block;"/></span>"""
                )
                pos = bodyEnd
                continue
            }

            val work = File(cache, key).apply { mkdirs() }
            val texFile = File(work, "fig.tex")
            val pdfFile = File(work, "fig.pdf")
            val svgFile = File(work, "fig.svg")
            texFile.writeText(texDoc)

            ensureLocalTikzLibs(srcLibs, work)

            fun runLocal(cmd: List<String>, timeoutMs: Long = 120_000): Pair<Int, String> {
                val pb = ProcessBuilder(cmd).directory(work).redirectErrorStream(true)
                TikzRenderer.currentBaseDir?.let { base ->
                    pb.environment()["TEXINPUTS"] = buildTexInputs(base)
                }
                val p = pb.start()
                val out = StringBuilder()
                val drain = Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { line -> out.appendLine(line) }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                drain.isDaemon = true
                drain.start()
                val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    drain.interrupt()
                }
                try {
                    drain.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val log = out.toString()
                if (!finished) {
                    return -1 to "Timeout running: $cmd\n$log"
                }
                return p.exitValue() to log
            }

            val (pcode, plog) = runLocal(
                listOf("pdflatex","-interaction=nonstopmode","-halt-on-error", texFile.absolutePath)
            )
            File(work, "build.log").writeText(plog)
            if (pcode != 0 || !pdfFile.exists()) {
                val msg = htmlEscapeAll(plog.takeLast(4000))
                result.append("""<div class="tikz-error" style="color:#b91c1c;">[TikZ compile failed]<pre>$msg

[tip] Open: ${work.absolutePath.replace("\\","/")}/build.log</pre></div>""")
                pos = bodyEnd
                continue
            }

            val (scode, slog) = runLocal(
                listOf(
                    "dvisvgm", "--pdf", "--no-fonts", "--exact", "-n",
                    pdfFile.absolutePath, "-o", svgFile.absolutePath,
                )
            )
            File(work, "convert.log").writeText(slog)
            if (scode != 0 || !svgFile.exists()) {
                val msg = htmlEscapeAll(slog.takeLast(4000))
                result.append("""<div class="tikz-error" style="color:#b91c1c;">[dvisvgm failed]<pre>$msg

[tip] Open: ${work.absolutePath.replace("\\","/")}/convert.log</pre></div>""")
            } else {
                val svgText = svgFile.readText()
                svg.writeText(svgText)
                result.append(
                    """<span class="tikz-wrap" style="display:block;margin:12px 0;"><img src="${fileUrl(svg)}" alt="tikz" style="max-width:100%;height:auto;display:block;"/></span>"""
                )
            }
            pos = bodyEnd
        }
        result.append(htmlLike, pos, htmlLike.length)
        return result.toString()
    }

    /** When TikZ rendering is disabled: replace \SST... macro calls with a placeholder (no compilation). */
    fun replaceSstTikzMacrosWithPlaceholder(s: String): String {
        val placeholder = """<span class="tikz-placeholder" style="display:inline-block;margin:4px 0;padding:4px 8px;background:#f0f0f0;color:#666;font-size:11px;border-radius:4px;">[TikZ uit]</span>"""
        return sstStandaloneMacroRegex.replace(s) { placeholder }
    }

    // Detect and render macro calls like \SSTdown, \SSTHopfLink[...]{...}{...}, etc.
    fun convertSstTikzMacros(s: String, srcNoComments: String): String {
        // If user didn't \usetikzlibrary{sstknots} but uses \SST* macros, add it.
        val preSeen = collectTikzPreamble(srcNoComments)
        val needsSst = Regex("""\\SST(?!GuidesPoints)[A-Za-z]""").containsMatchIn(s) &&
                !Regex("""\\usetikzlibrary\{[^}]*\bsstknots\b""").containsMatchIn(preSeen)
        val preamble = if (needsSst) preSeen + "\n\\usetikzlibrary{sstknots}\n" else preSeen

        // Heuristic: match a \SSTName with optional [..] and up to 3 braces (your macros use ≤3)
        val rx = sstStandaloneMacroRegex
        return rx.replace(s) { m ->
            bumpLiveRenderProgress("SST macro")
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

    private fun run(cmd: List<String>, cwd: File, timeoutMs: Long = 180_000): Pair<Boolean,String> {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)

        // >>> Add TEXINPUTS so pdflatex finds local *.tex/ TikZ libs in your project
        currentBaseDir?.let { base ->
            pb.environment()["TEXINPUTS"] = buildTexInputs(base)
        }
        // <<<

        val p = pb.start()
        val out = StringBuilder()
        val t = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line -> out.appendLine(line) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        t.start()
        val ok = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) {
            p.destroyForcibly()
            t.interrupt()
        }
        try {
            t.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val log = out.toString()
        if (!ok) return false to "Timeout running: $cmd\n$log"
        return (p.exitValue() == 0) to log
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
            if (nargs == 0) sb.append("\\providecommand{\\$name}{${m.def}}\n")
            else            sb.append("\\providecommand{\\$name}[$nargs]{${m.def}}\n")
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

    /** Remove every balanced `\tikzset{...}` block (preamble cleanup; see [collectTikzPreamble]). */
    private fun removeTikzsetBlocks(s: String): String {
        val sb = StringBuilder()
        var i = 0
        val cmd = "\\tikzset"
        while (true) {
            val j = s.indexOf(cmd, i)
            if (j < 0) {
                sb.append(s, i, s.length)
                break
            }
            sb.append(s, i, j)
            var k = j + cmd.length
            while (k < s.length && s[k].isWhitespace()) k++
            if (k < s.length && s[k] == '{') {
                val close = findBalancedBrace(s, k)
                if (close > k) {
                    i = close + 1
                    continue
                }
            }
            sb.append(cmd)
            i = j + cmd.length
        }
        return sb.toString()
    }

    // Parse \tikzset{...} occurrences from the document preamble only (before \begin{document}).
    // Hoisting \tikzset from inside tikzpicture bodies breaks spath3 and order-dependent styles.
    private fun collectTikzsetBlocksFromSource(src: String): String {
        val out = mutableListOf<String>()
        var i = 0
        val cmd = "\\tikzset"
        while (true) {
            val j = src.indexOf(cmd, i)
            if (j < 0) break
            var k = j + cmd.length
            while (k < src.length && src[k].isWhitespace()) k++
            if (k < src.length && src[k] == '{') {
                val close = findBalancedBrace(src, k)
                if (close > k) {
                    out += src.substring(j, close + 1).trim()
                    i = close + 1
                    continue
                }
            }
            i = k
        }
        return out.joinToString("\n")
    }

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

internal fun TikzRenderer.escapeHtmlKeepBackslashes(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
