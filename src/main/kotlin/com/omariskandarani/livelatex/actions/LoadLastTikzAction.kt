package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages

/** Re-open the TikZ canvas with the last exported session (knot + flip + width). */
class LoadLastTikzAction : AnAction(
    "Load Last TikZ…",
    "Reopen the last TikZ canvas session from this IDE run",
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val session = ApplicationManager.getApplication().getService(TikzSessionStore::class.java)
        if (!session.hasLastSession()) {
            Messages.showInfoMessage(
                project,
                "No TikZ session yet in this IDE run. Create or edit a figure first (OK / Add to TeX).",
                "Load Last TikZ",
            )
            return
        }

        val dialog = TikzCanvasDialog(project, restoreFromSession = true)
        if (!dialog.showAndGet()) return
        val body = dialog.resultTikz ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val caretPos = editor.caretModel.offset
            editor.document.insertString(caretPos, "\n$body\n")
        }
    }

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            vFile?.extension?.lowercase() in setOf("tex", "sty", "tikz")
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
