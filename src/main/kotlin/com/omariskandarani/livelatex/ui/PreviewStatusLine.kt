package com.omariskandarani.livelatex.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.MatteBorder

/** Single-line status strip above the LaTeX preview browser. */
class PreviewStatusLine : JPanel(BorderLayout()) {

    private val label = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
    }

    init {
        border = MatteBorder(0, 0, 1, 0, Color(0xE0, 0xE0, 0xE0))
        add(label, BorderLayout.CENTER)
        isVisible = false
    }

    fun setStatus(text: String) {
        label.text = text
        isVisible = text.isNotBlank() && text != "Done"
    }

    fun clear() = setStatus("")
}
