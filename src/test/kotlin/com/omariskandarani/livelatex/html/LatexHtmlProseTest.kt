package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for [LatexHtmlProse]: sections, lists, multicols, description lists,
 * llmark anchors, and the low-level scanning helpers. Golden-master style (locks current behavior).
 */
class LatexHtmlProseTest {

    // ── convertSections ───────────────────────────────────────────────────────

    @Test
    fun convertSections_sectionBecomesH2WithSlugId() {
        val out = convertSections("""\section{Intro}""", absOffset = 1)
        assertTrue(out.contains("""<h2 id="section-intro" class="ll-section-heading">Intro</h2>"""))
        assertTrue(out.contains("""class="llmark""""))
        assertTrue(out.contains("""data-abs="""))
    }

    @Test
    fun convertSections_subsectionAndSubsubsectionLevels() {
        val out = convertSections("""\subsection{Methods}\subsubsection{Detail}""", absOffset = 1)
        assertTrue(out.contains("""<h3 id="subsection-methods" class="ll-section-heading">Methods</h3>"""))
        assertTrue(out.contains("""<h4 id="subsubsection-detail" class="ll-section-heading">Detail</h4>"""))
    }

    @Test
    fun convertSections_paragraphBecomesH5() {
        val out = convertSections("""\paragraph{Note}""", absOffset = 1)
        assertTrue(out.contains("""<h5 id="paragraph-note" class="ll-section-heading""""))
        assertTrue(out.contains("Note"))
    }

    // ── extractSectionHeadingTitle ────────────────────────────────────────────

    @Test
    fun extractSectionHeadingTitle_returnsTitleAndEndIndex() {
        val s = """\section{Title}"""
        val cmdEnd = "\\section".length
        val res = extractSectionHeadingTitle(s, cmdEnd)
        assertEquals("Title", res?.first)
        assertEquals(s.length, res?.second)
    }

    @Test
    fun extractSectionHeadingTitle_skipsOptionalShortTitle() {
        val s = """\section[Short]{Full}"""
        val res = extractSectionHeadingTitle(s, "\\section".length)
        assertEquals("Full", res?.first)
    }

    @Test
    fun convertSections_sectionWithInlineMathGetsStableSlug() {
        val out = convertSections("""\section{New Section $\omegas\quad$}""", absOffset = 1)
        assertTrue(out.contains("""id="section-new-section""""))
    }

    // ── collectSectionsList ───────────────────────────────────────────────────

    @Test
    fun collectSectionsList_collectsIdsAndLabelsInOrder() {
        val list = collectSectionsList("""\section{Alpha}\subsection{Beta}""", 0)
        assertEquals(
            listOf("section-alpha" to "Alpha", "subsection-beta" to "Beta"),
            list,
        )
    }

    // ── convertListEnvironmentsNested ─────────────────────────────────────────

    @Test
    fun convertListEnvironmentsNested_itemizeToUl() {
        // Items must be line-anchored (the \item split regex is (?m)^\s*\\item).
        val src = "\\begin{itemize}\n\\item one\n\\item two\n\\end{itemize}"
        val out = convertListEnvironmentsNested(src)
        assertTrue(out.contains("<ul"))
        assertTrue(out.contains("<li>one</li>"))
        assertTrue(out.contains("<li>two</li>"))
    }

    @Test
    fun convertListEnvironmentsNested_nestedEnumerateInsideItemize() {
        val src = "\\begin{itemize}\n\\item a\n\\begin{enumerate}\n\\item x\n\\item y\n\\end{enumerate}\n\\end{itemize}"
        val out = convertListEnvironmentsNested(src)
        assertTrue(out.contains("<ul"))
        assertTrue(out.contains("<ol"))
        assertTrue(out.contains("<li>x</li>"))
    }

    // ── convertItemize / convertEnumerate (legacy single-level) ───────────────

    @Test
    fun convertItemize_and_convertEnumerate() {
        val ul = convertItemize("\\begin{itemize}\n\\item a\n\\item b\n\\end{itemize}")
        val ol = convertEnumerate("\\begin{enumerate}\n\\item a\n\\item b\n\\end{enumerate}")
        assertTrue(ul.contains("<ul"))
        assertTrue(ul.contains("<li>a</li>"))
        assertTrue(ol.contains("<ol"))
        assertTrue(ol.contains("<li>b</li>"))
    }

    // ── convertMulticols ──────────────────────────────────────────────────────

    @Test
    fun convertMulticols_wrapsInColumnDiv() {
        val out = convertMulticols("""\begin{multicols}{2}Column body\end{multicols}""")
        assertTrue(out.contains("""class="multicol""""))
        assertTrue(out.contains("column-count:2"))
        assertTrue(out.contains("Column body"))
    }

    // ── convertDescription ────────────────────────────────────────────────────

    @Test
    fun convertDescription_termAndDefinition() {
        val out = convertDescription("""\begin{description}\item[Term] The definition.\end{description}""")
        assertTrue(out.contains("<dt>"))
        assertTrue(out.contains("Term"))
        assertTrue(out.contains("<dd>"))
        assertTrue(out.contains("The definition."))
    }

    // ── convertLlmark ─────────────────────────────────────────────────────────

    @Test
    fun convertLlmark_insertsAnchorSpan() {
        val out = convertLlmark("""\llmark{focus}""", absOffset = 1)
        assertTrue(out.contains("""class="llmark""""))
        assertTrue(out.contains("""data-id="mark-focus""""))
    }

    @Test
    fun convertLlmark_optionalTitleRendersCaption() {
        val out = convertLlmark("""\llmark[My Caption]{focus}""", absOffset = 1)
        assertTrue(out.contains("My Caption"))
        assertTrue(out.contains("""data-id="mark-focus""""))
    }

    // ── unescapeLatexSpecials ─────────────────────────────────────────────────

    @Test
    fun unescapeLatexSpecials_commonEscapes() {
        assertEquals(
            """<span class="tex2jax_ignore">&#36;</span>5 &amp; # _""",
            unescapeLatexSpecials("""\$5 \& \# \_"""),
        )
        assertEquals("{a}", unescapeLatexSpecials("""\{a\}"""))
        assertEquals("~^", unescapeLatexSpecials("""\~{}\^{}"""))
    }

    @Test
    fun unescapeLatexSpecials_doesNotTouchInlineMathDollars() {
        assertEquals("""$\alpha$""", unescapeLatexSpecials("""$\alpha$"""))
        assertEquals(
            """<span class="tex2jax_ignore">&#36;</span> then $\beta$""",
            unescapeLatexSpecials("""\$ then $\beta$"""),
        )
    }

    // ── replaceTexorpdfstringBalanced ─────────────────────────────────────────

    @Test
    fun replaceTexorpdfstringBalanced_keepsFirstArgument() {
        assertEquals("a PDF b", replaceTexorpdfstringBalanced("""a \texorpdfstring{PDF}{bookmark} b"""))
    }

    // ── indexOfDisplayMathOpenBracket ─────────────────────────────────────────

    @Test
    fun indexOfDisplayMathOpenBracket_matchesRealDisplayMath() {
        assertEquals(0, indexOfDisplayMathOpenBracket("""\[x\]""", 0))
    }

    @Test
    fun indexOfDisplayMathOpenBracket_ignoresLineBreakDim() {
        // '\\[0.5em]' is a TeX line break, not display math.
        assertEquals(-1, indexOfDisplayMathOpenBracket("""\\[0.5em]""", 0))
    }

    // ── skipBeginEnvBracketOptions ────────────────────────────────────────────

    @Test
    fun skipBeginEnvBracketOptions_skipsBracketGroup() {
        assertEquals(5, skipBeginEnvBracketOptions("[opt]rest", 0))
    }

    @Test
    fun skipBeginEnvBracketOptions_noBracketReturnsSameIndex() {
        assertEquals(0, skipBeginEnvBracketOptions("rest", 0))
    }

    // ── findMatchingEndListEnvironment ────────────────────────────────────────

    @Test
    fun findMatchingEndListEnvironment_findsMatchingEnd() {
        val s = """\begin{itemize}\item a\end{itemize}"""
        val bodyStart = s.indexOf("""\begin{itemize}""") + "\\begin{itemize}".length
        assertEquals(s.indexOf("""\end{itemize}"""), findMatchingEndListEnvironment(s, "itemize", bodyStart))
    }

    // ── latexProseToHtmlWithMath (prose-focused) ──────────────────────────────

    @Test
    fun latexProseToHtmlWithMath_preservesInlineMathAndConvertsBold() {
        val out = latexProseToHtmlWithMath("Text \\textbf{bold} and \$x^2\$ end")
        assertTrue(out.contains("<strong>bold</strong>"))
        assertTrue(out.contains("\$x^2\$"))
    }

    @Test
    fun formatInlineProseNonMath_lineBreakConversion() {
        val out = formatInlineProseNonMath("""line one \\ line two""")
        assertTrue(out.contains("<br/>"))
        assertFalse(out.contains("""\\"""))
    }

    @Test
    fun formatInlineProseNonMath_fboxBecomesBorderedSpan() {
        val out = formatInlineProseNonMath("""\fbox{\emph{Sample box}}""")
        assertTrue(out.contains("Sample box"))
        assertTrue(out.contains("<em>Sample box</em>") || out.contains("Sample box"))
        assertTrue(out.contains("border:1px solid"))
        assertFalse(out.contains("""\fbox{"""))
    }
}
