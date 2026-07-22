package com.omariskandarani.livelatex.html

import com.intellij.openapi.application.ApplicationManager
import com.omariskandarani.livelatex.core.LiveLatexSettings
import java.io.File
import java.nio.file.Paths
import java.util.LinkedHashMap
import kotlin.text.Regex
import kotlin.text.lowercase
import kotlin.text.replace

import com.omariskandarani.livelatex.html.convertDescription
import com.omariskandarani.livelatex.html.convertListEnvironmentsNested
import com.omariskandarani.livelatex.html.convertLlmark
import com.omariskandarani.livelatex.html.convertMulticols
import com.omariskandarani.livelatex.html.convertParagraphsOutsideTags
import com.omariskandarani.livelatex.html.convertTextblockStar

internal const val BEGIN_DOCUMENT = "\\begin{document}"
internal const val END_DOCUMENT = "\\end{document}"

internal fun slugify(s: String): String {
    var t = s.trim().lowercase()
    // Drop inline/display math (section titles often include `$...$` or `\(...\)`).
    t = Regex("""(?<!\\)\$[^$]*\$""").replace(t, "")
    t = Regex("""\\\(.*?\\\)""").replace(t, "")
    t = Regex("""\\\[.*?\\\]""").replace(t, "")
    // Unwrap simple braced command arguments (preserve inner words for slugs).
    t = t.replace(Regex("""\\[A-Za-z@]+\*?(?:\{([^{}]*)})?""")) { m ->
        m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ""
    }
    t = t.replace(Regex("""\\[A-Za-z@]+"""), "")
    t = t.replace(Regex("""[{}_^$\\]"""), "")
    t = t.replace(Regex("""[^a-z0-9]+"""), "-")
    return t.trim('-').ifBlank { "section" }
}

internal fun isEscaped(s: String, i: Int): Boolean {
    var k = i - 1
    var bs = 0
    while (k >= 0 && s[k] == '\\') { bs++; k-- }
    return (bs and 1) == 1
}

internal var currentBaseDir: String? = null
internal var lineMapOrigToMergedJson: String? = null
internal var lineMapMergedToOrigJson: String? = null
internal var charMapOrigToMergedJson: String? = null
internal var charMapMergedToOrigJson: String? = null
internal var srcMapJson: String? = null
/** Parsed char maps from the last [wrapWithInputs] call (for selection bridge). */
internal var lastCharOrigToMerged: IntArray = intArrayOf()
internal var lastCharMergedToOrig: IntArray = intArrayOf()

/**
 * Minimal LaTeX → HTML previewer for prose + MathJax math.
 * - Parses user \newcommand / \def into MathJax macros
 * - Converts common prose constructs (sections, lists, tables, theorems, etc.)
 * - Leaves math regions intact ($...$, \[...\], \(...\), equation/align/...)
 * - Inserts invisible line anchors to sync scroll with editor
 */
object LatexHtml {
    /** Sections from the last wrap (for the Sections dropdown without JS bridge). */
    var lastCollectedSections: List<Pair<String, String>> = emptyList()
        private set

    @Volatile
    var buildProgressHandler: ((String) -> Unit)? = null

    private fun reportBuildProgress(message: String) {
        checkBuildInterrupted()
        buildProgressHandler?.invoke(message)
    }

    private fun checkBuildInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Preview build cancelled")
        }
    }

    /** Map editor (orig) char offset → merged source offset. */
    fun charOrigToMerged(offset: Int): Int {
        val map = lastCharOrigToMerged
        if (map.isEmpty()) return offset
        if (offset < 0) return 0
        if (offset >= map.size) return map.last()
        return map[offset]
    }

    /** Map merged source char offset → editor (orig) offset. */
    fun charMergedToOrig(offset: Int): Int {
        val map = lastCharMergedToOrig
        if (map.isEmpty()) return offset
        if (offset < 0) return 0
        if (offset >= map.size) return map.last()
        return map[offset]
    }

    // ─────────────────────────── PUBLIC ENTRY ───────────────────────────

    fun wrap(texSource: String): String {
        return wrapInternal(texSource, usePreparedInputMaps = false)
    }

    private fun wrapInternal(texSource: String, usePreparedInputMaps: Boolean): String {
        /*
         * PIPELINE_ORDER — do not reorder without updating tests.
         * Prep: stripPreamble → stripLineComments → assembleSplitTitlepageMacros → expandZeroArgMacros
         *       → sanitizeForMathJaxProse → convertIncludeGraphics → TikZ/picture convert/placeholder
         * Prose (applyProseConversions): convertLlmark → convertMakeTitle → convertSiunitx
         *       → convertTextblockStar → convertHref → convertSections → convertFigureEnvs
         *       → convertIncludeGraphics → convertMulticols → convertLongtablesToTables
         *       → convertTcolorboxes → convertTableEnvs → convertListEnvironmentsNested
         *       → convertDescription → convertTabulars → convertTheBibliography → stripAuxDirectives
         * Inline: convertParagraphsOutsideTags → applyInlineFormattingOutsideTags
         *       → fixInlineBoundarySpaces → injectLineAnchors → SourceMapBuilder.applySourceMap
         * formatInlineProseNonMath: \\[dim] before generic \\; replaceCmd1ArgBalanced after unescape.
         * TikZ/picture are converted once in wrapInternal (not again in applyProseConversions).
         */
        LatexTikzJobStore.clear()
        val renderTikz = renderTikzInPreviewEnabled()

        if (isHtmlOnlyPreviewInput(texSource)) {
            clearPreviewSourceMaps()
            lastCollectedSections = emptyList()
            return buildHtml(texSource, macrosJs = "")
        }

        val srcNoComments = stripLineComments(texSource)
        val userMacros    = extractNewcommands(srcNoComments)
        val macrosJs      = buildMathJaxMacros(userMacros, srcNoComments)
        val titleMeta     = extractTitleMeta(srcNoComments)
        val tikzPreamble  = TikzRenderer.collectTikzPreamble(srcNoComments)


        // Find body & absolute line offset of the first body line
        val beginIdx  = texSource.indexOf(BEGIN_DOCUMENT)
        val absOffset = if (beginIdx >= 0)
            texSource.substring(0, beginIdx).count { it == '\n' } + 1
        else
            1

        val body0 = stripPreamble(texSource)
        val body1 = stripLineComments(body0)
        reportBuildProgress("Parsing macros…")
        checkBuildInterrupted()
        val body1a = assembleSplitTitlepageMacros(body1, userMacros)
        val body1b = expandZeroArgMacros(body1a, userMacros)
        checkBuildInterrupted()
        val body2 = sanitizeForMathJaxProse(body1b)
        val body2a = unwrapResizebox(body2)
        val body2b = convertIncludeGraphics(body2a)

        val body2c: String
        val body2d: String
        val body2e: String
        try {
            if (renderTikz) {
                val nTikz = TikzRenderer.countTikzPictureStarts(body2b)
                val nSst = TikzRenderer.countSstStandaloneMacros(body2b)
                val nPic = PictureRenderer.countPictureStarts(body2b)
                TikzRenderer.initLiveRenderProgress(nTikz + nSst + nPic)
                if (nTikz + nSst + nPic > 0) reportBuildProgress("TikZ 0 / ${nTikz + nSst + nPic}")
            }
            reportBuildProgress("Rendering TikZ…")
            body2c = if (renderTikz)
                TikzRenderer.convertTikzPictures(body2b, srcNoComments, tikzPreamble)
            else
                TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(body2b, srcNoComments, tikzPreamble)
            body2d = if (renderTikz)
                TikzRenderer.convertSstTikzMacros(body2c, srcNoComments)
            else
                TikzRenderer.replaceSstTikzMacrosWithPlaceholder(body2c)
            body2e = if (renderTikz)
                PictureRenderer.convertPictures(body2d)
            else
                PictureRenderer.replacePicturesWithLazyPlaceholder(body2d)
        } finally {
            TikzRenderer.finishLiveRenderProgressPhase()
        }

        // Clear leftover \setlength{\unitlength} after pictures became SVG/lazy HTML.
        val body2f = stripLegacyPictureCommands(body2e)

        reportBuildProgress("Converting prose…")
        checkBuildInterrupted()

        lastCollectedSections = collectSectionsList(body2f, absOffset)
        val body3 = applyProseConversions(body2f, titleMeta, absOffset, srcNoComments, tikzPreamble)
        checkBuildInterrupted()
        reportBuildProgress("Building source map…")
        val body3b = convertParagraphsOutsideTags(body3)
        val body4 = applyInlineFormattingOutsideTags(body3b)
        val body4c = fixInlineBoundarySpaces(body4)
        // Insert anchors (no blanket escaping here; we preserve math)
        val withAnchors = injectLineAnchors(body4c, absOffset, everyN = 1)
        val sourceMap = SourceMapBuilder.applySourceMap(withAnchors, texSource)
        srcMapJson = sourceMap.json

        if (!usePreparedInputMaps) {
            installIdentityPreviewMaps(texSource)
        }

        return buildHtml(sourceMap.html, macrosJs)
    }

    private fun isHtmlOnlyPreviewInput(source: String): Boolean {
        if (source.contains(BEGIN_DOCUMENT)) return false
        val trimmed = source.trimStart()
        if (!trimmed.startsWith("<")) return false
        return Regex(
            """^<(?:!doctype\s+html\b|html\b|head\b|body\b|main\b|article\b|section\b|div\b|p\b|span\b|h[1-6]\b|ul\b|ol\b|li\b|pre\b|code\b|blockquote\b|table\b|figure\b|figcaption\b|strong\b|em\b|small\b|a\b|br\b|hr\b)""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(trimmed)
    }

    private fun clearPreviewSourceMaps() {
        lineMapOrigToMergedJson = "[]"
        lineMapMergedToOrigJson = "[]"
        charMapOrigToMergedJson = "[]"
        charMapMergedToOrigJson = "[]"
        srcMapJson = "[]"
        lastCharOrigToMerged = intArrayOf()
        lastCharMergedToOrig = intArrayOf()
    }

    private fun installIdentityPreviewMaps(source: String) {
        val lineCount = source.count { it == '\n' } + 1
        val lineMap = IntArray(lineCount) { it + 1 }
        lineMapOrigToMergedJson = lineMap.joinToString(prefix = "[", postfix = "]") { it.toString() }
        lineMapMergedToOrigJson = lineMapOrigToMergedJson

        val charMap = IntArray(source.length + 1) { it }
        lastCharOrigToMerged = charMap
        lastCharMergedToOrig = charMap.copyOf()
        charMapOrigToMergedJson = SourceMapBuilder.charMapToJson(lastCharOrigToMerged)
        charMapMergedToOrigJson = charMapOrigToMergedJson
    }

    private fun renderTikzInPreviewEnabled(): Boolean {
        val app = ApplicationManager.getApplication() ?: return false
        return app.getService(LiveLatexSettings::class.java)?.renderTikzInPreview ?: false
    }


    // buildHtml() extracted to LatexHtmlTemplate.kt


    private fun applyProseConversions(s: String, meta: TitleMeta, absOffset: Int,
                                      fullSourceNoComments: String, tikzPreamble: String): String {
        var t = s
        t = convertLlmark(t, absOffset)
        t = convertMakeTitle(t, meta)
        t = convertSiunitx(t)
        t = convertTextblockStar(t)
        t = convertHref(t)
        t = convertSections(t, absOffset)
        t = convertFigureEnvs(t)
        t = convertIncludeGraphics(t)
        t = convertMulticols(t)

        t = convertLongtablesToTables(t)                 // longtable → table/tabular
        t = convertTcolorboxes(t)                        // ← NEW: render tcolorbox

        t = convertTableEnvs(t)
        t = convertListEnvironmentsNested(t)
        t = convertDescription(t)
        t = convertTabulars(t)
        t = convertTheBibliography(t)
        t = stripAuxDirectives(t)
        return t
    }

    // Parsing helpers -> LatexHtmlParsing.kt

    // formatInlineProseNonMath, convertParagraphsOutsideTags -> LatexHtmlProse.kt

    // convertSections, convertLlmark, unescapeLatexSpecials, latexProseToHtmlWithMath, MATH_ENVS,
    // convertMulticols, convertListEnvironmentsNested, convertDescription -> LatexHtmlProse.kt

    // peelTopLevelTextWrapper, ColSpec, convertTcolorboxes, parseTcolorOptions, findBalancedBraceAllowMath,
    // xcolorToCss, convertTabulars, parseColSpecBalanced, linewidthToPercent, convertHref, stripAuxDirectives,
    // convertTableEnvs, convertLongtablesToTables, convertFigureEnvs, convertTheBibliography,
    // sanitizeForMathJaxProse, convertSiunitx -> LatexHtmlSanitizer.kt

    // fixInlineBoundarySpaces, TitleMeta, findLastCmdArg, extractTitleMeta, renderDate, splitAuthors,
    // processThanksWithin, buildMakTitleHtml, convertMakeTitle, escapeHtmlKeepBackslashes,
    // applyInlineFormattingOutsideTags, applyInlineFormattingOutsideTags_NoTables, proseNoBr,
    // htmlEscapeAll, replaceTextSymbols, injectLineAnchors, toFileUrl, resolveImagePath,
    // convertIncludeGraphics, includeGraphicsStyle -> LatexHtmlUtils.kt

    /** Compile a queued lazy TikZ job by key into the cache. Returns the SVG File on success, null on failure. */
    @JvmStatic
    fun renderLazyTikzKeyToSvg(key: String): File? {
        val texDoc = LatexTikzJobStore.get(key) ?: return null
        return LatexHtmlTikz.renderTexToSvg(texDoc, key)
    }

    // (extracted code removed - see LatexHtmlProse, LatexHtmlBlocks, LatexHtmlSanitizer, LatexHtmlUtils, LatexHtmlTikz)

    /** Recursively inline all \input{...} and \include{...} files. */
    fun inlineInputs(source: String, baseDir: String, seen: MutableSet<String> = mutableSetOf()): String {
        val rx = Regex("""\\(input|include)\{([^}]+)\}""")

        var result: String = source

        rx.findAll(source).forEach { m ->
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
        currentBaseDir = baseDir
        TikzRenderer.currentBaseDir = baseDir

        reportBuildProgress("Inlining \\input files…")
        val markerPrefix = "%%LLM"
        val origLines = texSource.split('\n')
        val marked = buildString(texSource.length + origLines.size * 10) {
            origLines.forEachIndexed { idx, line ->
                append(markerPrefix).append(idx + 1).append("%%").append(line)
                if (idx < origLines.lastIndex) append('\n')
            }
        }
        val inlinedMarked = inlineInputs(marked, baseDir)

        // Compute mapping orig line (1-based) -> merged line (1-based)
        val o2m = IntArray(origLines.size) { it + 1 }
        var searchFrom = 0
        for (i in 1..origLines.size) {
            val token = markerPrefix + i + "%%"
            val idx = inlinedMarked.indexOf(token, searchFrom)
            val pos = if (idx >= 0) idx else inlinedMarked.indexOf(token)
            if (pos >= 0) {
                val before = inlinedMarked.substring(0, pos)
                val mergedLine = before.count { it == '\n' } + 1
                o2m[i - 1] = mergedLine
                if (idx >= 0) searchFrom = idx + token.length
            } else {
                // token not found (rare): fallback to previous mapping or 1
                o2m[i - 1] = if (i > 1) o2m[i - 2] else 1
            }
        }

        // Strip markers
        val fullSource = inlinedMarked.replace(Regex("""${markerPrefix}\d+%%"""), "")

        // Build inverse mapping merged -> original using step function (last original line at/ before m)
        val mergedLinesCount = fullSource.count { it == '\n' } + 1
        val m2o = IntArray(mergedLinesCount) { 1 }
        var j = 0 // index into o2m (0-based)
        for (m in 1..mergedLinesCount) {
            while (j + 1 < o2m.size && o2m[j + 1] <= m) j++
            m2o[m - 1] = j + 1 // original line number (1-based)
        }

        // Cache JSON strings for HTML embedding (package-level vars for buildHtml)
        lineMapOrigToMergedJson = o2m.joinToString(prefix = "[", postfix = "]") { it.toString() }
        lineMapMergedToOrigJson = m2o.joinToString(prefix = "[", postfix = "]") { it.toString() }

        val o2mChar = SourceMapBuilder.buildOrigToMergedCharMap(texSource, inlinedMarked, markerPrefix)
        val mergedLen = fullSource.length
        val m2oChar = SourceMapBuilder.buildMergedToOrigCharMap(o2mChar, mergedLen)
        charMapOrigToMergedJson = SourceMapBuilder.charMapToJson(o2mChar)
        charMapMergedToOrigJson = SourceMapBuilder.charMapToJson(m2oChar)
        lastCharOrigToMerged = o2mChar
        lastCharMergedToOrig = m2oChar

        val html = wrapInternal(fullSource, usePreparedInputMaps = true)
        // keep baseDir for subsequent renders; do not clear to allow incremental refreshes
        return html
    }
}