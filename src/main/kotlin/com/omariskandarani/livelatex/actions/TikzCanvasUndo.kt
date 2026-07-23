package com.omariskandarani.livelatex.actions

/**
 * Generic linear undo/redo stack (plan 10).
 * [push] clears the redo side; [undo]/[redo] move the current pointer.
 */
class UndoStack<T>(private val limit: Int = 64) {
    private val entries = ArrayDeque<T>()
    private var index = -1 // points at current state; -1 = empty

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index >= 0 && index < entries.size - 1
    val size: Int get() = entries.size

    fun clear() {
        entries.clear()
        index = -1
    }

    fun push(state: T) {
        while (entries.size - 1 > index) {
            entries.removeLast()
        }
        entries.addLast(state)
        if (entries.size > limit) {
            entries.removeFirst()
        } else {
            index++
        }
        // After removeFirst while at capacity, index stays at last
        index = entries.size - 1
    }

    fun undo(): T? {
        if (!canUndo) return null
        index--
        return entries.elementAt(index)
    }

    fun redo(): T? {
        if (!canRedo) return null
        index++
        return entries.elementAt(index)
    }

    fun current(): T? = if (index in entries.indices) entries.elementAt(index) else null
}

/** Viewport math so canvas origin (cx, cy) sits in the middle of the visible scroll area. */
object TikzCanvasOrigin {
    data class ScrollPos(val x: Int, val y: Int)

    fun scrollToCenterOrigin(
        originX: Int,
        originY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        maxScrollX: Int,
        maxScrollY: Int,
    ): ScrollPos {
        val x = (originX - viewportWidth / 2).coerceIn(0, maxScrollX.coerceAtLeast(0))
        val y = (originY - viewportHeight / 2).coerceIn(0, maxScrollY.coerceAtLeast(0))
        return ScrollPos(x, y)
    }
}
