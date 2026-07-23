package com.omariskandarani.livelatex.core

/**
 * Pure helpers for extracting Keep-a-Changelog / what's-new sections and deciding when to announce.
 */
object ChangelogNotes {

    fun shouldAnnounce(lastSeenVersion: String?, currentVersion: String): Boolean {
        if (currentVersion.isBlank()) return false
        return lastSeenVersion.isNullOrBlank() || lastSeenVersion != currentVersion
    }

    /**
     * Returns the body of `## {version}` / `## {version} - date` (without the header line),
     * or null if that version is missing.
     */
    fun sectionForVersion(changelogMarkdown: String, version: String): String? {
        if (version.isBlank()) return null
        val header = Regex("""^##\s+${Regex.escape(version)}(?:\s+-.*)?\s*$""", RegexOption.MULTILINE)
        val match = header.find(changelogMarkdown) ?: return null
        val start = match.range.last + 1
        val rest = changelogMarkdown.substring(start)
        val nextHeader = Regex("""^##\s+\S+""", RegexOption.MULTILINE).find(rest)
        val body = if (nextHeader != null) rest.substring(0, nextHeader.range.first) else rest
        return body.trim().ifEmpty { null }
    }

    /**
     * HTML suitable for an IntelliJ notification (content is HTML; plain `\n` collapses).
     * Supports intro paragraphs, `###` headings, `-` / `*` bullets, and `**bold**`.
     */
    fun toNotificationHtml(sectionMarkdown: String): String {
        val parts = mutableListOf<String>()
        var pendingBlank = false

        for (raw in sectionMarkdown.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                pendingBlank = parts.isNotEmpty()
                continue
            }
            if (pendingBlank) {
                parts.add("")
                pendingBlank = false
            }
            parts.add(
                when {
                    line.startsWith("### ") ->
                        "<b>${inlineMd(line.removePrefix("### ").trim())}</b>"
                    line.startsWith("- ") ->
                        "• ${inlineMd(line.removePrefix("- ").trim())}"
                    line.startsWith("* ") ->
                        "• ${inlineMd(line.removePrefix("* ").trim())}"
                    else ->
                        inlineMd(line.trim())
                },
            )
        }

        val body = parts.joinToString("<br>")
        return "<html>$body</html>"
    }

    /** Escape HTML, turn `**bold**` into `<b>`, strip `` `code` `` markers. */
    internal fun inlineMd(s: String): String {
        val escaped = escapeHtml(s)
        val withCode = escaped.replace(Regex("""`([^`]+)`"""), "$1")
        return withCode.replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>")
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}