package com.omariskandarani.livelatex.html

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Shared store for lazy TikZ jobs. Used by LatexHtml (during wrap) and LatexPreviewService (for compile).
 * Decouples the job store from LatexHtml so LatexPreviewService can compile without depending on LatexHtml.
 */
object LatexTikzJobStore {
    private val jobs = Collections.synchronizedMap(LinkedHashMap<String, String>())

    fun clear() = jobs.clear()
    fun put(key: String, texDoc: String) { jobs[key] = texDoc }
    fun get(key: String): String? = synchronized(jobs) { jobs[key] }
}
