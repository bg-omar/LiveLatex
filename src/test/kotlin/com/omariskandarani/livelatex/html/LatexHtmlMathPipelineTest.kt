package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexHtmlMathPipelineTest {

  @Test
  fun splitHtmlTagsRespectingMath_doesNotSplitOnAngleBracketsInsideMath() {
    val html = """<h3>T</h3>Before ${'$'}x > 0${'$'} and ${'$'}a < b${'$'} after"""
    val pieces = splitHtmlTagsRespectingMath(html)
    val texts = pieces.filterIsInstance<HtmlMathSplitPiece.Text>().map { it.value }
    val tags = pieces.filterIsInstance<HtmlMathSplitPiece.Tag>().map { it.value }
    assertEquals(listOf("<h3>", "</h3>"), tags)
    assertTrue(texts.any { it.contains("${'$'}x > 0${'$'}") })
    assertTrue(texts.any { it.contains("${'$'}a < b${'$'}") })
  }

  @Test
  fun splitHtmlTagsRespectingMath_splitsRealHtmlTags() {
    val html = """<strong>bold</strong> ${'$'}x>0${'$'}"""
    val pieces = splitHtmlTagsRespectingMath(html)
    val tags = pieces.filterIsInstance<HtmlMathSplitPiece.Tag>().map { it.value }
    val texts = pieces.filterIsInstance<HtmlMathSplitPiece.Text>().map { it.value }
    assertEquals(listOf("<strong>", "</strong>"), tags)
    assertTrue(texts.any { it.contains("bold") })
    assertTrue(texts.any { it.contains("${'$'}x>0${'$'}") })
  }

  @Test
  fun escapeAngleBracketsInMathFragment_escapesRawBrackets() {
    assertEquals("${'$'}2&lt;x&lt;3${'$'}", escapeAngleBracketsInMathFragment("${'$'}2<x<3${'$'}"))
    assertEquals("${'$'}x &gt; 0${'$'}", escapeAngleBracketsInMathFragment("${'$'}x > 0${'$'}"))
  }

  @Test
  fun escapeAngleBracketsInMathFragment_doesNotDoubleEncode() {
    assertEquals("${'$'}a &lt; b${'$'}", escapeAngleBracketsInMathFragment("${'$'}a &lt; b${'$'}"))
  }

  @Test
  fun latexProseToHtmlWithMath_escapesComparisonOperatorsInMath() {
    val out = latexProseToHtmlWithMath("Value ${'$'}x > 0${'$'} here.")
    assertTrue(out.contains("${'$'}x &gt; 0${'$'}"))
    assertFalse(out.contains("${'$'}x > 0${'$'}"))
  }

  @Test
  fun latexProseToHtmlWithMath_escapesTightChainComparison() {
    val out = latexProseToHtmlWithMath("Range ${'$'}2<x<3${'$'} ok.")
    assertTrue(out.contains("${'$'}2&lt;x&lt;3${'$'}"))
    assertFalse(out.contains("<x<"))
  }

  @Test
  fun formatInlineProseNonMath_lineBreakAfterMathContent() {
    val out = formatInlineProseNonMath("""Text ${'$'}x > 0${'$'} \\ More""")
    assertTrue(out.contains("<br/>"))
  }

  @Test
  fun formatInlineProseNonMath_dimLineBreakNotDisplayMath() {
    val spaced = formatInlineProseNonMath("""Line1 \\[0.5em] Line2""")
    assertTrue(spaced.contains("<br/>"))
    assertFalse(spaced.contains("\\["))
    val display = latexProseToHtmlWithMath("""\[a > b\]""")
    assertTrue(display.contains("\\["))
    assertTrue(display.contains("&gt;"))
  }

  @Test
  fun convertParagraphsOutsideTags_formatsChunkWithGreaterThanInMath() {
    val html = """<h3 id="a">A</h3>Line ${'$'}x > 0${'$'} \\ More"""
    val out = convertParagraphsOutsideTags(html)
    assertTrue(out.contains("<br/>"))
    assertTrue(out.contains("&gt;"))
  }

  @Test
  fun applyInlineFormattingOutsideTags_NoTables_formatsMathWithGreaterThan() {
    val html = """<h3>T</h3>${'$'}x > 0${'$'} \\ tail"""
    val out = applyInlineFormattingOutsideTags_NoTables(html)
    assertTrue(out.contains("<br/>"))
    assertTrue(out.contains("&gt;"))
  }

  @Test
  fun convertParagraphsOutsideTags_sectionScopeBothSubsectionsFormatted() {
    val html = """
      <h3 id="a">A</h3>First ${'$'}x>0${'$'} \\
      <h3 id="b">B</h3>Second text
    """.trimIndent()
    val out = convertParagraphsOutsideTags(html)
    val brCount = out.split("<br").size - 1
    assertTrue("expected line break in first subsection", brCount >= 1)
    assertTrue(out.contains("Second text"))
  }

  @Test
  fun latexProseToHtmlWithMath_textbfWithMathInside() {
    val out = latexProseToHtmlWithMath("""\textbf{${'$'}x > 0${'$'}}""")
    assertTrue(out.contains("<strong>"))
    assertTrue(out.contains("&gt;"))
  }

  @Test
  fun convertParagraphsOutsideTags_noDoubleParagraphWrapOnSecondPassInput() {
    val once = convertParagraphsOutsideTags("""<p>Hello ${'$'}x > 0${'$'}</p>""")
    val twice = applyInlineFormattingOutsideTags_NoTables(once)
    assertFalse(twice.contains("<p><p>"))
  }

  @Test
  fun indexOfDisplayMathOpenBracket_skipsLineBreakDim() {
    assertEquals(-1, indexOfDisplayMathOpenBracket("""\\[0.5em]""", 0))
    assertEquals(0, indexOfDisplayMathOpenBracket("""\[x\]""", 0))
    val mixed = """\\[0.5em] then \[x\]"""
    val after = indexOfDisplayMathOpenBracket(mixed, mixed.indexOf("then"))
    assertTrue(after >= 0)
  }
}
