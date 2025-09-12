package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.omariskandarani.livelatex.tables.parseCsvLike
import com.omariskandarani.livelatex.ui.TableWizardDialog

class GenerateLatexTableAction : AnAction("Generate LaTeX Table…") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor  = e.getData(CommonDataKeys.EDITOR) ?: return

        val seed = editor.selectionModel.let { sm ->
            if (sm.hasSelection()) parseCsvLike(sm.selectedText ?: "") else emptyList()
        }

        val dlg = TableWizardDialog(project, seed)
        if (dlg.showAndGet()) {
            val latex = dlg.resultLatex()
            insertAtCaret(project, editor, latex)
        }
    }

    private fun insertAtCaret(project: Project, editor: Editor, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val caret = editor.caretModel.currentCaret
            val offset = caret.offset
            editor.document.insertString(offset, "\n$text\n")
            caret.moveToOffset(offset + text.length + 2)
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