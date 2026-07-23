package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/** Top-level editor popup: Edit TikZ… when caret is inside a tikzpicture. */
class EditTikzFigureAction : AnAction(
    "Edit TikZ…",
    "Edit the tikzpicture under the caret in the canvas",
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        TikzFigureEditor.openAndApply(project, editor, requireEditBlock = true)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val okFile = TikzFigureEditor.isTexLikeExtension(vFile?.extension)
        if (!okFile || editor == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val inEdit = TikzFigureEditor.isCaretInTikzpicture(editor.document.text, editor.caretModel.offset)
        e.presentation.isEnabledAndVisible = inEdit
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
