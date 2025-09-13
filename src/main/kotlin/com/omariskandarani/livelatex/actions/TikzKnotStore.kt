package com.omariskandarani.livelatex.actions

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.awt.Point
import java.util.ArrayDeque

@Service(Service.Level.PROJECT)
class TikzKnotStore {
    private val mru = ArrayDeque<Pair<String, List<Point>>>() // most recent first
    private val MAX = 9

    fun save(name: String, pts: List<Point>) {
        val clean = pts.map { Point(it) }
        // remove existing with same name
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
