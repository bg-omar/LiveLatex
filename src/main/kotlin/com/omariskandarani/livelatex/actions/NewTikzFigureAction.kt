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

        val tikzPreamble = """
% ------- TikZ Preamble -------
\RequirePackage{tikz}
\usetikzlibrary{knots,hobby,calc,intersections,decorations.pathreplacing,shapes.geometric,spath3}

% ------- Shared styles (from your preamble) -------
\tikzset{
    knot diagram/every strand/.append style={ultra thick, black},
%               every path/.style={black,line width=2pt},
%               every node/.style={transform shape,knot crossing,inner sep=1.5pt},
%               every knot/.style={line cap=round,line join=round,very thick},
%               strand/.style={line cap=round,line join=round,line width=3pt,draw=black},
%               over/.style={preaction={draw=white,line width=6.5pt}},
%               sst/ring A/.style={draw=black, line width=3pt},
%               sst/ring B/.style={draw=black,  line width=3pt},
%               sst/ring C/.style={draw=black, line width=3pt},
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
    \draw[gray!40, dashed]
    \foreach \i [remember=\i as \lasti (initially 1)] in {2,...,#2,1} { (#1\lasti)--(#1\i) };
  \fi
}
        """.trimIndent()

        val dialog = TikzCanvasDialog(project, initialTikz = selection)
        if (!dialog.showAndGet()) return
        val body = dialog.resultTikz ?: return

        val preambleKey = Regex("""\\usetikzlibrary\{knots,hobby,calc,intersections,decorations\.pathreplacing,shapes\.geometric,spath3}""")
        var inserted = false

        WriteCommandAction.runWriteCommandAction(project) {
            if (!preambleKey.containsMatchIn(document.text)) {
                val docClassRegex = Regex("""\\documentclass.*\n""")
                val match = docClassRegex.find(document.text)
                val pos = match?.range?.last?.plus(1) ?: 0
                document.insertString(pos, tikzPreamble + "\n\n")
                inserted = true
            }
            val code = """
\begin{tikzpicture}[use Hobby shortcut]
    $body
\end{tikzpicture}
            """.trimIndent()
            val caret = editor.caretModel.currentCaret
            document.insertString(caret.offset, code)
        }

        if (inserted) {
            Messages.showInfoMessage(project, "TikZ preamble inserted at top of document.", "TikZ Preamble Added")
        }
    }

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val ok = vFile?.extension?.lowercase() in setOf("tex", "sty", "tikz")
        e.presentation.isEnabledAndVisible = ok
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
