# Canon-derived LaTeX fixtures

Small `.tex` snippets distilled from `SST_CANON-v0.8.19.tex`, one construct category each. Used by
`CanonFixtureLoaderTest` (smoke tests) and `CanonEndToEndTest` (pipeline order). Kept intentionally
tiny so tests stay fast.

| Fixture | Construct | Responsible function(s) |
|---|---|---|
| `newcommands_prelude.tex` | `\providecommand` / `\newcommand` math atoms + usage | `extractNewcommands`, `buildMathJaxMacros`, `expandZeroArgMacros` |
| `align_with_tag.tex` | `align` with multiple `\tag` | `sanitizeForMathJaxProse` (kept intact for MathJax) |
| `tabular_colspec.tex` | `tabular` with `>{...}p{..\textwidth}` colspec | `convertTabulars`, `parseColSpecBalanced`, `linewidthToPercent` |
| `textblock_star.tex` | `textpos` `textblock*` footer | `convertTextblockStar` |
| `nested_lists.tex` | nested `itemize`/`enumerate` | `convertListEnvironmentsNested`, `findMatchingEndListEnvironment` |
| `figure_env.tex` | `figure` + `\includegraphics` + `\caption`/`\label` | `convertFigureEnvs`, `resolveImagePath`, `includeGraphicsStyle` |
| `description_list.tex` | `description` with `\item[label]` | `convertDescription`, `peelTopLevelTextWrapper` |
| `sections.tex` | `\section` / `\subsection` | `convertSections`, `collectSectionsList` |
| `siunitx.tex` | `\SI` / `\num` / `\si` | `convertSiunitx` |
| `input_root.tex` (+ `input_child.tex`) | `\input{...}` inclusion | `inlineInputs` (via `wrapWithInputs`) |
