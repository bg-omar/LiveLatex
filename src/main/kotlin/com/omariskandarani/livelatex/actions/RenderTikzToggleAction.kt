package com.omariskandarani.livelatex.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.omariskandarani.livelatex.core.LiveLatexSettings

/** Toggle for LiveRender (TikZ in preview). When off, TikZ is not compiled so the IDE stays responsive. */
class RenderTikzToggleAction : ToggleAction(
    "LiveRender",
    "TikZ automatisch in de preview compileren (uit = snellere IDE)",
    AllIcons.FileTypes.Image
) {
    override fun isSelected(e: AnActionEvent): Boolean =
        ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview = state
        e.project?.getService(LatexPreviewService::class.java)?.requestRefresh()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
