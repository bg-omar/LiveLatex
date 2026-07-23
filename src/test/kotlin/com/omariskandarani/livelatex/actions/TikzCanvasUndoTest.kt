package com.omariskandarani.livelatex.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TikzCanvasUndoTest {

    @Test
    fun undoRedo_pushClearsRedoBranch() {
        val stack = UndoStack<String>()
        stack.push("a")
        stack.push("b")
        stack.push("c")
        assertEquals("b", stack.undo())
        assertEquals("a", stack.undo())
        assertFalse(stack.canUndo)
        assertEquals("b", stack.redo())
        stack.push("x") // clears redo of "c"
        assertFalse(stack.canRedo)
        assertEquals("b", stack.undo())
        assertEquals("x", stack.redo())
    }

    @Test
    fun undo_emptyReturnsNull() {
        val stack = UndoStack<Int>()
        assertNull(stack.undo())
        stack.push(1)
        assertNull(stack.undo())
        assertEquals(1, stack.current())
    }

    @Test
    fun scrollToCenterOrigin_clamps() {
        val pos = TikzCanvasOrigin.scrollToCenterOrigin(
            originX = 600,
            originY = 400,
            viewportWidth = 200,
            viewportHeight = 100,
            maxScrollX = 1000,
            maxScrollY = 800,
        )
        assertEquals(500, pos.x)
        assertEquals(350, pos.y)

        val clamped = TikzCanvasOrigin.scrollToCenterOrigin(
            originX = 10,
            originY = 10,
            viewportWidth = 200,
            viewportHeight = 100,
            maxScrollX = 50,
            maxScrollY = 50,
        )
        assertEquals(0, clamped.x)
        assertEquals(0, clamped.y)
        assertTrue(clamped.x >= 0)
    }
}
