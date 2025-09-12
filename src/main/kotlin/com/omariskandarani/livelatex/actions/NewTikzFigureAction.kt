package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.omariskandarani.livelatex.util.LatexUtils

class NewTikzFigureAction : AnAction("New TikZ Figure…", "Draw a quick TikZ picture and insert it", null) {

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val isTexContext = editor != null && file?.extension in setOf("tex", "tikz")
        val isImageContext = file?.extension in setOf("png","jpg","jpeg","svg","pdf")
        e.presentation.isEnabledAndVisible = isTexContext || isImageContext
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val document = editor.document
        if (editor != null) {
            // Insert TikZ snippet into current editor
            val document = editor.document
            val caret = editor.caretModel.offset
            val tikz = """
                \begin{tikzpicture}
                    % TODO: your figure here
                \end{tikzpicture}
            """.trimIndent()

            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(caret, tikz)
                editor.caretModel.moveToOffset(caret + tikz.length)
            }
            return
        }

        // No text editor (e.g., invoked from image or project view) → copy to clipboard
        val tikz = "\\begin{tikzpicture}\n  % ...\n\\end{tikzpicture}\n"
        CopyPasteManager.getInstance().setContents(StringSelection(tikz))
        NotificationGroupManager.getInstance()
            .getNotificationGroup("LiveLaTeX")
            .createNotification("TikZ figure copied to clipboard. Paste into a .tex buffer.", NotificationType.INFORMATION)
            .notify(project)


        val tikzPreamble = """
            % ------- TikZ Preamble -------
            \RequirePackage{tikz}
            \usetikzlibrary{knots,hobby,calc,intersections,decorations.pathreplacing,shapes.geometric,spath3}
            % ------- Shared styles (from your preamble) -------
            \tikzset{
                knot diagram/every strand/.append style={ultra thick, black},
                every path/.style={black,line width=2pt},
                every node/.style={transform shape,knot crossing,inner sep=1.5pt},
                every knot/.style={line cap=round,line join=round,very thick},
            }
            % ------- Guides toggle -------
            \newif\ifsstguides
            \sstguidestrue
            % ------- Helper: label & skeleton for points P1..Pn -------
            \newcommand{\SSTGuidesPoints}[2]{% #1=basename (e.g. P), #2=last index
                \ifsstguides
                \foreach \i in {1,...,#2}{
                    \fill[blue] (#1\i) circle (1.2pt);
                    \node[blue,font=\scriptsize,above] at (#1\i) {\i};
                }
                \draw[gray!40, dashed] \foreach \i [remember=\i as \lasti (initially 1)] in {2,...,#2,1} { (#1\lasti)--(#1\i) };
                \fi
            }
        """.trimIndent()

        val dialog = TikzCanvasDialog(project)
        if (!dialog.showAndGet()) return  // cancelled

        val tikzBody = dialog.resultTikz ?: return

        // Check if preamble is present
        val docText = document.text
        val preambleKey = "\\usetikzlibrary{knots,hobby,calc,intersections,decorations.pathreplacing,shapes.geometric,spath3}"
        var preambleInserted = false
        WriteCommandAction.runWriteCommandAction(project) {
            if (!docText.contains(preambleKey)) {
                // Insert after \documentclass if present, else at top
                val docClassRegex = Regex("\\\\documentclass.*\\n")
                val match = docClassRegex.find(docText)
                val insertPos = match?.range?.last?.plus(1) ?: 0
                document.insertString(insertPos, tikzPreamble + "\n\n")
                preambleInserted = true
            }
            val code = """
                \begin{tikzpicture}[scale=1]
                $tikzBody
                \end{tikzpicture}
            """.trimIndent()
            val caret = editor.caretModel.currentCaret
            document.insertString(caret.offset, code)
        }
        if (preambleInserted) {
            Messages.showInfoMessage(project, "TikZ preamble inserted at top of document.", "TikZ Preamble Added")
        }
    }

    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }
}