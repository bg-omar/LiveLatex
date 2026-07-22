package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for [LatexHtmlBlocks]. These lock down the CURRENT behavior of the
 * table/figure/box converters and the brace/color helpers. They are golden-master style:
 * where behavior looks surprising it is captured as-is and flagged with `// NOTE:`.
 */
class LatexHtmlBlocksTest {

    @After
    fun tearDown() {
        currentBaseDir = null
    }

    // ── findBalancedBraceAllowMath ────────────────────────────────────────────

    @Test
    fun findBalancedBraceAllowMath_skipsBraceInsideInlineMath() {
        // The '}' at index 5 is inside $...$ and must be ignored; the closer is the final '}'.
        val s = "{a \$x}\$ b}"
        assertEquals(s.lastIndex, findBalancedBraceAllowMath(s, 0))
    }

    @Test
    fun findBalancedBraceAllowMath_returnsMinusOneWhenNotBrace() {
        assertEquals(-1, findBalancedBraceAllowMath("abc", 0))
    }

    @Test
    fun findBalancedBraceAllowMath_nestedBraces() {
        val s = "{a{b}c}"
        assertEquals(s.lastIndex, findBalancedBraceAllowMath(s, 0))
    }

    // ── parseColSpecBalanced ──────────────────────────────────────────────────

    @Test
    fun parseColSpecBalanced_simpleLcr() {
        val cols = parseColSpecBalanced("lcr")
        assertEquals(listOf("left", "center", "right"), cols.map { it.align })
        assertTrue(cols.all { it.widthPct == null })
    }

    @Test
    fun parseColSpecBalanced_pColumnWithLinewidth() {
        val cols = parseColSpecBalanced("""p{0.5\linewidth}""")
        assertEquals(1, cols.size)
        assertEquals("left", cols[0].align)
        assertEquals(50, cols[0].widthPct)
    }

    @Test
    fun parseColSpecBalanced_prefixGroupAndTextwidthYieldsNullWidth() {
        // NOTE: '>{...}' prefix group is skipped; \textwidth is NOT recognized by linewidthToPercent -> null.
        val cols = parseColSpecBalanced("""@{}>{\raggedleft\arraybackslash}p{0.21\textwidth}@{}""")
        assertEquals(1, cols.size)
        assertEquals("left", cols[0].align)
        assertNull(cols[0].widthPct)
    }

    // ── linewidthToPercent ────────────────────────────────────────────────────

    @Test
    fun linewidthToPercent_variants() {
        assertEquals(50, linewidthToPercent("""0.5\linewidth"""))
        assertEquals(50, linewidthToPercent("50%"))
        assertNull(linewidthToPercent("""0.21\textwidth"""))
        assertNull(linewidthToPercent("garbage"))
    }

    // ── xcolorToCss ───────────────────────────────────────────────────────────

    @Test
    fun xcolorToCss_namedColors() {
        assertEquals("#000000", xcolorToCss("black"))
        assertEquals("#ffffff", xcolorToCss("white"))
        assertEquals("#dc2626", xcolorToCss("red"))
    }

    @Test
    fun xcolorToCss_mixAndUnknown() {
        assertEquals("#7f7f7f", xcolorToCss("black!50!white"))
        // Unknown name falls back to the SST default blue.
        assertEquals("#1e3a8a", xcolorToCss("chartreuse"))
    }

    // ── parseTcolorOptions ────────────────────────────────────────────────────

    @Test
    fun parseTcolorOptions_commaSeparated() {
        val kv = parseTcolorOptions("colback=blue!5!white, title=Hello")
        assertEquals("blue!5!white", kv["colback"])
        assertEquals("Hello", kv["title"])
    }

    @Test
    fun parseTcolorOptions_bracedValueKeepsCommas() {
        val kv = parseTcolorOptions("title={A, B}")
        assertEquals("A, B", kv["title"])
    }

    // ── convertTcolorboxes ────────────────────────────────────────────────────

    @Test
    fun convertTcolorboxes_rendersTitleAndBody() {
        val out = convertTcolorboxes("""\begin{tcolorbox}[title=Note]Body text\end{tcolorbox}""")
        assertTrue(out.contains("""class="tcb""""))
        assertTrue(out.contains("Note"))
        assertTrue(out.contains("Body text"))
    }

    // ── convertTabulars ───────────────────────────────────────────────────────

    @Test
    fun convertTabulars_buildsHtmlTable() {
        val out = convertTabulars("""\begin{tabular}{lc}a & b \\ c & d\end{tabular}""")
        assertTrue(out.contains("<table"))
        assertTrue(out.contains("<tr>"))
        assertTrue(out.contains("<td"))
        assertTrue(out.contains("a"))
        assertTrue(out.contains("d"))
    }

    @Test
    fun convertTabulars_testfileSnippet_keepsAlphaRowSeparate() {
        // Mirrors testfile.tex: center sanitize turns `\\` into <br/> before convertTabulars.
        val snippet = """
            \begin{tabular}{l|c|r}
              \textbf{Value 1} & \textbf{Value 2} & \textbf{Value 3}<br/>
              $\alpha$ & $\beta$ & $\gamma$ <br/>
              \hline
              1 & 1110.1 & a\\
            \end{tabular}
        """.trimIndent()
        val out = convertTabulars(snippet)
        val value3 = Regex("""<td[^>]*>\s*<strong>Value 3</strong>\s*</td>""").find(out)
        assertTrue("Value 3 alone in header cell", value3 != null)
        assertFalse("no tex2jax_ignore in Value 3 cell", value3!!.value.contains("tex2jax_ignore"))
        assertTrue(
            "alpha cell intact",
            Regex("""<td[^>]*>\s*\$\\alpha\$\s*</td>""").containsMatchIn(out),
        )
        assertTrue(
            "beta cell intact",
            Regex("""<td[^>]*>\s*\$\\beta\$\s*</td>""").containsMatchIn(out),
        )
        assertFalse("hline must not leak as prose", out.contains(">hline"))
        assertFalse("must not glue a\\\\2 into a\\2", out.contains("""a\2"""))
    }

    @Test
    fun convertTabulars_rowBreakBeforeDollar_splitsTwoRows() {
        val out = convertTabulars(
            """\begin{tabular}{c}Value 3\\
$\alpha$\end{tabular}"""
        )
        assertTrue(out.contains("<strong>") || out.contains("Value 3"))
        assertTrue(out.contains("""$\alpha$"""))
        assertFalse(out.contains("tex2jax_ignore"))
        // Two data rows (header + alpha), not one glued cell
        assertEquals(2, Regex("""<tr>""").findAll(out).count())
    }

    // ── convertTableEnvs ──────────────────────────────────────────────────────

    @Test
    fun convertTableEnvs_figureWithCaptionAndLabelId() {
        val out = convertTableEnvs("""\begin{table}[h]\centering\caption{My Cap}\label{tab:x}BODY\end{table}""")
        assertTrue(out.contains("<figure"))
        assertTrue(out.contains("""id="tab:x""""))
        assertTrue(out.contains("My Cap"))
        assertTrue(out.contains("BODY"))
    }

    // ── convertLongtablesToTables ─────────────────────────────────────────────

    @Test
    fun convertLongtablesToTables_rewritesToTabularLatex() {
        val out = convertLongtablesToTables(
            """\begin{longtable}{ll}\toprule a & b \\ \endhead c & d \\\end{longtable}"""
        )
        // Rewrites into a table/tabular block (later converted by convertTabulars).
        assertTrue(out.contains("""\begin{tabular}{ll}"""))
        assertTrue(out.contains("""\begin{table}"""))
        assertFalse(out.contains("\\toprule"))
        assertFalse(out.contains("\\endhead"))
    }

    // ── convertFigureEnvs ─────────────────────────────────────────────────────

    @Test
    fun convertFigureEnvs_imageCaptionAndLabel() {
        val out = convertFigureEnvs(
            """\begin{figure}\centering\includegraphics[width=0.5\linewidth]{img}\caption{Fig Cap}\label{fig:x}\end{figure}"""
        )
        assertTrue(out.contains("<figure"))
        assertTrue(out.contains("<figcaption"))
        assertTrue(out.contains("Fig Cap"))
        assertTrue(out.contains("""id="fig:x""""))
    }

    @Test
    fun figureEnv_imageNeverUsesRawPdfUrl() {
        val out = convertFigureEnvs(
            """\begin{figure}\centering\includegraphics{width=0.5\linewidth]{missing}\caption{Cap}\end{figure}"""
        )
        assertFalse(Regex("""<img[^>]+src="[^"]*\.pdf""", RegexOption.IGNORE_CASE).containsMatchIn(out))
        assertTrue(out.contains("ll-figure-unavailable") || out.contains("<img"))
    }

    @Test
    fun unwrapResizebox_stripsWrapperKeepsBody() {
        val src = """\resizebox{\textwidth}{!}{\begin{tikzpicture}\draw (0,0)--(1,1);\end{tikzpicture}}"""
        val out = unwrapResizebox(src)
        assertFalse(out.contains("""\resizebox{"""))
        assertTrue(out.contains("tikzpicture"))
    }

    @Test
    fun unwrapResizebox_noResizebox_returnsInputUnchanged() {
        val src = """\begin{tikzpicture}\draw (0,0)--(1,1);\end{tikzpicture}"""
        val out = unwrapResizebox(src)
        assertEquals(src, out)
    }

    @Test
    fun unwrapResizebox_withTrailingContent_keepsSuffixOnce() {
        val src = """before \resizebox{\textwidth}{!}{inner} after"""
        val out = unwrapResizebox(src)
        assertEquals("before inner after", out)
    }

    @Test
    fun convertSubfloats_rendersPanels() {
        val out = convertSubfloats(
            """\subfloat[Cap A]{text A}\hfill\subfloat[Cap B]{text B}"""
        )
        assertFalse(out.contains("""\subfloat["""))
        assertTrue(out.contains("subfloat-row"))
        assertTrue(out.contains("Cap A"))
    }

    // ── convertHref ───────────────────────────────────────────────────────────

    @Test
    fun convertHref_urlBecomesAnchor() {
        val out = convertHref("""See \url{https://example.com/path} here""")
        assertFalse(out.contains("""\url{"""))
        assertTrue(out.contains("""href="https://example.com/path""""))
    }

    @Test
    fun convertHref_anchorWithTargetBlank() {
        val out = convertHref("""See \href{https://example.com}{the link} now""")
        assertTrue(out.contains("""href="https://example.com""""))
        assertTrue(out.contains("the link"))
        assertTrue(out.contains("""target="_blank""""))
    }

    // ── convertTheBibliography ────────────────────────────────────────────────

    @Test
    fun convertTheBibliography_listsEntries() {
        val out = convertTheBibliography(
            """\begin{thebibliography}{9}\bibitem{keyA} First ref.\bibitem{keyB} Second ref.\end{thebibliography}"""
        )
        assertTrue(out.contains("<h4>References</h4>"))
        assertTrue(out.contains("""id="keyA""""))
        assertTrue(out.contains("""id="keyB""""))
        assertTrue(out.contains("First ref."))
    }

    @Test
    fun convertTheBibliography_emptyWhenBodyHasNoBibitem() {
        // Body present (whitespace) but no \bibitem => environment collapses to empty string.
        val out = convertTheBibliography("\\begin{thebibliography}{9}\n  \n\\end{thebibliography}")
        assertEquals("", out)
    }

    // ── stripAuxDirectives / stripTitleAuthorDate ─────────────────────────────

    @Test
    fun stripAuxDirectives_removesNociteAndBibStyleAndAnnotatesBibliography() {
        val out = stripAuxDirectives("""\nocite{x}\bibliographystyle{plain}\bibliography{refs}Body""")
        assertFalse(out.contains("\\nocite"))
        assertFalse(out.contains("\\bibliographystyle"))
        assertTrue(out.contains("[References: compile in PDF mode]"))
        assertTrue(out.contains("Body"))
    }

    @Test
    fun stripTitleAuthorDate_removesFrontMatterCommands() {
        val out = stripTitleAuthorDate("""\title{T}\author{A}\date{D}Body""")
        assertEquals("Body", out)
    }

    // ── peelTopLevelTextWrapper ───────────────────────────────────────────────

    @Test
    fun peelTopLevelTextWrapper_peelsTextbf() {
        val (inner, tag) = peelTopLevelTextWrapper("""\textbf{Hello}""")
        assertEquals("Hello", inner)
        assertEquals("strong", tag)
    }

    @Test
    fun peelTopLevelTextWrapper_plainTextUnchanged() {
        val (inner, tag) = peelTopLevelTextWrapper("plain text")
        assertEquals("plain text", inner)
        assertNull(tag)
    }

    @Test
    fun peelTopLevelTextWrapper_notPeeledWhenTrailingContent() {
        // Trailing content after the wrapper => not peeled (returned unchanged, tag null).
        val raw = """\textbf{a} tail"""
        val (inner, tag) = peelTopLevelTextWrapper(raw)
        assertEquals(raw, inner)
        assertNull(tag)
    }
}
