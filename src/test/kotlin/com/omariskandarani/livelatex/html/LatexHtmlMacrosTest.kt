package com.omariskandarani.livelatex.html

import org.junit.Assert.assertTrue
import org.junit.Test

class LatexHtmlMacrosTest {

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
        val body = """$\vswirl$ and $\rhof$"""
        val out = expandZeroArgMacros(body, macros)
        assertTrue("""\mkern""", out.contains("""\mkern"""))
        assertTrue("""\boldsymbol""", out.contains("""\boldsymbol"""))
        assertTrue("""\rho""", out.contains("""\rho"""))
    }
}
