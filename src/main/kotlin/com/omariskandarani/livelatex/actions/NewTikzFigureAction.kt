package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.omariskandarani.livelatex.util.LatexUtils

class NewTikzFigureAction : AnAction("New TikZ Figure…", "Draw a quick TikZ picture and insert it", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document

        val dialog = TikzCanvasDialog(project)
        if (!dialog.showAndGet()) return  // cancelled

        val tikzBody = dialog.resultTikz ?: return
        // Ensure \usepackage{tikz}
        LatexUtils.ensurePackage(project, editor, "tikz")

        val code = """
            \begin{tikzpicture}[scale=1]
            $tikzBody
            \end{tikzpicture}
        """.trimIndent()

        val caret = editor.caretModel.currentCaret
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(caret.offset, code)
        }
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