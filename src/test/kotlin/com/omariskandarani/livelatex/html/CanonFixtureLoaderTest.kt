package com.omariskandarani.livelatex.html

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Plan 2 — canon-derived fixture smoke tests.
 *
 * Loads each `canon-fixtures` snippet and pushes it through [LatexHtml.wrap] (or
 * [LatexHtml.wrapWithInputs] for the `\input` case), asserting the pipeline does not crash and
 * produces the expected core HTML for that construct. See `canon-fixtures/README.md` for the
 * construct -> fixture -> function catalog.
 */
class CanonFixtureLoaderTest {

    @After
    fun tearDown() {
        currentBaseDir = null
        lineMapOrigToMergedJson = null
        lineMapMergedToOrigJson = null
        charMapOrigToMergedJson = null
        charMapMergedToOrigJson = null
        srcMapJson = null
        lastCharOrigToMerged = intArrayOf()
        lastCharMergedToOrig = intArrayOf()
    }

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/canon-fixtures/$name")
        assertNotNull("fixture resource missing: $name", stream)
        return stream!!.bufferedReader().use { it.readText() }
    }

    private fun wrapFixture(name: String): String = LatexHtml.wrap(loadFixture(name))

    @Test
    fun newcommandsPrelude_macrosExposedToMathJax() {
        val html = wrapFixture("newcommands_prelude.tex")
        assertTrue(html.contains("rhoM"))
        assertTrue(html.contains("rhoF"))
    }

    @Test
    fun alignWithTag_preservesEnvironmentAndTags() {
        val html = wrapFixture("align_with_tag.tex")
        assertTrue(html.contains("""\begin{align}"""))
        assertTrue(html.contains("""\tag{F1}"""))
        assertTrue(html.contains("""\tag{F2}"""))
    }

    @Test
    fun tabularColspec_becomesHtmlTable() {
        val html = wrapFixture("tabular_colspec.tex")
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("Quantity"))
        assertTrue(html.contains("Value"))
    }

    @Test
    fun textblockStar_becomesFooterBlock() {
        val html = wrapFixture("textblock_star.tex")
        assertTrue(html.contains("ll-textblock"))
        assertTrue(html.contains("Footer note"))
    }

    @Test
    fun nestedLists_produceUlAndOl() {
        val html = wrapFixture("nested_lists.tex")
        assertTrue(html.contains("<ul"))
        assertTrue(html.contains("<ol"))
        assertTrue(html.contains("Inner a"))
    }

    @Test
    fun figureEnv_producesFigureAndImage() {
        val html = wrapFixture("figure_env.tex")
        assertTrue(html.contains("<figure"))
        assertTrue(html.contains("<img"))
        assertTrue(html.contains("sample figure caption"))
    }

    @Test
    fun descriptionList_producesDefinitionList() {
        val html = wrapFixture("description_list.tex")
        assertTrue(html.contains("<dt"))
        assertTrue(html.contains("Swirl"))
        assertTrue(html.contains("<dd"))
    }

    @Test
    fun sections_becomeHeadings() {
        val html = wrapFixture("sections.tex")
        assertTrue(html.contains("<h2"))
        assertTrue(html.contains("Introduction"))
        assertTrue(html.contains("<h3"))
        assertTrue(html.contains("Background"))
    }

    @Test
    fun siunitx_expandsNumScientific() {
        val html = wrapFixture("siunitx.tex")
        assertTrue(html.contains("10^{-3}"))
    }

    @Test
    fun inputRoot_inlinesChildContent() {
        val tempDir = Files.createTempDirectory("canon-input-fixture")
        Files.writeString(tempDir.resolve("input_child.tex"), loadFixture("input_child.tex"))
        val mainPath = tempDir.resolve("input_root.tex")
        Files.writeString(mainPath, loadFixture("input_root.tex"))

        val html = LatexHtml.wrapWithInputs(loadFixture("input_root.tex"), mainPath.toString())
        assertTrue(html.contains("Before the child"))
        assertTrue(html.contains("Included child paragraph"))
        assertTrue(html.contains("After the child"))
    }
}
