package com.omariskandarani.livelatex.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages

private data class TikzBlock(val start: Int, val end: Int, val body: String)

/** Flatten nested \begin{tikzpicture}...\end{tikzpicture}: replace each inner picture with just its content. */
private fun flattenNestedTikzpicture(body: String): String {
    val beginTok = "\\begin{tikzpicture}"
    val endTok = "\\end{tikzpicture}"
    var s = body
    while (true) {
        val innerBegin = s.indexOf(beginTok)
        if (innerBegin < 0) break
        var bodyStart = innerBegin + beginTok.length
        if (bodyStart < s.length && s[bodyStart] == '[') {
            val closeBracket = s.indexOf(']', bodyStart)
            if (closeBracket >= 0) bodyStart = closeBracket + 1
        }
        var depth = 1
        var i = bodyStart
        var innerEnd = -1
        while (i < s.length && depth > 0) {
            val nextBegin = s.indexOf(beginTok, i)
            val nextEnd = s.indexOf(endTok, i)
            if (nextEnd < 0) break
            if (nextBegin >= 0 && nextBegin < nextEnd) {
                depth++
                i = nextBegin + beginTok.length
            } else {
                depth--
                if (depth == 0) {
                    innerEnd = nextEnd + endTok.length
                    break
                }
                i = nextEnd + endTok.length
            }
        }
        if (innerEnd < 0) break
        val innerContent = s.substring(bodyStart, innerEnd - endTok.length).trim()
        s = s.substring(0, innerBegin) + innerContent + s.substring(innerEnd)
    }
    return s
}

private fun findAnyTikzBlockAtCaret(text: String, caretOffset: Int): TikzBlock? {
    val beginTok = "\\begin{tikzpicture}"
    val endTok = "\\end{tikzpicture}"
    var pos = 0
    while (true) {
        val start = text.indexOf(beginTok, pos)
        if (start < 0) break
        var bodyStart = start + beginTok.length
        if (bodyStart < text.length && text[bodyStart] == '[') {
            val closeBracket = text.indexOf(']', bodyStart)
            if (closeBracket >= 0) bodyStart = closeBracket + 1
        }
        var depth = 1
        var i = bodyStart
        var blockEnd = -1
        while (i < text.length && depth > 0) {
            val nextBegin = text.indexOf(beginTok, i)
            val nextEnd = text.indexOf(endTok, i)
            if (nextEnd < 0) break
            if (nextBegin >= 0 && nextBegin < nextEnd) {
                depth++
                i = nextBegin + beginTok.length
            } else {
                depth--
                if (depth == 0) {
                    blockEnd = nextEnd + endTok.length
                    break
                }
                i = nextEnd + endTok.length
            }
        }
        if (blockEnd >= 0 && caretOffset >= start && caretOffset <= blockEnd) {
            val body = text.substring(bodyStart, blockEnd - endTok.length).trim()
            return TikzBlock(start, blockEnd, body)
        }
        pos = bodyStart
    }
    return null
}

private fun findHobbyTikzBlockAtCaret(text: String, caretOffset: Int): TikzBlock? {
    val beginTok = "\\begin{tikzpicture}"
    val endTok = "\\end{tikzpicture}"
    var pos = 0
    while (true) {
        val start = text.indexOf(beginTok, pos)
        if (start < 0) break
        var bodyStart = start + beginTok.length
        var opts = ""
        if (bodyStart < text.length && text[bodyStart] == '[') {
            val closeBracket = text.indexOf(']', bodyStart)
            if (closeBracket >= 0) {
                opts = text.substring(bodyStart + 1, closeBracket)
                bodyStart = closeBracket + 1
            }
        }
        if ("use Hobby shortcut" !in opts) {
            pos = bodyStart
            continue
        }
        var depth = 1
        var i = bodyStart
        var blockEnd = -1
        while (i < text.length && depth > 0) {
            val nextBegin = text.indexOf(beginTok, i)
            val nextEnd = text.indexOf(endTok, i)
            if (nextEnd < 0) break
            if (nextBegin >= 0 && nextBegin < nextEnd) {
                depth++
                i = nextBegin + beginTok.length
            } else {
                depth--
                if (depth == 0) {
                    blockEnd = nextEnd + endTok.length
                    break
                }
                i = nextEnd + endTok.length
            }
        }
        if (blockEnd >= 0 && caretOffset >= start && caretOffset <= blockEnd) {
            val body = text.substring(bodyStart, blockEnd - endTok.length).trim()
            return TikzBlock(start, blockEnd, body)
        }
        pos = bodyStart
    }
    return null
}

class NewTikzFigureAction : AnAction("New TikZ Figure…", "Draw a quick TikZ picture and insert it", null) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val text = document.text
        val caret = editor.caretModel.offset
        val editBlock = findHobbyTikzBlockAtCaret(text, caret) ?: findAnyTikzBlockAtCaret(text, caret)
        val rawInitial = editBlock?.body ?: editor.selectionModel.selectedText
        val initialTikz = rawInitial?.let { flattenNestedTikzpicture(it) }

        // Minimal libs required for the new two-strand exporter
        val minimalLibs = setOf("hobby", "decorations.markings")

        // Base preamble (always): use \usepackage{tikz} so preamble has the include package when missing
        val basePreamble = """
% ------- TikZ Preamble -------
\usepackage{tikz}
\usetikzlibrary{knots,hobby,calc,intersections,decorations.pathreplacing,decorations.markings,shapes.geometric,spath3}
% ------- TikZ Preamble -------
        """.trimIndent()

        // Knot-only additions (strand style + guides macro)
        val knotPreamble = """

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
        """.trimIndent()

        val dialog = TikzCanvasDialog(project, initialTikz = initialTikz)

        if (!dialog.showAndGet()) return
        val body = dialog.resultTikz ?: return
        val isKnot = body.contains("\\begin{knot}") || body.contains("\\KPATH")

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
            if (editBlock != null) {
                document.replaceString(editBlock.start, editBlock.end, body)
            } else {
                // If the file is basically empty of TikZ config, insert the full preamble once after \documentclass
                val hasAnyUsetikz = allLibsRegex.containsMatchIn(document.text)
                if (!hasRequireTikz(document.text) && !hasAnyUsetikz) {
                    val pos = findAfterDocumentClass(document.text)
                    val preamble = if (isKnot) "$basePreamble\n\n$knotPreamble" else basePreamble
                    document.insertString(pos, preamble + "\n\n")
                    preambleInserted = true
                } else {
                    // Ensure \usepackage{tikz} in preamble when not yet present
                    if (!hasRequireTikz(document.text)) {
                        val pos = findAfterDocumentClass(document.text)
                        document.insertString(pos, "\\usepackage{tikz}\n")
                    }
                    // Ensure minimal libs are loaded (append a new \usetikzlibrary line with only the missing ones)
                    if (missingMinimal.isNotEmpty()) {
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
                    if (isKnot && !Regex("""\\newcommand\{\\SSTGuidesPoints}""").containsMatchIn(document.text)) {
                        val pos = findAfterDocumentClass(document.text)
                        val helper = """
% ------- TikZ Guide Lines -------
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
% ------- TikZ Guide Lines -------
                        """.trimIndent()
                        document.insertString(pos, helper + "\n\n")
                    }
                }
                val caretPos = editor.caretModel.offset
                document.insertString(caretPos, "\n$body\n")
            }
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
        if (ok && editor != null) {
            val text = editor.document.text
            val caret = editor.caretModel.offset
            val inEdit = findHobbyTikzBlockAtCaret(text, caret) != null || findAnyTikzBlockAtCaret(text, caret) != null
            e.presentation.text = if (inEdit) "Edit TikZ…" else "New TikZ Figure…"
            e.presentation.description = if (inEdit) "Edit the TikZ picture in the canvas" else "Draw a quick TikZ picture and insert it"
        }
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}