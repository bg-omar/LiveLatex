package com.omariskandarani.livelatex.ui

import com.omariskandarani.livelatex.core.LatexPreviewService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.content.ContentFactory

class LatexPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser() // Embedded Chromium
        val content = ContentFactory.getInstance().createContent(browser.component, "", false)
        toolWindow.contentManager.addContent(content)

        // Register the browser with the service so it can push updates
        val svc = project.getService(LatexPreviewService::class.java)
        svc.attachBrowser(browser)
    }
}
