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
import com.omariskandarani.livelatex.html.convertEnumerate
import com.omariskandarani.livelatex.html.convertItemize
import com.omariskandarani.livelatex.html.convertLlmark
import com.omariskandarani.livelatex.html.convertMulticols
import com.omariskandarani.livelatex.html.convertParagraphsOutsideTags

internal const val BEGIN_DOCUMENT = "\\begin{document}"
internal const val END_DOCUMENT = "\\end{document}"

internal fun slugify(s: String): String =
    s.lowercase()
        .replace(Regex("""\\[A-Za-z@]+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

internal fun isEscaped(s: String, i: Int): Boolean {
    var k = i - 1
    var bs = 0
    while (k >= 0 && s[k] == '\\') { bs++; k-- }
    return (bs and 1) == 1
}

internal var currentBaseDir: String? = null
internal var lineMapOrigToMergedJson: String? = null
internal var lineMapMergedToOrigJson: String? = null

/**
 * Minimal LaTeX → HTML previewer for prose + MathJax math.
 * - Parses user \newcommand / \def into MathJax macros
 * - Converts common prose constructs (sections, lists, tables, theorems, etc.)
 * - Leaves math regions intact ($...$, \[...\], \(...\), equation/align/...)
 * - Inserts invisible line anchors to sync scroll with editor
 */
object LatexHtml {
    /** Secties uit de laatste wrap (voor Secties-dropdown zonder JS-bridge). */
    var lastCollectedSections: List<Pair<String, String>> = emptyList()
        private set

    // ─────────────────────────── PUBLIC ENTRY ───────────────────────────

    fun wrap(texSource: String): String {
        LatexTikzJobStore.clear()
        val srcNoComments = stripLineComments(texSource)
        val userMacros    = extractNewcommands(srcNoComments)
        val macrosJs      = buildMathJaxMacros(userMacros)
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
        val body1b = expandZeroArgMacros(body1, userMacros)
        val body2 = sanitizeForMathJaxProse(body1b)
        val body2b = convertIncludeGraphics(body2)

        val renderTikz = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview
        val body2c = if (renderTikz)
            TikzRenderer.convertTikzPictures(body2b, srcNoComments, tikzPreamble)
        else
            TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(body2b, srcNoComments, tikzPreamble)
        val body2d = if (renderTikz)
            TikzRenderer.convertSstTikzMacros(body2c, srcNoComments)
        else
            TikzRenderer.replaceSstTikzMacrosWithPlaceholder(body2c)

        lastCollectedSections = collectSectionsList(body2d, absOffset)
        val body3 = applyProseConversions(body2d, titleMeta, absOffset, srcNoComments, tikzPreamble)
        val body3b = convertParagraphsOutsideTags(body3)
        val body4 = applyInlineFormattingOutsideTags(body3b)
        val body4c = fixInlineBoundarySpaces(body4)
        // Insert anchors (no blanket escaping here; we preserve math)
        val withAnchors = injectLineAnchors(body4c, absOffset, everyN = 1)

        return buildHtml(withAnchors, macrosJs)
    }


    // buildHtml() extracted to LatexHtmlTemplate.kt


    private fun applyProseConversions(s: String, meta: TitleMeta, absOffset: Int,
                                      fullSourceNoComments: String, tikzPreamble: String): String {
        var t = s
        t = convertLlmark(t, absOffset)
        t = convertMakeTitle(t, meta)
        t = convertSiunitx(t)
        t = convertHref(t)
        t = convertSections(t, absOffset)
        t = convertFigureEnvs(t)
        t = convertIncludeGraphics(t)
        t = convertMulticols(t)

        t = convertLongtablesToTables(t)                 // longtable → table/tabular
        t = convertTcolorboxes(t)                        // ← NEW: render tcolorbox
        t = if (ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview)
            TikzRenderer.convertTikzPictures(t, fullSourceNoComments, tikzPreamble)
        else
            TikzRenderer.replaceTikzPicturesWithLazyPlaceholder(t, fullSourceNoComments, tikzPreamble)

        t = convertTableEnvs(t)
        t = convertItemize(t)
        t = convertEnumerate(t)
        t = convertDescription(t)
        t = convertTabulars(t)
        t = convertTheBibliography(t)
        t = stripAuxDirectives(t)
        return t
    }

    // Parsing helpers -> LatexHtmlParsing.kt

    // formatInlineProseNonMath, convertParagraphsOutsideTags -> LatexHtmlProse.kt

    // convertSections, convertLlmark, unescapeLatexSpecials, latexProseToHtmlWithMath, MATH_ENVS,
    // convertMulticols, convertItemize, convertEnumerate, convertDescription -> LatexHtmlProse.kt

    // peelTopLevelTextWrapper, ColSpec, convertTcolorboxes, parseTcolorOptions, findBalancedBraceAllowMath,
    // xcolorToCss, convertTabulars, parseColSpecBalanced, linewidthToPercent, convertHref, stripAuxDirectives,
    // convertTableEnvs, convertLongtablesToTables, convertFigureEnvs, convertTheBibliography,
    // convertAlignWithMultipleTagsToBlocks -> LatexHtmlBlocks.kt

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

        // Build marked source to compute orig→merged line mapping across \input/\include expansions
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

        val html = wrap(fullSource)
        // keep baseDir for subsequent renders; do not clear to allow incremental refreshes
        return html
    }
}