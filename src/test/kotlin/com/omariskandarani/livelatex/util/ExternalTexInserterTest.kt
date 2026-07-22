package com.omariskandarani.livelatex.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Paths

class ExternalTexInserterTest {

    @Test
    fun relativePath_parentSiblingDirectory() {
        val editor = Paths.get("/project/main/chapter.tex")
        val source = Paths.get("/project/andere/latex.tex")
        assertEquals("../andere/latex.tex", ExternalTexInserter.relativePath(editor, source))
        assertEquals("\\input{../andere/latex.tex}", ExternalTexInserter.inputSnippet("../andere/latex.tex"))
    }

    @Test
    fun relativePath_subdirectory() {
        val editor = Paths.get("/project/main.tex")
        val source = Paths.get("/project/chapters/intro.tex")
        assertEquals("chapters/intro.tex", ExternalTexInserter.relativePath(editor, source))
    }

    @Test
    fun relativePath_sameDirectory() {
        val editor = Paths.get("/project/main.tex")
        val source = Paths.get("/project/appendix.tex")
        assertEquals("appendix.tex", ExternalTexInserter.relativePath(editor, source))
    }

    @Test
    fun relativePath_windowsBackslashesNormalized() {
        val editor = Paths.get("C:\\project\\main\\chapter.tex")
        val source = Paths.get("C:\\project\\andere\\latex.tex")
        val rel = ExternalTexInserter.relativePath(editor, source)
        assertEquals("../andere/latex.tex", rel)
        assertEquals(-1, rel?.indexOf('\\') ?: -1)
    }

    @Test
    fun relativePath_differentRootsReturnsNull() {
        val editor = Paths.get("C:\\project\\main.tex")
        val source = Paths.get("D:\\other\\file.tex")
        assertNull(ExternalTexInserter.relativePath(editor, source))
    }

    @Test
    fun normalizeForDocument_convertsCrlfToLf() {
        val raw = "\\documentclass{article}\r\n\\begin{document}\r\nHi\r\n\\end{document}\r\n"
        val normalized = ExternalTexInserter.normalizeForDocument(raw)
        assertEquals(-1, normalized.indexOf('\r'))
        assertEquals(
            "\\documentclass{article}\n\\begin{document}\nHi\n\\end{document}\n",
            normalized
        )
    }
}