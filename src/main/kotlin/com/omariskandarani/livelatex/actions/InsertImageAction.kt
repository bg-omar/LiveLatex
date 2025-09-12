package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.omariskandarani.livelatex.util.LatexUtils
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class InsertImageAction : AnAction() {

    init {
        templatePresentation.text = "Insert Image…"
        templatePresentation.description = "Copy image to project and insert \\includegraphics snippet"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val editorFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editorPath = Paths.get(editorFile.path)
        val projectBase = project.basePath?.let { Paths.get(it) } ?: editorPath.parent

        // Pick an image
        val allowedExtensions = setOf("png", "jpg", "jpeg", "gif", "svg", "bmp", "webp", "pdf")
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("png")
        descriptor.withFileFilter { file ->
            val ext = file.extension?.lowercase()
            ext in allowedExtensions
        }
        descriptor.setTitle("Choose Image")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return

        try {
            val chosenPath = Paths.get(chosen.path)
            val relPath: String
            if (chosenPath.startsWith(projectBase)) {
                // Image is inside project, use relative path to editor's file
                relPath = editorPath.parent.relativize(chosenPath).toString().replace("\\", "/")
            } else {
                // Image is outside, copy to figures next to editor's file
                val figuresDir = editorPath.parent.resolve("figures")
                Files.createDirectories(figuresDir)
                val dest = figuresDir.resolve(chosen.name)
                Files.copy(chosenPath, dest, StandardCopyOption.REPLACE_EXISTING)
                val ioFile = dest.toFile()
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                VfsUtil.markDirtyAndRefresh(false, true, true, vf)
                relPath = "figures/${chosen.name}"
            }

            LatexUtils.ensurePackage(project, editor, "graphicx")
            val baseName = chosen.nameWithoutExtension
            val caret = editor.caretModel.currentCaret
            val snippet = """
                \begin{figure}[htbp]
                  \centering
                  \includegraphics[width=0.7\linewidth]{$relPath}
                  \caption{${humanizeCaption(baseName)}}
                  \label{fig:${sanitizeLabel(baseName)}}
                \end{figure}
            """.trimIndent()

            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(caret.offset, snippet)
            }
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Failed to insert image: ${ex.message}", "Insert Image")
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

    private fun sanitizeLabel(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun humanizeCaption(s: String): String =
        s.replace(Regex("[-_]+"), " ").replace(Regex("\\s+"), " ").trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}