package com.omariskandarani.livelatex.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Shows a balloon + Event Log / Notifications-list entry when the installed
 * plugin version differs from the last announced version.
 */
object WhatsNewAnnouncer {
    const val PLUGIN_ID = "com.omariskandarani.livelatex"
    const val NOTIFICATION_GROUP_ID = "LiveLatex"

    private val lock = Any()
    private val versionTag = Regex("""<version>\s*([^<]*?)\s*</version>""", RegexOption.IGNORE_CASE)

    fun announceIfNeeded(project: Project) {
        if (project.isDisposed) return
        synchronized(lock) {
            val current = currentPluginVersion()?.trim().orEmpty()
            if (current.isEmpty()) return

            val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
            if (!ChangelogNotes.shouldAnnounce(settings.lastSeenPluginVersion, current)) return

            val changelog = loadBundledWhatsNew() ?: return
            val section = ChangelogNotes.sectionForVersion(changelog, current) ?: return
            val body = ChangelogNotes.toNotificationHtml(section)
            if (body.isBlank() || body == "<html></html>") return

            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "LiveLatex $current — What's new",
                    body,
                    NotificationType.INFORMATION,
                )
                .notify(project)

            settings.lastSeenPluginVersion = current
        }
    }

    /**
     * Reads the Marketplace/plugin version from the packaged `/META-INF/plugin.xml`
     * (stamped by `patchPluginXml`). Avoids PluginManager / PluginId APIs for
     * cross-IDE binary compatibility.
     */
    fun currentPluginVersion(): String? =
        WhatsNewAnnouncer::class.java.getResourceAsStream("/META-INF/plugin.xml")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { parseVersionFromPluginXml(it.readText()) }

    /** Extracts `<version>` from plugin.xml text; null if missing/blank/malformed. */
    fun parseVersionFromPluginXml(pluginXml: String): String? {
        val match = versionTag.find(pluginXml) ?: return null
        return match.groupValues[1].trim().ifEmpty { null }
    }

    fun loadBundledWhatsNew(): String? =
        WhatsNewAnnouncer::class.java.getResourceAsStream("/META-INF/whats-new.md")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
}
