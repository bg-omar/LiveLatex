package com.omariskandarani.livelatex.actions

import java.awt.Dimension
import javax.swing.JComboBox

/** TikZ named colors and common mixes (no hex) for canvas color pickers. */
object TikzColorHelpers {

    val NAMED: List<String> = listOf(
        "black", "red", "blue", "green", "teal", "orange", "purple", "gray",
    )

    val MIXES: List<String> = listOf(
        "black!60!black",
        "black!40!white",
        "red!70!black",
        "red!70!green",
        "blue!60!black",
        "green!50!black",
        "teal!70!black",
    )

    fun allPresets(): List<String> = (NAMED + MIXES).distinct()

    fun createPicker(initial: String, toolTip: String, columnsHint: Int = 12): JComboBox<String> {
        val box = JComboBox(allPresets().toTypedArray())
        box.isEditable = true
        box.selectedItem = initial.ifBlank { NAMED.first() }
        box.toolTipText = toolTip
        box.preferredSize = Dimension((columnsHint * 9).coerceIn(100, 180), box.preferredSize.height)
        return box
    }

    fun value(box: JComboBox<*>): String {
        val fromEditor = box.editor?.item?.toString()
        val raw = fromEditor?.takeIf { it.isNotBlank() } ?: box.selectedItem?.toString().orEmpty()
        return raw.trim()
    }

    fun setValue(box: JComboBox<*>, value: String) {
        val v = value.trim()
        box.selectedItem = v
        box.editor?.item = v
    }
}
