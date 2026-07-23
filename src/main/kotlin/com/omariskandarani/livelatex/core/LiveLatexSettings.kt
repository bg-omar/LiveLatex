package com.omariskandarani.livelatex.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Application-level settings for LiveLatex. Persisted across restarts. */
@Service(Service.Level.APP)
@State(
    name = "LiveLatexSettings",
    storages = [Storage(value = "livelatex.xml")]
)
class LiveLatexSettings : PersistentStateComponent<LiveLatexSettings.State> {

    data class State(
        var renderTikzInPreview: Boolean = false,  // default off: lighter for IDE/Android; per-figure LiveRender button or toolbar checkbox
        var autoPreview: Boolean = true,
        var autoScrollPreview: Boolean = true,
        var syncSelection: Boolean = false,    // default off; UI hidden until next version
        var showTikzDebugOverlay: Boolean = false,
        var debugScrollLog: Boolean = false,   // Debug Mode: scroll HUD + auto-export HTML; default off
        var invertScrollHorizontal: Boolean = false,
        var invertScrollVertical: Boolean = false,
        var showDropdownSubsections: Boolean = true,
        var showDropdownSubsubsections: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        // Clear any previously persisted TikZ-debug toggle (feature is one-shot export now).
        state.showTikzDebugOverlay = false
        // Always start with LiveRender off: a persisted "on" blocks/delays first preview until toggled off.
        state.renderTikzInPreview = false
        this.state = state
    }

    var renderTikzInPreview: Boolean
        get() = state.renderTikzInPreview
        set(value) { state.renderTikzInPreview = value }

    var autoPreview: Boolean
        get() = state.autoPreview
        set(value) { state.autoPreview = value }

    var autoScrollPreview: Boolean
        get() = state.autoScrollPreview
        set(value) { state.autoScrollPreview = value }

    var syncSelection: Boolean
        get() = state.syncSelection
        set(value) { state.syncSelection = value }

    var showTikzDebugOverlay: Boolean
        get() = state.showTikzDebugOverlay
        set(value) { state.showTikzDebugOverlay = value }

    var debugScrollLog: Boolean
        get() = state.debugScrollLog
        set(value) { state.debugScrollLog = value }

    var invertScrollHorizontal: Boolean
        get() = state.invertScrollHorizontal
        set(value) { state.invertScrollHorizontal = value }

    var invertScrollVertical: Boolean
        get() = state.invertScrollVertical
        set(value) { state.invertScrollVertical = value }

    var showDropdownSubsections: Boolean
        get() = state.showDropdownSubsections
        set(value) { state.showDropdownSubsections = value }

    var showDropdownSubsubsections: Boolean
        get() = state.showDropdownSubsubsections
        set(value) { state.showDropdownSubsubsections = value }
}