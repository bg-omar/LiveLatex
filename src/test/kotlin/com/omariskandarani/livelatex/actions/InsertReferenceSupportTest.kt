package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InsertReferenceSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun extractLabels_andGroupByPrefix() {
        val tex = """
            \label{sec:intro}
            \label{fig:a}
            \label{eq:1}
            \label{lonely}
        """.trimIndent()
        val labels = InsertReferenceSupport.extractLabels(tex)
        assertEquals(listOf("sec:intro", "fig:a", "eq:1", "lonely"), labels)
        val groups = InsertReferenceSupport.groupLabelsByPrefix(labels)
        assertEquals(listOf("sec:intro"), groups["sec"])
        assertEquals(listOf("fig:a"), groups["fig"])
        assertEquals(listOf("lonely"), groups["other"])
        assertNull(groups["lonely"])
    }

    @Test
    fun bibliographyFileNames_splitsCommaList() {
        val tex = """\bibliography{refs, more}"""
        assertEquals(listOf("refs", "more"), InsertReferenceSupport.bibliographyFileNames(tex))
    }

    @Test
    fun parseBibEntries_readsKeys() {
        val bib = """
            @article{Alpha2020,
              title={A},
            }
            @book{Beta2021,
              title={B},
            }
        """.trimIndent()
        val entries = InsertReferenceSupport.parseBibEntries(bib)
        assertTrue(entries.containsKey("Alpha2020"))
        assertTrue(entries.containsKey("Beta2021"))
    }

    @Test
    fun resolveBibFile_directUnderProjectRoot() {
        val root = tmp.root
        val bib = File(root, "refs.bib").apply { writeText("@article{X,}") }
        val found = InsertReferenceSupport.resolveBibFile("refs", root.absolutePath, null)
        assertEquals(bib.canonicalFile, found?.canonicalFile)
    }

    @Test
    fun resolveBibFile_walksParentsFromEditorNotWholeTree() {
        val root = tmp.root
        val deep = File(root, "a/b/c").apply { mkdirs() }
        // Noise: many sibling dirs (would hurt walkTopDown); helper must still find via parent chain
        repeat(20) { i -> File(root, "noise$i").mkdirs() }
        val bib = File(root, "paper.bib").apply { writeText("@article{Y,}") }
        val found = InsertReferenceSupport.resolveBibFile("paper", root.absolutePath, deep)
        assertEquals(bib.canonicalFile, found?.canonicalFile)
    }

    @Test
    fun resolveBibFile_missingReturnsNull() {
        val root = tmp.root
        assertNull(InsertReferenceSupport.resolveBibFile("missing", root.absolutePath, root))
    }
}
