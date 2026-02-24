package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.omariskandarani.livelatex.util.SectionMover

class MoveSectionAction : AnAction(), DumbAware {
    init {
        templatePresentation.text = "Move Section to External File"
        templatePresentation.description = "Move current LaTeX section body into .sections and insert an \\input"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        // Ensure file type is LaTeX-like
        val ext = vFile.extension?.lowercase()
        if (ext !in setOf("tex", "sty", "tikz")) {
            Messages.showInfoMessage(project, "Not a LaTeX file.", "Move Section")
            return
        }

        val caretOffset = editor.caretModel.currentCaret.offset
        // Document changes handled inside SectionMover via write command
        SectionMover.moveSectionAtOffset(project, editor.document, vFile, caretOffset)
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