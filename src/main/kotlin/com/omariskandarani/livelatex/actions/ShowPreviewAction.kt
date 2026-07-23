package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

object PreviewToolWindowToggle {
    fun nextVisible(isCurrentlyVisible: Boolean): Boolean = !isCurrentlyVisible

    fun applyToggle(tw: ToolWindow) {
        if (tw.isVisible) tw.hide() else tw.show()
    }
}

class ShowPreviewAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview") ?: return
        PreviewToolWindowToggle.applyToggle(tw)
    }

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val ext = vFile?.extension?.lowercase()
        val ok = ext in setOf("tex", "sty", "tikz")
        e.presentation.isEnabledAndVisible = ok
        if (!ok) return
        val tw = e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("LaTeX Preview") }
        e.presentation.text = if (tw?.isVisible == true) "Hide LaTeX Preview" else "Show LaTeX Preview"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
