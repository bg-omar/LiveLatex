package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexHtmlMacrosTest {

    // ── extractNewcommands (providecommand / def / DeclareMathOperator / args) ──

    @Test
    fun extractNewcommands_providecommandZeroArgIsGrouped() {
        val m = extractNewcommands("""\providecommand{\rhoM}{\rho_{\!m}}""")
        val macro = m["rhoM"]!!
        assertEquals(0, macro.nargs)
        // Rich 0-arg body is wrapped in braces so it expands as one atom.
        assertEquals("""{\rho_{\!m}}""", macro.def)
    }

    @Test
    fun extractNewcommands_defAndDeclareMathOperator() {
        val m = extractNewcommands("""\def\foo{bar}\DeclareMathOperator{\Tr}{Tr}""")
        assertEquals("bar", m["foo"]!!.def)
        assertEquals("""\operatorname{Tr}""", m["Tr"]!!.def)
    }

    @Test
    fun extractNewcommands_withArgumentCountKeepsPlaceholder() {
        val m = extractNewcommands("""\newcommand{\abc}[1]{x#1}""")
        assertEquals(1, m["abc"]!!.nargs)
        assertEquals("x#1", m["abc"]!!.def)
    }

    // ── buildMathJaxMacros base + jsonEscape ──────────────────────────────────

    @Test
    fun buildMathJaxMacros_includesBaseShims() {
        val js = buildMathJaxMacros(emptyMap())
        assertTrue(js.contains(""""bm": ["""))       // 1-arg macro emitted as [def, n]
        assertTrue(js.contains(", 1]"))
        assertTrue(js.contains(""""Lam": "\\Lambda""""))
    }

    @Test
    fun buildMathJaxMacros_siunitxAddsTwoArgQty() {
        val src = """\usepackage{siunitx}\AtBeginDocument{\RenewCommandCopy\qty\SI}"""
        val js = buildMathJaxMacros(emptyMap(), src)
        assertTrue(js.contains(""""qty": ["""))
        assertTrue(js.contains(", 2]"))
    }

    @Test
    fun jsonEscape_escapesBackslashAndWraps() {
        assertEquals(""""x"""", jsonEscape("x"))
        assertEquals(""""a\\b"""", jsonEscape("""a\b"""))
    }

    // ── assembleSplitTitlepageMacros ──────────────────────────────────────────

    @Test
    fun assembleSplitTitlepageMacros_joinsOpenMiddleClose() {
        val macros = mapOf(
            "titlepageOpen" to Macro("<OPEN>", 0),
            "titlepageClose" to Macro("<CLOSE>", 0),
        )
        val out = assembleSplitTitlepageMacros("""\titlepageOpen MID \titlepageClose""", macros)
        assertEquals("<OPEN> MID <CLOSE>", out)
    }

    @Test
    fun assembleSplitTitlepageMacros_noOpenReturnsUnchanged() {
        val out = assembleSplitTitlepageMacros("""plain body""", emptyMap())
        assertEquals("plain body", out)
    }

    @Test
    fun extractNewcommand_nestedBraces_keepsBackslashesInVswirlStyleMacro() {
        val src = """\newcommand{\vswirl}{v_{\mkern-2mu\scriptscriptstyle\boldsymbol{\circlearrowleft}}}"""
        val m = extractNewcommands(src)
        val def = m["vswirl"]!!.def
        assertTrue(def.contains("""\mkern"""))
        assertTrue(def.contains("""\scriptscriptstyle"""))
        assertTrue(def.contains("""\boldsymbol"""))
        assertTrue(def.contains("""\circlearrowleft"""))
    }

    @Test
    fun buildMathJaxMacros_jsonEscapesBackslashes() {
        val user = mapOf(
            "vswirl" to Macro("""v_{\mkern-2mu\boldsymbol{x}}""", 0),
        )
        val js = buildMathJaxMacros(user)
        assertTrue(js.contains("\\\\mkern"))
        assertTrue(js.contains("\\\\boldsymbol"))
    }

    @Test
    fun expandZeroArgMacros_preservesBackslashesInExpandedBody() {
        val macros = mapOf(
            "vswirl" to Macro("""v_{\mkern-2mu\scriptscriptstyle\boldsymbol{\circlearrowleft}}""", 0),
            "rhof" to Macro("""\rho_{\!f}""", 0),
        )
        // Outside math: expansion must keep TeX backslashes in the replacement.
        val body = """See \vswirl and \rhof outside."""
        val out = expandZeroArgMacros(body, macros)
        assertTrue("""\mkern""", out.contains("""\mkern"""))
        assertTrue("""\boldsymbol""", out.contains("""\boldsymbol"""))
        assertTrue("""\rho""", out.contains("""\rho"""))
    }

    @Test
    fun expandZeroArgMacros_doesNotExpandInsideMath() {
        val macros = mapOf(
            "vswirl" to Macro("""v_{\mkern-2mu\scriptscriptstyle\boldsymbol{\circlearrowleft}}""", 0),
            "rhof" to Macro("""\rho_{\!f}""", 0),
        )
        val body = """$\vswirl$ and \begin{equation}\rhof\end{equation} and outside \rhof"""
        val out = expandZeroArgMacros(body, macros)
        assertTrue("inline math keeps macro", out.contains("""$\vswirl$"""))
        assertTrue("equation keeps macro", out.contains("""\begin{equation}\rhof\end{equation}"""))
        assertTrue("prose still expands", out.contains("""\rho_{\!f}""") && !out.contains("""outside \rhof"""))
    }

    @Test
    fun expandZeroArgMacros_skipsEmptyDefsSoSwirlarrowSurvives() {
        val macros = mapOf(
            "swirlarrow" to Macro("", 0),
        )
        val body = """$\mathbf{b}_{\swirlarrow}$"""
        val out = expandZeroArgMacros(body, macros)
        assertTrue(out.contains("""\swirlarrow"""))
    }

    @Test
    fun expandZeroArgMacros_doesNotFreezeKpathInsideTikzpicture() {
        // First-wins extract would record Unknot circle; expansion must not rewrite later pictures.
        val macros = mapOf(
            "KPATH" to Macro("(0,0) circle (2cm)", 0),
        )
        val body = """
            \begin{tikzpicture}
            \def\KPATH{(0,0) circle (2cm)}
            \RenderStrand{\KPATH}{6}{0.15}
            \end{tikzpicture}
            \begin{tikzpicture}
            \def\KPATH{([closed] P1)..(P2)..(P3)}
            \RenderStrand{\KPATH}{12}{0.15}
            \node{Trefoil};
            \end{tikzpicture}
            Outside \KPATH should expand.
        """.trimIndent()
        val out = expandZeroArgMacros(body, macros)
        val parts = Regex("""\\begin\{tikzpicture}.*?\\end\{tikzpicture}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(out).toList()
        assertEquals(2, parts.size)
        val secondPic = parts[1].value
        assertTrue(
            "second picture must keep \\KPATH usage for local \\def",
            secondPic.contains("""\RenderStrand{\KPATH}"""),
        )
        assertFalse(
            "must not bake Unknot circle into second picture",
            secondPic.contains("(0,0) circle (2cm)"),
        )
        assertTrue(
            "prose outside tikzpicture still expands",
            out.contains("Outside (0,0) circle (2cm) should expand."),
        )
    }
}
