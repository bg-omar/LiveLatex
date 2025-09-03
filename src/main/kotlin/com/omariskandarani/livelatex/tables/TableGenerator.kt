package com.omariskandarani.livelatex.tables

enum class ColAlign { L, C, R, P } // p{width}

data class Col(
    val align: ColAlign,
    val width: String? = null,     // only for P; e.g. 0.2\\linewidth or 3cm
    val verticalLeft: Boolean = false,
    val verticalRight: Boolean = false
)

data class TableOptions(
    val withTableEnv: Boolean = true,
    val placement: String = "htbp",
    val caption: String? = null,
    val label: String? = null,
    val booktabs: Boolean = true,
    val headerRows: Int = 1,
    val addOuterRules: Boolean = false,
    val cols: List<Col>
)

/** Build a LaTeX column spec like |l|c|p{0.3\\linewidth}| */
private fun buildColSpec(opts: TableOptions): String {
    val sb = StringBuilder()
    if (opts.addOuterRules) sb.append("|")
    opts.cols.forEachIndexed { i, c ->
        if (c.verticalLeft && i == 0 && !opts.addOuterRules) sb.append("|")
        if (c.verticalLeft && i > 0) sb.append("|")
        sb.append(
            when (c.align) {
                ColAlign.L -> "l"
                ColAlign.C -> "c"
                ColAlign.R -> "r"
                ColAlign.P -> "p{${c.width ?: "0.2\\linewidth"}}"
            }
        )
        if (c.verticalRight) sb.append("|")
    }
    if (opts.addOuterRules) sb.append("|")
    return sb.toString()
}

private fun sanitizeCell(s: String): String =
    s.replace("&", "\\\\&")
     .replace("%", "\\\\%")
     .replace("#", "\\\\#")
     .replace("_", "\\\\_")
     .replace("{", "\\\\{")
     .replace("}", "\\\\}")
     .trim()

/** Core generator (booktabs optional). */
fun generateLatexTable(data: List<List<String>>, opts: TableOptions): String {
    require(opts.cols.isNotEmpty()) { "No columns specified." }
    val ncols = opts.cols.size
    val spec = buildColSpec(opts)

    fun rowToLatex(row: List<String>): String =
        (0 until ncols).joinToString(" & ") { i -> sanitizeCell(row.getOrNull(i) ?: "") } + " \\\\"

    val headerCount = opts.headerRows.coerceIn(0, data.size)
    val headers = data.take(headerCount)
    val body    = data.drop(headerCount)

    val lines = mutableListOf<String>()
    if (opts.withTableEnv) {
        lines += "\\begin{table}[${opts.placement}]"
        lines += "\\centering"
        opts.caption?.takeIf { it.isNotBlank() }?.let { lines += "\\caption{$it}" }
        opts.label?.takeIf { it.isNotBlank() }?.let { lines += "\\label{$it}" }
    }

    lines += "\\begin{tabular}{$spec}"
    lines += if (opts.booktabs) "\\toprule" else "\\hline"

    headers.forEach { lines += rowToLatex(it) }
    if (headerCount > 0) lines += if (opts.booktabs) "\\midrule" else "\\hline"
    body.forEach { lines += rowToLatex(it) }

    lines += if (opts.booktabs) "\\bottomrule" else "\\hline"
    lines += "\\end{tabular}"
    if (opts.withTableEnv) lines += "\\end{table}"

    return lines.joinToString("\n")
}

/* ---------- CSV/TSV/Markdown ingestion ---------- */

fun parseCsvLike(input: String): List<List<String>> {
    val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()

    val looksMarkdown = lines.any { it.contains('|') } &&
                        lines.any { it.matches(Regex("^\\s*\\|?\\s*:?-{3,}.*")) }

    return if (looksMarkdown) parseMarkdownTable(lines) else {
        val delim = when {
            lines.any { it.contains("\\t") } -> '\t'
            lines.any { it.contains(';') }  -> ';'
            else                            -> ','
        }
        lines.map { row ->
            row.trim('|', ' ').split(delim).map { it.trim().trim('|').trim() }
        }
    }
}

private fun parseMarkdownTable(lines: List<String>): List<List<String>> {
    val noSep = lines.filterNot { it.matches(Regex("^\\s*\\|?\\s*:?-{3,}.*")) }
    return noSep.map { row ->
        row.trim().trim('|').split('|').map { it.trim() }
    }
}
