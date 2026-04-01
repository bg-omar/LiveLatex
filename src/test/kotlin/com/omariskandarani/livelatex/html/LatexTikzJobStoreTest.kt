package com.omariskandarani.livelatex.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatexTikzJobStoreTest {

    @Test
    fun putGetRoundTrip() {
        LatexTikzJobStore.clear()
        LatexTikzJobStore.put("k1", "% tex")
        assertEquals("% tex", LatexTikzJobStore.get("k1"))
    }

    @Test
    fun clearRemovesJobs() {
        LatexTikzJobStore.put("k2", "x")
        LatexTikzJobStore.clear()
        assertNull(LatexTikzJobStore.get("k2"))
    }
}
