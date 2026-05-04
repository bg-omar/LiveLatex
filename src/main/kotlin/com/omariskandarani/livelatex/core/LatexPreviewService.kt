package com.omariskandarani.livelatex.core

import com.omariskandarani.livelatex.html.LatexHtml
import com.omariskandarani.livelatex.html.LatexHtmlTikz
import com.omariskandarani.livelatex.html.LatexTikzJobStore
import com.omariskandarani.livelatex.html.TikzRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.IdeFocusManager
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Dimension
import java.awt.Point
import com.intellij.ui.jcef.JBCefBrowserBase
import com.omariskandarani.livelatex.ui.PreviewToolbarPanel
import java.io.File
import java.security.MessageDigest


class LatexPreviewService(private val project: Project) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(LatexPreviewService::class.java)
    }

    private var browser: JBCefBrowser? = null
    private var pageReady = false
    private val pendingJs = ArrayDeque<String>()
    private var boundEditor: Editor? = null
    private var jsMoveCaret: JBCefJSQuery? = null
    private var jsRenderTikzQuery: JBCefJSQuery? = null
    /** Bridge: preview page checkbox → [LiveLatexSettings.renderTikzInPreview]. */
    private var jsRenderTikzSettingQuery: JBCefJSQuery? = null
    private var jsClearCacheQuery: JBCefJSQuery? = null
    private var jsSectionsQuery: JBCefJSQuery? = null
    /** Sectielijst uit de preview-pagina (voor Secties-dropdown in titelbalk). */
    @Volatile
    var lastSections: List<Pair<String, String>> = emptyList()
        private set
    /** Toolbar panel met sectie-combo; gezet door LatexPreviewToolWindowFactory. */
    var toolbarPanel: PreviewToolbarPanel? = null
        set(value) { field = value }
    private val tikzExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("TikzPool", 1)
    /** Single-thread pool so only one `wrapWithInputs` runs at a time (mutates LatexHtml / TikzRenderer globals). */
    private val previewBuildExecutor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("LiveLatexPreview", 1)

    @Volatile
    private var previewBuildGeneration = 0

    /** Alleen voor sectie-sync / vertraagde JS (niet voor preview-debounce). */
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    /** Aparte alarm: `scheduleRefresh` mag niet `cancelAllRequests` op de sectie-alarm uitvoeren. */
    private val refreshDebounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val debounceMs = 150

    private fun isLiveLatexPreviewFile(file: VirtualFile?): Boolean {
        val ext = file?.extension?.lowercase() ?: return false
        return ext in listOf("tex", "ltx", "latex", "tikz")
    }

    /** True while we're moving editor caret/scroll from a preview click/scroll; prevents feedback loop (editor→preview sync). */
    @Volatile
    private var syncingFromPreview = false

    private val docListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            scheduleRefresh()
        }
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            // Sync by viewport center so both panels show the same line in the middle
            syncToViewportCenter(event.editor)
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
                LOG.warn("LiveLatex TikZ request received: key=$key")
                // Submit the compile work on our 1-thread pool
                val f = tikzExecutor.submit<TikzResult> {
                    runTikzCompileSafely(key)
                }

                ApplicationManager.getApplication().executeOnPooledThread {
                    val result: TikzResult = try {
                        f.get(3, TimeUnit.MINUTES) // must exceed LatexHtmlTikz PDF build + dvisvgm for heavy figures
                    } catch (t: Throwable) {
                        f.cancel(true)
                        LOG.warn("LiveLatex TikZ request failed/timed out: key=$key, err=${t.message}")
                        TikzResult.fail(key, "Compile timeout or error: ${t.message ?: t::class.java.simpleName}")
                    }

                    // Post the result back into the page on the EDT for safety
                    val cef = browser?.cefBrowser ?: return@executeOnPooledThread
                    val json = result.toJsonForJs()
                    ApplicationManager.getApplication().invokeLater({
                        // double-check browser still alive
                        LOG.warn("LiveLatex TikZ posting result: key=$key, ok=${result.ok}, hasUrl=${result.url != null}, hasSvg=${result.svgText != null}")
                        cef.executeJavaScript("window.postMessage($json, '*');", cef.url, 0)
                    }, { project.isDisposed })
                }

                // Immediate ack to the page (it relies on the later postMessage for the real payload)
                JBCefJSQuery.Response("OK")
            }

        }
        installTikzBridgeJs(browser)
    }

    private fun installTikzBridgeJs(browser: JBCefBrowserBase) {
        val q = jsRenderTikzQuery ?: return
        val js = """
        (function(){
          window.__llHostRenderTikz = function(key){
            try { return ${q.inject("key")}; }
            catch(e){ /* ignore; page has a timeout */ }
          };
          // Fallback route: page asks via postMessage → we call the bridge
          window.addEventListener('message', function(ev){
            var d = ev.data||{};
            if (d.type==='tikz-render' && d.key){
              try { ${q.inject("d.key")} } catch(e){}
            }
          }, false);
        })();
    """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    private fun exposeClearCacheBridge(browser: JBCefBrowserBase) {
        jsClearCacheQuery = JBCefJSQuery.create(browser).also { q ->
            Disposer.register(this, q)
            q.addHandler { _ ->
                ApplicationManager.getApplication().invokeLater {
                    clearCacheForPaper()
                }
                JBCefJSQuery.Response("OK")
            }
        }
        installClearCacheBridgeJs(browser)
    }

    private fun installClearCacheBridgeJs(browser: JBCefBrowserBase) {
        val q = jsClearCacheQuery ?: return
        val js = """
        (function(){
          window.__llHostClearCache = function(){
            try { ${q.inject("''")}; } catch(e){}
          };
          window.addEventListener('message', function(ev){
            var d = ev.data||{};
            if (d.type==='clear-cache'){ try { ${q.inject("''")}; } catch(e){} }
          }, false);
        })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    private fun exposeSectionsBridge(browser: JBCefBrowserBase) {
        jsSectionsQuery?.let { Disposer.dispose(it) }
        jsSectionsQuery = JBCefJSQuery.create(browser).also { q ->
            Disposer.register(this, q)
            q.addHandler { json ->
                lastSections = parseSectionsJson(json)
                ApplicationManager.getApplication().invokeLater {
                    toolbarPanel?.setSections(lastSections)
                }
                JBCefJSQuery.Response("OK")
            }
        }
        installSectionsBridgeJs(browser)
        // Vraag secties meerdere keren (bridge kan laat klaar zijn; MathJax kan DOM later updaten)
        listOf(400, 900, 1800).forEach { delayMs ->
            alarm.addRequest({
                eval("try { if (typeof window.sendSectionsToHost === 'function') window.sendSectionsToHost(); } catch(e){}")
                if (delayMs == 400) syncAutoScrollSettingsToPage()
            }, delayMs)
        }
    }

    private fun installSectionsBridgeJs(browser: JBCefBrowserBase) {
        val q = jsSectionsQuery ?: return
        val js = """
        (function(){
          window.__llHostSectionsReady = function(json){
            try { return ${q.inject("json")}; }
            catch(e){ return ''; }
          };
          if (typeof window.sendSectionsToHost === 'function') window.sendSectionsToHost();
        })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    private fun exposeRenderTikzSettingBridge(browser: JBCefBrowserBase) {
        jsRenderTikzSettingQuery = JBCefJSQuery.create(browser).also { q ->
            Disposer.register(this, q)
            q.addHandler { payload ->
                val enabled = payload.trim().equals("true", ignoreCase = true) ||
                    payload.contains("\"enabled\":true")
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (project.isDisposed) return@invokeLater
                        val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
                        if (settings.renderTikzInPreview != enabled) {
                            settings.renderTikzInPreview = enabled
                            toolbarPanel?.syncRenderTikzFromSettings()
                            requestRefresh()
                        } else {
                            toolbarPanel?.syncRenderTikzFromSettings()
                        }
                    },
                    Condition<Any?> { project.isDisposed }
                )
                JBCefJSQuery.Response("OK")
            }
        }
    }

    private fun installRenderTikzSettingBridgeJs(browser: JBCefBrowserBase) {
        val q = jsRenderTikzSettingQuery ?: return
        val js = """
            (function(){
              window.__llHostSetRenderTikz = function(on){
                var s = on ? 'true' : 'false';
                try { return ${q.inject("s")}; }
                catch(e) { return ''; }
              };
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    /** Zet auto-scroll state uit IDE-instellingen in de preview-pagina (localStorage). */
    private fun syncAutoScrollSettingsToPage() {
        val settings = ApplicationManager.getApplication().getService(com.omariskandarani.livelatex.core.LiveLatexSettings::class.java)
        eval(
            "try { " +
                "localStorage.setItem('ll_auto_scroll', ${settings.autoScrollPreview}); " +
                "localStorage.setItem('ll_auto_scroll_editor', ${settings.autoScrollEditor}); " +
                "localStorage.setItem('ll_show_tikz_debug', ${settings.showTikzDebugOverlay}); " +
                "if (typeof window.__llSetTikzDebug === 'function') window.__llSetTikzDebug(${settings.showTikzDebugOverlay}); " +
            "} catch(e){}"
        )
        syncRenderTikzInPageCheckbox()
    }

    /** Align in-preview “Render TikZ” checkbox with [LiveLatexSettings.renderTikzInPreview]. */
    private fun syncRenderTikzInPageCheckbox() {
        val on = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview
        eval(
            "try { var el=document.getElementById('ll-render-tikz-in-preview'); if(el) el.checked=" + on + "; } catch(e){}"
        )
    }

    private fun parseSectionsJson(json: String): List<Pair<String, String>> {
        if (json.isBlank()) return emptyList()
        return try {
            val idRe = Regex("""\"id\"\s*:\s*\"([^\"]*)\"""")
            val labelRe = Regex("""\"label\"\s*:\s*\"([^\"]*)\"""")
            val raw = json.trim().removeSurrounding("[", "]").trim()
            if (raw.isEmpty()) return emptyList()
            val items = raw.split("},{")
            items.mapNotNull { part ->
                val id = idRe.find(part)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val label = labelRe.find(part)?.groupValues?.getOrNull(1) ?: id
                id to label
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearCacheForPaper() {
        val cacheDir = currentDocumentCacheDir()
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
        scheduleRefresh()
    }

    private fun clearAllCache() {
        val cacheDir = globalCacheDir()
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
        scheduleRefresh()
    }

    private fun globalCacheDir(): File =
        File(PathManager.getSystemPath(), "livelatex-cache")

    private fun currentDocumentCacheDir(): File {
        val path = currentTexFileAndText()?.first?.path
        if (path.isNullOrBlank()) return globalCacheDir()
        return File(globalCacheDir(), sha1(path))
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // Compile and capture output/errors safely
    private fun runTikzCompileSafely(key: String): TikzResult {
        return try {
            // choose a single cache under the project ROOT, not user home:
            val texDoc = LatexTikzJobStore.get(key)
            if (texDoc == null) {
                LOG.warn("LiveLatex TikZ job miss: key=$key")
            } else {
                LOG.warn("LiveLatex TikZ job hit: key=$key, texLen=${texDoc.length}")
            }
            val out = if (texDoc != null) LatexHtmlTikz.renderTexToSvg(texDoc, key) else null
            if (out != null && out.exists()) {
                // Return URL instead of inline SVG text to avoid SVG id collisions
                // across multiple rendered TikZ blocks in the same HTML document.
                val bust = out.lastModified()
                LOG.warn("LiveLatex TikZ compile ok: key=$key, out=${out.absolutePath}")
                TikzResult.okUrl(key, out.toURI().toString() + "?v=" + bust)
            } else {
                LOG.warn("LiveLatex TikZ compile produced no SVG: key=$key")
                TikzResult.fail(key, "TikZ compile produced no SVG. See last-compile.log.")
            }
        } catch (t: Throwable) {
            LOG.warn("LiveLatex TikZ compile exception: key=$key, err=${t.message}", t)
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
                    if (isLiveLatexPreviewFile(file)) {
                        toolWindow.setWidth(400)
                        // Keep focus in the editor/project flow: do not auto-show/activate the preview tool window
                        // when tabs change (e.g. opening/creating files from the project view).
                        rebindToSelectedEditor()
                    } else {
                        unbindEditor()
                        toolWindow.setWidth(10)
                        // do not hide — keep strip visible so it re-expands when back on .tex
                    }
                    // Next EDT tick so FileEditorManager / document match the newly selected tab (avoids stale snapshot).
                    ApplicationManager.getApplication().invokeLater(
                        {
                            if (project.isDisposed) return@invokeLater
                            refresh()
                            // Some IDE flows (e.g. New File from project view/template) can leave focus in the
                            // project sidebar. Pull focus back to the active editor tab if one exists.
                            FileEditorManager.getInstance(project).selectedTextEditor?.contentComponent?.let { editorComponent ->
                                IdeFocusManager.getInstance(project).requestFocus(editorComponent, true)
                            }
                        },
                        Condition<Any?> { project.isDisposed }
                    )
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
                        syncingFromPreview = true
                        val lineToAlign = line
                        ApplicationManager.getApplication().invokeLater {
                            try {
                                val fem = FileEditorManager.getInstance(project)
                                val ed = fem.selectedTextEditor ?: return@invokeLater
                                val doc = ed.document
                                val lineIdx = (lineToAlign - 1).coerceIn(0, doc.lineCount - 1)
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
                                // Align preview to same line at center so both panels show the same position
                                eval("""window.postMessage({type:'sync-line', abs:$lineToAlign, source:'align', mode:'center'}, '*');""")
                            } finally {
                                alarm.addRequest({ syncingFromPreview = false }, 80)
                            }
                        }
                    }
                } catch (_: Throwable) {}
                JBCefJSQuery.Response("OK")
            }
        }

        // 2) TikZ bridge (JSQuery + pooling + page glue) — single source of truth
        exposeTikzBridge(base)
        exposeClearCacheBridge(base)
        exposeSectionsBridge(base)
        exposeRenderTikzSettingBridge(base)

        // 3) flush pending JS after each page load (do not re-create JBCefJSQuery here — must be created before load; re-creating after browser load triggers IllegalStateException)
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                br: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean
            ) {
                if (!isLoading) {
                    pageReady = true
                    installTikzBridgeJs(base)
                    installClearCacheBridgeJs(base)
                    installSectionsBridgeJs(base)
                    installRenderTikzSettingBridgeJs(base)
                    flushPending()
                }
            }
        }, b.cefBrowser)

        // bind to selected editor + initial render (document listener per editor, betrouwbaarder dan alleen multicaster)
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
        // Single-arg registration so [unbindEditor]/[dispose] removal matches (no parent-Disposable auto-hook).
        editor?.document?.addDocumentListener(docListener)
        editor?.caretModel?.addCaretListener(caretListener)
        editor?.scrollingModel?.addVisibleAreaListener(visibleListener)
        if (editor != null) {
            // Initial sync: same line at center in both panels
            syncToViewportCenter(editor)
        }
    }

    private fun unbindEditor() {
        boundEditor?.let { ed ->
            ed.document.removeDocumentListener(docListener)
            ed.caretModel.removeCaretListener(caretListener)
            ed.scrollingModel.removeVisibleAreaListener(visibleListener)
        }
        boundEditor = null
    }


    private fun syncToViewportCenter(editor: Editor) {
        // Center-of-viewport absolute line (1-based) — same reference in both panels
        val area = editor.scrollingModel.visibleArea
        val midY = area.y + area.height / 2
        val vis = editor.xyToVisualPosition(Point(0, midY))
        val log = editor.visualToLogicalPosition(vis)
        val abs = log.line + 1
        postSync(abs, source = "scroll")
    }

    private fun postSync(abs: Int, source: String) {
        if (syncingFromPreview) return
        eval("""window.postMessage({type:'sync-line', abs:$abs, source:'$source', mode:'center'}, '*');""")
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
        refreshDebounceAlarm.cancelAllRequests()
        refreshDebounceAlarm.addRequest({ refresh() }, debounceMs)
    }

    /** Call to refresh the preview (e.g. after toggling TikZ rendering). */
    fun requestRefresh() {
        scheduleRefresh()
    }

    /** Run JS in the preview page (for zoom, localStorage, etc.). */
    fun evalJs(js: String) {
        eval(js)
    }

    fun requestZoomIn() {
        eval("try { if (typeof window.setZoom === 'function') window.setZoom(1.15); } catch(e){}")
    }

    fun requestZoomOut() {
        eval("try { if (typeof window.setZoom === 'function') window.setZoom(1/1.15); } catch(e){}")
    }

    fun requestClearCache() {
        clearCacheForPaper()
    }

    fun requestClearAllCache() {
        clearAllCache()
    }

    fun requestJumpToSection(id: String) {
        val escaped = id.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        eval("try { if (typeof window.jumpToMarkId === 'function') window.jumpToMarkId('$escaped'); } catch(e){}")
    }

    private fun refresh() {
        val fem = FileEditorManager.getInstance(project)
        val editor = fem.selectedTextEditor
        val caretLine = editor?.caretModel?.logicalPosition?.line?.plus(1) ?: 1
        val doc = editor?.document
        val vf = if (doc != null) FileDocumentManager.getInstance().getFile(doc) else null
        val ext = vf?.extension?.lowercase()
        val isTexLike = ext in listOf("tex", "ltx", "latex", "tikz")
        val snapshotText = if (isTexLike && doc != null && vf != null) doc.text else null
        val snapshotPath = vf?.path
        val cacheRootForSnapshot = if (snapshotPath != null) {
            File(globalCacheDir(), sha1(snapshotPath)).absolutePath
        } else {
            globalCacheDir().absolutePath
        }
        val token = ++previewBuildGeneration

        previewBuildExecutor.execute {
            val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
            val wantsLiveRenderProgress =
                settings.renderTikzInPreview && isTexLike && snapshotText != null
            if (wantsLiveRenderProgress) {
                TikzRenderer.setLiveRenderProgressHandler { cur, tot, detail ->
                    ApplicationManager.getApplication().invokeLater(
                        {
                            if (project.isDisposed || token != previewBuildGeneration) return@invokeLater
                            toolbarPanel?.setLiveRenderProgress(cur, tot, detail)
                        },
                        Condition<Any?> { project.isDisposed }
                    )
                }
            }
            val html = try {
                TikzRenderer.pluginCacheRoot = cacheRootForSnapshot
                when {
                    isTexLike && snapshotText != null && snapshotPath != null ->
                        LatexHtml.wrapWithInputs(snapshotText, snapshotPath)
                    else ->
                        LatexHtml.wrap("<p style='opacity:.66'>Open a <code>.tex</code> file to preview.</p>")
                }
            } catch (t: Throwable) {
                val msg = (t.message ?: t.toString()).replace("<", "&lt;")
                LatexHtml.wrap("<p style='opacity:.66;color:#b91c1c'>Preview build failed: $msg</p>")
            } finally {
                TikzRenderer.setLiveRenderProgressHandler(null)
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (project.isDisposed || token != previewBuildGeneration) return@invokeLater
                        toolbarPanel?.clearLiveRenderProgress()
                    },
                    Condition<Any?> { project.isDisposed }
                )
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed) return@invokeLater
                    if (token != previewBuildGeneration) return@invokeLater
                    if (isTexLike && snapshotPath != null) {
                        val ed = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
                        val vfNow = FileDocumentManager.getInstance().getFile(ed.document) ?: return@invokeLater
                        val a = FileUtil.toSystemIndependentName(snapshotPath)
                        val b = FileUtil.toSystemIndependentName(vfNow.path)
                        if (!FileUtil.pathsEqual(a, b)) return@invokeLater
                    }
                    if (isTexLike) {
                        lastSections = LatexHtml.lastCollectedSections
                    } else {
                        lastSections = emptyList()
                    }
                    renderHtml(html, caretLine)
                },
                Condition<Any?> { project.isDisposed }
            )
        }
    }

    /** Must run on EDT (invoked from [refresh] completion after background build). */
    private fun renderHtml(html: String, caretLine: Int) {
        pageReady = false
        browser?.loadHTML(html, "http://latex-preview.local/")
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
        postSync(caretLine, source = "initial")
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
        unbindEditor()
        try { previewBuildExecutor.shutdownNow() } catch (_: Throwable) {}
        try { tikzExecutor.shutdownNow() } catch (_: Throwable) {}
    }

}