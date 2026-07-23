package com.omariskandarani.livelatex.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** After project open, announce changelog via the Notifications list if the version changed. */
class WhatsNewProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                WhatsNewAnnouncer.announceIfNeeded(project)
            }
        }
    }
}