package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexHtmlParsingTest {

    @Test
    fun stripPreamble_keepsBodyOnly() {
        val s = """
            \documentclass{article}
            \usepackage{tikz}
            \begin{document}
            Hello
            \end{document}
        """.trimIndent()
        val body = stripPreamble(s)
        assertTrue(body.contains("Hello"))
        assertFalse(body.contains("\\documentclass"))
    }

    @Test
    fun stripLineComments_preservesEscapedPercent() {
        val s = "100\\% pure\nreal % comment\nend"
        val out = stripLineComments(s)
        assertEquals("100\\% pure\nreal \nend", out)
    }

    @Test
    fun firstUnescapedPercent_findsCommentStart() {
        val line = "a \\% b % c"
        val idx = firstUnescapedPercent(line)
        assertEquals(line.indexOf('%', 6), idx)
    }

    @Test
    fun findBalancedBrace_nestedAndEscaped() {
        val s = """\cmd{a{b}c{d}}"""
        val open = s.indexOf('{')
        val close = findBalancedBrace(s, open)
        assertEquals(s.length - 1, close)
    }

    @Test
    fun replaceCmd1ArgBalanced_wrapsEachOccurrence() {
        val s = """\foo{one} mid \foo{two}"""
        val out = replaceCmd1ArgBalanced(s, "foo") { inner -> "<$inner>" }
        assertEquals("""<one> mid <two>""", out)
    }

    @Test
    fun slugify_stripsCommandsAndPunctuation() {
        assertEquals("hello-world", slugify("""\textbf{Hello} — World!"""))
    }

    @Test
    fun isEscaped_detectsOddBackslashes() {
        val s = """\%x"""
        val i = s.indexOf('%')
        assertTrue(isEscaped(s, i))
        val t = "\\\\%y"
        val j = t.indexOf('%')
        assertFalse(isEscaped(t, j))
    }
}
