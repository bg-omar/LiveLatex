package com.omariskandarani.livelatex.ui

import com.omariskandarani.livelatex.actions.PreviewCancelRenderAction
import com.omariskandarani.livelatex.actions.PreviewChapterComboAction
import com.omariskandarani.livelatex.actions.PreviewOptionsAction
import com.omariskandarani.livelatex.actions.PreviewRefreshAction
import com.omariskandarani.livelatex.actions.PreviewZoomInAction
import com.omariskandarani.livelatex.actions.PreviewZoomOutAction
import com.omariskandarani.livelatex.actions.RenderTikzToggleAction
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

class LatexPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        if (!JBCefApp.isSupported()) {
            panel.add(
                JLabel(
                    "Embedded browser (JCEF) is not available in this IDE. LiveLatex preview requires JCEF.",
                    SwingConstants.CENTER,
                ),
                BorderLayout.CENTER,
            )
            toolWindow.contentManager.addContent(
                ContentFactory.getInstance().createContent(panel, "", false)
            )
            return
        }

        val browser = JBCefBrowser()
        val statusLine = PreviewStatusLine()
        panel.add(statusLine, BorderLayout.NORTH)
        panel.add(browser.component, BorderLayout.CENTER)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, "", false)
        )

        toolWindow.setTitleActions(
            listOf(
                RenderTikzToggleAction(),
                PreviewChapterComboAction(project),
                PreviewRefreshAction(project),
                PreviewCancelRenderAction(project),
                PreviewZoomOutAction(project),
                PreviewZoomInAction(project),
                PreviewOptionsAction(project),
            )
        )

        project.getService(LatexPreviewService::class.java).attachBrowser(browser, statusLine)
    }
}
