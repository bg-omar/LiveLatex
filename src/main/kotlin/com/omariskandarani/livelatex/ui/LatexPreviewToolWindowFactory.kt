package com.omariskandarani.livelatex.ui

import com.omariskandarani.livelatex.actions.PreviewOptionsAction
import com.omariskandarani.livelatex.actions.PreviewSectionsAction
import com.omariskandarani.livelatex.actions.PreviewZoomInAction
import com.omariskandarani.livelatex.actions.PreviewZoomOutAction
import com.omariskandarani.livelatex.actions.RenderTikzToggleAction
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.content.ContentFactory

class LatexPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()
        val content = ContentFactory.getInstance().createContent(browser.component, "", false)
        toolWindow.contentManager.addContent(content)

        toolWindow.setTitleActions(
            listOf(
                PreviewSectionsAction(project),
                PreviewZoomOutAction(project),
                PreviewZoomInAction(project),
                PreviewOptionsAction(project),
                RenderTikzToggleAction()
            )
        )

        project.getService(LatexPreviewService::class.java).attachBrowser(browser)
    }
}
