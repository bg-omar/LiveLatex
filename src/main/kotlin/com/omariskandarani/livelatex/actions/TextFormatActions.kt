package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * A small set of editor popup actions to wrap the current selection with LaTeX commands
 * or insert suitable templates when nothing is selected.
 */
abstract open class BaseLatexWrapAction(private val label: String) : AnAction(label), DumbAware {
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
    }

    protected fun getSelection(editor: Editor): Pair<Int, String> {
        val selModel = editor.selectionModel
        return if (selModel.hasSelection()) {
            Pair(selModel.selectionStart, selModel.selectedText ?: "")
        } else {
            Pair(editor.caretModel.offset, "")
        }
    }

    protected fun replaceRange(project: Project, editor: Editor, start: Int, end: Int, text: String, moveCaretTo: Int) {
        val doc = editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(start, end, text)
            editor.caretModel.moveToOffset(moveCaretTo)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE)
            editor.selectionModel.removeSelection()
        }
    }
}

open class WrapWithSingleArgCommandAction(private val command: String, text: String) : BaseLatexWrapAction(text) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val (start, selected) = getSelection(editor)
        val end = if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionEnd else start

        if (selected.isNotEmpty()) {
            val replaced = "\\$command{" + selected + "}"
            replaceRange(project, editor, start, end, replaced, start + 2 + command.length) // caret after '{' per request
        } else {
            val snippet = "\\$command{}"
            val caret = start + 2 + command.length // position after '{'
            replaceRange(project, editor, start, end, snippet, caret)
        }
    }
}

class WrapWithTexorpdfstringAction : BaseLatexWrapAction("LaTeX: \n texorpdfstring") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val (start, selected) = getSelection(editor)
        val end = if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionEnd else start

        if (selected.isNotEmpty()) {
            val replaced = "\\texorpdfstring{" + selected + "}{" + selected + "}"
            val caret = start + "\\texorpdfstring{".length // after first '{'
            replaceRange(project, editor, start, end, replaced, caret)
        } else {
            val snippet = "\\texorpdfstring{}{}"
            val caret = start + "\\texorpdfstring{".length
            replaceRange(project, editor, start, end, snippet, caret)
        }
    }
}

class InsertEquationEnvironmentAction : BaseLatexWrapAction("LaTeX: equation environment") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val (start, selected) = getSelection(editor)
        val end = if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionEnd else start

        val newline = if (selected.contains('\n')) "\n" else "\n"
        val template = StringBuilder()
            .append("\\begin{equation}")
            .append(newline)
            .append(if (selected.isNotEmpty()) selected else "")
            .append(newline)
            .append("\\end{equation}")
            .toString()

        val caretOffset = start + "\\begin{equation}\n".length
        val caret = if (selected.isNotEmpty()) caretOffset else caretOffset
        replaceRange(project, editor, start, end, template, caret)
    }
}

// Convenience concrete actions with proper names for popup
class MakeTextBoldAction : WrapWithSingleArgCommandAction("textbf", "LaTeX: bold (\\textbf{…})")
class MakeTextItalicAction : WrapWithSingleArgCommandAction("textit", "LaTeX: italic (\\textit{…})")
class MakeTextEmphAction : WrapWithSingleArgCommandAction("emph", "LaTeX: emph (\\emph{…})")
class MakeTextCiteAction : WrapWithSingleArgCommandAction("cite", "LaTeX: cite (\\cite{…})")