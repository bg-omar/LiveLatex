package com.omariskandarani.livelatex.util

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path
import java.nio.file.Paths

object ExternalTexInserter {

    fun relativePath(editorFile: Path, sourceFile: Path): String? {
        val editorDir = editorFile.parent ?: return null
        return try {
            editorDir.relativize(sourceFile).toString().replace("\\", "/")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun inputSnippet(relPath: String): String = "\\input{$relPath}"

    fun insertAsInput(project: Project, editor: Editor, editorFile: VirtualFile, sourceFile: VirtualFile) {
        val editorPath = Paths.get(editorFile.path)
        val sourcePath = Paths.get(sourceFile.path)
        val relPath = relativePath(editorPath, sourcePath)
            ?: throw IllegalArgumentException("Cannot compute a relative path from the editor file to the source file.")
        val line = inputSnippet(relPath)
        insertAtCaret(project, editor, line)
    }

    fun insertAsText(project: Project, editor: Editor, sourceFile: VirtualFile) {
        val content = VfsUtil.loadText(sourceFile)
        insertAtCaret(project, editor, content)
    }

    private fun insertAtCaret(project: Project, editor: Editor, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val caret = editor.caretModel.currentCaret
            editor.document.insertString(caret.offset, text)
        }
    }
}