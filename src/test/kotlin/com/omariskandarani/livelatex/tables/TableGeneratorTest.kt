package com.omariskandarani.livelatex.tables

import org.junit.Assert.assertEquals
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
    fun parseCsvLike_splitsCommaRows() {
        val rows = parseCsvLike("a,b\nc,d")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), rows)
    }
}
