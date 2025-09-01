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
import java.awt.Point
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
        override fun caretPositionChanged(event: CaretEvent) = scheduleRefresh()
    }

    private val visibleListener = VisibleAreaListener { e: VisibleAreaEvent ->
        val editor = e.editor ?: return@VisibleAreaListener
        val topY = e.newRectangle.y
        val topLogical: LogicalPosition = editor.xyToLogicalPosition(Point(0, topY))
        val absLine = topLogical.line + 1  // IntelliJ lines are 0-based; we use 1-based-ish in anchors
        // Call the JS API in the JCEF page
        browser?.cefBrowser?.executeJavaScript(
            "window.sync && window.sync.scrollToAbs(${absLine});",
            browser?.cefBrowser?.url ?: "about:blank",
            0
        )
    }

    init {
        Disposer.register(project, this)

        // Fire when the selected editor tab changes
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    scheduleRefresh()
                }
            }
        )
    }

    fun attachBrowser(b: JBCefBrowser) {
        browser = b

        // Listen to all docs for changes
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(docListener, this)
        // Also refresh when caret moves
        EditorFactory.getInstance().eventMulticaster.addCaretListener(caretListener, this)
        // Initial render
        scheduleRefresh()

        EditorFactory.getInstance().eventMulticaster.addVisibleAreaListener(visibleListener, this)

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
        val (vf, text) = currentTexFileAndText() ?: run {
            renderHtml(LatexHtml.wrap("<p style='opacity:0.66'>Open a <code>.tex</code> file to preview.</p>"))
            return
        }
        // Generate HTML with MathJax
        val html = LatexHtml.wrap(text)
        renderHtml(html)
    }

    private fun renderHtml(html: String) {
        ApplicationManager.getApplication().invokeLater {
            // Use a stable fake base URL so relative assets (if any later) can resolve
            browser?.loadHTML(html, "http://latex-preview.local/")
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
