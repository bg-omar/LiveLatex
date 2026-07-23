package com.omariskandarani.livelatex.actions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/** Shared TikZ block find / apply for New TikZ and Edit TikZ actions. */
object TikzFigureEditor {

    data class TikzBlock(val start: Int, val end: Int, val body: String)

    private val texExtensions = setOf("tex", "sty", "tikz")

    fun isTexLikeExtension(ext: String?): Boolean =
        ext?.lowercase() in texExtensions

    fun findEditableBlockAtCaret(text: String, caretOffset: Int): TikzBlock? =
        findHobbyTikzBlockAtCaret(text, caretOffset) ?: findAnyTikzBlockAtCaret(text, caretOffset)

    fun isCaretInTikzpicture(text: String, caretOffset: Int): Boolean =
        findEditableBlockAtCaret(text, caretOffset) != null

    /** Flatten nested \begin{tikzpicture}...\end{tikzpicture}: replace each inner picture with just its content. */
    fun flattenNestedTikzpicture(body: String): String {
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

    fun findAnyTikzBlockAtCaret(text: String, caretOffset: Int): TikzBlock? {
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

    fun findHobbyTikzBlockAtCaret(text: String, caretOffset: Int): TikzBlock? {
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

    /**
     * Open the TikZ canvas and apply the result to the editor.
     * @param requireEditBlock when true, only runs if caret is inside a tikzpicture (Edit TikZ).
     */
    fun openAndApply(project: Project, editor: Editor, requireEditBlock: Boolean) {
        val document = editor.document
        val text = document.text
        val caret = editor.caretModel.offset
        val editBlock = findEditableBlockAtCaret(text, caret)
        if (requireEditBlock && editBlock == null) return

        val rawInitial = editBlock?.body ?: editor.selectionModel.selectedText
        val initialTikz = rawInitial?.let { flattenNestedTikzpicture(it) }

        val minimalLibs = setOf("hobby", "decorations.markings")
        val basePreamble = """
% ------- TikZ Preamble -------
\usepackage{tikz}
\usetikzlibrary{knots,hobby,calc,intersections,decorations.pathreplacing,decorations.markings,shapes.geometric,spath3}
% ------- TikZ Preamble -------
        """.trimIndent()

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
        val rawBody = dialog.resultTikzRaw
        val wrappedBody = dialog.resultTikz ?: return
        val bodyForEdit = rawBody ?: wrappedBody
        val isKnot = bodyForEdit.contains("\\begin{knot}") || bodyForEdit.contains("\\KPATH")

        fun findAfterDocumentClass(docText: String): Int {
            val m = Regex("""\\documentclass[^\n]*\n""").find(docText)
            return m?.range?.last?.plus(1) ?: 0
        }
        fun hasRequireTikz(docText: String) =
            Regex("""\\RequirePackage\{tikz}""").containsMatchIn(docText) ||
                Regex("""\\usepackage\{tikz}""").containsMatchIn(docText)

        val allLibsRegex = Regex("""\\usetikzlibrary\{([^}]*)}""")
        val existingLibs = buildSet {
            for (m in allLibsRegex.findAll(document.text)) {
                m.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            }
        }
        val missingMinimal = minimalLibs - existingLibs

        var preambleInserted = false
        WriteCommandAction.runWriteCommandAction(project) {
            if (editBlock != null) {
                val (start, end, payload) = TikzToolbarHelpers.tikzEditorReplace(
                    document.text,
                    editBlock.start,
                    editBlock.end,
                    bodyForEdit,
                )
                document.replaceString(start, end, payload)
            } else {
                val hasAnyUsetikz = allLibsRegex.containsMatchIn(document.text)
                if (!hasRequireTikz(document.text) && !hasAnyUsetikz) {
                    val pos = findAfterDocumentClass(document.text)
                    val preamble = if (isKnot) "$basePreamble\n\n$knotPreamble" else basePreamble
                    document.insertString(pos, preamble + "\n\n")
                    preambleInserted = true
                } else {
                    if (!hasRequireTikz(document.text)) {
                        val pos = findAfterDocumentClass(document.text)
                        document.insertString(pos, "\\usepackage{tikz}\n")
                    }
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
                document.insertString(caretPos, "\n$wrappedBody\n")
            }
        }

        if (preambleInserted) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                Messages.showInfoMessage(
                    project,
                    "TikZ preamble inserted at top of document.",
                    "TikZ Preamble Added",
                )
            }
        }
        dialog.show()
    }
}
