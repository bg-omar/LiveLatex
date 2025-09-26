package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages

class NewTikzFigureAction : AnAction("New TikZ Figure…", "Draw a quick TikZ picture and insert it", null) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val selection = editor.selectionModel.selectedText

        // Minimal libs required for the new two-strand exporter
        val minimalLibs = setOf("hobby", "decorations.markings")

        // Your original, richer preamble (kept as fallback for new/empty docs)
        val fullPreamble = """
% ------- TikZ Preamble -------
\RequirePackage{tikz}
\usetikzlibrary{knots,hobby,calc,intersections,decorations.pathreplacing,decorations.markings,shapes.geometric,spath3}
\tikzset{ knot diagram/every strand/.append style={ultra thick, black}}

\newcommand{\SSTGuidesPoints}[2]{% #1=basename (e.g. P), #2=last index
  \ifsstguides
    \foreach \i in {1,...,#2}{
      \fill[blue] (#1\i) circle (1.2pt);
      \node[blue,font=\scriptsize,above] at (#1\i) {\i};
    }
    \draw[gray!40, dashed]
    \foreach \i [remember=\i as \lasti (initially 1)] in {2,...,#2,1} { (#1\lasti)--(#1\i) };
  \fi
}
% ------- TikZ Preamble -------
        """.trimIndent()

        val dialog = TikzCanvasDialog(project, initialTikz = selection)

        if (!dialog.showAndGet()) return
        val body = dialog.resultTikz ?: return

        // Helpers to find insertion points
        fun findAfterDocumentClass(text: String): Int {
            val m = Regex("""\\documentclass[^\n]*\n""").find(text)
            return m?.range?.last?.plus(1) ?: 0
        }
        fun hasRequireTikz(text: String) =
            Regex("""\\RequirePackage\{tikz}""").containsMatchIn(text) ||
                    Regex("""\\usepackage\{tikz}""").containsMatchIn(text)

        // Collect already-loaded libraries across *all* \usetikzlibrary lines
        val allLibsRegex = Regex("""\\usetikzlibrary\{([^}]*)}""")
        val existingLibs = buildSet {
            for (m in allLibsRegex.findAll(document.text)) {
                m.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            }
        }
        // Determine which minimal libs are missing
        val missingMinimal = minimalLibs - existingLibs

        var preambleInserted = false
        WriteCommandAction.runWriteCommandAction(project) {
            // If the file is basically empty of TikZ config, insert the full preamble once after \documentclass
            val hasAnyUsetikz = allLibsRegex.containsMatchIn(document.text)
            if (!hasRequireTikz(document.text) && !hasAnyUsetikz) {
                val pos = findAfterDocumentClass(document.text)
                document.insertString(pos, fullPreamble + "\n\n")
                preambleInserted = true
            } else {
                // Ensure \RequirePackage{tikz}
                if (!hasRequireTikz(document.text)) {
                    val pos = findAfterDocumentClass(document.text)
                    document.insertString(pos, "\\RequirePackage{tikz}\n")
                }
                // Ensure minimal libs are loaded (append a new \usetikzlibrary line with only the missing ones)
                if (missingMinimal.isNotEmpty()) {
                    // place it just after the last existing \usetikzlibrary if any, otherwise after \RequirePackage
                    val lastLibMatch = allLibsRegex.findAll(document.text).lastOrNull()
                    val insertPos = when {
                        lastLibMatch != null -> lastLibMatch.range.last + 1
                        else -> {
                            val req = Regex("""\\RequirePackage\{tikz}""").find(document.text)
                            (req?.range?.last ?: findAfterDocumentClass(document.text)) + 1
                        }
                    }
                    document.insertString(insertPos, "\n\\usetikzlibrary{${missingMinimal.joinToString(",")}}\n")
                }

                // Ensure \SSTGuidesPoints exists (used by classic export mode)
                if (!Regex("""\\newcommand\{\\SSTGuidesPoints}""").containsMatchIn(document.text)) {
                    val pos = findAfterDocumentClass(document.text)
                    val helper = """
% ------- TikZ Guide Lines -------
\newcommand{\SSTGuidesPoints}[2]{% #1=basename (e.g. P), #2=last index
    \foreach \i in {1,...,#2}{
      \fill[blue] (#1\i) circle (1.2pt);
      \node[blue,font=\scriptsize,above] at (#1\i) {\i};
    }
    \draw[gray!40, dashed]
    \foreach \i [remember=\i as \lasti (initially 1)] in {2,...,#2,1} { (#1\lasti)--(#1\i) };
}
% ------- TikZ Guide Lines -------
                    """.trimIndent()
                    document.insertString(pos, helper + "\n\n")
                }
            }

            // Insert the generated TikZ at caret
            val caret = editor.caretModel.currentCaret
            document.insertString(caret.offset, "\n$body\n")
        }

        // UI outside write action
        if (preambleInserted) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "TikZ preamble inserted at top of document.",
                    "TikZ Preamble Added"
                )
            }
        }
        dialog.show()
    }


    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val ok = vFile?.extension?.lowercase() in setOf("tex", "sty", "tikz")
        e.presentation.isEnabledAndVisible = ok
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}