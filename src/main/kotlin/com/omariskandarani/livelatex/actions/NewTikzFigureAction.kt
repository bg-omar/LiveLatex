package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class NewTikzFigureAction : AnAction(
    "New TikZ…",
    "Open the TikZ canvas. Right-click inside an existing tikzpicture to edit it.",
    null,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        TikzFigureEditor.openAndApply(project, editor, requireEditBlock = false)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val ok = TikzFigureEditor.isTexLikeExtension(vFile?.extension)
        e.presentation.isEnabledAndVisible = ok
        if (ok && editor != null) {
            val inEdit = TikzFigureEditor.isCaretInTikzpicture(editor.document.text, editor.caretModel.offset)
            e.presentation.text = if (inEdit) "Edit TikZ…" else "New TikZ…"
            e.presentation.description = if (inEdit) {
                "Edit the tikzpicture under the caret in the canvas"
            } else {
                "Open the TikZ canvas. Right-click inside an existing tikzpicture to edit it."
            }
        }
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
