package com.omariskandarani.livelatex.actions

import java.io.File

/**
 * Pure helpers for [InsertReferenceActionGroup] (unit-testable; no EDT filesystem walks).
 */
object InsertReferenceSupport {

    private val labelRegex = Regex("""\\label\{([^}]+)}""")
    private val bibCommandRegex = Regex("""\\bibliography\{([^}]+)}""")
    private val bibEntryRegex =
        Regex("""@\w+\s*\{\s*([^,\s]+)[^}]*\}(.*?)(?=@|\z)""", RegexOption.DOT_MATCHES_ALL)

    fun extractLabels(docText: String): List<String> =
        labelRegex.findAll(docText).map { it.groupValues[1] }.toList()

    /** Group labels by prefix before the first colon (or "other"). */
    fun groupLabelsByPrefix(labels: List<String>): Map<String, List<String>> =
        labels.groupBy { it.substringBefore(':', "other") }

    fun bibliographyFileNames(docText: String): List<String> {
        val match = bibCommandRegex.find(docText) ?: return emptyList()
        return match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** BibTeX entry key → raw entry text. */
    fun parseBibEntries(bibText: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        bibEntryRegex.findAll(bibText).forEach { match ->
            val key = match.groupValues[1].trim()
            out[key] = match.value.trim()
        }
        return out
    }

    /**
     * Resolve `name.bib` without walking the whole project tree.
     * 1) `$projectBase/name.bib`
     * 2) Same dir as the editor file, then each parent up to [projectBasePath]
     */
    fun resolveBibFile(
        bibFileName: String,
        projectBasePath: String?,
        editorFileParent: File?,
    ): File? {
        val fileName = if (bibFileName.endsWith(".bib", ignoreCase = true)) bibFileName else "$bibFileName.bib"

        if (projectBasePath != null) {
            val direct = File(projectBasePath, fileName)
            if (direct.isFile) return direct
        }

        var dir = editorFileParent
        val root = projectBasePath?.let { File(it).canonicalFile }
        var depth = 0
        while (dir != null && depth < 32) {
            val candidate = File(dir, fileName)
            if (candidate.isFile) return candidate
            if (root != null && dir.canonicalFile == root) break
            dir = dir.parentFile
            depth++
        }

        if (projectBasePath == null) {
            val local = File(fileName)
            if (local.isFile) return local
        }
        return null
    }
}
