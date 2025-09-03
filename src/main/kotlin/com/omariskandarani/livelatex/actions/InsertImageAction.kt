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

        // Pick an image
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        descriptor.title = "Choose Image"
        descriptor.withFileFilter { file ->
            val ext = file.extension?.lowercase()
            ext in setOf("png", "jpg", "jpeg", "gif", "svg", "bmp", "webp", "pdf")
        }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return

        try {
            val basePath = project.basePath ?: Paths.get(chosen.path).parent.toString()
            val figuresDir = Paths.get(basePath, "figures")
            Files.createDirectories(figuresDir)

            val dest = figuresDir.resolve(chosen.name)
            // Ensure we have a real file on disk
            val ioSrc = Paths.get(chosen.path)
            Files.copy(ioSrc, dest, StandardCopyOption.REPLACE_EXISTING)

            // Refresh VFS
            val ioFile = dest.toFile()
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            VfsUtil.markDirtyAndRefresh(false, true, true, vf)

            // Make sure \usepackage{graphicx} is present
            LatexUtils.ensurePackage(project, editor, "graphicx")

            // Insert snippet at caret
            val relPath = "figures/" + chosen.name
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

    private fun sanitizeLabel(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun humanizeCaption(s: String): String =
        s.replace(Regex("[-_]+"), " ").replace(Regex("\\s+"), " ").trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}