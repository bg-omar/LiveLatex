package com.omariskandarani.livelatex.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

sealed class ShapeElt {
    data class Node(val x: Int, val y: Int): ShapeElt()
    data class Line(val x1: Int, val y1: Int, val x2: Int, val y2: Int): ShapeElt()
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int): ShapeElt()
    data class Circle(val cx: Int, val cy: Int, val r: Int): ShapeElt()
}

class TikzCanvasDialog(project: Project) : DialogWrapper(project, true) {

    private val canvas = DrawPanel()
    private val toolGroup = ButtonGroup()
    private val nodeBtn = JToggleButton("Node")
    private val lineBtn = JToggleButton("Line")
    private val rectBtn = JToggleButton("Rect")
    private val circBtn = JToggleButton("Circle")
    private val clearBtn = JButton("Clear")
    private val undoBtn = JButton("Undo")

    var resultTikz: String? = null
        private set

    private lateinit var content: JPanel

    init {
        title = "TikZ Canvas"
        initUI()
        init()
        setSize(800, 600)
    }

    private fun initUI() {
        nodeBtn.isSelected = true
        listOf(nodeBtn, lineBtn, rectBtn, circBtn).forEach { toolGroup.add(it) }

        clearBtn.addActionListener { canvas.clear() }
        undoBtn.addActionListener { canvas.undo() }

        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Tool:"))
            add(nodeBtn); add(lineBtn); add(rectBtn); add(circBtn)
            add(Box.createHorizontalStrut(12))
            add(undoBtn); add(clearBtn)
        }

        content = JPanel(BorderLayout())
        content.add(top, BorderLayout.NORTH)
        content.add(JScrollPane(canvas), BorderLayout.CENTER)
        content.preferredSize = Dimension(900, 600)
        setResizable(true)
        setOKButtonText("Export & Insert")
        setCancelButtonText("Cancel")
        super.setTitle("TikZ Canvas")

        canvas.toolProvider = { currentTool() }
    }

    override fun createCenterPanel(): JComponent? {
        return content
    }

    private fun currentTool(): String = when {
        nodeBtn.isSelected -> "node"
        lineBtn.isSelected -> "line"
        rectBtn.isSelected -> "rect"
        circBtn.isSelected -> "circ"
        else -> "node"
    }

    override fun doOKAction() {
        resultTikz = canvas.exportTikz()
        super.doOKAction()
    }

    private class DrawPanel : JPanel() {
        private val shapes = mutableListOf<ShapeElt>()
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var preview: ShapeElt? = null
        var toolProvider: (() -> String)? = null

        init {
            background = JBColor(Color(0xF8F9FB), Color(0x23272E))
            preferredSize = Dimension(1200, 800)
            border = BorderFactory.createLineBorder(JBColor(Color(0xD0D7DE), Color(0x23272E)), 1)

            val ma = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val tool = toolProvider?.invoke() ?: "node"
                    startX = e.x; startY = e.y
                    when (tool) {
                        "node" -> {
                            shapes += ShapeElt.Node(e.x, e.y)
                            repaint()
                        }
                        else -> {
                            dragging = true
                            preview = null
                        }
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (!dragging) return
                    val tool = toolProvider?.invoke() ?: "line"
                    val x1 = startX; val y1 = startY
                    val x2 = e.x; val y2 = e.y
                    preview = when (tool) {
                        "line" -> ShapeElt.Line(x1, y1, x2, y2)
                        "rect" -> {
                            val x = min(x1, x2); val y = min(y1, y2)
                            val w = max(1, kotlin.math.abs(x2 - x1))
                            val h = max(1, kotlin.math.abs(y2 - y1))
                            ShapeElt.Rect(x, y, w, h)
                        }
                        "circ" -> {
                            val r = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toInt()
                            ShapeElt.Circle(x1, y1, r)
                        }
                        else -> null
                    }
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (!dragging) return
                    dragging = false
                    preview?.let { shapes += it }
                    preview = null
                    repaint()
                }
            }
            addMouseListener(ma)
            addMouseMotionListener(ma)
        }

        fun clear() {
            shapes.clear()
            preview = null
            repaint()
        }

        fun undo() {
            if (preview != null) {
                preview = null
            } else if (shapes.isNotEmpty()) {
                shapes.removeAt(shapes.lastIndex)
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // grid
            g2.color = JBColor(Color(0xEAECEF), Color(0x23272E))
            for (x in 0 until width step 25) g2.drawLine(x, 0, x, height)
            for (y in 0 until height step 25) g2.drawLine(0, y, width, y)

            // axes
            g2.color = JBColor(Color(0xCCD1D9), Color(0x23272E))
            g2.drawLine(0, 0, width, 0)
            g2.drawLine(0, 0, 0, height)

            // shapes
            g2.color = JBColor(Color(0x374151), Color(0xEAECEF))
            for (s in shapes) drawShape(g2, s)
            preview?.let {
                val old = g2.composite
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
                g2.color = JBColor(Color(0x1F2937), Color(0xEAECEF))
                drawShape(g2, it)
                g2.composite = old
            }
        }

        private fun drawShape(g2: Graphics2D, s: ShapeElt) {
            when (s) {
                is ShapeElt.Node -> {
                    g2.fillOval(s.x - 3, s.y - 3, 6, 6)
                }
                is ShapeElt.Line -> {
                    g2.drawLine(s.x1, s.y1, s.x2, s.y2)
                }
                is ShapeElt.Rect -> {
                    g2.drawRect(s.x, s.y, s.w, s.h)
                }
                is ShapeElt.Circle -> {
                    g2.drawOval(s.cx - s.r, s.cy - s.r, 2 * s.r, 2 * s.r)
                }
            }
        }

        fun exportTikz(): String {
            val sb = StringBuilder()
            // TikZ measures in cm by default; convert pixels to cm with a scale (96 dpi ≈ 37.8 px/cm)
            val pxPerCm = 37.8
            fun f(v: Int) = String.format("%.2f", v / pxPerCm)

            for (s in shapes) {
                when (s) {
                    is ShapeElt.Node -> {
                        sb.append("\\fill (${f(s.x)},${f(height - s.y)}) circle (0.05);\n")
                    }
                    is ShapeElt.Line -> {
                        sb.append("\\draw (${f(s.x1)},${f(height - s.y1)}) -- (${f(s.x2)},${f(height - s.y2)});\n")
                    }
                    is ShapeElt.Rect -> {
                        val x2 = s.x + s.w
                        val y2 = s.y + s.h
                        val x1c = f(s.x); val y1c = f(height - s.y)
                        val x2c = f(x2);  val y2c = f(height - y2)
                        sb.append("\\draw ($x1c,$y1c) rectangle ($x2c,$y2c);\n")
                    }
                    is ShapeElt.Circle -> {
                        sb.append("\\draw (${f(s.cx)},${f(height - s.cy)}) circle (${f(s.r)});\n")
                    }
                }
            }
            return sb.toString().trim()
        }
    }
}