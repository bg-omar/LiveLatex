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
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

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
    private var jsRenderTikz: JBCefJSQuery? = null
    private var jsRenderTikzQuery: JBCefJSQuery? = null
    private val tikzExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("TikzPool", 1)

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

    private fun exposeTikzBridge(browser: JBCefBrowserBase) {
        jsRenderTikzQuery = JBCefJSQuery.create(browser).also { q ->
            Disposer.register(this, q)
            q.addHandler { key ->
                // Submit the compile work on our 1-thread pool
                val f = tikzExecutor.submit<TikzResult> {
                    runTikzCompileSafely(key)
                }

                ApplicationManager.getApplication().executeOnPooledThread {
                    val result: TikzResult = try {
                        f.get(60, TimeUnit.SECONDS) // hard timeout
                    } catch (t: Throwable) {
                        f.cancel(true)
                        TikzResult.fail(key, "Compile timeout or error: ${t.message ?: t::class.java.simpleName}")
                    }

                    // Post the result back into the page on the EDT for safety
                    val cef = browser?.cefBrowser ?: return@executeOnPooledThread
                    val json = result.toJsonForJs()
                    ApplicationManager.getApplication().invokeLater({
                        // double-check browser still alive
                        cef.executeJavaScript("window.postMessage($json, '*');", cef.url, 0)
                    }, { project.isDisposed })
                }

                // Immediate ack to the page (it relies on the later postMessage for the real payload)
                JBCefJSQuery.Response("OK")
            }

        }

        // Expose a callable bridge in the page
        val js = """
        (function(){
          window.__llHostRenderTikz = function(key){
            try { return ${jsRenderTikzQuery!!.inject("key")}; }
            catch(e){ /* ignore; page has a timeout */ }
          };
          // Fallback route: page asks via postMessage → we call the bridge
          window.addEventListener('message', function(ev){
            var d = ev.data||{};
            if (d.type==='tikz-render' && d.key){
              try { ${jsRenderTikzQuery!!.inject("d.key")} } catch(e){}
            }
          }, false);
        })();
    """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    // Compile and capture output/errors safely
    private fun runTikzCompileSafely(key: String): TikzResult {
        return try {
            // choose a single cache under the project ROOT, not user home:
            val out = LatexHtml.renderLazyTikzKeyToSvg(key) // your existing function
            if (out != null && out.exists()) {
                TikzResult.okSvg(key, out.readText())
            } else {
                TikzResult.fail(key, "TikZ compile produced no SVG. See last-compile.log.")
            }
        } catch (t: Throwable) {
            TikzResult.fail(key, (t.message ?: t.toString()))
        }
    }


    private fun TikzResult.toJsonForJs(): String {
        fun esc(s:String) = s
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r","")
        val parts = mutableListOf(
            "\"type\":\"$type\"",
            "\"ok\":$ok",
            "\"key\":\"${esc(key)}\""
        )
        svgText?.let { parts += "\"svgText\":\"${esc(it)}\"" }
        url?.let     { parts += "\"url\":\"${esc(it)}\"" }
        error?.let   { parts += "\"error\":\"${esc(it)}\"" }
        return "{${parts.joinToString(",")}}"
    }


    init {
        Disposer.register(project, this)

        // React to editor-tab changes: update preview + rebind listeners, but do NOT auto-show toolwindow
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview") ?: return
                    val file = event.newFile
                    if (file?.name?.endsWith(".tex") == true) {
                        // Do NOT auto-show toolwindow
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

        val base = b as JBCefBrowserBase

        // 1) caret bridge (preview → move caret in editor)
        jsMoveCaret = JBCefJSQuery.create(base).also { query ->
            Disposer.register(this, query)
            query.addHandler { payload ->
                try {
                    val line = Regex("""\"line\"\s*:\s*(\d+)""")
                        .find(payload)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val word = Regex("""\"word\"\s*:\s*\"(.*?)\"""")
                        .find(payload)?.groupValues?.getOrNull(1) ?: ""
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

        // 2) TikZ bridge (JSQuery + pooling + page glue) — single source of truth
        exposeTikzBridge(base)

        // 3) flush pending JS after each page load + re-inject page glue
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                br: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean
            ) {
                if (!isLoading) {
                    pageReady = true
                    // re-inject page-side glue so window.__llHostRenderTikz is live again
                    exposeTikzBridge(base)
                    flushPending()
                }
            }
        }, b.cefBrowser)

        // global doc changes → refresh
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(docListener, this)

        // bind to selected editor + initial render
        rebindToSelectedEditor()
        scheduleRefresh()
    }



    // 3) helper to safely embed text in JSON from Kotlin
    private fun String.jsonEscapeForJs(): String =
        "\"" + this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""


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
        val html = when (ext) {
            "tex", "ltx", "latex", "tikz" -> LatexHtml.wrapWithInputs(doc!!.text, vf.path)
            else -> LatexHtml.wrap("<p style='opacity:.66'>Open a <code>.tex</code> file to preview.</p>")
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

    // ---- TikzResult helpers ----
    private data class TikzResult(
        val type: String = "tikz-render-result",
        val ok: Boolean,
        val key: String,
        val svgText: String? = null, // inline SVG result, preferred
        val url: String? = null,     // or a file:// URL if you serve an image
        val error: String? = null
    ) {
        companion object {
            fun okSvg(key: String, svgText: String) =
                TikzResult(ok = true, key = key, svgText = svgText)

            fun okUrl(key: String, url: String) =
                TikzResult(ok = true, key = key, url = url)

            fun fail(key: String, error: String) =
                TikzResult(ok = false, key = key, error = error)
        }
    }



    override fun dispose() {
        try { tikzExecutor.shutdownNow() } catch (_: Throwable) {}
    }

}