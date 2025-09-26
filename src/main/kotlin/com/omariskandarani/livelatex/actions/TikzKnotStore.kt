package com.omariskandarani.livelatex.actions

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Stores a small MRU set of named knot point lists per project.
 * NOTE: State must use bean-serializable types (no java.awt.Point).
 */
@Service(Service.Level.PROJECT)
@State(
    name = "LiveLatexTikzKnotStore",
    storages = [Storage("LiveLatexTikzKnotStore.xml")]
)
class TikzKnotStore(private val project: Project) : PersistentStateComponent<TikzKnotStore.State> {

    // --- Serializable beans (NO AWT POINTS) ---
    data class PointBean(var x: Int = 0, var y: Int = 0)
    data class Entry(var name: String = "", var points: MutableList<PointBean> = mutableListOf())

    data class State(
        var entries: MutableList<Entry> = mutableListOf(),
        var maxItems: Int = 9
    )

    // in-memory (never null)
    private var state: State = State()

    // --- PersistentStateComponent ---
    override fun getState(): State = state

    override fun loadState(s: State) {
        // Be defensive against corrupted / partial data
        try {
            val clean = State(
                entries = s.entries.orEmpty().mapNotNull { e ->
                    if (e.name.isBlank()) return@mapNotNull null
                    val pts = e.points.orEmpty().mapNotNull { p ->
                        // guard for nulls / wrong numbers
                        if (p == null) null else PointBean(p.x, p.y)
                    }.toMutableList()
                    Entry(e.name, pts)
                }.toMutableList(),
                maxItems = if (s.maxItems <= 0) 9 else s.maxItems
            )
            state = clean
        } catch (_: Throwable) {
            // fallback to empty state if anything explodes
            state = State()
        }
    }

    // --- Public API used by the dialog ---

    fun names(): List<String> = state.entries.map { it.name }

    fun save(title: String, points: List<java.awt.Point>) {
        val t = title.trim()
        if (t.isEmpty()) return

        // convert to beans
        val beans = points.map { PointBean(it.x, it.y) }.toMutableList()

        // upsert at front (MRU)
        val existingIdx = state.entries.indexOfFirst { it.name == t }
        if (existingIdx >= 0) {
            state.entries.removeAt(existingIdx)
        }
        state.entries.add(0, Entry(t, beans))

        // trim
        while (state.entries.size > state.maxItems) {
            state.entries.removeLast()
        }
    }

    fun load(title: String): List<java.awt.Point>? {
        val e = state.entries.firstOrNull { it.name == title.trim() } ?: return null
        return e.points.map { java.awt.Point(it.x, it.y) }
    }

    fun delete(title: String) {
        val idx = state.entries.indexOfFirst { it.name == title.trim() }
        if (idx >= 0) state.entries.removeAt(idx)
    }

    // (optional) expose maxItems tweak
    fun setMaxItems(n: Int) {
        state.maxItems = n.coerceAtLeast(1)
        while (state.entries.size > state.maxItems) state.entries.removeLast()
    }
}
