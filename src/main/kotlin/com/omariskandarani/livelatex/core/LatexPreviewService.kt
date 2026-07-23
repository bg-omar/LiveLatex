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
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.IdeFocusManager
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Dimension
import java.awt.Point
import com.intellij.ui.jcef.JBCefBrowserBase
import com.omariskandarani.livelatex.ui.PreviewStatusLine
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
    private var jsClearCacheQuery: JBCefJSQuery? = null
    private var jsSectionsQuery: JBCefJSQuery? = null
    private var jsActiveSectionQuery: JBCefJSQuery? = null
    private var jsSyncSelection: JBCefJSQuery? = null
    /** Section list from the preview page (for the Sections dropdown in the title bar). */
    @Volatile
    var lastSections: List<Pair<String, String>> = emptyList()
        private set
    /** Section id currently centered in the preview viewport (scroll-spy). */
    @Volatile
    var activeSectionId: String? = null
        private set

    fun interface SectionsUiListener {
        fun onSectionsUiChanged(sections: List<Pair<String, String>>, activeId: String?)
    }

    private val sectionsUiListeners = java.util.concurrent.CopyOnWriteArrayList<SectionsUiListener>()

    fun addSectionsUiListener(listener: SectionsUiListener) {
        sectionsUiListeners.addIfAbsent(listener)
        // Push current state so a late-created title combo is filled immediately.
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) {
                    listener.onSectionsUiChanged(lastSections, activeSectionId)
                }
            },
            Condition<Any?> { project.isDisposed },
        )
    }

    fun removeSectionsUiListener(listener: SectionsUiListener) {
        sectionsUiListeners.remove(listener)
    }

    private fun notifySectionsUiListeners() {
        val sections = lastSections
        val active = activeSectionId
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) return@invokeLater
                sectionsUiListeners.forEach { it.onSectionsUiChanged(sections, active) }
            },
            Condition<Any?> { project.isDisposed },
        )
    }

    /** Re-push section list to title UI (e.g. after Options filter toggles). */
    fun refreshSectionsUi() {
        notifySectionsUiListeners()
    }

    private fun setLastSections(sections: List<Pair<String, String>>, clear: Boolean = false) {
        // Ignore empty flashes from early sendSectionsToHost (marks not ready yet).
        if (sections.isEmpty() && !clear && lastSections.isNotEmpty()) return
        lastSections = sections
        if (activeSectionId != null && sections.none { it.first == activeSectionId }) {
            activeSectionId = null
        }
        notifySectionsUiListeners()
    }

    private fun setActiveSectionId(id: String?) {
        val trimmed = id?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        if (trimmed == activeSectionId) return
        activeSectionId = trimmed
        notifySectionsUiListeners()
    }

    /** Last HTML shown in the preview (for one-shot export). */
    @Volatile
    private var lastRenderedHtml: String? = null
    @Volatile
    private var lastRenderedTexPath: String? = null
    private var statusLine: PreviewStatusLine? = null
    private data class CachedPreview(
        val html: String,
        val sections: List<Pair<String, String>>,
        val token: Int,
        val renderTikz: Boolean,
    )
    private val previewCache = ConcurrentHashMap<String, CachedPreview>()
    @Volatile
    private var previewBuildFuture: Future<*>? = null
    @Volatile
    var isPreviewBuilding: Boolean = false
        private set
    private var statusShowAlarm: Runnable? = null
    private val tikzExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("TikzPool", 1)
    /** In-flight lazy TikZ compiles; cancelled on file switch / preview cancel. */
    private val tikzJobFutures = ConcurrentHashMap.newKeySet<Future<*>>()
    @Volatile
    private var tikzJobGeneration = 0
    /** Single-thread pool so only one `wrapWithInputs` runs at a time (mutates LatexHtml / TikzRenderer globals). */
    private val previewBuildExecutor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("LiveLatexPreview", 1)

    @Volatile
    private var previewBuildGeneration = 0

    /** Only for section sync / deferred JS (not for preview debounce). */
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    /** Separate alarm: `scheduleRefresh` must not `cancelAllRequests` on the section alarm. */
    private val refreshDebounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val selectionSyncAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
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
            val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
            if (!settings.autoPreview) return
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
        syncToViewportCenter(e.editor)
    }

    private val selectionListener = object : SelectionListener {
        override fun selectionChanged(event: SelectionEvent) {
            if (syncingFromPreview) return
            if (!ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).syncSelection) return
            selectionSyncAlarm.cancelAllRequests()
            selectionSyncAlarm.addRequest({ syncEditorSelectionToPreview(event.editor) }, 50)
        }
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
                val jobGen = tikzJobGeneration
                // Submit the compile work on our 1-thread pool
                val f = tikzExecutor.submit<TikzResult> {
                    runTikzCompileSafely(key)
                }
                tikzJobFutures.add(f)

                ApplicationManager.getApplication().executeOnPooledThread {
                    val result: TikzResult = try {
                        f.get(3, TimeUnit.MINUTES) // must exceed LatexHtmlTikz PDF build + dvisvgm for heavy figures
                    } catch (t: Throwable) {
                        f.cancel(true)
                        LOG.warn("LiveLatex TikZ request failed/timed out: key=$key, err=${t.message}")
                        TikzResult.fail(key, "Compile timeout or error: ${t.message ?: t::class.java.simpleName}")
                    } finally {
                        tikzJobFutures.remove(f)
                    }

                    if (jobGen != tikzJobGeneration) {
                        LOG.warn("LiveLatex TikZ stale result ignored: key=$key")
                        return@executeOnPooledThread
                    }

                    // Post the result back into the page on the EDT for safety
                    val cef = browser.cefBrowser
                    val json = result.toJsonForJs()
                    ApplicationManager.getApplication().invokeLater({
                        // double-check browser still alive
                        if (jobGen != tikzJobGeneration) return@invokeLater
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

    private fun installMoveCaretBridgeJs(browser: JBCefBrowserBase) {
        val q = jsMoveCaret ?: return
        val js = """
        (function(){
          window.__jbcefMoveCaret = function(obj){
            try {
              var s = (typeof obj === 'string') ? obj : JSON.stringify(obj);
              ${q.inject("s")}
            } catch(e) {}
          };
        })();
        """.trimIndent()
        runPageJs(browser, js)
    }

    private fun installSyncSelectionBridgeJs(browser: JBCefBrowserBase) {
        val q = jsSyncSelection ?: return
        val js = """
        (function(){
          window.__jbcefSyncSelection = function(obj){
            try {
              var s = (typeof obj === 'string') ? obj : JSON.stringify(obj);
              ${q.inject("s")}
            } catch(e) {}
          };
        })();
        """.trimIndent()
        runPageJs(browser, js)
    }

    /** Execute JS in the preview page; falls back to [eval] pending queue when frame not ready. */
    private fun runPageJs(browser: JBCefBrowserBase, js: String) {
        try {
            val frame = browser.cefBrowser.mainFrame
            if (frame != null && pageReady) {
                frame.executeJavaScript(js, frame.url, 0)
                return
            }
        } catch (_: Throwable) {
        }
        eval(js)
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
                val parsed = parseSectionsJson(json)
                if (parsed.isNotEmpty()) setLastSections(parsed)
                JBCefJSQuery.Response("OK")
            }
        }
        jsActiveSectionQuery?.let { Disposer.dispose(it) }
        jsActiveSectionQuery = JBCefJSQuery.create(browser).also { q ->
            Disposer.register(this, q)
            q.addHandler { id ->
                setActiveSectionId(id)
                JBCefJSQuery.Response("OK")
            }
        }
        installSectionsBridgeJs(browser)
        // Request sections several times (bridge may be late; MathJax may update the DOM later)
        listOf(400, 900, 1800).forEach { delayMs ->
            alarm.addRequest({
                eval("try { if (typeof window.sendSectionsToHost === 'function') window.sendSectionsToHost(); } catch(e){}")
                if (delayMs == 400) syncAutoScrollSettingsToPage()
            }, delayMs)
        }
    }

    private fun installSectionsBridgeJs(browser: JBCefBrowserBase) {
        val sectionsQ = jsSectionsQuery ?: return
        val activeQ = jsActiveSectionQuery
        val activeFn = if (activeQ != null) {
            """
          window.__llHostActiveSection = function(id){
            try { return ${activeQ.inject("id")}; }
            catch(e){ return ''; }
          };
            """.trimIndent()
        } else ""
        val js = """
        (function(){
          window.__llHostSectionsReady = function(json){
            try { return ${sectionsQ.inject("json")}; }
            catch(e){ return ''; }
          };
          $activeFn
          if (typeof window.sendSectionsToHost === 'function') window.sendSectionsToHost();
        })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    /** Push preview UI settings from IDE settings into the preview page (localStorage). */
    private fun syncAutoScrollSettingsToPage() {
        val settings = ApplicationManager.getApplication().getService(com.omariskandarani.livelatex.core.LiveLatexSettings::class.java)
        // TikZ debug overlay is no longer a persisted toggle — always off in the page.
        eval(
            "try { " +
                "localStorage.setItem('ll_auto_scroll', ${settings.autoScrollPreview}); " +
                "localStorage.setItem('ll_sync_selection', ${settings.syncSelection}); " +
                "localStorage.setItem('ll_show_tikz_debug', false); " +
                "localStorage.setItem('ll_invert_scroll_h', ${settings.invertScrollHorizontal}); " +
                "localStorage.setItem('ll_invert_scroll_v', ${settings.invertScrollVertical}); " +
                "if (typeof window.__llSetTikzDebug === 'function') window.__llSetTikzDebug(false); " +
                "var cbH=document.getElementById('ll-invert-scroll-h'); if(cbH) cbH.checked=${settings.invertScrollHorizontal}; " +
                "var cbV=document.getElementById('ll-invert-scroll-v'); if(cbV) cbV.checked=${settings.invertScrollVertical}; " +
            "} catch(e){}"
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
        PreviewCacheDirs.deleteIfExists(currentDocumentCacheDir())
        scheduleRefresh()
    }

    private fun clearAllCache() {
        PreviewCacheDirs.deleteIfExists(globalCacheDir())
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
                    refreshDebounceAlarm.cancelAllRequests()
                    cancelPreviewBuild()
                    if (isLiveLatexPreviewFile(file)) {
                        toolWindow.setWidth(400)
                        rebindToSelectedEditor()
                    } else {
                        unbindEditor()
                        toolWindow.setWidth(10)
                    }
                    ApplicationManager.getApplication().invokeLater(
                        {
                            if (project.isDisposed) return@invokeLater
                            // Drop deferred JS aimed at the previous page; show B immediately.
                            pendingJs.clear()
                            pageReady = false
                            val newPath = file?.path
                            if (isLiveLatexPreviewFile(file) && newPath != null) {
                                if (!showCachedPreviewIfAvailable(newPath)) {
                                    showSwitchingPlaceholder(file.name)
                                }
                            }
                            if (ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).autoPreview) {
                                refresh(file)
                            }
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

    fun attachBrowser(b: JBCefBrowser, status: PreviewStatusLine) {
        browser = b
        statusLine = status
        pageReady = false

        val base = b as JBCefBrowserBase

        // 1) caret bridge (preview → move caret in editor)
        jsMoveCaret = JBCefJSQuery.create(base).also { query ->
            Disposer.register(this, query)
            query.addHandler { payload ->
                try {
                    LOG.warn("LiveLatex moveCaret payload=${payload.take(120)}")
                    val line = Regex("""\"line\"\s*:\s*(\d+)""")
                        .find(payload)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val word = Regex("""\"word\"\s*:\s*\"(.*?)\"""")
                        .find(payload)?.groupValues?.getOrNull(1) ?: ""
                    if (line != null) {
                        syncingFromPreview = true
                        val lineToAlign = line
                        ApplicationManager.getApplication().invokeLater {
                            try {
                                val ed = boundEditor
                                    ?: FileEditorManager.getInstance(project).selectedTextEditor
                                    ?: return@invokeLater
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
                                // No sync-line echo: that caused preview snap-back after jumps.
                            } finally {
                                alarm.addRequest({ syncingFromPreview = false }, 400)
                            }
                        }
                    }
                } catch (_: Throwable) {}
                JBCefJSQuery.Response("OK")
            }
        }

        // 1b) selection bridge (preview → select text in editor)
        jsSyncSelection = JBCefJSQuery.create(base).also { query ->
            Disposer.register(this, query)
            query.addHandler { payload ->
                try {
                    val mergedStart = Regex("""\"mergedStart\"\s*:\s*(\d+)""")
                        .find(payload)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val mergedEnd = Regex("""\"mergedEnd\"\s*:\s*(\d+)""")
                        .find(payload)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (mergedStart != null && mergedEnd != null && mergedEnd > mergedStart) {
                        syncingFromPreview = true
                        ApplicationManager.getApplication().invokeLater {
                            try {
                                val settings = ApplicationManager.getApplication()
                                    .getService(LiveLatexSettings::class.java)
                                if (!settings.syncSelection) return@invokeLater
                                val ed = boundEditor
                                    ?: FileEditorManager.getInstance(project).selectedTextEditor
                                    ?: return@invokeLater
                                val docLen = ed.document.textLength
                                val origStart = LatexHtml.charMergedToOrig(mergedStart)
                                    .coerceIn(0, docLen)
                                val origEnd = LatexHtml.charMergedToOrig(mergedEnd)
                                    .coerceIn(0, docLen)
                                if (origEnd > origStart) {
                                    ed.selectionModel.setSelection(origStart, origEnd)
                                    ed.scrollingModel.scrollTo(
                                        ed.offsetToLogicalPosition(origStart),
                                        com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE
                                    )
                                }
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

        // 3) flush pending JS after each page load (do not re-create JBCefJSQuery here — must be created before load; re-creating after browser load triggers IllegalStateException)
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                br: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean
            ) {
                if (!isLoading) {
                    pageReady = true
                    // One failing bridge must not skip the others (sections dropdown / caret).
                    runCatching { installMoveCaretBridgeJs(base) }
                    runCatching { installSyncSelectionBridgeJs(base) }
                    runCatching { installTikzBridgeJs(base) }
                    runCatching { installClearCacheBridgeJs(base) }
                    runCatching { installSectionsBridgeJs(base) }
                    flushPending()
                    // Re-push sections after marks settle (DOMContentLoaded / MathJax).
                    listOf(200, 600, 1200).forEach { delayMs ->
                        alarm.addRequest({
                            eval("try { if (typeof window.__collectMarks === 'function') window.__collectMarks(); if (typeof window.sendSectionsToHost === 'function') window.sendSectionsToHost(); } catch(e){}")
                        }, delayMs)
                    }
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
        // Two-arg overload: required API (single-arg Document.addDocumentListener is deprecated).
        editor?.document?.addDocumentListener(docListener, this)
        editor?.caretModel?.addCaretListener(caretListener)
        editor?.selectionModel?.addSelectionListener(selectionListener)
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
            ed.selectionModel.removeSelectionListener(selectionListener)
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

    private fun syncEditorSelectionToPreview(editor: Editor) {
        if (syncingFromPreview) return
        val sm = editor.selectionModel
        if (!sm.hasSelection()) {
            eval("""window.postMessage({type:'sync-selection', clear:true}, '*');""")
            return
        }
        val start = sm.selectionStart
        val end = sm.selectionEnd
        if (end <= start) return
        eval(
            """window.postMessage({type:'sync-selection', srcStart:$start, srcEnd:$end, source:'editor'}, '*');"""
        )
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

    fun cancelPreviewBuild() {
        val hadBuild = isPreviewBuilding || previewBuildFuture != null || tikzJobFutures.isNotEmpty()
        val cancelToken = ++previewBuildGeneration
        pendingJs.clear()
        pageReady = false
        val draining = previewBuildFuture
        draining?.cancel(true)
        cancelTikzPoolJobs()
        LatexHtmlTikz.killRunningProcesses()
        isPreviewBuilding = false
        // Keep Future until it finishes so refresh can observe/replace it; clear async
        // on a side thread (not the single-thread build pool — that would queue behind the zombie).
        if (draining != null && !draining.isDone) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    draining.get(2, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }
                if (previewBuildFuture === draining) {
                    previewBuildFuture = null
                }
            }
        } else {
            previewBuildFuture = null
        }
        if (hadBuild) {
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!project.isDisposed && cancelToken == previewBuildGeneration) {
                        statusLine?.setStatus("Cancelled")
                    }
                },
                Condition<Any?> { project.isDisposed }
            )
            refreshDebounceAlarm.addRequest({
                if (cancelToken == previewBuildGeneration) statusLine?.clear()
            }, 2000)
        }
    }

    private fun cancelTikzPoolJobs() {
        ++tikzJobGeneration
        for (f in tikzJobFutures.toTypedArray()) {
            try {
                f.cancel(true)
            } catch (_: Throwable) {
            }
        }
        tikzJobFutures.clear()
    }

    /** @return true if a matching cached preview was shown for [path]. */
    private fun showCachedPreviewIfAvailable(path: String): Boolean {
        val cached = previewCache[path] ?: return false
        val renderTikzNow = ApplicationManager.getApplication()
            .getService(LiveLatexSettings::class.java).renderTikzInPreview
        if (cached.renderTikz != renderTikzNow) return false
        val ed = FileEditorManager.getInstance(project).selectedTextEditor ?: return false
        val vfNow = FileDocumentManager.getInstance().getFile(ed.document) ?: return false
        if (!FileUtil.pathsEqual(FileUtil.toSystemIndependentName(path), FileUtil.toSystemIndependentName(vfNow.path))) {
            return false
        }
        val caretLine = ed.caretModel.logicalPosition.line + 1
        setLastSections(cached.sections)
        renderHtml(cached.html, caretLine, path)
        return true
    }

    /** Replace previous file's page so the tool window does not stay on A during B's LiveRender. */
    private fun showSwitchingPlaceholder(fileName: String) {
        val safe = fileName.replace("<", "&lt;").replace(">", "&gt;")
        val html = LatexHtml.wrap(
            "<p style='opacity:.66;margin:1.5em'>Switching to <code>$safe</code>…</p>",
        )
        setLastSections(emptyList(), clear = true)
        setActiveSectionId(null)
        renderHtml(html, 1, null)
    }

    private fun notifyBackgroundPreviewComplete(fileName: String) {
        Notifications.Bus.notify(
            Notification(
                "LiveLatex",
                "Preview finished",
                "Preview for $fileName finished while you were elsewhere.",
                NotificationType.INFORMATION,
            ),
            project,
        )
    }

    private fun setBuildStatus(text: String, token: Int) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed || token != previewBuildGeneration) return@invokeLater
                statusLine?.setStatus(text)
            },
            Condition<Any?> { project.isDisposed },
        )
    }

    private fun clearBuildStatus(token: Int) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed || token != previewBuildGeneration) return@invokeLater
                statusLine?.clear()
            },
            Condition<Any?> { project.isDisposed },
        )
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
        setActiveSectionId(id)
        eval("try { if (typeof window.jumpToMarkId === 'function') window.jumpToMarkId('$escaped'); } catch(e){}")
    }

    /**
     * One-shot: write the current preview HTML next to the source .tex.
     * @return false if no HTML/source is available yet.
     */
    fun exportPreviewHtmlBesideSource(): Boolean {
        val texPath = currentTexFileAndText()?.first?.path
            ?: lastRenderedTexPath
            ?: return false
        val pathKey = FileUtil.toSystemIndependentName(texPath)
        val html = previewCache.entries.firstOrNull { (k, _) ->
            FileUtil.pathsEqual(FileUtil.toSystemIndependentName(k), pathKey)
        }?.value?.html ?: lastRenderedHtml ?: return false
        dumpPreviewHtmlBesideSource(texPath, html)
        return true
    }

    private fun refresh(targetFile: VirtualFile? = null) {
        val fem = FileEditorManager.getInstance(project)
        val editor = fem.selectedTextEditor
        val caretLine = editor?.caretModel?.logicalPosition?.line?.plus(1) ?: 1
        val doc = editor?.document
        val vfFromEditor = if (doc != null) FileDocumentManager.getInstance().getFile(doc) else null
        // Prefer explicit tab-switch target so we don't race selectedTextEditor.
        val vf = when {
            targetFile != null && isLiveLatexPreviewFile(targetFile) -> targetFile
            else -> vfFromEditor
        }
        val snapshotText = when {
            vf == null -> null
            vfFromEditor != null && doc != null &&
                FileUtil.pathsEqual(
                    FileUtil.toSystemIndependentName(vf.path),
                    FileUtil.toSystemIndependentName(vfFromEditor.path),
                ) -> doc.text
            else -> FileDocumentManager.getInstance().getDocument(vf)?.text
        }
        val ext = vf?.extension?.lowercase()
        val isTexLike = ext in listOf("tex", "ltx", "latex", "tikz")
        val snapshotPath = vf?.path
        val snapshotName = vf?.name ?: "document"
        val cacheRootForSnapshot = if (snapshotPath != null) {
            File(globalCacheDir(), sha1(snapshotPath)).absolutePath
        } else {
            globalCacheDir().absolutePath
        }
        val token = ++previewBuildGeneration
        val previous = previewBuildFuture
        previous?.cancel(true)
        cancelTikzPoolJobs()
        LatexHtmlTikz.killRunningProcesses()
        isPreviewBuilding = true
        val renderTikzSnapshot = ApplicationManager.getApplication()
            .getService(LiveLatexSettings::class.java).renderTikzInPreview

        val buildStartedAt = System.currentTimeMillis()
        statusShowAlarm?.let { refreshDebounceAlarm.cancelRequest(it) }
        val showStatusRunnable = Runnable {
            if (token == previewBuildGeneration && isPreviewBuilding) {
                setBuildStatus("Building preview…", token)
            }
        }
        statusShowAlarm = showStatusRunnable
        refreshDebounceAlarm.addRequest(showStatusRunnable, 300)

        previewBuildFuture = previewBuildExecutor.submit {
            try {
                // Single-thread pool: we only start after [previous] unwinds; wait briefly
                // if cancel left a zombie so killRunningProcesses can take effect.
                if (previous != null && !previous.isDone) {
                    try {
                        previous.get(2, TimeUnit.SECONDS)
                    } catch (_: Throwable) {
                    }
                }
                val progressHandler: (String) -> Unit = progress@{ detail ->
                    if (token != previewBuildGeneration) return@progress
                    val elapsed = System.currentTimeMillis() - buildStartedAt
                    if (elapsed >= 300 || detail.startsWith("TikZ")) {
                        setBuildStatus(detail, token)
                    }
                }
                LatexHtml.buildProgressHandler = progressHandler
                TikzRenderer.setLiveRenderProgressHandler { cur, tot, detail ->
                    if (token != previewBuildGeneration) return@setLiveRenderProgressHandler
                    val msg = if (tot > 0) "TikZ $cur / $tot: $detail" else detail
                    progressHandler(msg)
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
                    if (t is InterruptedException || Thread.currentThread().isInterrupted) {
                        finishPreviewBuildIfCurrent(token)
                        return@submit
                    }
                    val msg = (t.message ?: t.toString()).replace("<", "&lt;")
                    LatexHtml.wrap("<p style='opacity:.66;color:#b91c1c'>Preview build failed: $msg</p>")
                } finally {
                    LatexHtml.buildProgressHandler = null
                    TikzRenderer.setLiveRenderProgressHandler(null)
                }

                if (token != previewBuildGeneration || Thread.currentThread().isInterrupted) {
                    finishPreviewBuildIfCurrent(token)
                    return@submit
                }

                val sections = if (isTexLike) LatexHtml.lastCollectedSections else emptyList()
                if (isTexLike && snapshotPath != null && token == previewBuildGeneration) {
                    previewCache[snapshotPath] = CachedPreview(html, sections, token, renderTikzSnapshot)
                }

                ApplicationManager.getApplication().invokeLater(
                    {
                        if (project.isDisposed) return@invokeLater
                        if (token != previewBuildGeneration) return@invokeLater
                        isPreviewBuilding = false
                        previewBuildFuture = null
                        clearBuildStatus(token)

                        val activePath = FileEditorManager.getInstance(project).selectedTextEditor?.let { ed ->
                            FileDocumentManager.getInstance().getFile(ed.document)?.path
                        }
                        val sameFile = snapshotPath != null && activePath != null &&
                            FileUtil.pathsEqual(
                                FileUtil.toSystemIndependentName(snapshotPath),
                                FileUtil.toSystemIndependentName(activePath),
                            )

                        if (sameFile) {
                            setLastSections(sections)
                            renderHtml(html, caretLine, snapshotPath)
                        } else if (isTexLike && snapshotPath != null) {
                            notifyBackgroundPreviewComplete(snapshotName)
                        }
                    },
                    Condition<Any?> { project.isDisposed },
                )
            } catch (_: InterruptedException) {
                finishPreviewBuildIfCurrent(token)
            }
        }
    }

    /** Clear building flag/status only when [token] is still the active generation. */
    private fun finishPreviewBuildIfCurrent(token: Int) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed && token == previewBuildGeneration) {
                    isPreviewBuilding = false
                    previewBuildFuture = null
                    clearBuildStatus(token)
                }
            },
            Condition<Any?> { project.isDisposed },
        )
    }

    /** Must run on EDT (invoked from [refresh] completion after background build). */
    private fun renderHtml(html: String, caretLine: Int, texPath: String? = null) {
        pageReady = false
        lastRenderedHtml = html
        if (texPath != null) lastRenderedTexPath = texPath
        browser?.loadHTML(html, "http://latex-preview.local/")
        // Also queue bridges into pendingJs in case an intermediate load flushed before Install ran.
        val b = browser
        if (b != null) {
            val base = b as JBCefBrowserBase
            runCatching { installMoveCaretBridgeJs(base) }
            runCatching { installSyncSelectionBridgeJs(base) }
        }
        syncAutoScrollSettingsToPage()
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

    /** Write preview HTML next to the source .tex (e.g. diagram.tex → diagram.html). */
    private fun dumpPreviewHtmlBesideSource(texPath: String, html: String) {
        try {
            val texFile = File(texPath)
            val parent = texFile.parentFile ?: return
            val baseName = texFile.nameWithoutExtension
            if (baseName.isBlank()) return
            val out = File(parent, "$baseName.html")
            out.writeText(html)
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(out)
            LOG.info("Wrote preview HTML: ${out.absolutePath}")
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed) return@invokeLater
                    statusLine?.setStatus("Exported HTML: ${out.name}")
                    refreshDebounceAlarm.addRequest({ statusLine?.clear() }, 4000)
                    vf?.refresh(false, false)
                },
                Condition<Any?> { project.isDisposed },
            )
        } catch (t: Throwable) {
            LOG.warn("Failed to write preview HTML beside $texPath", t)
        }
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