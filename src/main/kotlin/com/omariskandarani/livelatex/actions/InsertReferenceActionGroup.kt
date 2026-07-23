package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

class InsertReferenceActionGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val editor = e?.getData(CommonDataKeys.EDITOR) ?: return emptyArray()
        val docText = editor.document.text

        val labels = InsertReferenceSupport.extractLabels(docText)
        val labelGroups = InsertReferenceSupport.groupLabelsByPrefix(labels)

        val labelGroupActions = labelGroups.entries.sortedBy { it.key }.map { (prefix, groupLabels) ->
            object : ActionGroup(prefix, true), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                    return groupLabels.sorted().map { label ->
                        object : AnAction(label), DumbAware {
                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                            override fun actionPerformed(e: AnActionEvent) {
                                insertAtCaret(editor, "\\ref{$label}")
                            }
                        }
                    }.toTypedArray()
                }
            }
        }

        val project = e.project ?: return labelGroupActions.toTypedArray()
        val bibFileNames = InsertReferenceSupport.bibliographyFileNames(docText)
        val bibKeys = linkedSetOf<String>()
        val bibEntryMap = linkedMapOf<String, String>()
        val editorParent = e.getData(CommonDataKeys.VIRTUAL_FILE)?.parent?.let { VfsUtilCore.virtualToIoFile(it) }

        for (bibFileName in bibFileNames) {
            val bibFile = resolveBibFile(project, bibFileName, editorParent) ?: continue
            val bibText = runCatching { bibFile.readText() }.getOrNull() ?: continue
            InsertReferenceSupport.parseBibEntries(bibText).forEach { (key, entry) ->
                bibKeys.add(key)
                bibEntryMap[key] = entry
            }
        }
        val sortedBibKeys = bibKeys.sorted()

        val citationGroup = object : ActionGroup("Citations", true), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                if (sortedBibKeys.isEmpty()) {
                    return arrayOf(object : AnAction("No citations found"), DumbAware {
                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                        override fun actionPerformed(e: AnActionEvent) {}
                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = false
                        }
                    })
                }
                return sortedBibKeys.map { key ->
                    object : AnAction(key), DumbAware {
                        init {
                            templatePresentation.description = bibEntryMap[key] ?: ""
                        }
                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                        override fun actionPerformed(e: AnActionEvent) {
                            insertAtCaret(editor, "\\cite{$key}")
                        }
                    }
                }.toTypedArray()
            }
        }

        return (labelGroupActions + citationGroup).toTypedArray()
    }

    private fun resolveBibFile(project: Project, bibFileName: String, editorParent: File?): File? {
        val fromFs = InsertReferenceSupport.resolveBibFile(bibFileName, project.basePath, editorParent)
        if (fromFs != null) return fromFs

        val fileName = if (bibFileName.endsWith(".bib", ignoreCase = true)) bibFileName else "$bibFileName.bib"
        val scope = GlobalSearchScope.projectScope(project)
        val virtual = FilenameIndex.getVirtualFilesByName(fileName, true, scope).firstOrNull()
        return virtual?.let { VfsUtilCore.virtualToIoFile(it) }?.takeIf { it.isFile }
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
