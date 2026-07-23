package com.omariskandarani.livelatex.core

import java.io.File

/** Filesystem helper for document/global cache clears (unit-testable). */
object PreviewCacheDirs {
    /** Deletes [dir] if it exists. Returns true if a delete was attempted on an existing path. */
    fun deleteIfExists(dir: File): Boolean {
        if (!dir.exists()) return false
        dir.deleteRecursively()
        return true
    }
}
