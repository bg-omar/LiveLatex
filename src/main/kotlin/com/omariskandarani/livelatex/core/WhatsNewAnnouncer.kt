package com.omariskandarani.livelatex.core

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project

/**
 * Shows a balloon + Event Log / Notifications-list entry when the installed
 * plugin version differs from the last announced version.
 */
object WhatsNewAnnouncer {
    const val PLUGIN_ID = "com.omariskandarani.livelatex"
    const val NOTIFICATION_GROUP_ID = "LiveLatex"

    private val lock = Any()

    fun announceIfNeeded(project: Project) {
        if (project.isDisposed) return
        synchronized(lock) {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: return
            val current = plugin.version?.trim().orEmpty()
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

    fun loadBundledWhatsNew(): String? =
        WhatsNewAnnouncer::class.java.getResourceAsStream("/META-INF/whats-new.md")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
}