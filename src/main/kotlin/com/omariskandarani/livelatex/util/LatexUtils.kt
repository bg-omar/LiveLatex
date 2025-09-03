package com.omariskandarani.livelatex.util

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

object LatexUtils {

    fun ensurePackage(project: Project, editor: Editor, pkg: String) {
        val doc = editor.document
        val text = doc.text

        val preambleEnd = text.indexOf("\\begin{document}").let { if (it < 0) text.length else it }
        val preamble = text.substring(0, preambleEnd)

        if (hasPackage(preamble, pkg)) return

        val insertAt = preambleEnd
        val insertion = if (preamble.endsWith("\n")) "\\usepackage{$pkg}\n" else "\n\\usepackage{$pkg}\n"

        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(insertAt, insertion)
        }
    }

    private fun hasPackage(preamble: String, pkg: String): Boolean {
        val rx = Regex("""\\usepackage(?:\[[^\]]*])?\{([^}]*)}""")
        rx.findAll(preamble).forEach { m ->
            val inside = m.groupValues[1]
            val list = inside.split(',').map { it.trim() }
            if (list.any { it == pkg }) return true
        }
        return false
    }
}