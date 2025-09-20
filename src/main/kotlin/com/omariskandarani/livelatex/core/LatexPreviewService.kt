package com.omariskandarani.livelatex.core

import com.omariskandarani.livelatex.html.LatexHtml
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Dimension
import java.awt.Point
import com.intellij.ui.jcef.JBCefBrowserBase


class LatexPreviewService(private val project: Project) : Disposable {

    private var browser: JBCefBrowser? = null
    private var pageReady = false
    private val pendingJs = ArrayDeque<String>()
    private var boundEditor: Editor? = null
    private var jsMoveCaret: JBCefJSQuery? = null

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val debounceMs = 150

    private val docListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduleRefresh()
        }
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            syncToCaret(event.editor)
        }
    }

    private val visibleListener = VisibleAreaListener { e: VisibleAreaEvent ->
        val editor = e.editor ?: return@VisibleAreaListener
        syncToViewportCenter(editor)
    }

    // ToolWindow width helper
    private fun ToolWindow.setWidth(width: Int) {
        this.component.preferredSize = Dimension(width, this.component.height)
        this.component.revalidate()
    }

    init {
        Disposer.register(project, this)

        // React to editor-tab changes: show/hide preview + rebind listeners
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview") ?: return
                    val file = event.newFile
                    if (file?.name?.endsWith(".tex") == true) {
                        // toolWindow.show() Do NOT auto-show the tool window; only update width and listeners
                        toolWindow.setWidth(400)
                        rebindToSelectedEditor()
                    } else {
                        unbindEditor()
                        toolWindow.setWidth(40)
                        toolWindow.hide()
                    }
                    scheduleRefresh()
                }
            }
        )
    }

    fun attachBrowser(b: JBCefBrowser) {
        browser = b
        pageReady = false

        // Set up JS bridge for clicks from preview → move caret in editor
        // --- FIX: use the factory that takes JBCefBrowserBase
        val base = b as JBCefBrowserBase
        jsMoveCaret = try {
            JBCefJSQuery.create(base).also { query ->
                Disposer.register(this, query)
                query.addHandler { payload ->
                    try {
                        val line = Regex("""\"line\"\s*:\s*(\d+)""").find(payload)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val word = Regex("""\"word\"\s*:\s*\"(.*?)\"""").find(payload)?.groupValues?.getOrNull(1) ?: ""
                        if (line != null) {
                            ApplicationManager.getApplication().invokeLater {
                                val fem = FileEditorManager.getInstance(project)
                                val ed = fem.selectedTextEditor ?: return@invokeLater
                                val doc = ed.document
                                val lineIdx = (line - 1).coerceIn(0, doc.lineCount - 1)
                                val start = doc.getLineStartOffset(lineIdx)
                                val end = doc.getLineEndOffset(lineIdx)
                                var caret = start
                                if (word.isNotEmpty()) {
                                    val text = doc.charsSequence.subSequence(start, end).toString()
                                    val idx = text.indexOf(word)
                                    if (idx >= 0) caret = start + idx
                                }
                                ed.caretModel.moveToOffset(caret)
                                ed.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                            }
                        }
                    } catch (_: Throwable) {}
                    JBCefJSQuery.Response("OK")
                }
            }
        } catch (t: Throwable) {
            // Fallback for older IDEs where the Base overload may not exist yet
            @Suppress("DEPRECATION")
            JBCefJSQuery.create(b).also { query ->
                Disposer.register(this, query)
                query.addHandler { _ -> JBCefJSQuery.Response("OK") }
            }
        }

        // Load-state tracker so we can flush queued JS once the page is ready
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(br: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                if (!isLoading) {
                    pageReady = true
                    flushPending()
                }
            }
        }, b.cefBrowser)

        // Global doc changes (so typing refreshes the preview)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(docListener, this)

        // Bind to current selection
        rebindToSelectedEditor()

        // Initial render
        scheduleRefresh()
    }

    private fun rebindToSelectedEditor() {
        val fem = FileEditorManager.getInstance(project)
        bindEditor(fem.selectedTextEditor)
    }

    private fun bindEditor(editor: Editor?) {
        if (boundEditor == editor) return
        unbindEditor()
        boundEditor = editor
        editor?.caretModel?.addCaretListener(caretListener, this)
        editor?.scrollingModel?.addVisibleAreaListener(visibleListener, this)
        if (editor != null) {
            // Initial sync to caret location
            syncToCaret(editor)
        }
    }

    private fun unbindEditor() {
        boundEditor?.let { ed ->
            ed.caretModel.removeCaretListener(caretListener)
            ed.scrollingModel.removeVisibleAreaListener(visibleListener)
        }
        boundEditor = null
    }


    private fun syncToViewportCenter(editor: Editor) {
        // Center-of-viewport absolute line (1-based)
        val area = editor.scrollingModel.visibleArea
        val midY = area.y + area.height / 2
        val vis = editor.xyToVisualPosition(Point(0, midY))
        val log = editor.visualToLogicalPosition(vis)
        val abs = log.line + 1
        postSync(abs, source = "scroll")
    }

    private fun syncToCaret(editor: Editor) {
        val abs = editor.caretModel.logicalPosition.line + 1
        postSync(abs, source = "caret")
    }

    private fun postSync(abs: Int, source: String) {
        eval("""window.postMessage({type:'sync-line', abs:$abs, source:'$source'}, '*');""")
    }

    private fun eval(js: String) {
        val frame = browser?.cefBrowser?.mainFrame ?: return
        if (pageReady) {
            frame.executeJavaScript(js, frame.url, 0)
        } else {
            pendingJs += js
        }
    }

    private fun flushPending() {
        val frame = browser?.cefBrowser?.mainFrame ?: return
        while (pendingJs.isNotEmpty()) {
            frame.executeJavaScript(pendingJs.removeFirst(), frame.url, 0)
        }
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, debounceMs)
    }

    private fun refresh() {
        val fem = FileEditorManager.getInstance(project)
        val editor = fem.selectedTextEditor
        val caretLine = editor?.caretModel?.logicalPosition?.line?.plus(1) ?: 1
        val doc = editor?.document
        val vf = if (doc != null) FileDocumentManager.getInstance().getFile(doc) else null
        val ext = vf?.extension?.lowercase()
        val html = if (vf == null || ext !in setOf("tex", "ltx", "latex")) {
            LatexHtml.wrap("<p style='opacity:0.66'>Open a <code>.tex</code> file to preview.</p>")
        } else {
            // If inlining inputs, see note below about line mapping.
            LatexHtml.wrapWithInputs(doc!!.text, vf.path)
        }
        renderHtml(html, caretLine)
    }

    private fun renderHtml(html: String, caretLine: Int) {
        ApplicationManager.getApplication().invokeLater {
            pageReady = false
            browser?.loadHTML(html, "http://latex-preview.local/")
            // Define the bridge function for preview → IDE
            jsMoveCaret?.let { q ->
                val def = (
                    """
                    window.__jbcefMoveCaret = function(obj){
                      try {
                        var s = (typeof obj === 'string') ? obj : JSON.stringify(obj);
                        ${q.inject("s")}
                      } catch(e) {}
                    };
                    """
                ).trimIndent()
                eval(def)
            }
            // Queue the initial sync; it will run after the page finishes loading.
            postSync(caretLine, source = "initial")
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

    override fun dispose() { /* no-op */ }
}