package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class ShowPreviewAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview") ?: return
        tw.show()
    }

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val ext = vFile?.extension?.lowercase()
        e.presentation.isEnabledAndVisible = ext in setOf("tex", "sty", "tikz")
    }

    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }
}