package com.omariskandarani.livelatex.html

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for SST-style split title pages (`\titlepageOpen` … `\titlepageClose`).
 */
class TitlepageConversionTest {

    private val sst05TitlepageSource = """
        \newcommand{\paperdoi}{10.5281/zenodo.17593016}

        \newcommand{\titlepageOpen}{
            \begin{titlepage}
            \thispagestyle{empty}
            \centering
            {\Large\bfseries Revisiting Structured Space: \\  From Einstein to a Swirl–String Framework \par }
            \vspace{1cm}
            {\Large\itshape \textbf{Omar Iskandarani}\textsuperscript{\textbf{*}} \par}
            \vspace{0.5cm}
            {\today \par}
            \vspace{0.5cm}
        }

        \newcommand{\titlepageClose}{
            \raggedright
            \null
            \begin{picture}(0,0)
            \put(0,-45){
                \begin{minipage}[b]{\textwidth}
                \footnotesize
                \renewcommand{\arraystretch}{1.0}
                \noindent\rule{\textwidth}{0.4pt} \\[0.5em]
                \textsuperscript{\textbf{*}} Independent Researcher, Groningen, The Netherlands \\
                Email: \texttt{info@omariskandarani.com} \\
                ORCID: \texttt{\href{https://orcid.org/0009-0006-1686-3961}{0009-0006-1686-3961}} \\
                DOI: \href{https://doi.org/\paperdoi}{\paperdoi} \\
                \end{minipage}
            }
            \end{picture}
            \end{titlepage}
        }

        \begin{document}
            \titlepageOpen
            \begin{abstract}
            This paper revisits Einstein's later remarks on a structured space and proposes a modern realization in a swirl–string framework with preferred foliation.
            \end{abstract}
            \vfill
            \paragraph{keyword} emergent gravity; topological fluid dynamics; analogue gravity
            \noindent\paragraph{\textit{Authorial note.—}} This is a single-author work; I use "we" in the conventional authorial sense.
            \vspace{0.5cm}
            \titlepageClose
            \section{Introduction}
            Body text follows.
        \end{document}
    """.trimIndent()

    private fun titlepageHtmlFromSource(source: String): String {
        val noComments = stripLineComments(source)
        val macros = extractNewcommands(noComments)
        val body = stripLineComments(stripPreamble(source))
        val assembled = assembleSplitTitlepageMacros(body, macros)
        val expanded = expandZeroArgMacros(assembled, macros)
        return sanitizeForMathJaxProse(expanded)
    }

    @Test
    fun sst05SplitTitlepage_rendersHtmlWithoutRawLatex() {
        val html = titlepageHtmlFromSource(sst05TitlepageSource)

        assertTrue("expected titlepage wrapper", html.contains("ll-titlepage"))
        assertTrue("expected title text", html.contains("Revisiting Structured Space"))
        assertTrue("expected footer block", html.contains("ll-titlepage-footer"))
        assertTrue("expected ORCID link", html.contains("orcid.org") || html.contains("0009-0006-1686-3961"))
        assertTrue("expected DOI", html.contains("10.5281/zenodo.17593016"))
        assertTrue("expected abstract label", html.contains("Abstract."))

        assertFalse("raw title brace group", html.contains("{ Revisiting Structured Space"))
        assertFalse("raw author brace group", html.contains("{Omar Iskandarani"))
        assertFalse("raw picture put", html.contains("""\put(0,-45)"""))
        assertFalse("raw vfill", html.contains("""\vfill"""))
        assertFalse("raw picture env", html.contains("""\begin{picture}"""))
        assertFalse("literal titlepageOpen macro", html.contains("""\titlepageOpen"""))
        assertFalse("literal titlepageClose macro", html.contains("""\titlepageClose"""))
    }

    @Test
    fun assembleSplitTitlepageMacros_buildsCompleteTitlepageEnv() {
        val noComments = stripLineComments(sst05TitlepageSource)
        val macros = extractNewcommands(noComments)
        val body = stripLineComments(stripPreamble(sst05TitlepageSource))

        val assembled = assembleSplitTitlepageMacros(body, macros)

        assertTrue(assembled.contains("""\begin{titlepage}"""))
        assertTrue(assembled.contains("""\end{titlepage}"""))
        assertFalse(assembled.contains("""\titlepageOpen"""))
        assertFalse(assembled.contains("""\titlepageClose"""))
    }

    @Test
    fun convertTitlepage_onAssembledSst05Body_producesFooterAndTitle() {
        val noComments = stripLineComments(sst05TitlepageSource)
        val macros = extractNewcommands(noComments)
        val body = stripLineComments(stripPreamble(sst05TitlepageSource))
        val expanded = expandZeroArgMacros(assembleSplitTitlepageMacros(body, macros), macros)

        val html = convertTitlepage(expanded)

        assertTrue(html.contains("ll-titlepage"))
        assertTrue(html.contains("ll-titlepage-footer"))
        assertFalse(html.contains("""\put(0,-45)"""))
    }
}