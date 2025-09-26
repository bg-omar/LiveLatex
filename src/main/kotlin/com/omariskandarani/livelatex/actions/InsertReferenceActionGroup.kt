package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class InsertReferenceActionGroup : ActionGroup(), DumbAware {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val editor = e?.getData(CommonDataKeys.EDITOR) ?: return emptyArray()
        val docText = editor.document.text

        // Find all \label{...}
        val labelRegex = Regex("""\\label\{([^}]+)}""")
        val labels = labelRegex.findAll(docText).map { it.groupValues[1] }.toList()

        // Group labels by prefix before the colon
        val labelGroups = labels.groupBy { label ->
            label.substringBefore(':', "other")
        }

        // Create a submenu for each label group
        val labelGroupActions = labelGroups.entries.sortedBy { it.key }.map { (prefix, groupLabels) ->
            object : ActionGroup("$prefix", true) {
                override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                    return groupLabels.sorted().map { label ->
                        object : AnAction(label) {
                            override fun actionPerformed(e: AnActionEvent) {
                                insertAtCaret(editor, "\\ref{$label}")
                            }
                        }
                    }.toTypedArray()
                }
            }
        }

        // Find bibliography file name(s) from \bibliography{FILENAME}
        val bibCommandRegex = Regex("\\\\bibliography\\{([^}]+)\\}")
        val bibCommandMatch = bibCommandRegex.find(docText)
        val bibFileNames = bibCommandMatch?.groupValues?.get(1)?.split(',')?.map { it.trim() } ?: emptyList()
        val bibKeys = mutableSetOf<String>()
        val bibEntryMap = mutableMapOf<String, String>()
        val project = e.project ?: return (labelGroupActions).toTypedArray()
        val projectBasePath = project.basePath
        fun findBibFile(bibFileName: String): File? {
            if (projectBasePath != null) {
                val direct = File(projectBasePath, "$bibFileName.bib")
                if (direct.exists()) return direct
                return File(projectBasePath).walkTopDown().find { it.name == "$bibFileName.bib" }
            }
            val local = File("$bibFileName.bib")
            return if (local.exists()) local else null
        }
        for (bibFileName in bibFileNames) {
            val bibFile = findBibFile(bibFileName)
            val bibText = bibFile?.readText()
            if (bibText != null) {
                // More robust BibTeX entry key extraction and mapping
                val bibEntryRegex = Regex("@\\w+\\s*\\{\\s*([^,\\s]+)[^}]*\\}(.*?)(?=@|\\z)", RegexOption.DOT_MATCHES_ALL)
                bibEntryRegex.findAll(bibText).forEach { match ->
                    val key = match.groupValues[1].trim()
                    val entryStart = match.range.first
                    val nextEntryStart = match.range.last + 1
                    val entryText = bibText.substring(entryStart, nextEntryStart)
                    bibKeys.add(key)
                    bibEntryMap[key] = match.value.trim()
                }
            }
        }
        val sortedBibKeys = bibKeys.sorted()

        // Citations submenu
        val citationGroup = object : ActionGroup("Citations", true) {
            override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                if (sortedBibKeys.isEmpty()) {
                    return arrayOf(object : AnAction("No citations found") {
                        override fun actionPerformed(e: AnActionEvent) {}
                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = false
                        }
                    })
                }
                return sortedBibKeys.map { key ->
                    object : AnAction(key) {
                        init {
                            templatePresentation.description = bibEntryMap[key] ?: ""
                        }
                        override fun actionPerformed(e: AnActionEvent) {
                            insertAtCaret(editor, "\\cite{$key}")
                        }
                    }
                }.toTypedArray()
            }
        }

        // Return all label groups and citations as submenus
        return (labelGroupActions + citationGroup).toTypedArray()
    }

    private fun insertAtCaret(editor: Editor, text: String) {
        val caret = editor.caretModel.currentCaret
        val offset = caret.offset
        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.insertString(offset, text)
            caret.moveToOffset(offset + text.length)
        }
    }

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val ext = vFile?.extension?.lowercase()
        e.presentation.isEnabledAndVisible = ext in setOf("tex", "sty", "tikz")
    }
}