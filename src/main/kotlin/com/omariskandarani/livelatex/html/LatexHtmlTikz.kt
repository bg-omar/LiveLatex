package com.omariskandarani.livelatex.html

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * TikZ compilation and SVG caching. Standalone object used by LatexHtml.
 */
object LatexHtmlTikz {
    private val LOG = Logger.getInstance(LatexHtmlTikz::class.java)
    private const val LOG_TAIL = 1200
    /** Dense TikZ (e.g. hundreds of decorated segments) often exceeds 60s on first MiKTeX run. */
    private const val PDFLATEX_TIMEOUT_MS = 180_000L
    private const val PDF_TO_SVG_TIMEOUT_MS = 120_000L
    private fun sha1(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }

    private data class TikzTools(val dvisvgm: String?, val pdf2svg: String?)
    private var _tikzTools: TikzTools? = null

    private fun findTikzTools(): TikzTools {
        _tikzTools?.let { return it }
        fun which(cmd: String): String? {
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val proc = ProcessBuilder(if (isWin) listOf("where", cmd) else listOf("which", cmd))
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val ok = proc.waitFor() == 0 && out.isNotBlank()
            return if (ok) out.lineSequence().firstOrNull()?.trim() else null
        }
        val tools = TikzTools(
            dvisvgm = which("dvisvgm"),
            pdf2svg = which("pdf2svg")
        )
        _tikzTools = tools
        return tools
    }

    private fun run(cmd: List<String>, cwd: File, timeoutMs: Long = PDFLATEX_TIMEOUT_MS): Pair<Boolean, String> {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
        TikzRenderer.currentBaseDir?.let { base ->
            val sep = if (System.getProperty("os.name").contains("win", true)) ";" else ":"
            val path = File(base).absolutePath
            pb.environment()["TEXINPUTS"] = path + sep + File(path, "tex").absolutePath + sep
        }
        val p = pb.start()
        val out = StringBuilder()
        val t = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        t.start()
        val ok = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) { p.destroyForcibly(); return false to "Timeout running: $cmd\n$out" }
        return (p.exitValue() == 0) to out.toString()
    }

    private fun tikzCacheDir(): File {
        val dir = if (TikzRenderer.pluginCacheRoot != null) {
            File(TikzRenderer.pluginCacheRoot, "tikz")
        } else {
            val base = TikzRenderer.currentBaseDir?.let(::File) ?: File(".")
            File(base, ".livelatex-cache/tikz")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Compile a TikZ tex document to SVG. Returns the cached SVG file on success, null on failure. */
    fun renderTexToSvg(texDoc: String, jobKey: String): File? {
        val cache = tikzCacheDir()
        // Keep lazy renders isolated per placeholder key to prevent accidental
        // cross-block reuse in the preview when multiple TikZ jobs are active.
        val safeKey = jobKey.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val svg = File(cache, "lazy-$safeKey.svg")
        if (svg.exists()) return svg
        val work = File(cache, "job-$jobKey").apply { mkdirs() }
        val tex = File(work, "fig.tex")
        val pdf = File(work, "fig.pdf")
        val produced = File(work, "fig.svg")
        tex.writeText(texDoc)
        val (ok1, log1) = run(
            listOf("pdflatex", "-interaction=nonstopmode", "-halt-on-error", tex.absolutePath),
            work,
            PDFLATEX_TIMEOUT_MS,
        )
        if (!ok1 || !pdf.exists()) {
            File(work, "build.log").writeText(log1)
            LOG.warn(
                "LiveLatex TikZ pdflatex failed (job=$jobKey, work=${work.absolutePath}): " +
                    log1.trim().takeLast(LOG_TAIL)
            )
            return null
        }
        val tools = findTikzTools()
        val (ok2, log2) = when {
            tools.dvisvgm != null -> run(
                listOf(tools.dvisvgm!!, "--pdf", "--no-fonts", "--exact", "-n", pdf.absolutePath, "-o", produced.absolutePath),
                work,
                PDF_TO_SVG_TIMEOUT_MS,
            )
            tools.pdf2svg != null -> run(listOf(tools.pdf2svg!!, pdf.absolutePath, produced.absolutePath), work, PDF_TO_SVG_TIMEOUT_MS)
            else -> false to "Neither dvisvgm nor pdf2svg is available."
        }
        if (!ok2) {
            File(work, "convert.log").writeText(log2)
            LOG.warn(
                "LiveLatex TikZ PDF→SVG failed (job=$jobKey, work=${work.absolutePath}): " +
                    log2.trim().takeLast(LOG_TAIL)
            )
            return null
        }
        return if (produced.exists()) {
            svg.writeText(produced.readText())
            svg
        } else null
    }
}
