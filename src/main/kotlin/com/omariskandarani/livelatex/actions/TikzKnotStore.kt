package com.omariskandarani.livelatex.actions

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.Service
import java.awt.Point
import java.util.ArrayDeque

@State(name = "TikzKnotStore", storages = [Storage("tikzKnotStore.xml")])
@Service(Service.Level.PROJECT)
class TikzKnotStore : PersistentStateComponent<TikzKnotStore.State> {
    private val mru = ArrayDeque<Pair<String, List<Point>>>() // most recent first
    private val MAX = 9

    data class Knot(val name: String, val points: List<Point>)
    data class State(var knots: List<Knot> = emptyList())

    override fun getState(): State {
        return State(mru.map { Knot(it.first, it.second) })
    }

    override fun loadState(state: State) {
        mru.clear()
        state.knots.forEach { mru.addLast(it.name to it.points.map { p -> Point(p) }) }
    }

    fun save(name: String, pts: List<Point>) {
        val clean = pts.map { Point(it) }
        val it = mru.iterator()
        while (it.hasNext()) if (it.next().first == name) it.remove()
        mru.addFirst(name to clean)
        while (mru.size > MAX) mru.removeLast()
    }

    fun load(name: String): List<Point>? =
        mru.firstOrNull { it.first == name }?.second?.map { Point(it) }

    fun delete(name: String) {
        val it = mru.iterator()
        while (it.hasNext()) if (it.next().first == name) it.remove()
    }

    fun names(): List<String> = mru.map { it.first }
}
