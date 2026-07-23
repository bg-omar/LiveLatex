package com.omariskandarani.livelatex.actions

import com.intellij.openapi.components.Service
import java.awt.Point

/**
 * Lives for the lifetime of the IDE process (no disk persistence).
 * Holds the latest knot / flip / export so Load Last can restore the canvas.
 */
@Service(Service.Level.APP)
class TikzSessionStore {
    @Volatile var lastFlip: String = ""
    @Volatile var lastExportBody: String? = null
    @Volatile var lastWidthPercent: Int = 80
    @Volatile private var lastKnotInternal: MutableList<Point> = mutableListOf()

    fun setLastKnot(gridPoints: List<Point>) {
        lastKnotInternal = gridPoints.map { Point(it) }.toMutableList()
    }

    fun getLastKnot(): List<Point> = lastKnotInternal.map { Point(it) }

    fun hasLastSession(): Boolean =
        lastKnotInternal.isNotEmpty() || !lastExportBody.isNullOrBlank()

    fun remember(
        knotPoints: List<Point>,
        flip: String,
        exportBody: String,
        widthPercent: Int,
    ) {
        setLastKnot(knotPoints)
        lastFlip = flip
        lastExportBody = exportBody
        lastWidthPercent = TikzToolbarHelpers.clampWidthPercent(widthPercent)
    }
}
