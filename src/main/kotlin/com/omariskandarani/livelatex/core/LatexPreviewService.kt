package com.omariskandarani.livelatex.core

import com.omariskandarani.livelatex.html.LatexHtml
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindow
import java.awt.Point
import java.awt.Dimension
import java.nio.charset.StandardCharsets

class LatexPreviewService(private val project: Project) : Disposable {

    private var browser: JBCefBrowser? = null

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val debounceMs = 150

    private val docListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduleRefresh()
        }
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            val editor = event.editor
            val line = editor.caretModel.logicalPosition.line + 1
            browser?.cefBrowser?.executeJavaScript(
                "window.sync && window.sync.scrollToAbs($line);",
                browser?.cefBrowser?.url ?: "about:blank", 0
            )
        }
    }

    private val visibleListener = VisibleAreaListener { e: VisibleAreaEvent ->
        val editor = e.editor ?: return@VisibleAreaListener
        val topY = e.newRectangle.y
        val topLogical: LogicalPosition = editor.xyToLogicalPosition(Point(0, topY))
        val absLine = topLogical.line + 1
        browser?.cefBrowser?.executeJavaScript(
            "window.sync && window.sync.scrollToAbs($absLine);",
            browser?.cefBrowser?.url ?: "about:blank", 0
        )
    }

    // Extension function for ToolWindow width
    private fun ToolWindow.setWidth(width: Int) {
        this.component.preferredSize = Dimension(width, this.component.height)
        this.component.revalidate()
    }

    init {
        Disposer.register(project, this)

        // Fire when the selected editor tab changes
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview") ?: return
                    if (file?.name?.endsWith(".tex") == true) {
                        toolWindow.show()
                        toolWindow.setWidth(400) // Expand to normal width
                    } else {
                        toolWindow.setWidth(40) // Collapse to minimal width
                        toolWindow.hide()
                    }
                    scheduleRefresh()
                }
            }
        )
    }

    fun attachBrowser(b: JBCefBrowser) {
        browser = b

        // Listen to all docs for changes
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(docListener, this)
        // Only add caret and visible listeners for scroll sync, not refresh
        EditorFactory.getInstance().eventMulticaster.addCaretListener(caretListener, this)
        EditorFactory.getInstance().eventMulticaster.addVisibleAreaListener(visibleListener, this)
        // Initial render
        scheduleRefresh()


        // Initial sync to caret/visible line (optional)
        FileEditorManager.getInstance(project).selectedTextEditor?.let { ed ->
            val line = ed.caretModel.logicalPosition.line + 1
            b.cefBrowser.executeJavaScript("window.sync && window.sync.scrollToAbs($line);", b.cefBrowser.url, 0)
        }
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, debounceMs)
    }

    private fun refresh() {
        // Get caret line before refresh
        val caretLine = FileEditorManager.getInstance(project).selectedTextEditor?.caretModel?.logicalPosition?.line?.plus(1) ?: 1
        val (vf, text) = currentTexFileAndText() ?: run {
            renderHtml(LatexHtml.wrap("<p style='opacity:0.66'>Open a <code>.tex</code> file to preview.</p>"), caretLine)
            return
        }
        // Generate HTML with MathJax
        val html = LatexHtml.wrap(text)
        renderHtml(html, caretLine)
    }

    private fun renderHtml(html: String, caretLine: Int) {
        ApplicationManager.getApplication().invokeLater {
            browser?.loadHTML(html, "http://latex-preview.local/")
            // After HTML is loaded, scroll to caret line
            browser?.cefBrowser?.executeJavaScript(
                "window.sync && window.sync.scrollToAbs($caretLine);",
                browser?.cefBrowser?.url ?: "about:blank", 0
            )
        }
    }


    private fun currentTexFileAndText(): Pair<VirtualFile, String>? {
        val fem = FileEditorManager.getInstance(project)
        val editor = fem.selectedTextEditor ?: return null

        val doc = editor.document
        val vf = FileDocumentManager.getInstance().getFile(doc) ?: return null

        val ext = vf.extension?.lowercase()
        if (ext !in setOf("tex", "ltx", "latex")) return null

        return vf to doc.text
    }
    override fun dispose() {
        // nothing
    }

    init {
        Disposer.register(project, this)
    }
}