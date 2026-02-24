package com.omariskandarani.livelatex.util

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Utility to extract the body of a LaTeX \section and move it into an external file under a `.sections` folder,
 * replacing the original body with an \input{...} statement.
 */
object SectionMover {
    private val sectionRegex = Regex("^\\\\section\\{(.*)}\\s*$")
    private val labelRegex = Regex("^\\\\label\\{([^}]*)}\\s*$")

    private data class SectionBlock(
        val title: String,
        val headerLineIdx: Int,
        val labelLineIdx: Int?, // line index where label appears (if any)
        val contentStartLineIdx: Int, // first line of movable content
        val contentEndLineIdxExclusive: Int, // line index exclusive end (next section header or end-of-file)
        val ordinal: Int // 1-based position
    )

    data class PublicSectionInfo(
        val title: String,
        val ordinal: Int,
        val headerLineIdx: Int,
        val labelLineIdx: Int?,
        val contentStartLineIdx: Int,
        val contentEndLineIdxExclusive: Int
    )

    /**
     * Perform move at given caret offset.
     */
    fun moveSectionAtOffset(project: Project, document: Document, vFile: VirtualFile, caretOffset: Int) {
        val lines = document.text.split("\n")
        val sectionBlocks = parseSections(lines)
        if (sectionBlocks.isEmpty()) {
            Messages.showInfoMessage(project, "No sections found in this file.", "Move Section")
            return
        }

        val caretLine = document.getLineNumber(caretOffset)
        val target = sectionBlocks.find { caretLine >= it.headerLineIdx && caretLine < it.contentEndLineIdxExclusive }
        if (target == null) {
            Messages.showInfoMessage(project, "Caret is not inside a section.", "Move Section")
            return
        }

        // Ensure there is content to move
        if (target.contentStartLineIdx >= target.contentEndLineIdxExclusive) {
            Messages.showInfoMessage(project, "Section '${target.title}' has no body to move.", "Move Section")
            return
        }

        val bodyLines = lines.subList(target.contentStartLineIdx, target.contentEndLineIdxExclusive)
        // Avoid moving if already replaced by \input
        val firstNonBlank = bodyLines.firstOrNull { it.isNotBlank() }
        if (firstNonBlank != null && firstNonBlank.trim().startsWith("\\input{")) {
            Messages.showInfoMessage(project, "Section '${target.title}' already appears to be externalized.", "Move Section")
            return
        }

        val safeTitle = target.title.replace(" ", "_").replace(Regex("[^A-Za-z0-9_]+"), "")
        val filename = "%02d_%s.tex".format(target.ordinal, safeTitle.ifBlank { "section" })

        val baseDir = Path.of(vFile.parent.path)
        val sectionsDir = baseDir.resolve(".sections")
        Files.createDirectories(sectionsDir)
        val newFilePath = sectionsDir.resolve(filename)

        // Write external file (overwrite allowed)
        Files.writeString(newFilePath, bodyLines.joinToString("\n"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        // Refresh VFS
        val ioFile = newFilePath.toFile()
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        if (vf != null) {
            VfsUtil.markDirtyAndRefresh(false, true, true, vf)
        }

        val inputStatement = "\\input{.sections/$filename}" // relative path from main file

        WriteCommandAction.runWriteCommandAction(project) {
            val newLines = mutableListOf<String>()
            // Keep everything up to contentStart
            newLines.addAll(lines.subList(0, target.contentStartLineIdx))
            // Insert input statement in place of body
            newLines.add(inputStatement)
            // Append remainder
            newLines.addAll(lines.subList(target.contentEndLineIdxExclusive, lines.size))
            document.setText(newLines.joinToString("\n"))
        }

        Messages.showInfoMessage(project, "Moved section '${target.title}' body to .sections/$filename", "Move Section")
    }

    /**
     * Extracts sections from the given text for testing purposes.
     */
    fun extractSectionsForTesting(text: String): List<PublicSectionInfo> =
        parseSections(text.split("\n")).map {
            PublicSectionInfo(
                title = it.title,
                ordinal = it.ordinal,
                headerLineIdx = it.headerLineIdx,
                labelLineIdx = it.labelLineIdx,
                contentStartLineIdx = it.contentStartLineIdx,
                contentEndLineIdxExclusive = it.contentEndLineIdxExclusive
            )
        }

    private fun parseSections(lines: List<String>): List<SectionBlock> {
        val blocks = mutableListOf<SectionBlock>()
        var ordinal = 0
        for (i in lines.indices) {
            val m = sectionRegex.matchEntire(lines[i].trim()) ?: continue
            val title = m.groupValues[1].trim()
            ordinal += 1
            // Find label line (first \label after header before next \section)
            var labelIdx: Int? = null
            var contentStart = i + 1
            var j = i + 1
            while (j < lines.size) {
                val lineTrim = lines[j].trim()
                if (sectionRegex.matches(lineTrim)) break
                if (labelIdx == null && labelRegex.matches(lineTrim)) {
                    labelIdx = j
                    contentStart = j + 1
                    // continue scanning until next section for body end
                }
                j += 1
            }
            val endExclusive = j
            blocks += SectionBlock(
                title = title,
                headerLineIdx = i,
                labelLineIdx = labelIdx,
                contentStartLineIdx = contentStart,
                contentEndLineIdxExclusive = endExclusive,
                ordinal = ordinal
            )
        }
        return blocks
    }
}