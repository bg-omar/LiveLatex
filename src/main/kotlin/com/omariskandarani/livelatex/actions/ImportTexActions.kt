package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.omariskandarani.livelatex.util.ExternalTexInserter

private val LATEX_EXTENSIONS = setOf("tex", "sty", "tikz")

private fun isLatexFile(file: VirtualFile?): Boolean =
    file?.extension?.lowercase() in LATEX_EXTENSIONS

private fun activeLatexEditorContext(project: Project): Pair<com.intellij.openapi.editor.Editor, VirtualFile>? {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
    return latexEditorContext(editor)
}

private fun latexEditorContext(editor: com.intellij.openapi.editor.Editor): Pair<com.intellij.openapi.editor.Editor, VirtualFile>? {
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    if (!isLatexFile(file)) return null
    return editor to file
}

private fun latexEditorContextFromEvent(e: AnActionEvent): Pair<com.intellij.openapi.editor.Editor, VirtualFile>? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    return latexEditorContext(editor)
}

private fun resolveLatexEditorContext(project: Project, e: AnActionEvent): Pair<com.intellij.openapi.editor.Editor, VirtualFile>? =
    latexEditorContextFromEvent(e) ?: activeLatexEditorContext(project)

private fun texFileChooserDescriptor() =
    FileChooserDescriptorFactory.createSingleFileDescriptor("tex").apply {
        withFileFilter { isLatexFile(it) }
        title = "Choose LaTeX source file"
    }

abstract class BrowseImportTexAction(
    private val asInput: Boolean,
    text: String,
    description: String,
) : AnAction(text, description, null), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (editor, editorFile) = resolveLatexEditorContext(project, e) ?: return

        val startFolder = editorFile.parent ?: editorFile
        val chosen = FileChooser.chooseFile(texFileChooserDescriptor(), project, startFolder) ?: return
        performInsert(project, editor, editorFile, chosen, asInput)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && resolveLatexEditorContext(project, e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

abstract class ProjectImportTexAction(
    private val asInput: Boolean,
    text: String,
    description: String,
) : AnAction(text, description, null), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!isLatexFile(sourceFile)) return

        val (editor, editorFile) = activeLatexEditorContext(project) ?: run {
            Messages.showInfoMessage(project, "Open a LaTeX file in the editor first.", "Insert source latex")
            return
        }

        performInsert(project, editor, editorFile, sourceFile, asInput)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val enabled = project != null &&
            isLatexFile(sourceFile) &&
            activeLatexEditorContext(project) != null
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private fun performInsert(
    project: Project,
    editor: com.intellij.openapi.editor.Editor,
    editorFile: VirtualFile,
    sourceFile: VirtualFile,
    asInput: Boolean,
) {
    try {
        if (asInput) {
            ExternalTexInserter.insertAsInput(project, editor, editorFile, sourceFile)
        } else {
            ExternalTexInserter.insertAsText(project, editor, sourceFile)
        }
    } catch (ex: Exception) {
        Messages.showErrorDialog(
            project,
            "Failed to insert source: ${ex.message}",
            if (asInput) "\\input source latex" else "Insert source latex",
        )
    }
}

class BrowseImportTexAsTextAction : BrowseImportTexAction(
    asInput = false,
    text = "Insert source latex",
    description = "Browse for a .tex file and paste its contents at the caret",
)

class BrowseImportTexAsInputAction : BrowseImportTexAction(
    asInput = true,
    text = "\\input source latex",
    description = "Browse for a .tex file and insert \\input{relative/path.tex} as one line",
)

class ProjectImportTexAsTextAction : ProjectImportTexAction(
    asInput = false,
    text = "Insert source latex",
    description = "Paste the selected .tex file contents into the active editor at the caret",
)

class ProjectImportTexAsInputAction : ProjectImportTexAction(
    asInput = true,
    text = "\\input source latex",
    description = "Insert \\input{relative/path.tex} for the selected .tex file into the active editor",
)

class ProjectInsertTexActionGroup : DefaultActionGroup(), DumbAware {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val sourceFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val visible = project != null &&
            isLatexFile(sourceFile) &&
            activeLatexEditorContext(project) != null
        e.presentation.isEnabledAndVisible = visible
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}