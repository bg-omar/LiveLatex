package com.omariskandarani.livelatex.core

/** Parse sections JSON from the preview page (`JSON.stringify` of `{id, abs, label}` objects). */
object PreviewSectionsJson {
    fun parse(json: String): List<Pair<String, String>> {
        if (json.isBlank()) return emptyList()
        return try {
            val idRe = Regex("""\"id\"\s*:\s*\"([^\"]*)\"""")
            val labelRe = Regex("""\"label\"\s*:\s*\"([^\"]*)\"""")
            val raw = json.trim().removeSurrounding("[", "]").trim()
            if (raw.isEmpty()) return emptyList()
            val items = raw.split("},{")
            items.mapNotNull { part ->
                val id = idRe.find(part)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val label = labelRe.find(part)?.groupValues?.getOrNull(1) ?: id
                id to label
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
