package com.omariskandarani.livelatex.html

import com.intellij.openapi.diagnostic.Logger
import java.io.File

import java.util.Collections

/**
 * TikZ compilation, PDF/EPS → web-showable image conversion (SVG preferred, PNG fallback), and caching.
 */
object LatexHtmlTikz {
    private val LOG = Logger.getInstance(LatexHtmlTikz::class.java)
    private val runningProcesses = Collections.synchronizedSet(mutableSetOf<Process>())
    private const val LOG_TAIL = 1200
    /** Dense TikZ (e.g. hundreds of decorated segments) often exceeds 60s on first MiKTeX run. */
    private const val PDFLATEX_TIMEOUT_MS = 180_000L
    private const val PDF_TO_SVG_TIMEOUT_MS = 120_000L

    /** A converted figure ready for `<img src="...">` (SVG or PNG). */
    data class WebImageResult(val file: File, val mime: String)

    private fun sha1(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun sha1FileKey(f: File): String =
        sha1("${f.absolutePath}|${f.length()}|${f.lastModified()}")

    private data class TikzTools(
        val dvisvgm: String?,
        val pdf2svg: String?,
        val pdftoppm: String?,
        val magick: String?,
        val epstopdf: String?,
    )

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
            pdf2svg = which("pdf2svg"),
            pdftoppm = which("pdftoppm"),
            magick = which("magick") ?: which("convert"),
            epstopdf = which("epstopdf"),
        )
        _tikzTools = tools
        return tools
    }

    fun killRunningProcesses() {
        synchronized(runningProcesses) {
            runningProcesses.forEach { proc ->
                try {
                    proc.destroyForcibly()
                } catch (_: Throwable) {
                }
            }
            runningProcesses.clear()
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Preview build cancelled")
    }

    internal fun run(cmd: List<String>, cwd: File, timeoutMs: Long = PDFLATEX_TIMEOUT_MS): Pair<Boolean, String> {
        checkInterrupted()
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
        TikzRenderer.currentBaseDir?.let { base ->
            val sep = if (System.getProperty("os.name").contains("win", true)) ";" else ":"
            val path = File(base).absolutePath
            pb.environment()["TEXINPUTS"] = path + sep + File(path, "tex").absolutePath + sep
        }
        val p = pb.start()
        runningProcesses.add(p)
        try {
            val out = StringBuilder()
            val t = Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    checkInterrupted()
                    out.appendLine(line)
                }
            }
            t.start()
            val ok = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!ok) {
                p.destroyForcibly()
                return false to "Timeout running: $cmd\n$out"
            }
            checkInterrupted()
            return (p.exitValue() == 0) to out.toString()
        } finally {
            if (p.isAlive) {
                try {
                    p.destroyForcibly()
                } catch (_: Throwable) {
                }
            }
            runningProcesses.remove(p)
        }
    }

    internal fun tikzCacheDir(): File {
        val dir = if (TikzRenderer.pluginCacheRoot != null) {
            File(TikzRenderer.pluginCacheRoot, "tikz")
        } else {
            val base = TikzRenderer.currentBaseDir?.let(::File) ?: File(".")
            File(base, ".livelatex-cache/tikz")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Convert an existing PDF (or EPS via epstopdf) to SVG (preferred) or PNG (fallback).
     * Results are SHA-cached under the TikZ cache directory.
     */
    fun convertPdfToWebImage(sourceFile: File, cacheKey: String? = null): WebImageResult? {
        if (!sourceFile.exists() || !sourceFile.isFile) return null
        val ext = sourceFile.extension.lowercase()
        if (ext !in setOf("pdf", "eps")) return null

        val key = cacheKey ?: sha1FileKey(sourceFile)
        val cache = tikzCacheDir()
        val svgCached = File(cache, "pdf-$key.svg")
        if (svgCached.exists()) return WebImageResult(svgCached, "image/svg+xml")
        val pngCached = File(cache, "pdf-$key.png")
        if (pngCached.exists()) return WebImageResult(pngCached, "image/png")

        val work = File(cache, "pdf-$key").apply { mkdirs() }
        val pdf = File(work, "fig.pdf")
        if (ext == "eps") {
            sourceFile.copyTo(File(work, "fig.eps"), overwrite = true)
            val epsPdf = ensurePdfFromEps(work) ?: return null
            if (epsPdf.absolutePath != pdf.absolutePath) epsPdf.copyTo(pdf, overwrite = true)
        } else {
            sourceFile.copyTo(pdf, overwrite = true)
        }
        if (!pdf.exists()) return null

        return convertPdfInWorkDir(work, pdf, cache, key)
    }

    private fun ensurePdfFromEps(work: File): File? {
        val eps = File(work, "fig.eps")
        if (!eps.exists()) return null
        val pdf = File(work, "fig.pdf")
        val tools = findTikzTools()
        if (tools.epstopdf != null) {
            val (ok, log) = run(listOf(tools.epstopdf, eps.absolutePath, pdf.absolutePath), work, PDF_TO_SVG_TIMEOUT_MS)
            File(work, "epstopdf.log").writeText(log)
            if (ok && pdf.exists()) return pdf
        }
        return null
    }

    /** PDF → SVG (dvisvgm / pdf2svg) or PNG (pdftoppm / magick). Writes cache copies on success. */
    internal fun convertPdfInWorkDir(work: File, pdf: File, cache: File, cacheKey: String): WebImageResult? {
        val producedSvg = File(work, "fig.svg")
        val producedPng = File(work, "fig.png")
        val tools = findTikzTools()

        val (svgOk, svgLog) = when {
            tools.dvisvgm != null -> run(
                listOf(
                    tools.dvisvgm!!, "--pdf", "--no-fonts", "--exact", "-n",
                    pdf.absolutePath, "-o", producedSvg.absolutePath,
                ),
                work,
                PDF_TO_SVG_TIMEOUT_MS,
            )
            tools.pdf2svg != null -> run(
                listOf(tools.pdf2svg!!, pdf.absolutePath, producedSvg.absolutePath),
                work,
                PDF_TO_SVG_TIMEOUT_MS,
            )
            else -> false to "Neither dvisvgm nor pdf2svg is available."
        }
        File(work, "convert.log").writeText(svgLog)
        if (svgOk && producedSvg.exists()) {
            val cached = File(cache, "pdf-$cacheKey.svg")
            cached.writeText(producedSvg.readText())
            return WebImageResult(cached, "image/svg+xml")
        }

        val (pngOk, pngLog) = when {
            tools.pdftoppm != null -> run(
                listOf(tools.pdftoppm!!, "-png", "-r", "150", "-singlefile", pdf.absolutePath, File(work, "fig").absolutePath),
                work,
                PDF_TO_SVG_TIMEOUT_MS,
            )
            tools.magick != null -> run(
                listOf(tools.magick!!, pdf.absolutePath, "-density", "150", producedPng.absolutePath),
                work,
                PDF_TO_SVG_TIMEOUT_MS,
            )
            else -> false to "Neither pdftoppm nor magick/convert is available for PNG fallback."
        }
        File(work, "convert-png.log").writeText(
            buildString {
                appendLine("--- SVG attempt ---")
                append(svgLog.takeLast(LOG_TAIL))
                appendLine()
                appendLine("--- PNG attempt ---")
                append(pngLog.takeLast(LOG_TAIL))
            },
        )
        val pngFile = when {
            producedPng.exists() -> producedPng
            File(work, "fig.png").exists() -> File(work, "fig.png")
            else -> null
        }
        if (pngOk && pngFile != null) {
            val cached = File(cache, "pdf-$cacheKey.png")
            pngFile.copyTo(cached, overwrite = true)
            return WebImageResult(cached, "image/png")
        }

        LOG.warn(
            "LiveLatex PDF→web-image failed (work=${work.absolutePath}): " +
                svgLog.trim().takeLast(LOG_TAIL),
        )
        return null
    }

    /**
     * Compile a TeX document with pdflatex, then convert the resulting PDF to SVG/PNG.
     * Used by TikZ blocks, standalone figure sources, and lazy-render jobs.
     */
    fun renderTexDocumentToWebImage(texDoc: String, jobKey: String): WebImageResult? {
        val cache = tikzCacheDir()
        val safeKey = jobKey.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val svgCached = File(cache, "$safeKey.svg")
        if (svgCached.exists()) return WebImageResult(svgCached, "image/svg+xml")
        val pngCached = File(cache, "$safeKey.png")
        if (pngCached.exists()) return WebImageResult(pngCached, "image/png")

        val contentKey = sha1(texDoc)
        val work = File(cache, contentKey).apply { mkdirs() }
        val tex = File(work, "fig.tex")
        val pdf = File(work, "fig.pdf")
        tex.writeText(texDoc)

        val (ok1, log1) = run(
            listOf("pdflatex", "-interaction=nonstopmode", "-halt-on-error", tex.absolutePath),
            work,
            PDFLATEX_TIMEOUT_MS,
        )
        File(work, "build.log").writeText(log1)
        if (!ok1 || !pdf.exists()) {
            LOG.warn(
                "LiveLatex TikZ pdflatex failed (job=$jobKey, work=${work.absolutePath}): " +
                    log1.trim().takeLast(LOG_TAIL),
            )
            return null
        }

        val converted = convertPdfInWorkDir(work, pdf, cache, safeKey) ?: return null
        val legacy = File(cache, "$safeKey.${converted.file.extension}")
        if (!legacy.exists() || legacy.length() == 0L) {
            converted.file.copyTo(legacy, overwrite = true)
        }
        return WebImageResult(legacy, converted.mime)
    }

    /** Compile a TikZ tex document to SVG/PNG. Returns the cached image file on success, null on failure. */
    fun renderTexToSvg(texDoc: String, jobKey: String): File? =
        renderTexDocumentToWebImage(texDoc, jobKey)?.file

    /** Best-effort log path after a failed conversion in [workDir]. */
    fun latestLogPath(workDir: File): String? {
        for (name in listOf("build.log", "convert.log", "convert-png.log", "epstopdf.log")) {
            val f = File(workDir, name)
            if (f.exists()) return f.absolutePath
        }
        return if (workDir.exists()) workDir.absolutePath else null
    }
}
