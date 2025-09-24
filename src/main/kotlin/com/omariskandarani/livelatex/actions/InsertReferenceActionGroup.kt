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
        val bibCommandRegex = Regex("""\\\\bibliography\{([^}]+)}""")
        val bibCommandMatch = bibCommandRegex.find(docText)
        val bibFileNames = bibCommandMatch?.groupValues?.get(1)?.split(',')?.map { it.trim() } ?: emptyList()
        val projectBasePath = e.project?.basePath
        val bibKeys = mutableSetOf<String>()
        for (bibFileName in bibFileNames) {
            val bibFilePath = if (projectBasePath != null) "$projectBasePath/$bibFileName.bib" else "$bibFileName.bib"
            val bibFile = File(bibFilePath)
            if (bibFile.exists()) {
                val bibText = bibFile.readText()
                val bibEntryRegex = Regex("""@\w+\{\s*([^,]+),""")
                bibEntryRegex.findAll(bibText).forEach { bibKeys.add(it.groupValues[1]) }
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
