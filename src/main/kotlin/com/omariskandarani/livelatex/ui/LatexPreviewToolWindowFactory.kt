package com.omariskandarani.livelatex.ui

import com.omariskandarani.livelatex.actions.PreviewCancelRenderAction
import com.omariskandarani.livelatex.actions.PreviewOptionsAction
import com.omariskandarani.livelatex.actions.PreviewRefreshAction
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
import java.awt.BorderLayout
import javax.swing.JPanel

class LatexPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()
        val statusLine = PreviewStatusLine()
        val panel = JPanel(BorderLayout()).apply {
            add(statusLine, BorderLayout.NORTH)
            add(browser.component, BorderLayout.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        toolWindow.setTitleActions(
            listOf(
                PreviewSectionsAction(project),
                PreviewRefreshAction(project),
                PreviewCancelRenderAction(project),
                PreviewZoomOutAction(project),
                PreviewZoomInAction(project),
                PreviewOptionsAction(project),
                RenderTikzToggleAction()
            )
        )

        project.getService(LatexPreviewService::class.java).attachBrowser(browser, statusLine)
    }
}
