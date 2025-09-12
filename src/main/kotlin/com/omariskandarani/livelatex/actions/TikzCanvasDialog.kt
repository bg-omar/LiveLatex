package com.omariskandarani.livelatex.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import com.intellij.openapi.ui.ComboBox
import kotlin.math.*

sealed class ShapeElt {
    data class Node(val x: Int, val y: Int): ShapeElt()
    data class Line(val x1: Int, val y1: Int, val x2: Int, val y2: Int): ShapeElt()
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int): ShapeElt()
    data class Circle(val cx: Int, val cy: Int, val r: Int): ShapeElt()
    data class Knot(val points: List<Point>): ShapeElt()
}

class TikzCanvasDialog(project: Project) : DialogWrapper(project, true) {

    private val canvas = DrawPanel()
    private val toolGroup = ButtonGroup()
    private val nodeBtn = JToggleButton("Node")
    private val lineBtn = JToggleButton("Line")
    private val rectBtn = JToggleButton("Rect")
    private val circBtn = JToggleButton("Circle")
    private val knotBtn = JToggleButton("Knot")
    private val clearBtn = JButton("Clear")
    private val undoBtn = JButton("Undo")
    private val showPointsBox = JCheckBox("Show Points", true)
    private val stylePathBox = JCheckBox("Export every path style", false)
    private val styleNodeBox = JCheckBox("Export every node style", false)
    private val styleKnotBox = JCheckBox("Export every knot style", false)
    private val gridSizeOptions = arrayOf("2x2", "4x4", "6x6")
    private val gridSizeBox = ComboBox(gridSizeOptions)
    private val gridStepOptions = arrayOf("1", "0.5", "0.25")
    private val gridStepBox = ComboBox(gridStepOptions)
    private val zoomOptions = arrayOf("25%", "50%", "100%", "200%", "400%")
    private val zoomBox = ComboBox(zoomOptions).apply { selectedIndex = 2 }

    var resultTikz: String? = null
        private set

    private lateinit var content: JPanel

    init {
        title = "TikZ Canvas"
        initUI()
        init()
        setSize(800, 600)
    }

    // Defensive initialization for knotModeGroup
    private var knotModeGroup: ButtonGroup? = null
    private val knotAddBtn = JRadioButton("Add", true)
    private val knotEraseBtn = JRadioButton("Erase")
    private val knotMoveBtn = JRadioButton("Move")

    private fun initUI() {
        nodeBtn.isSelected = true
        // Assert all components are non-null before adding to top panel
        requireNotNull(nodeBtn) { "nodeBtn is null" }
        requireNotNull(lineBtn) { "lineBtn is null" }
        requireNotNull(rectBtn) { "rectBtn is null" }
        requireNotNull(circBtn) { "circBtn is null" }
        requireNotNull(knotBtn) { "knotBtn is null" }
        requireNotNull(undoBtn) { "undoBtn is null" }
        requireNotNull(clearBtn) { "clearBtn is null" }
        requireNotNull(gridSizeBox) { "gridSizeBox is null" }
        requireNotNull(gridStepBox) { "gridStepBox is null" }
        requireNotNull(zoomBox) { "zoomBox is null" }
        requireNotNull(showPointsBox) { "showPointsBox is null" }
        requireNotNull(stylePathBox) { "stylePathBox is null" }
        requireNotNull(styleNodeBox) { "styleNodeBox is null" }
        requireNotNull(styleKnotBox) { "styleKnotBox is null" }

        clearBtn.addActionListener { canvas.clear() }
        undoBtn.addActionListener { canvas.undo() }

        // Explicitly initialize knotModeGroup here
        knotModeGroup = ButtonGroup()
        knotModeGroup!!.add(knotAddBtn)
        knotModeGroup!!.add(knotEraseBtn)
        knotModeGroup!!.add(knotMoveBtn)
        val knotModePanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Knot Mode:"))
            add(knotAddBtn)
            add(knotEraseBtn)
            add(knotMoveBtn)
        }
        val top = JPanel(FlowLayout(FlowLayout.LEFT))
        fun logAdd(comp: Component, name: String) {
            // Avoid NullPointerException by not accessing comp.parent before adding
            println("DEBUG: Adding $name, class=${comp.javaClass.simpleName}, hash=${comp.hashCode()}")
            top.add(comp)
        }
        logAdd(JLabel("Tool:"), "ToolLabel")
        logAdd(nodeBtn, "nodeBtn")
        logAdd(lineBtn, "lineBtn")
        logAdd(rectBtn, "rectBtn")
        logAdd(circBtn, "circBtn")
        logAdd(knotBtn, "knotBtn")
        logAdd(Box.createHorizontalStrut(12), "Strut1")
        logAdd(undoBtn, "undoBtn")
        logAdd(clearBtn, "clearBtn")
        logAdd(Box.createHorizontalStrut(12), "Strut2")
        logAdd(JLabel("Grid:"), "GridLabel")
        logAdd(gridSizeBox, "gridSizeBox")
        logAdd(JLabel("Grid Step:"), "GridStepLabel")
        logAdd(gridStepBox, "gridStepBox")
        logAdd(JLabel("Zoom:"), "ZoomLabel")
        logAdd(zoomBox, "zoomBox")
        logAdd(showPointsBox, "showPointsBox")
        logAdd(stylePathBox, "stylePathBox")
        logAdd(styleNodeBox, "styleNodeBox")
        logAdd(styleKnotBox, "styleKnotBox")
        logAdd(Box.createHorizontalStrut(12), "Strut3")
        logAdd(knotModePanel, "knotModePanel")
        content = JPanel(BorderLayout())
        content.add(top, BorderLayout.NORTH)
        content.add(JScrollPane(canvas), BorderLayout.CENTER)
        content.preferredSize = Dimension(900, 600)
        setResizable(true)
        setOKButtonText("Export & Insert")
        setCancelButtonText("Cancel")
        super.setTitle("TikZ Canvas")

        canvas.toolProvider = { currentTool() }
        canvas.showPointsProvider = { showPointsBox.isSelected }
        canvas.gridSizeProvider = { gridSizeBox.selectedItem as String }
        canvas.gridStepProvider = { (gridStepBox.selectedItem as String).toDouble() }
        canvas.zoomProvider = {
            val zoomStr = zoomBox.selectedItem as String
            when (zoomStr) {
                "25%" -> 0.25
                "50%" -> 0.5
                "100%" -> 1.0
                "200%" -> 2.0
                "400%" -> 4.0
                else -> 1.0
            }
        }
        canvas.knotModeProvider = {
            when {
                knotAddBtn.isSelected -> "add"
                knotEraseBtn.isSelected -> "erase"
                knotMoveBtn.isSelected -> "move"
                else -> "add"
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        // Controls (strongly typed)
        val gridSizeOptions = arrayOf("2x2","4x4","6x6")
        val gridStepOptions = arrayOf("1","0.5","0.25")
        val zoomOptions     = arrayOf("25%","50%","100%","200%","400%")

        val gridSizeBox = ComboBox<String>(gridSizeOptions)
        val gridStepBox = ComboBox<String>(gridStepOptions)
        val zoomBox     = ComboBox<String>(zoomOptions).apply { selectedIndex = 2 }

        // Tool buttons grouped so exactly one is on
        val toolGroup = ButtonGroup().apply {
            add(nodeBtn); add(lineBtn); add(rectBtn); add(circBtn); add(knotBtn)
        }
        nodeBtn.isSelected = true

        // Knot mode radio buttons grouped
        val knotModeGroup = ButtonGroup().apply {
            add(knotAddBtn); add(knotEraseBtn); add(knotMoveBtn)
        }
        val knotModePanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Knot Mode:"))
            add(knotAddBtn); add(knotEraseBtn); add(knotMoveBtn)
        }

        // Top toolbar row (build without the logAdd indirection)
        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Tool:")); add(nodeBtn); add(lineBtn); add(rectBtn); add(circBtn); add(knotBtn)
            add(Box.createHorizontalStrut(12))
            add(undoBtn); add(clearBtn)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Grid:")); add(gridSizeBox)
            add(JLabel("Grid Step:")); add(gridStepBox)
            add(JLabel("Zoom:")); add(zoomBox)
            add(showPointsBox); add(stylePathBox); add(styleNodeBox); add(styleKnotBox)
            add(Box.createHorizontalStrut(12))
            add(knotModePanel)
        }

        // Wire providers AFTER controls exist
        canvas.toolProvider = { currentTool() }
        canvas.showPointsProvider = { showPointsBox.isSelected }
        canvas.gridSizeProvider = { gridSizeBox.selectedItem as String }
        canvas.gridStepProvider = { (gridStepBox.selectedItem as String).toDouble() }
        canvas.zoomProvider = {
            when (zoomBox.selectedItem as String) {
                "25%" -> 0.25; "50%" -> 0.5; "100%" -> 1.0; "200%" -> 2.0; "400%" -> 4.0
                else -> 1.0
            }
        }
        canvas.knotModeProvider = {
            when {
                knotAddBtn.isSelected -> "add"
                knotEraseBtn.isSelected -> "erase"
                knotMoveBtn.isSelected -> "move"
                else -> "add"
            }
        }

        // Actions
        clearBtn.addActionListener { canvas.clear() }
        undoBtn.addActionListener { canvas.undo() }

        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JScrollPane(canvas), BorderLayout.CENTER)
            preferredSize = Dimension(900, 600)
        }
    }


    private fun currentTool(): String = when {
        nodeBtn.isSelected -> "node"
        lineBtn.isSelected -> "line"
        rectBtn.isSelected -> "rect"
        circBtn.isSelected -> "circ"
        knotBtn.isSelected -> "knot"
        else -> "node"
    }

    override fun doOKAction() {
        resultTikz = canvas.exportTikz(
            showPoints = showPointsBox.isSelected,
            stylePath = stylePathBox.isSelected,
            styleNode = styleNodeBox.isSelected,
            styleKnot = styleKnotBox.isSelected
        )
        super.doOKAction()
    }

    private class DrawPanel : JPanel() {
        private val shapes = mutableListOf<ShapeElt>()
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var preview: ShapeElt? = null
        var toolProvider: (() -> String)? = null
        var showPointsProvider: (() -> Boolean)? = null
        var gridSizeProvider: (() -> String)? = null
        var gridStepProvider: (() -> Double)? = null
        var zoomProvider: (() -> Double)? = null
        var knotModeProvider: (() -> String)? = null

        private var knotPoints = mutableListOf<Point>()
        private var isDrawingKnot = false
        private var movingKnotIdx: Int? = null

        init {
            background = JBColor(Color(0xF8F9FB), Color(0x23272E))
            preferredSize = Dimension(1200, 800)
            border = BorderFactory.createLineBorder(JBColor(Color(0xD0D7DE), Color(0x23272E)), 1)

            val ma = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val tool = toolProvider?.invoke() ?: "node"
                    val knotMode = knotModeProvider?.invoke() ?: "add"
                    val gridSizeStr = gridSizeProvider?.invoke() ?: "4x4"
                    val gridCount = when (gridSizeStr) {
                        "2x2" -> 2
                        "4x4" -> 4
                        "6x6" -> 6
                        else -> 4
                    }
                    val gridStep = gridStepProvider?.invoke() ?: 1.0
                    val pxPerCm = 37.8
                    val gridStepPx = (gridStep * pxPerCm).toInt()
                    val centerX = width / 2
                    val centerY = height / 2
                    val zoom = zoomProvider?.invoke() ?: 1.0
                    fun snapToGrid(x: Int, y: Int): Point {
                        val zx = (x.toDouble() / zoom).toInt()
                        val zy = (y.toDouble() / zoom).toInt()
                        val dx = ((zx - centerX) / gridStepPx.toDouble()).roundToInt()
                        val dy = ((zy - centerY) / gridStepPx.toDouble()).roundToInt()
                        val gx = centerX + (dx * gridStepPx)
                        val gy = centerY + (dy * gridStepPx)
                        return Point(gx, gy)
                    }
                    startX = e.x; startY = e.y
                    when (tool) {
                        "node" -> {
                            val pt = snapToGrid(e.x, e.y)
                            shapes += ShapeElt.Node(pt.x, pt.y)
                            repaint()
                        }
                        "knot" -> {
                            if (!isDrawingKnot) {
                                knotPoints.clear()
                                isDrawingKnot = true
                            }
                            val pt = snapToGrid(e.x, e.y)
                            when (knotMode) {
                                "add" -> {
                                    knotPoints.add(pt)
                                    repaint()
                                }
                                "erase" -> {
                                    // Remove nearest node within threshold
                                    val idx = knotPoints.indexOfFirst { p -> p.distance(pt) < 12 }
                                    if (idx != -1) {
                                        knotPoints.removeAt(idx)
                                        repaint()
                                    }
                                }
                                "move" -> {
                                    // Select node to move
                                    movingKnotIdx = knotPoints.indexOfFirst { p -> p.distance(pt) < 12 }
                                }
                            }
                            if (e.clickCount == 2 || SwingUtilities.isRightMouseButton(e)) {
                                if (knotPoints.size > 1) {
                                    shapes += ShapeElt.Knot(knotPoints.toList())
                                }
                                knotPoints.clear()
                                isDrawingKnot = false
                                movingKnotIdx = null
                                repaint()
                            }
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
            val zoom: Double = zoomProvider?.invoke() ?: 1.0
            g2.scale(zoom, zoom)

            // grid size logic
            val gridSizeStr = gridSizeProvider?.invoke() ?: "4x4"
            val gridCount = when (gridSizeStr) {
                "2x2" -> 2
                "4x4" -> 4
                "6x6" -> 6
                else -> 4
            }
            val gridStep = gridStepProvider?.invoke() ?: 1.0
            val pxPerCm = 37.8
            val gridStepPx = (gridStep * pxPerCm).toInt()
            val fontGrid = g2.font.deriveFont(Font.PLAIN, 9f)
            val fontKnot = g2.font.deriveFont(Font.BOLD, 12f)
            g2.font = fontGrid
            val centerX = width / 2
            val centerY = height / 2
            // Draw grid lines
            for (i in -gridCount/2..gridCount/2) {
                val x = centerX + (i * gridStepPx)
                g2.drawLine(x, 0, x, height)
                val y = centerY + (i * gridStepPx)
                g2.drawLine(0, y, width, y)
                // Draw coordinate label at grid intersection
                for (j in -gridCount/2..gridCount/2) {
                    val gx = centerX + (i * gridStepPx)
                    val gy = centerY + (j * gridStepPx)
                    val xCoord = i * gridStep
                    val yCoord = -j * gridStep
                    val label = String.format("(%.2f,%.2f)", xCoord, yCoord)
                    g2.setColor(JBColor(Color(0xAAAAAA), Color(0xAAAAAA)))
                    g2.drawString(label, gx + 2, gy + 12)
                    g2.setColor(JBColor(Color(0xEAECEF), Color(0x23272E)))
                }
            }
            // Draw axes x=0 and y=0
            g2.setColor(JBColor(Color(0x1C4DB9), Color(0x1C4DB9)))
            g2.setStroke(BasicStroke(2f))
            g2.drawLine(centerX, 0, centerX, height) // y axis
            g2.drawLine(0, centerY, width, centerY) // x axis
            g2.setStroke(BasicStroke(1f))

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

            // Draw knot in progress
            if (isDrawingKnot && knotPoints.isNotEmpty()) {
                val showPoints = showPointsProvider?.invoke() ?: true
                g2.font = fontKnot
                for ((i, pt) in knotPoints.withIndex()) {
                    g2.setColor(JBColor(Color(0xB91C1C), Color(0xB91C1C)))
                    g2.fillOval(pt.x - 3, pt.y - 3, 6, 6)
                    // Calculate integer grid coordinates
                    val gridX = ((pt.x - centerX) / gridStepPx.toDouble()).roundToInt()
                    val gridY = -((pt.y - centerY) / gridStepPx.toDouble()).roundToInt()
                    val label = "(${gridX},${gridY})"
                    val metrics = g2.getFontMetrics()
                    val labelWidth = metrics.stringWidth(label)
                    val labelHeight = metrics.height
                    g2.setColor(JBColor(Color(0xF8F9FB), Color(0x23272E)))
                    g2.fillRect(pt.x + 8, pt.y - labelHeight / 2, labelWidth + 4, labelHeight)
                    g2.setColor(JBColor(Color(0x1C4DB9), Color(0x1C4DB9)))
                    g2.drawString(label, pt.x + 10, pt.y + labelHeight / 2)
                }
                g2.setColor(JBColor(Color(0xB91C1C), Color(0xB91C1C)))
                for (i in 0 until knotPoints.size - 1) {
                    val p1 = knotPoints[i]
                    val p2 = knotPoints[i + 1]
                    g2.drawLine(p1.x, p1.y, p2.x, p2.y)
                }
            }
            // Draw finished knots
            for (s in shapes) {
                if (s is ShapeElt.Knot) {
                    val showPoints = showPointsProvider?.invoke() ?: true
                    g2.font = fontKnot
                    for ((i, pt) in s.points.withIndex()) {
                        g2.setColor(JBColor(Color(0xB91C1C), Color(0xB91C1C)))
                        g2.fillOval(pt.x - 3, pt.y - 3, 6, 6)
                        // Calculate integer grid coordinates
                        val gridX = ((pt.x - centerX) / gridStepPx.toDouble()).roundToInt()
                        val gridY = -((pt.y - centerY) / gridStepPx.toDouble()).roundToInt()
                        val label = "(${gridX},${gridY})"
                        val metrics = g2.getFontMetrics()
                        val labelWidth = metrics.stringWidth(label)
                        val labelHeight = metrics.height
                        g2.setColor(JBColor(Color(0xF8F9FB), Color(0x23272E)))
                        g2.fillRect(pt.x + 8, pt.y - labelHeight / 2, labelWidth + 4, labelHeight)
                        g2.setColor(JBColor(Color(0x1C4DB9), Color(0x1C4DB9)))
                        g2.drawString(label, pt.x + 10, pt.y + labelHeight / 2)
                    }
                    g2.setColor(JBColor(Color(0xB91C1C), Color(0xB91C1C)))
                    for (i in 0 until s.points.size - 1) {
                        val p1 = s.points[i]
                        val p2 = s.points[i + 1]
                        g2.drawLine(p1.x, p1.y, p2.x, p2.y)
                    }
                }
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

                else -> {}
            }
        }

        // In exportTikz, use snapped grid coordinates for export (no zoom applied)
        fun exportTikz(
            showPoints: Boolean = true,
            stylePath: Boolean = false,
            styleNode: Boolean = false,
            styleKnot: Boolean = false
        ): String {
            val sb = StringBuilder()
            val pxPerCm = 37.8
            val gridStep = gridStepProvider?.invoke() ?: 1.0
            val centerX = width / 2
            val centerY = height / 2
            fun toGrid(pt: Point): Pair<Double, Double> {
                val gridX = ((pt.x - centerX) / (gridStep * pxPerCm)).toDouble()
                val gridY = -((pt.y - centerY) / (gridStep * pxPerCm)).toDouble()
                return Pair(gridX, gridY)
            }

            // TikZ styles
            val tikzStyles = mutableListOf<String>()
            tikzStyles += "knot diagram/every strand/.append style={ultra thick, black}"
            if (stylePath) tikzStyles += "every path/.style={black,line width=2pt}"
            if (styleNode) tikzStyles += "every node/.style={transform shape,knot crossing,inner sep=1.5pt}"
            if (styleKnot) tikzStyles += "every knot/.style={line cap=round,line join=round,very thick}"
            sb.append("\\tikzset{\n  " + tikzStyles.joinToString(",\n  ") + "\n}\n")

            var knotCount = 1
            for (s in shapes) {
                when (s) {
                    is ShapeElt.Node -> {
                        val (gridX, gridY) = toGrid(Point(s.x, s.y))
                        sb.append("\\fill ($gridX,$gridY) circle (0.05);\n")
                    }
                    is ShapeElt.Line -> {
                        val (x1, y1) = toGrid(Point(s.x1, s.y1))
                        val (x2, y2) = toGrid(Point(s.x2, s.y2))
                        sb.append("\\draw ($x1,$y1) -- ($x2,$y2);\n")
                    }
                    is ShapeElt.Rect -> {
                        val (x1, y1) = toGrid(Point(s.x, s.y))
                        val (x2, y2) = toGrid(Point(s.x + s.w, s.y + s.h))
                        sb.append("\\draw ($x1,$y1) rectangle ($x2,$y2);\n")
                    }
                    is ShapeElt.Circle -> {
                        val (cx, cy) = toGrid(Point(s.cx, s.cy))
                        sb.append("\\draw ($cx,$cy) circle (${"%.2f".format(s.r / pxPerCm)});\n")
                    }
                    is ShapeElt.Knot -> {
                        for ((i, pt) in s.points.withIndex()) {
                            val (gridX, gridY) = toGrid(pt)
                            sb.append("\\coordinate (P${i + 1}) at ($gridX,$gridY);\n")
                        }
                        sb.append("\\begin{knot}[consider self intersections, clip width=5pt, clip radius=3pt, ignore endpoint intersections=false]\n")
                        sb.append("\\strand ([closed] ")
                        sb.append((1..s.points.size).joinToString("..") { "P$it" })
                        sb.append(");\n\\end{knot}\n")
                        if (showPoints) {
                            sb.append("\\SSTGuidesPoints{P}{${s.points.size}}\n")
                        }
                    }
                }
            }
            return sb.toString().trim()
        }
    }
}