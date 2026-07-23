package com.omariskandarani.livelatex.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewCacheDirsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deleteIfExists_removesDirectoryTree() {
        val dir = tmp.newFolder("cache")
        File(dir, "a.txt").writeText("x")
        assertTrue(PreviewCacheDirs.deleteIfExists(dir))
        assertFalse(dir.exists())
    }

    @Test
    fun deleteIfExists_missingReturnsFalse() {
        assertFalse(PreviewCacheDirs.deleteIfExists(File(tmp.root, "nope")))
    }
}
