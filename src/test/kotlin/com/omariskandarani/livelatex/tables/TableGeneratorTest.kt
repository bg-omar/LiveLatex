package com.omariskandarani.livelatex.tables

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableGeneratorTest {

    @Test
    fun generateLatexTable_buildsTabularWithColumnSpec() {
        val tex = generateLatexTable(
            listOf(listOf("H1", "H2"), listOf("a&b", "c")),
            TableOptions(
                withTableEnv = false,
                cols = listOf(Col(ColAlign.L), Col(ColAlign.C)),
            ),
        )
        assertTrue(tex.contains("""\begin{tabular}{lc}"""))
        assertTrue(tex.contains("""H1 & H2 \\"""))
        assertTrue(tex.contains("""a\\&b"""))
    }

    @Test
    fun generateLatexTable_oneHeaderAndNBodyRows() {
        val data = listOf(
            listOf("H1", "H2"),
            listOf("a", "b"),
            listOf("c", "d"),
            listOf("e", "f"),
        )
        val tex = generateLatexTable(
            data,
            TableOptions(
                withTableEnv = false,
                headerRows = 1,
                cols = listOf(Col(ColAlign.L), Col(ColAlign.L)),
            ),
        )
        assertTrue(tex.contains("\\toprule"))
        assertTrue(tex.contains("\\midrule"))
        assertTrue(tex.contains("\\bottomrule"))
        assertTrue(tex.contains("""H1 & H2 \\"""))
        assertTrue(tex.contains("""a & b \\"""))
        assertTrue(tex.contains("""c & d \\"""))
        assertTrue(tex.contains("""e & f \\"""))
        val midIdx = tex.indexOf("\\midrule")
        assertTrue(tex.indexOf("""H1 & H2 \\""") < midIdx)
        assertTrue(tex.indexOf("""a & b \\""") > midIdx)
    }

    @Test
    fun generateLatexTable_colspecLcrAndP() {
        val tex = generateLatexTable(
            listOf(listOf("H1", "H2", "H3", "H4"), listOf("a", "b", "c", "d")),
            TableOptions(
                withTableEnv = false,
                cols = listOf(
                    Col(ColAlign.L),
                    Col(ColAlign.C),
                    Col(ColAlign.R),
                    Col(ColAlign.P, width = "3cm"),
                ),
            ),
        )
        assertTrue(tex.contains("""\begin{tabular}{lcrp{3cm}}"""))
    }

    @Test
    fun generateLatexTable_placementInTableEnv() {
        val withEnv = generateLatexTable(
            listOf(listOf("H"), listOf("a")),
            TableOptions(
                withTableEnv = true,
                placement = "t",
                cols = listOf(Col(ColAlign.L)),
            ),
        )
        assertTrue(withEnv.contains("""\begin{table}[t]"""))
        assertTrue(withEnv.contains("""\end{table}"""))

        val noEnv = generateLatexTable(
            listOf(listOf("H"), listOf("a")),
            TableOptions(
                withTableEnv = false,
                placement = "t",
                cols = listOf(Col(ColAlign.L)),
            ),
        )
        assertFalse(noEnv.contains("""\begin{table}"""))
        assertEquals(-1, noEnv.indexOf("[t]"))
    }

    @Test
    fun parseCsvLike_splitsCommaRows() {
        val rows = parseCsvLike("a,b\nc,d")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), rows)
    }
}
