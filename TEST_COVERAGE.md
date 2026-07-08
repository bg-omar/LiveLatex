# LiveLatex — LaTeX->HTML function test coverage

Status of the `com.omariskandarani.livelatex.html` pipeline functions. This file is generated as part of
the "LiveLatex test hardening" characterization-test effort. Tests marked **char** are golden-master /
characterization tests that lock down the *current* behavior (they may encode behavior that is not
necessarily desirable; such cases are flagged with `// NOTE:` in the test).

Legend: [x] covered · [~] partial · [ ] none (before this effort)

## LatexHtml.kt (orchestration)
- [~] `wrap` / `wrapInternal` — `LatexHtmlWrapTest`, `PipelineOrderTest`, `CanonEndToEndTest`
- [~] `wrapWithInputs` — `LatexHtmlWrapTest`, `PipelineOrderTest`
- [x] `inlineInputs` — `PipelineOrderTest`
- [x] `isEscaped` — `LatexHtmlParsingTest`
- [x] `slugify` — `LatexHtmlParsingTest`

## LatexHtmlParsing.kt (primitives)
- [x] `stripPreamble`, `stripLineComments`, `firstUnescapedPercent`, `findBalancedBrace`, `replaceCmd1ArgBalanced` — `LatexHtmlParsingTest`

## LatexHtmlProse.kt (math + prose core)
- [x] `latexProseToHtmlWithMath` — `LatexHtmlMathPipelineTest`, `LatexHtmlProseTest`
- [x] `formatInlineProseNonMath` — `LatexHtmlMathPipelineTest`, `LatexHtmlProseTest`
- [x] `convertSections` — `LatexHtmlProseTest`
- [x] `extractSectionHeadingTitle` — `LatexHtmlProseTest`
- [x] `collectSectionsList` — `LatexHtmlProseTest`
- [x] `convertListEnvironmentsNested` — `LatexHtmlProseTest`
- [x] `convertMulticols` — `LatexHtmlProseTest`
- [x] `convertDescription` — `LatexHtmlProseTest`
- [x] `convertLlmark` — `LatexHtmlProseTest`
- [x] `unescapeLatexSpecials` — `LatexHtmlProseTest`
- [x] `replaceTexorpdfstringBalanced` — `LatexHtmlProseTest`
- [x] `indexOfDisplayMathOpenBracket` — `LatexHtmlMathPipelineTest`, `LatexHtmlProseTest`
- [x] `skipBeginEnvBracketOptions` — `LatexHtmlProseTest`
- [x] `findMatchingEndListEnvironment` — `LatexHtmlProseTest`
- [x] `convertItemize` / `convertEnumerate` — `LatexHtmlProseTest`

## LatexHtmlBlocks.kt (tables / figures / boxes)
- [x] `findBalancedBraceAllowMath` — `LatexHtmlBlocksTest`
- [x] `convertTabulars` — `LatexHtmlBlocksTest`
- [x] `parseColSpecBalanced` — `LatexHtmlBlocksTest`
- [x] `convertTableEnvs` — `LatexHtmlBlocksTest`
- [x] `convertLongtablesToTables` — `LatexHtmlBlocksTest`
- [x] `convertFigureEnvs` — `LatexHtmlBlocksTest`
- [x] `convertTcolorboxes` — `LatexHtmlBlocksTest`
- [x] `parseTcolorOptions` — `LatexHtmlBlocksTest`
- [x] `xcolorToCss` — `LatexHtmlBlocksTest`
- [x] `convertHref` — `LatexHtmlBlocksTest`
- [x] `convertTheBibliography` — `LatexHtmlBlocksTest`
- [x] `linewidthToPercent` — `LatexHtmlBlocksTest`
- [x] `peelTopLevelTextWrapper` — `LatexHtmlBlocksTest`
- [x] `stripAuxDirectives` — `LatexHtmlBlocksTest`
- [x] `stripTitleAuthorDate` — `LatexHtmlBlocksTest`

## LatexHtmlSanitizer.kt (env conversion)
- [x] `sanitizeForMathJaxProse` — `LatexHtmlSanitizerTest`
- [x] `convertLetterEnvironment` — `LatexHtmlSanitizerTest`
- [x] `convertSiunitx` — `LatexHtmlSanitizerTest`
- [x] `convertTextblockStar` — `LatexHtmlSanitizerTest`
- [x] `convertMinipagesToHtml` — `LatexHtmlSanitizerTest`
- [x] `parseMinipageWidthPercent` — `LatexHtmlSanitizerTest`
- [x] `convertPicturePutBlocks` — `LatexHtmlSanitizerTest`
- [x] `stripOuterLatexGroupBraces` — `LatexHtmlSanitizerTest`
- [x] `convertTitlepage` — `TitlepageConversionTest`

## LatexHtmlUtils.kt
- [x] `htmlEscapeAll`, `fixInlineBoundarySpaces`, `injectLineAnchors`, `replaceTextSymbols`,
  `extractTitleMeta`, `findLastCmdArg`, `splitAuthors`, `includeGraphicsStyle` — `LatexHtmlUtilsTest`
- [x] `findAllCmdArgs` — `LatexHtmlUtilsTest`
- [x] `renderDate` — `LatexHtmlUtilsTest`
- [x] `processThanksWithin` — `LatexHtmlUtilsTest`
- [x] `buildMakTitleHtml` / `convertMakeTitle` — `LatexHtmlUtilsTest`
- [x] `proseNoBr` — `LatexHtmlUtilsTest`
- [x] `resolveImagePath` — `LatexHtmlUtilsTest`
- [x] `applyInlineFormattingOutsideTags` — `LatexHtmlUtilsTest`

## LatexHtmlMacros.kt
- [x] `extractNewcommands` (`\newcommand`/`\renewcommand`/`\providecommand`/`\def`/`\DeclareMathOperator`) — `LatexHtmlMacrosTest`
- [x] `buildMathJaxMacros` / `jsonEscape` — `LatexHtmlMacrosTest`
- [x] `expandZeroArgMacros` — `LatexHtmlMacrosTest`
- [x] `assembleSplitTitlepageMacros` — `LatexHtmlMacrosTest`

## SourceMapBuilder.kt
- [x] `extractLatexVisibleText`, `extractHtmlVisibleText`, `alignSegments` — `SourceMapBuilderTest`
- [x] `charMapToJson` — `SourceMapBuilderTest`
- [x] `buildOrigToMergedCharMap` — `SourceMapBuilderTest`
- [x] `buildMergedToOrigCharMap` — `SourceMapBuilderTest`
- [x] `injectSpans` — `SourceMapBuilderTest`

## Not unit tested (out of scope; require external tooling / IDE platform)
- `TikzRenderer.*` / `LatexHtmlTikz.*` — need `pdflatex`/`dvisvgm` (see separate TikZ plan)
- `LatexHtmlTemplate.buildHtml` — browser/MathJax shell
- `LatexPreviewService`, UI/actions — IDE platform
