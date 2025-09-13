package com.omariskandarani.livelatex.actions

import com.intellij.openapi.components.Service
import java.awt.Point

/**
 * Lives for the lifetime of the IDE process (no disk persistence).
 * Holds the latest knot and flip list so new dialogs can restore them.
 */
@Service(Service.Level.APP)
class TikzSessionStore {
    @Volatile var lastFlip: String = ""
    @Volatile private var lastKnotInternal: MutableList<Point> = mutableListOf()

    fun setLastKnot(gridPoints: List<Point>) {
        // deep copy
        lastKnotInternal = gridPoints.map { Point(it) }.toMutableList()
    }

    fun getLastKnot(): List<Point> = lastKnotInternal.map { Point(it) }
}