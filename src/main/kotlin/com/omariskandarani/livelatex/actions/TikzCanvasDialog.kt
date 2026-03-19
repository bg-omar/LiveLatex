package com.omariskandarani.livelatex.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.omariskandarani.livelatex.html.LatexHtmlTikz
import com.omariskandarani.livelatex.html.TikzRenderer
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.math.*

private fun segmentDist2(p: Point, a: Point, b: Point): Int {
    val vx = (b.x - a.x).toDouble()
    val vy = (b.y - a.y).toDouble()
    val wx = (p.x - a.x).toDouble()
    val wy = (p.y - a.y).toDouble()

    val c1 = vx * wx + vy * wy
    if (c1 <= 0.0) return p.distanceSq(a).toInt()

    val c2 = vx * vx + vy * vy
    if (c2 <= c1) return p.distanceSq(b).toInt()

    val t = c1 / c2
    val projX = a.x + t * vx
    val projY = a.y + t * vy
    val dx = p.x - projX
    val dy = p.y - projY
    return (dx * dx + dy * dy).roundToInt()
}

/** Project p onto segment a–b, return the nearest point on the segment (between a and b). */
private fun projectOntoSegment(p: Point, a: Point, b: Point): Point {
    val vx = (b.x - a.x).toDouble()
    val vy = (b.y - a.y).toDouble()
    val wx = (p.x - a.x).toDouble()
    val wy = (p.y - a.y).toDouble()
    val c1 = vx * wx + vy * wy
    val c2 = vx * vx + vy * vy
    if (c2 <= 0.0) return Point(a)
    val t = (c1 / c2).coerceIn(0.0, 1.0)
    return Point((a.x + t * vx).roundToInt(), (a.y + t * vy).roundToInt())
}


class TikzCanvasDialog(
    private val project: Project,
    private val initialTikz: String? = null
) : DialogWrapper(project, true) {

    private companion object {
        const val STEP = 100          // 1 unit
        const val SUB = STEP / 4      // 0.25 unit
        private const val GRID_UNITS = 4
        private const val PICK_R = 10
        const val LONG_PRESS_MS = 220       // hold to add
        const val MOVE_TOL2 = PICK_R * PICK_R * 4 // reuse your pick radius *2
    }


    // --------- model types ----------
    private sealed interface Shape {
        fun hit(raw: Point, tol2: Int): Hit? = null
        data class Hit(val kind: String, val index: Int = -1) // kind: "center","p1","p2","radius","body"
    }
    private data class Dot(var p: Point) : Shape {
        override fun hit(raw: Point, tol2: Int) =
            if (raw.distanceSq(p) <= tol2) Shape.Hit("center") else null
    }
    private data class Label(var p: Point, var text: String) : Shape {
        override fun hit(raw: Point, tol2: Int) =
            if (raw.distanceSq(p) <= tol2) Shape.Hit("center") else null
    }
    private data class LineSeg(var a: Point, var b: Point) : Shape {
        override fun hit(raw: Point, tol2: Int): Shape.Hit? {
            if (raw.distanceSq(a) <= tol2) return Shape.Hit("p1")
            if (raw.distanceSq(b) <= tol2) return Shape.Hit("p2")
            // body (projection distance)
            val d2 = segmentDist2(raw, a, b)
            return if (d2 <= tol2) Shape.Hit("body") else null
        }
    }
    private data class Circ(var c: Point, var rUnits: Double) : Shape {
        override fun hit(raw: Point, tol2: Int): Shape.Hit? {
            if (raw.distanceSq(c) <= tol2) return Shape.Hit("center")
            val px = raw.distance(c).toInt()
            val ringPx = (rUnits * STEP).toInt()
            val diff = abs(px - ringPx)
            return if (diff <= sqrt(tol2.toDouble())) Shape.Hit("radius") else null
        }
    }
    private data class Rect(var a: Point, var b: Point) : Shape {
        override fun hit(raw: Point, tol2: Int): Shape.Hit? {
            if (raw.distanceSq(a) <= tol2) return Shape.Hit("p1")
            if (raw.distanceSq(b) <= tol2) return Shape.Hit("p2")
            val minX = min(a.x, b.x); val maxX = max(a.x, b.x)
            val minY = min(a.y, b.y); val maxY = max(a.y, b.y)
            if (raw.x in minX..maxX && raw.y in minY..maxY) return Shape.Hit("body")
            return null
        }
    }


    // Knot points are edited separately (tool=KNOT)
    private val knotPts = mutableListOf<Point>()
    // export buffer the action reads after OK
    var resultTikz: String? = null
        private set

    // Two-strand settings (bound to persistent service)
    private lateinit var twoStrandSettings: TwoStrandSettings

    private lateinit var spAmp: JSpinner
    private lateinit var spTurns: JSpinner
    private lateinit var spSamples: JSpinner
    private lateinit var tfWth: JTextField
    private lateinit var tfCore: JTextField
    private lateinit var tfMask: JTextField
    private lateinit var tfClrA: JTextField
    private lateinit var tfClrB: JTextField

    // --------- services / state ----------
    private val store = project.getService(TikzKnotStore::class.java)
    private val shapes = mutableListOf<Shape>()
    private var mode = EditMode.ADD
    private var tool = Tool.KNOT
    private var dirty = false

    // Background image for tracing knots
    private var backgroundImage: Image? = null
    private var backgroundOpacity: Float = 0.5f

    // ── Presets ─────────────────────────────────────────────────────────────────
    private data class Preset(
        val name: String,
        val ptsUnits: List<Pair<Double, Double>> = emptyList(),         // primary closed path (Hobby)
        val circlesUnits: List<Triple<Double, Double, Double>> = emptyList(), // (cx, cy, rUnits)
        val flipList: String? = null                                     // optional default flip crossing list
    )

    private fun unitsToPixelsPoints(units: List<Pair<Double,Double>>): List<Point> {
        val cx = canvas.width / 2; val cy = canvas.height / 2
        return units.map { (ux, uy) -> Point(cx + fromUnits(ux), cy - fromUnits(uy)) }
    }
    private fun unitsToPixelsCircles(units: List<Triple<Double,Double,Double>>): List<Circ> {
        val cx = canvas.width / 2; val cy = canvas.height / 2
        return units.map { (ux, uy, r) -> Circ(Point(cx + fromUnits(ux), cy - fromUnits(uy)), r) }
    }

    // simple star/rosette polygon in unit space (outer/inner), closed (last==first)
    private fun starPolygon(n: Int, rOuter: Double, rInner: Double, startDeg: Double = 90.0): List<Pair<Double,Double>> {
        val out = ArrayList<Pair<Double,Double>>(2*n + 1)
        val toRad = Math.PI / 180.0
        for (k in 0 until n) {
            val aOuter = (startDeg + 360.0 * k / n) * toRad
            val aInner = (startDeg + 360.0 * k / n + 180.0 / n) * toRad
            out += (rOuter * kotlin.math.cos(aOuter)) to (rOuter * kotlin.math.sin(aOuter))
            out += (rInner * kotlin.math.cos(aInner)) to (rInner * kotlin.math.sin(aInner))
        }
        out += out.first()
        return out
    }

    private val presets: List<Preset> = listOf(
        Preset(
            "★ 3₁ Half-Twist Trefoil",
            ptsUnits = listOf(
                0.0 to  2.0,
                -1.0 to  1.0,
                -1.0 to  0.0,
                1.0 to -1.0,
                2.0 to  0.0,
                0.0 to  0.75,
                -2.0 to  0.0,
                -1.0 to -1.0,
                1.0 to  0.0,
                1.0 to  1.0,
                0.0 to  2.0,
                0.0 to  2.0
            ),
            flipList = "2,4,6,8,10,12,14"
        ),
        Preset(
            "★ 4₁ Two-Half-Twist",
            ptsUnits = listOf(
                -2.0 to -2.0,
                -2.0 to  2.0,
                 1.0 to -0.5,
                -1.0 to -0.5,
                 2.0 to  2.0,
                 2.0 to -2.0,
                -1.0 to  0.5,
                 1.0 to  0.5,
                -2.0 to -2.0
            ),
            flipList = "2,4,6,8,10,12,14"
        ),
        Preset(
            "★ 5₁ Torus Cinquefoil",
            ptsUnits = listOf(
                -0.25 to  1.75,
                -1.25 to -0.50,
                -0.75 to -1.0,
                1.50 to  0.50,
                1.25 to  1.0,
                -1.25 to  1.0,
                -1.50 to  0.50,
                0.75 to -1.0,
                1.25 to -0.50,
                0.25 to  1.75,
                -0.25 to  1.75,
                -0.25 to  1.75
            ),
            flipList = "2,4,6,8,10,12,14"
        ),
        Preset(
            "★ 5₂ Three-Half-Twist",
            ptsUnits = listOf(
                2.0 to -1.5,
                1.5 to  1.0,
                0.0 to  2.0,
               -2.0 to  1.0,
               -1.0 to -1.5,
                0.5 to -2.0,
               -1.25 to -2.25,
               -2.0 to -1.5,
               -1.5 to  1.0,
                0.0 to  2.0,
                2.0 to  1.0,
                1.0 to -1.5,
               -0.5 to -2.0,
                1.25 to -2.25,
                2.0 to -1.5
            ),
            flipList = "2,4,6,8,10,12,14,16,18"
        ),
        Preset(
            "★ 6₁ Four-Half-Twist",
            ptsUnits = listOf(
                 0.0 to  2.0,
                -2.0 to  2.0,
                -1.5 to -1.0,
                 0.5 to -2.0,
                -1.5 to -2.0,
                -2.5 to -0.5,
                -2.0 to  1.0,
                 0.0 to  3.0,
                 2.0 to  1.0,
                 2.5 to -0.5,
                 1.5 to -2.0,
                -0.5 to -2.0,
                 1.5 to -1.0,
                 2.0 to  2.0,
                 0.0 to  2.0
            ),
            flipList = "2,4,6,8,10,12,14,16,18"
        ),
        Preset(
            "★ 7₁ Torus Septafoil",
            ptsUnits = listOf(
                -0.25 to  2.0,
                -1.50 to  0.50,
                -1.50 to  0.0,
                0.50 to -1.0,
                1.0  to -0.75,
                1.25 to  1.25,
                0.75 to  1.75,
                -0.75 to  1.75,
                -1.25 to  1.25,
                -1.0  to -0.75,
                -0.50 to -1.0,
                1.50 to  0.0,
                1.50 to  0.50,
                0.25 to  2.0,
                -0.25 to  2.0,
                -0.25 to  2.0
            ),
            flipList = "2,4,6,8,10,12,14,16,18"
        ),
        Preset(
            "★ 7₂ Twist",
            ptsUnits = listOf(
                0.0  to  2.0,
                -2.50 to  2.25,
                -1.75 to  0.0,
                -1.50 to -2.50,
                -0.25 to -2.75,
                0.50 to -1.50,
                -0.25 to -1.0,
                -3.0  to  0.0,
                -1.25 to  1.25,
                -0.25 to  3.25,
                1.25 to  1.25,
                2.75 to  0.0,
                0.75 to -0.75,
                -0.50 to -1.50,
                0.50 to -2.75,
                1.75 to -1.75,
                1.50 to  0.0,
                2.0  to  2.50,
                0.0  to  2.0,
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        Preset(
            "★ 8₁ Twist Octafoil",
            ptsUnits = listOf(
                0.75 to  3.25,
                -1.0  to  2.0,
                -3.0  to  1.75,
                -2.0  to -0.25,
                -1.75 to -2.50,
                -0.50 to -1.0,
                -3.25 to -0.50,
                -1.75 to  1.25,
                -1.25 to  3.25,
                0.25 to  2.0,
                2.50 to  1.75,
                1.0  to -2.50,
                -0.75 to -2.0,
                0.50 to -0.75,
                2.75 to -0.50,
                1.25 to  1.25,
                0.75 to  3.25
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"

        ),
        Preset(
            "★ 9₁ Torus Nonafoil (alt)",
            ptsUnits = listOf(
                 0.25 to  3.25,
                -1.25 to  2.0,
                -3.25 to  1.50,
                -2.25 to -0.50,
                -2.0  to -2.50,
                 0.0  to -2.0,
                 2.25 to -1.75,
                 1.75 to  0.0,
                 2.25 to  2.25,
                 0.25 to  2.25,
                -1.75 to  3.0,
                -2.25 to  1.0,
                -3.50 to -0.75,
                -1.50 to -1.75,
                 0.25 to -3.0,
                 1.75 to -1.0,
                 3.0  to  0.25,
                 1.0  to  2.0,
                 0.25 to  3.25,
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        Preset(
            "★ 9₂ Twist",
            ptsUnits = listOf(
                 0.75 to  3.25,
                -1.0  to  2.0,
                -3.0  to  1.75,
                -2.0  to -0.25,
                -1.75 to -2.50,
                -0.50 to -2.50,
                 0.25 to -1.50,
                -0.50 to -1.0,
                -3.25 to -0.50,
                -1.75 to  1.25,
                -1.25 to  3.25,
                 0.25 to  2.0,
                 2.50 to  1.75,
                 1.75 to  0.0,
                 1.0  to -2.50,
                -0.75 to -2.0,
                 0.50 to -0.75,
                 2.75 to -0.50,
                 1.25 to  1.25,
                 0.75 to  3.25,
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        Preset(
            "★ 10₁ Twist Dekafoil",
            ptsUnits = listOf(
                 0.0  to  2.0,
                -1.75 to  3.0,
                -1.75 to  1.0,
                -3.0  to -1.0,
                -1.0  to -0.75,
                 0.50 to -2.0,
                -1.50 to -2.0,
                -2.25 to -0.25,
                -3.25 to  1.25,
                -1.25 to  1.75,
                 0.0  to  3.25,
                 1.25 to  1.75,
                 3.25 to  1.25,
                 2.25 to -0.25,
                 1.50 to -2.0,
                -0.50 to -2.0,
                 1.0  to -0.75,
                 3.0  to -1.0,
                 1.75 to  1.0,
                 1.75 to  3.0,
                 0.0  to  2.0,
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),

        Preset(
            "★ Hopf link",
            circlesUnits = listOf(
                Triple( 1.0, 0.0, 2.0),
                Triple(-1.0, 0.0, 2.0)
            )
        ),
        Preset(
            "★ Borromean rings",
            circlesUnits = listOf(
                Triple( 1.0, 0.0, 2.0),
                Triple(-1.0, 0.0, 2.0),
                Triple( 0.0, kotlin.math.sqrt(3.0), 2.0)
            )
        )
    )

    // convert unit-space preset into pixel points centered on canvas
    private fun unitsToPixels(units: List<Pair<Double,Double>>): List<Point> {
        val cx = canvas.width / 2; val cy = canvas.height / 2
        return units.map { (ux, uy) -> Point(cx + fromUnits(ux), cy - fromUnits(uy)) }
    }

    // in-progress
    private var tmpA: Point? = null   // for line/circle first click
    private var circlePreview: Circ? = null
    private var linePreview: LineSeg? = null
    private var rectPreview: Rect? = null
    private var textEditPending: Point? = null
    private var textEditField: JTextField? = null

    // dragging
    private data class DragCtx(val idx: Int, val hit: Shape.Hit, val start: Point, val orig: Any)
    private var drag: DragCtx? = null
    private var dragKnotIndex: Int = -1
    private var dragKind: String = ""  // for circle radius, etc.

    // press/drag state for knot interactions
    private var pressStartMs: Long = 0L
    private var pressButton: Int = 0
    private var pressRaw: Point? = null                 // unsnapped original press
    private var provisionalAdd: Point? = null           // shown while holding to add (snapped)
    private var autoMoveGrab: Boolean = false           // true when we clicked on an exist.
    private var longPressFired: Boolean = false // Add near other press state
    private var longPressReady: Boolean = false // readiness flag for long-press add
    private var initialPresetLoaded = false
    private var initialTikzImported = false

    // --------- UI controls ----------
    private lateinit var rootPanel: JPanel

    private val titleCombo = JComboBox(DefaultComboBoxModel(store.names().toTypedArray())).apply {
        isEditable = true
        preferredSize = Dimension(220, preferredSize.height)
        toolTipText = "Select preset or saved knot"
        addActionListener {
            val s = currentTitle().trim()
            if (s.isNotBlank() && s != "— saved —") doLoadSelected()
        }
    }
    private val newBtn = JButton("New")

    private val flipField = JTextField().apply { columns = 14 }
    private val showPointsBox = JCheckBox("Guides", true)
    private val optionsBtn = JButton("Knot options…").apply {
        toolTipText = "Twist, knot color, flip crossings, and more"
    }

    private val loadBgBtn = JButton("Load Background").apply {
        toolTipText = "Load an image to trace a knot"
    }
    private val clearBgBtn = JButton("Clear Background").apply {
        toolTipText = "Remove background image"
    }
    private lateinit var bgOpacitySpinner: JSpinner

    private val exportSetupBtn = JButton("Export")
    private val importSetupBtn = JButton("Import")
    private val previewBtn = JButton("Preview").apply {
        toolTipText = "Render and preview the knot before export"
    }


    private lateinit var twoStrandBox: JCheckBox


    private val knotColor = JComboBox(arrayOf("black","red","blue","green","teal","orange","purple","gray")).apply {
        selectedItem = "black"
        toolTipText = "Knot color"
        preferredSize = Dimension(100, preferredSize.height)
    }
    private val toolCombo = JComboBox(arrayOf("Knot", "Line", "Circle", "Rectangle", "Dot", "Text")).apply {
        selectedIndex = 0
        toolTipText = "Drawing tool"
        preferredSize = Dimension(100, preferredSize.height)
    }

    private val twinBox = JCheckBox("Twin", false)
    private val twinOffset = JSpinner(SpinnerNumberModel(0.25, 0.25, 2.0, 0.25)).apply {
        toolTipText = "Twin strand offset (grid units)"
        preferredSize = Dimension(70, preferredSize.height)
    }

    // compute twin preview points in pixels (uses unit-space offset then converts back)
    private fun twinPixels(points: List<Point>, dUnits: Double): List<Point> {
        val cx = canvas.width / 2; val cy = canvas.height / 2
        val units = points.map { Pair(toUnits(it.x - cx), -toUnits(it.y - cy)) }
        val off = offsetUnits(units, dUnits)
        return off.map { (ux, uy) -> Point(cx + fromUnits(ux), cy - fromUnits(uy)) }
    }

    // smoothed offset of a closed polyline in unit space
    private fun offsetUnits(u: List<Pair<Double, Double>>, d: Double): List<Pair<Double, Double>> {
        val n = u.size
        if (n == 0) return emptyList()
        fun norm(x: Double, y: Double): Pair<Double, Double> {
            val L = hypot(x, y)
            return if (L == 0.0) 0.0 to 0.0 else (x / L) to (y / L)
        }
        val out = ArrayList<Pair<Double, Double>>(n)
        for (i in 0 until n) {
            val im = (i - 1 + n) % n
            val ip = (i + 1) % n
            val vx1 = u[i].first - u[im].first
            val vy1 = u[i].second - u[im].second
            val vx2 = u[ip].first - u[i].first
            val vy2 = u[ip].second - u[i].second
            val (nx1, ny1) = norm(-vy1, vx1) // left normal of segment im->i
            val (nx2, ny2) = norm(-vy2, vx2) // left normal of segment i->ip
            var nx = nx1 + nx2
            var ny = ny1 + ny2
            val L = hypot(nx, ny)
            if (L != 0.0) { nx /= L; ny /= L }
            out += (u[i].first + d * nx) to (u[i].second + d * ny)
        }
        return out
    }

    // canvas
    private val canvas = object : JPanel() {
        // track canvas center to keep model aligned on resize
        private var lastCx: Int = -1
        private var lastCy: Int = -1

        init {
            layout = null
            background = JBColor(Color(0xF8F9FB), Color(0x23272E))
            preferredSize = Dimension(1200, 800)
            border = BorderFactory.createLineBorder(JBColor(Color(0xD0D7DE), Color(0x23272E)), 1)
            val ma = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = onPress(e)
                override fun mouseDragged(e: MouseEvent) = onDrag(e)
                override fun mouseReleased(e: MouseEvent) = onRelease(e)
            }
            addMouseListener(ma); addMouseMotionListener(ma)

            // keep geometry aligned when the center shifts (e.g., width-only resize)
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    val newCx = width / 2
                    val newCy = height / 2
                    if (lastCx == -1 && lastCy == -1) {
                        lastCx = newCx
                        lastCy = newCy
                        return
                    }
                    val dx = newCx - lastCx
                    val dy = newCy - lastCy
                    if (dx != 0 || dy != 0) {
                        translateAll(dx, dy)
                        lastCx = newCx
                        lastCy = newCy
                    }
                }
            })
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val cx = width / 2; val cy = height / 2

            // Background image for tracing
            backgroundImage?.let { img ->
                val alpha = (bgOpacitySpinner.value as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                val iw = img.getWidth(null)
                val ih = img.getHeight(null)
                if (iw > 0 && ih > 0) {
                    val scale = minOf(width.toDouble() / iw, height.toDouble() / ih).toFloat()
                    val dw = (iw * scale).toInt()
                    val dh = (ih * scale).toInt()
                    val dx = (width - dw) / 2
                    val dy = (height - dh) / 2
                    g2.drawImage(img, dx, dy, dw, dh, null)
                }
                g2.composite = AlphaComposite.SrcOver
            }

            // grid (integer only)
            g2.color = JBColor(Color(0xEAECEF), Color(0x23272E))
            for (i in -GRID_UNITS..GRID_UNITS) {
                val x = cx + i * STEP
                val y = cy + i * STEP
                g2.drawLine(x, 0, x, height)
                g2.drawLine(0, y, width, y)
            }
            // axes
            val old = g2.stroke
            g2.stroke = BasicStroke(3f)
            g2.color = JBColor(Color(0x1C4DB9), Color(0x1C4DB9))
            g2.drawLine(cx, 0, cx, height)
            g2.drawLine(0, cy, width, cy)
            g2.stroke = old
            // labels (integers)
            g2.color = JBColor(Color(0xAAAAAA), Color(0xAAAAAA))
            for (i in -GRID_UNITS..GRID_UNITS) {
                g2.drawString(i.toString(), cx + i * STEP + 4, cy - 6)
                g2.drawString(i.toString(), cx + 6, cy + i * STEP + 12)
            }

            // shapes
            drawShapes(g2)

            // knot working polyline
            if (knotPts.size >= 2) {
                g2.color = JBColor(Color(0xB91C1C), Color(0xB91C1C))
                for (i in 0 until knotPts.size - 1) {
                    val a = knotPts[i]; val b = knotPts[i + 1]
                    g2.drawLine(a.x, a.y, b.x, b.y)
                }
            }
            // knot points + labels
            val font = g2.font.deriveFont(Font.BOLD, 12f)
            g2.font = font
            for ((i, p) in knotPts.withIndex()) {
                g2.color = JBColor(Color(0xB91C1C), Color(0xB91C1C))
                g2.fillOval(p.x - 4, p.y - 4, 8, 8)
                val gx = toUnits(p.x - cx); val gy = -toUnits(p.y - cy)
                val label = "${i + 1}: (${fmtQ(gx)},${fmtQ(gy)})"
                val fm = g2.fontMetrics; val w = fm.stringWidth(label); val h = fm.height
                g2.color = JBColor(Color(0xF8F9FB), Color(0x23272E))
                g2.fillRect(p.x + 8, p.y - h, w + 6, h)
                g2.color = JBColor(Color(0x1C4DB9), Color(0x1C4DB9))
                g2.drawString(label, p.x + 10, p.y - 4)
            }
            // provisional add point while holding
            provisionalAdd?.let { p0 ->
                val fill = if (longPressReady) JBColor(Color(0x10B981), Color(0x34D399))  // green-ish when armed
                           else JBColor(Color(0x2563EB), Color(0x93C5FD))                 // blue while holding
                g2.color = fill
                g2.fillOval(p0.x - 4, p0.y - 4, 8, 8)
                g2.drawOval(p0.x - 6, p0.y - 6, 12, 12)
            }

            // previews
            g2.color = JBColor(Color(0x1F2937), Color(0xEAECEF))
            if (twinBox.isSelected && knotPts.size >= 2) {
                val dUnits = (twinOffset.value as? Double) ?: 0.25
                val twin = twinPixels(knotPts, dUnits)
                if (twin.isNotEmpty()) {
                    g2.color = JBColor(Color(0x6B7280), Color(0x9CA3AF)) // subtle gray
                    for (i in 0 until twin.size - 1) {
                        val a = twin[i]; val b = twin[i + 1]
                        g2.drawLine(a.x, a.y, b.x, b.y)
                    }
                }
            }

            circlePreview?.let { c ->
                val rPx = (c.rUnits * STEP).toInt()
                g2.drawOval(c.c.x - rPx, c.c.y - rPx, 2 * rPx, 2 * rPx)
            }
            linePreview?.let { l -> g2.drawLine(l.a.x, l.a.y, l.b.x, l.b.y) }
            rectPreview?.let { r ->
                val minX = min(r.a.x, r.b.x); val maxX = max(r.a.x, r.b.x)
                val minY = min(r.a.y, r.b.y); val maxY = max(r.a.y, r.b.y)
                g2.drawRect(minX, minY, maxX - minX, maxY - minY)
            }
        }
    }

    init {
        title = "TikZ Knot"
        initUI()
        init() // DialogWrapper init
        setSize((Toolkit.getDefaultToolkit().screenSize.width * 0.8).toInt(), (Toolkit.getDefaultToolkit().screenSize.height * 0.8).toInt())

        canvas.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                // When editing a knot from .tex: import coordinates once canvas has size, then skip preset
                if (initialTikz != null && !initialTikzImported && canvas.width > 0 && canvas.height > 0) {
                    importCoordinates(initialTikz)
                    initialTikzImported = true
                    initialPresetLoaded = true
                }
                if (!initialPresetLoaded && presets.isNotEmpty()) {
                    titleCombo.selectedIndex = 0
                    doLoadSelected()
                    initialPresetLoaded = true
                }
            }
        })
    }

    private fun drawShapes(g2: Graphics2D) {
        g2.color = JBColor(Color(0x374151), Color(0xEAECEF))
        for (s in shapes) {
            when (s) {
                is Dot -> {
                    g2.fillOval(s.p.x - 3, s.p.y - 3, 6, 6)
                }
                is Label -> {
                    val fm = g2.fontMetrics
                    val w = fm.stringWidth(s.text)
                    val h = fm.height
                    // background box
                    g2.color = JBColor(Color(0xF8F9FB), Color(0x23272E))
                    g2.fillRect(s.p.x + 6, s.p.y - h, w + 8, h)
                    // text
                    g2.color = JBColor(Color(0x1C4DB9), Color(0x1C4DB9))
                    g2.drawString(s.text, s.p.x + 10, s.p.y - 4)
                    g2.color = JBColor(Color(0x374151), Color(0xEAECEF))
                }
                is LineSeg -> {
                    g2.drawLine(s.a.x, s.a.y, s.b.x, s.b.y)
                }
                is Rect -> {
                    val minX = min(s.a.x, s.b.x); val maxX = max(s.a.x, s.b.x)
                    val minY = min(s.a.y, s.b.y); val maxY = max(s.a.y, s.b.y)
                    g2.drawRect(minX, minY, maxX - minX, maxY - minY)
                }
                is Circ -> {
                    val rPx = (s.rUnits * STEP).toInt()
                    g2.drawOval(s.c.x - rPx, s.c.y - rPx, 2 * rPx, 2 * rPx)
                }
            }
        }
    }


    // ---------- UI build ----------
    private fun initUI() {
        // tools row
        fun toolFromCombo(): Tool = when (toolCombo.selectedItem?.toString().orEmpty()) {
            "Line" -> Tool.LINE
            "Circle" -> Tool.CIRCLE
            "Rectangle" -> Tool.RECTANGLE
            "Dot" -> Tool.DOT
            "Text" -> Tool.TEXT
            else -> Tool.KNOT
        }
        fun syncComboFromTool() {
            toolCombo.selectedIndex = when (tool) {
                Tool.KNOT -> 0
                Tool.LINE -> 1
                Tool.CIRCLE -> 2
                Tool.RECTANGLE -> 3
                Tool.DOT -> 4
                Tool.TEXT -> 5
            }
        }
        toolCombo.addActionListener { tool = toolFromCombo() }

        bgOpacitySpinner = JSpinner(SpinnerNumberModel(0.5, 0.1, 1.0, 0.1)).apply {
            toolTipText = "Background opacity for tracing"
            preferredSize = Dimension(60, preferredSize.height)
        }
        loadBgBtn.addActionListener { loadBackgroundImage() }
        clearBgBtn.addActionListener { clearBackgroundImage() }
        bgOpacitySpinner.addChangeListener { canvas.repaint() }

        val toolsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(optionsBtn)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Tool:"))
            add(toolCombo)
            add(Box.createHorizontalStrut(12))
            add(loadBgBtn); add(clearBgBtn)
        }
        twoStrandSettings = TwoStrandSettingsService.getInstance().state.copy()
        twoStrandBox = JCheckBox("Twist", false)
        refreshTitlesCombo()


        // --- Two-strand Setups row ---
        spAmp     = JSpinner(SpinnerNumberModel(twoStrandSettings.amp,    0.0,  5.0, 0.01))
        spTurns   = JSpinner(SpinnerNumberModel(twoStrandSettings.turns,  0.0, 20.0, 0.25))
        spSamples = JSpinner(SpinnerNumberModel(twoStrandSettings.samples, 50, 2000, 10))

        tfWth  = JTextField(twoStrandSettings.wth,   6)
        tfCore = JTextField(twoStrandSettings.core,  8)
        tfMask = JTextField(twoStrandSettings.mask,  6)
        tfClrA = JTextField(twoStrandSettings.clrA, 14)
        tfClrB = JTextField(twoStrandSettings.clrB, 14)

        newBtn.addActionListener { doNew() }

        val fileRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(titleCombo)
            add(newBtn)
            add(Box.createHorizontalStrut(12))
            add(exportSetupBtn)
            add(importSetupBtn)
            add(Box.createHorizontalStrut(12))
            add(previewBtn)
        }

        exportSetupBtn.addActionListener { saveSetupToFile() }
        importSetupBtn.addActionListener { loadSetupFromFile() }
        previewBtn.addActionListener { doPreview() }

        optionsBtn.addActionListener { showKnotOptionsModal() }

        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.add(toolsRow)
        header.add(fileRow)

        rootPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            add(header, BorderLayout.NORTH)
            add(JScrollPane(canvas), BorderLayout.CENTER)
        }

        setOKButtonText("Add to TeX")
        setCancelButtonText("Cancel")
    }

    override fun createCenterPanel(): JComponent = rootPanel

    // ---------- Mouse handling ----------
    private fun onPress(e: MouseEvent) {
        val p = snap(e.point)
        pressStartMs = System.currentTimeMillis()
        pressButton = e.button
        pressRaw = e.point
        provisionalAdd = null
        autoMoveGrab = false
        longPressFired = false // Add near other press state
        longPressReady = false // readiness flag for long-press add

        when (tool) {
            Tool.KNOT -> {
                // Right-click deletes nearest point
                if (SwingUtilities.isRightMouseButton(e) || e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) {
                    nearestKnot(e.point)?.let { idx ->
                        knotPts.removeAt(idx)
                        markDirty()
                        canvas.repaint()
                    }
                    return
                }

                // Left-click behavior
                if (SwingUtilities.isLeftMouseButton(e) || e.button == MouseEvent.BUTTON1) {
                    // If we clicked an existing point, auto-grab to move
                    val near = nearestKnot(e.point)
                    if (near != null) {
                        dragKnotIndex = near
                        autoMoveGrab = true
                        return
                    }

                    // Potential add: show provisional while holding
                    provisionalAdd = p
                    canvas.repaint()
                    // NOTE: we only actually add on release IF held long enough
                }
            }

            // original behavior for other tools
            Tool.DOT -> when (mode) {
                EditMode.ADD -> { shapes += Dot(p); markDirty() }
                EditMode.DELETE, EditMode.MOVE -> startShapeDrag(e, listOf("center"))
            }
            Tool.TEXT -> when (mode) {
                EditMode.ADD -> showInlineTextEditor(p)
                EditMode.DELETE, EditMode.MOVE -> startShapeDrag(e, listOf("center"))
            }
            Tool.LINE -> when (mode) {
                EditMode.ADD -> { tmpA = p; linePreview = LineSeg(p, p); canvas.repaint() }
                EditMode.DELETE, EditMode.MOVE -> startShapeDrag(e, listOf("p1","p2","body"))
            }
            Tool.CIRCLE -> when (mode) {
                EditMode.ADD -> { tmpA = p; circlePreview = Circ(p, 0.0); canvas.repaint() }
                EditMode.DELETE, EditMode.MOVE -> startShapeDrag(e, listOf("center","radius"))
            }
            Tool.RECTANGLE -> when (mode) {
                EditMode.ADD -> { tmpA = p; rectPreview = Rect(p, p); canvas.repaint() }
                EditMode.DELETE, EditMode.MOVE -> startShapeDrag(e, listOf("p1","p2","body"))
            }
        }
    }

    private fun onDrag(e: MouseEvent) {
        val p = snap(e.point)
        when (tool) {
            Tool.KNOT -> {
                if (autoMoveGrab || mode == EditMode.MOVE) {
                    if (dragKnotIndex >= 0) {
                        knotPts[dragKnotIndex] = p
                        markDirty()
                        canvas.repaint()
                    }
                } else if (pressButton == MouseEvent.BUTTON1) {
                    provisionalAdd = p
                    // Only flag readiness; do NOT add here
                    longPressReady = (System.currentTimeMillis() - pressStartMs) >= LONG_PRESS_MS
                    canvas.repaint()
                }
            }
            Tool.LINE -> {
                if (mode == EditMode.ADD) {
                    linePreview?.let { it.b = p; canvas.repaint() }
                } else if (mode == EditMode.MOVE) {
                    handleShapeDrag(p)
                }
            }
            Tool.CIRCLE -> {
                if (mode == EditMode.ADD) {
                    circlePreview?.let {
                        it.rUnits = quantQ(p.distance(it.c) / STEP)
                        canvas.repaint()
                    }
                } else if (mode == EditMode.MOVE) {
                    handleShapeDrag(p)
                }
            }
            Tool.RECTANGLE -> {
                if (mode == EditMode.ADD) {
                    rectPreview?.let { it.b = p; canvas.repaint() }
                } else if (mode == EditMode.MOVE) {
                    handleShapeDrag(p)
                }
            }
            Tool.DOT, Tool.TEXT -> {
                if (mode == EditMode.MOVE) handleShapeDrag(p)
                else if (pressButton == MouseEvent.BUTTON1) {
                    provisionalAdd = p
                    longPressReady = (System.currentTimeMillis() - pressStartMs) >= LONG_PRESS_MS
                    canvas.repaint()
                }
            }
        }

        // ── long-press “commit once” per gesture ────────────────────────────
        val now = System.currentTimeMillis()
        val dt = now - pressStartMs
        if (!longPressFired && dt >= LONG_PRESS_MS) {
            when (tool) {
                Tool.DOT -> {
                    shapes += Dot(p)
                    markDirty()
                    canvas.repaint()
                    longPressFired = true
                }
                Tool.TEXT -> {
                    // Editor already shown on press
                    longPressFired = true
                }
                Tool.LINE -> {
                    // Start a line on long-press (ADD mode). Drag updates endpoint above.
                    if (mode == EditMode.ADD && tmpA == null && linePreview == null) {
                        tmpA = p
                        linePreview = LineSeg(p, p)
                        canvas.repaint()
                    }
                    longPressFired = true
                }
                Tool.CIRCLE -> {
                    // Start a circle on long-press (ADD mode). Drag updates radius above.
                    if (mode == EditMode.ADD && tmpA == null && circlePreview == null) {
                        tmpA = p
                        circlePreview = Circ(p, 0.0)
                        canvas.repaint()
                    }
                    longPressFired = true
                }
                Tool.RECTANGLE -> {
                    if (mode == EditMode.ADD && tmpA == null && rectPreview == null) {
                        tmpA = p
                        rectPreview = Rect(p, p)
                        canvas.repaint()
                    }
                    longPressFired = true
                }
                Tool.KNOT -> {
                    // no-op: handled only on release
                }
            }
        }
    }

    private fun resetPressState() {
        provisionalAdd = null
        autoMoveGrab = false
        longPressReady = false
        dragKnotIndex = -1
        drag = null
    }

    private fun onRelease(e: MouseEvent) {
        val p = snap(e.point)
        when (tool) {
            Tool.KNOT -> {
                // If we were dragging an existing point, just end the drag
                if (autoMoveGrab || mode == EditMode.MOVE) {
                    // no-op; dragKnotIndex cleared in reset
                } else if (pressButton == MouseEvent.BUTTON1 && longPressReady) {
                    // Commit add on release if the press lasted long enough
                    val raw = pressRaw ?: e.point
                    val segIdx = segmentAt(raw)
                    val candidate = if (segIdx != null) {
                        val proj = projectOntoSegment(raw, knotPts[segIdx], knotPts[segIdx + 1])
                        snap(Point(proj.x, proj.y))
                    } else {
                        provisionalAdd ?: p
                    }
                    if (segIdx != null) {
                        knotPts.add(segIdx + 1, candidate)
                        updateFlipCrossingsForNewPoint()
                        markDirty()
                    } else {
                        val idx = nearest(knotPts, candidate)
                        val isDuplicate = idx != null && candidate.distanceSq(knotPts[idx]) <= MOVE_TOL2
                        if (!isDuplicate) {
                            knotPts += candidate
                            markDirty()
                        }
                    }
                }
                resetPressState()
            }
            Tool.DOT -> {
                if (longPressReady) {
                    shapes += Dot(p)
                    markDirty()
                }
                resetPressState()
            }
            Tool.TEXT -> {
                // Editor already shown on press
                resetPressState()
            }
            Tool.LINE -> {
                if (mode == EditMode.ADD) {
                    if (linePreview != null && tmpA != null) {
                        linePreview?.let { shapes += LineSeg(it.a, p) }
                        markDirty()
                    }
                    linePreview = null
                    tmpA = null
                }
                resetPressState()
            }
            Tool.CIRCLE -> {
                if (mode == EditMode.ADD) {
                    if (circlePreview != null && tmpA != null) {
                        circlePreview?.let {
                            shapes += Circ(it.c, max(0.0, quantQ(p.distance(it.c) / STEP)))
                            markDirty()
                        }
                    }
                    circlePreview = null
                    tmpA = null
                }
                resetPressState()
            }
            Tool.RECTANGLE -> {
                if (mode == EditMode.ADD) {
                    if (rectPreview != null && tmpA != null) {
                        rectPreview?.let { shapes += Rect(it.a, p); markDirty() }
                    }
                    rectPreview = null
                    tmpA = null
                }
                resetPressState()
            }
        }
        canvas.repaint()
    }

    // ---------- dragging helpers ----------
    private fun startShapeDrag(e: MouseEvent, preferKinds: List<String>) {
        val idx = pickShape(e.point) ?: return
        val s = shapes[idx]
        val tol2 = PICK_R * PICK_R
        val hit = s.hit(e.point, tol2) ?: return
        if (preferKinds.isNotEmpty() && hit.kind !in preferKinds) return
        val orig = when (s) {
            is Dot   -> Point(s.p)
            is Label -> Pair(Point(s.p), s.text)
            is LineSeg -> Pair(Point(s.a), Point(s.b))
            is Rect  -> Pair(Point(s.a), Point(s.b))
            is Circ -> Pair(Point(s.c), s.rUnits)
        }
        drag = DragCtx(idx, hit, e.point, orig)
        dragKind = hit.kind
    }

    private fun handleShapeDrag(pSnapped: Point) {
        val d = drag ?: return
        val s = shapes[d.idx]
        when (s) {
            is Dot -> {
                s.p = pSnapped
            }
            is Label -> {
                s.p = pSnapped
            }
            is LineSeg -> {
                when (d.hit.kind) {
                    "p1" -> s.a = pSnapped
                    "p2" -> s.b = pSnapped
                    "body" -> { // translate both
                        @Suppress("UNCHECKED_CAST")
                        val orig = d.orig as Pair<Point, Point>
                        val dx = pSnapped.x - d.start.x
                        val dy = pSnapped.y - d.start.y
                        s.a = snap(Point(orig.first.x + dx,  orig.first.y + dy))
                        s.b = snap(Point(orig.second.x + dx, orig.second.y + dy))
                    }
                }
            }
            is Rect -> {
                when (d.hit.kind) {
                    "p1" -> s.a = pSnapped
                    "p2" -> s.b = pSnapped
                    "body" -> {
                        @Suppress("UNCHECKED_CAST")
                        val orig = d.orig as Pair<Point, Point>
                        val dx = pSnapped.x - d.start.x
                        val dy = pSnapped.y - d.start.y
                        s.a = snap(Point(orig.first.x + dx,  orig.first.y + dy))
                        s.b = snap(Point(orig.second.x + dx, orig.second.y + dy))
                    }
                }
            }
            is Circ -> {
                when (d.hit.kind) {
                    "center" -> s.c = pSnapped
                    "radius" -> {
                        s.rUnits = quantQ(pSnapped.distance(s.c) / STEP)
                    }
                }
            }
        }
        markDirty(); canvas.repaint()
    }

    // ---------- save/load ----------
    private fun currentTitle(): String = (titleCombo.editor.item?.toString() ?: "").trim()
    private fun refreshTitlesCombo(select: String? = null) {
        val presetNames = presets.map { it.name }
        val savedNames = store.names()
        val items = mutableListOf<String>().apply {
            addAll(presetNames)
            if (savedNames.isNotEmpty()) {
                add("— saved —")
                addAll(savedNames)
            }
        }
        titleCombo.model = DefaultComboBoxModel(items.toTypedArray())
        if (select != null && items.contains(select)) {
            titleCombo.selectedItem = select
        } else if (items.isNotEmpty()) {
            titleCombo.selectedIndex = 0
        }
    }
    private fun autoTitle(): String {
        val base = "Untitled"
        val names = store.names().toSet()
        if (base !in names) return base
        var i = 2; while ("$base $i" in names) i++
        return "$base $i"
    }
    private fun doSave(explicit: Boolean) {
        var title = currentTitle().trim()
        if (title.isBlank() || presets.any { it.name == title } || title == "— saved —") {
            title = autoTitle()
            titleCombo.editor.item = title
        }
        store.save(title, knotPts)
        refreshTitlesCombo(title); dirty = false
        if (explicit) JOptionPane.showMessageDialog(rootPanel, "Saved \"$title\".")
    }
    private fun maybeAutoSave() {
        if (!dirty) return
        var title = currentTitle()
        if (title.isBlank()) { title = autoTitle(); titleCombo.editor.item = title }
        store.save(title, knotPts); refreshTitlesCombo(title); dirty = false
    }
    private fun doLoadSelected() {
        maybeAutoSave()
        cancelInlineText()
        val title = currentTitle().trim()
        if (title.isBlank() || title == "— saved —") {
            JOptionPane.showMessageDialog(rootPanel, "Choose a title to load."); return
        }

        val preset = presets.firstOrNull { it.name == title }
        if (preset != null) {
            // clear canvas
            knotPts.clear()
            shapes.clear()

            // load main path (if any)
            if (preset.ptsUnits.isNotEmpty()) {
                knotPts.addAll(unitsToPixelsPoints(preset.ptsUnits))
            }
            // load circles (if any)
            if (preset.circlesUnits.isNotEmpty()) {
                shapes.addAll(unitsToPixelsCircles(preset.circlesUnits))
            }
            // optional default flips for classic export
            flipField.text = preset.flipList ?: ""

            dirty = true
            canvas.repaint()
            return
        }

        // Saved
        val pts = store.load(title) ?: run {
            JOptionPane.showMessageDialog(rootPanel, "No saved knot named \"$title\"."); return
        }
        knotPts.clear(); knotPts.addAll(pts.map { Point(it) })
        dirty = false; canvas.repaint()
    }

    private fun doNew() {
        maybeAutoSave()
        cancelInlineText()
        titleCombo.editor.item = ""; knotPts.clear(); dirty = false; canvas.repaint()
    }

    // ---------- import/export ----------
    /** Parses coordinates and optional flip list from .tex body and loads them into the canvas for editing. */
    private fun importCoordinates(tikz: String) {
        // \coordinate (P1) at (0, 2); or ( 0.5, -2 ) etc.
        val coordRx = Regex("""\\coordinate\s*\(P(\d+)\)\s*at\s*\(\s*([+-]?\s*\d+(?:\.\d+)?)\s*,\s*([+-]?\s*\d+(?:\.\d+)?)\s*\)\s*;""")
        val pairs = coordRx.findAll(tikz)
            .map { m ->
                val idx = m.groupValues[1].toInt()
                val x = m.groupValues[2].replace(" ", "").toDouble()
                val y = m.groupValues[3].replace(" ", "").toDouble()
                idx to (x to y)
            }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
        if (pairs.isEmpty()) return
        val cx = canvas.width / 2; val cy = canvas.height / 2
        knotPts.clear()
        knotPts.addAll(pairs.map { (x, y) -> Point(cx + fromUnits(x), cy - fromUnits(y)) })
        // Parse flip crossing/.list={2,4,6,...} from \begin{knot}[...] so edit preserves crossings
        val flipRx = Regex("""flip\s+crossing/\s*\.list\s*=\s*\{\s*([^}]+)\s*\}""")
        flipRx.find(tikz)?.let { m ->
            flipField.text = m.groupValues[1].trim()
        }
        dirty = true
        canvas.repaint()
    }

    private fun buildCurrentExportBody(): String {
        val amp = (spAmp.value as Number).toDouble()
        val turns = (spTurns.value as Number).toDouble()
        val samples = (spSamples.value as Number).toInt()
        val wth = tfWth.text.trim().ifEmpty { "2.5pt" }
        val core = tfCore.text.trim().ifEmpty { "black" }
        val mask = tfMask.text.trim().ifEmpty { "5.0pt" }
        val clrA = tfClrA.text.trim().ifEmpty { "black!60!black" }
        val clrB = tfClrB.text.trim().ifEmpty { "red!70!black" }
        return when {
            twoStrandBox.isSelected && knotPts.isNotEmpty() ->
                exportBodyTwoStrand(knotPts, amp, turns, samples, wth, core, mask, clrA, clrB)
            knotPts.isNotEmpty() ->
                exportBody(knotPts, showPointsBox.isSelected, flipField.text.trim(), null, false)
            shapes.isNotEmpty() ->
                exportShapesOnly()
            else ->
                "% No content."
        }
    }

    private fun doPreview() {
        val body = buildCurrentExportBody()
        if (body.startsWith("%") || body.isBlank()) {
            JOptionPane.showMessageDialog(rootPanel, "Add knot points or draw shapes to preview.", "Preview", JOptionPane.WARNING_MESSAGE)
            return
        }
        previewBtn.isEnabled = false
        val texDoc = """
\documentclass[tikz,border=2pt]{standalone}
\usepackage{tikz}
\usetikzlibrary{knots,hobby,calc,intersections,decorations.pathreplacing,decorations.markings,shapes.geometric,spath3,topaths}
\newcommand{\SSTGuidesPoints}[2]{}
\begin{document}
$body
\end{document}
        """.trimIndent()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val baseDir = project.basePath
                if (baseDir != null) TikzRenderer.currentBaseDir = baseDir
                TikzRenderer.pluginCacheRoot = File(PathManager.getSystemPath(), "livelatex-cache").absolutePath
                val key = "tikz-knot-preview"
                val svgFile = LatexHtmlTikz.renderTexToSvg(texDoc, key)
                ApplicationManager.getApplication().invokeLater {
                    previewBtn.isEnabled = true
                    if (svgFile != null && svgFile.exists()) {
                        val svgText = svgFile.readText()
                        val html = """
<!DOCTYPE html><html><head><meta charset="utf-8"/></head>
<body style="margin:0;background:#f5f5f5;display:flex;justify-content:center;align-items:center;min-height:100vh;">
<div style="background:white;padding:16px;box-shadow:0 2px 8px rgba(0,0,0,.1);">$svgText</div>
</body></html>
                        """.trimIndent()
                        val owner = WindowManager.getInstance().getFrame(project) as? Window
                        val dlg = JDialog(owner, "Knot Preview", Dialog.ModalityType.MODELESS)
                        dlg.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                        dlg.layout = BorderLayout()
                        val browser = JBCefBrowser()
                        browser.loadHTML(html, "about:blank")
                        dlg.add(browser.component, BorderLayout.CENTER)
                        dlg.setSize(500, 500)
                        dlg.setLocationRelativeTo(owner)
                        dlg.isVisible = true
                        dlg.toFront()
                        dlg.requestFocus()
                    } else {
                        JOptionPane.showMessageDialog(rootPanel,
                            "TikZ compilation failed. Ensure pdflatex and dvisvgm/pdf2svg are installed.",
                            "Preview", JOptionPane.ERROR_MESSAGE)
                    }
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    previewBtn.isEnabled = true
                    JOptionPane.showMessageDialog(rootPanel, "Preview error: ${t.message}", "Preview", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
    }

    override fun doOKAction() {
        // 1) Read UI into settings
        twoStrandSettings.amp     = (spAmp.value as Number).toDouble()
        twoStrandSettings.turns   = (spTurns.value as Number).toDouble()
        twoStrandSettings.samples = (spSamples.value as Number).toInt()
        twoStrandSettings.wth     = tfWth.text.trim().ifEmpty { "2.5pt" }
        twoStrandSettings.core    = tfCore.text.trim().ifEmpty { "black" }
        twoStrandSettings.mask    = tfMask.text.trim().ifEmpty { "5.0pt" }
        twoStrandSettings.clrA    = tfClrA.text.trim().ifEmpty { "black!60!black" }
        twoStrandSettings.clrB    = tfClrB.text.trim().ifEmpty { "red!70!black" }

        // 2) Persist to service
        TwoStrandSettingsService.getInstance().loadState(twoStrandSettings)

        // 3) Export body (branch) — handles knot, two-strand, or shapes-only
        resultTikz = buildCurrentExportBody()
        super.doOKAction()
    }

    private fun exportBodyTwoStrand(
        pts: List<Point>,
        amp: Double = 0.13,
        turns: Double = 4.0,
        samples: Int = 400,
        wth: String = "2.5pt",
        core: String = "black",
        mask: String = "5.0pt",
        clrA: String = "black!60!black",
        clrB: String = "red!70!black"
    ): String {
        if (pts.isEmpty()) return "% No knot points."

        // Canvas grid -> integer grid coordinates (same convention as exportBody)
        val step = STEP
        val cx = canvas.width / 2
        val cy = canvas.height / 2
        data class G(val x: Double, val y: Double)
        fun toGridQ(p: Point) = G(quantQ((p.x - cx).toDouble() / step), quantQ(-((p.y - cy).toDouble() / step)))

        // De-duplicate consecutive duplicates (including when last equals first)
        val dedup = ArrayList<Point>(pts.size)
        for (p in pts) if (dedup.isEmpty() || dedup.last() != p) dedup += p
        while (dedup.size >= 2 && dedup.last() == dedup.first()) dedup.removeAt(dedup.size - 1)
        if (dedup.size < 2) return "% Need at least 2 points."

        val gpts = dedup.map(::toGridQ)
        val n = gpts.size

        val sb = StringBuilder()

        sb.appendLine("\\begin{tikzpicture}[use Hobby shortcut, line cap=round, line join=round, scale=1.05]")
        sb.appendLine()
        sb.appendLine("% ---- controls ----")
        sb.appendLine("\\def\\Amp{${fmtQ(amp)}}")
        sb.appendLine("\\def\\Turns{${fmtQ(turns)}}        % can be non-integer; extrema logic still works")
        sb.appendLine("\\def\\Samples{$samples}")
        sb.appendLine("\\def\\Wth{$wth}")
        sb.appendLine("\\def\\Core{$core}     % set to page color for clean masking")
        sb.appendLine("\\def\\Mask{$mask}")
        sb.appendLine("\\def\\ClrA{$clrA}")
        sb.appendLine("\\def\\ClrB{$clrB}")
        sb.appendLine()
        sb.appendLine("\\tikzset{")
        sb.appendLine("  fat strand/.style={")
        sb.appendLine("    draw=#1, line width=\\Wth,")
        sb.appendLine("    double=\\Core, double distance=1.2*\\Wth")
        sb.appendLine("  }")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("% ---- coordinates ----")
        for (i in 0 until n) {
            val q = gpts[i]
            sb.appendLine("\\coordinate (P${i + 1}) at (${fmtQ(q.x)}, ${fmtQ(q.y)});")
        }
        sb.appendLine("\\def\\KPATH{([closed] P1)..${(2..n).joinToString("..") { "(P$it)" }}}")
        sb.appendLine()
        sb.appendLine("% ---- build two offset coordinate lists ----")
        sb.appendLine("\\newcommand{\\MakeStrandCoords}[5]{%")
        sb.appendLine("  \\pgfmathtruncatemacro{\\Ns}{#2}")
        sb.appendLine("  \\def\\Phase{0}\\ifx#5B\\def\\Phase{0.5}\\fi")
        sb.appendLine("  \\foreach \\i in {0,...,\\Ns}{%")
        sb.appendLine("    \\pgfmathsetmacro{\\s}{\\i/\\Ns}%")
        sb.appendLine("    \\pgfmathsetmacro{\\y}{#3*sin(360*(#4*\\s + \\Phase))}%")
        sb.appendLine("    \\path[postaction={decorate, decoration={markings,")
        sb.appendLine("      mark=at position \\s with {\\coordinate (#5\\i) at (0,\\y);}}}] #1;%")
        sb.appendLine("  }%")
        sb.appendLine("}")
        sb.appendLine("\\MakeStrandCoords{\\KPATH}{\\Samples}{\\Amp}{\\Turns}{A}")
        sb.appendLine("\\MakeStrandCoords{\\KPATH}{\\Samples}{\\Amp}{\\Turns}{B}")
        sb.appendLine()
        sb.appendLine("% ---- segment drawer with overpass mask ----")
        sb.appendLine("\\newcommand{\\drawSeg}[4]{% name(A/B), i, color, over(0/1)")
        sb.appendLine("  \\ifnum#4=1")
        sb.appendLine("    \\draw[preaction={draw=\\Core, line width=\\Mask}, fat strand=#3]")
        sb.appendLine("      (#1#2) .. (#1\\the\\numexpr#2+1\\relax);")
        sb.appendLine("  \\else")
        sb.appendLine("    \\draw[fat strand=#3]")
        sb.appendLine("      (#1#2) .. (#1\\the\\numexpr#2+1\\relax);")
        sb.appendLine("  \\fi")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("% ---- swap at offset extrema (least visible) ----")
        sb.appendLine("\\pgfmathtruncatemacro{\\Last}{\\Samples-1}")
        sb.appendLine("\\foreach \\k in {0,...,\\Last}{")
        sb.appendLine("  \\pgfmathsetmacro{\\s}{\\k/\\Samples}")
        sb.appendLine("  % block index changes at s = (1/4 + n/2)/Turns  <=> when 2*Turns*s crosses half-integers")
        sb.appendLine("  \\pgfmathtruncatemacro{\\blk}{floor(2*\\Turns*\\s + 0.5)}")
        sb.appendLine("  \\ifodd\\blk")
        sb.appendLine("    % B over, A under")
        sb.appendLine("    \\drawSeg{A}{\\k}{\\ClrA}{0}")
        sb.appendLine("    \\drawSeg{B}{\\k}{\\ClrB}{1}")
        sb.appendLine("  \\else")
        sb.appendLine("    % A over, B under")
        sb.appendLine("    \\drawSeg{B}{\\k}{\\ClrB}{0}")
        sb.appendLine("    \\drawSeg{A}{\\k}{\\ClrA}{1}")
        sb.appendLine("  \\fi")
        sb.appendLine("}")
        sb.appendLine()
        appendShapesToTikz(sb)
        sb.appendLine("\\end{tikzpicture}")

        return sb.toString()
    }

    private fun exportBody(
        pts: List<Point>,
        showPoints: Boolean,
        flipCrossings: String,
        knotColor: String? = null,        // optional: e.g. "red" or "blue!60"
        wrapInFigure: Boolean = false     // optional: wrap with \begin{figure}...\end{figure}
    ): String {
        if (pts.isEmpty()) return "% No knot points."

        // Canvas grid -> quantized grid coordinates
        val step = STEP
        val cx = canvas.width / 2
        val cy = canvas.height / 2
        data class G(val x: Double, val y: Double)
        fun toGridQ(p: Point) = G(quantQ((p.x - cx).toDouble() / step), quantQ(-((p.y - cy).toDouble() / step)))

        // De-duplicate consecutive duplicates (including when last equals first)
        val dedup = ArrayList<Point>(pts.size)
        for (p in pts) if (dedup.isEmpty() || dedup.last() != p) dedup += p
        while (dedup.size >= 2 && dedup.last() == dedup.first()) dedup.removeAt(dedup.size - 1)
        val gpts = dedup.map(::toGridQ)
        val n = gpts.size

        val sb = StringBuilder()

        if (wrapInFigure) {
            sb.append("\\begin{figure}[t]\n")
            sb.append("    \\centering\n")
        }
        sb.append("    \\begin{tikzpicture}[use Hobby shortcut]\n")

        // Optional color for the strand
        if (!knotColor.isNullOrBlank()) {
            sb.append("    \\tikzset{knot diagram/every strand/.append style={draw=$knotColor}}\n")
        }

        // Coordinates P1..Pn only ([closed] connects Pn back to P1)
        for (i in 0 until n) {
            val q = gpts[i]
            sb.append("    \\coordinate (P${i + 1}) at (${fmtQ(q.x)}, ${fmtQ(q.y)});\n")
        }
        sb.append("\n")

        // Knot options (include flip crossings if provided)
        val opts = buildList {
            add("consider self intersections")
            add("clip width=5pt, clip radius=3pt")
            add("ignore endpoint intersections=false")
            if (flipCrossings.isNotBlank()) {
                add("flip crossing/.list={$flipCrossings}")
            } else {
                // If empty, add all even numbers up to n
                val evenList = (2..n step 2).joinToString(",")
                add("flip crossing/.list={$evenList}")
            }
            add("% ----draft mode=crossings % uncomment to see numbers")
        }
        sb.append(
            "    \\begin{knot}[\n" +
                    "        " + opts.joinToString(",\n        ") + "\n" +
                    "    ]\n"
        )

        // Strand: ([closed] P1)..(P2).. ... ..(Pn)
        val names = (1..n).map { "P$it" }
        val tail = names.drop(1).joinToString("..") { "($it)" }
        sb.append("    \\strand\n")
        sb.append("    ([closed] ${names.first()})..$tail;\n")

        sb.append("    \\end{knot}\n")

        if (showPoints) {
            sb.append("    \\SSTGuidesPoints{P}{$n}\n")
        }

        appendShapesToTikz(sb)

        sb.append("    \\end{tikzpicture}\n")
        if (wrapInFigure) sb.append("\\end{figure}\n")

        return sb.toString().trimEnd()
    }

    /** Appends TikZ \draw, \fill, \node commands for shapes (lines, circles, rectangles, dots, labels). */
    private fun appendShapesToTikz(sb: StringBuilder) {
        if (shapes.isEmpty()) return
        val cx = canvas.width / 2
        val cy = canvas.height / 2
        val step = STEP
        fun toTikz(p: Point): String {
            val x = quantQ((p.x - cx).toDouble() / step)
            val y = quantQ(-(p.y - cy).toDouble() / step)
            return "(${fmtQ(x)}, ${fmtQ(y)})"
        }
        for (s in shapes) {
            when (s) {
                is LineSeg -> sb.append("    \\draw ").append(toTikz(s.a)).append(" -- ").append(toTikz(s.b)).append(";\n")
                is Circ -> sb.append("    \\draw ").append(toTikz(s.c)).append(" circle (${fmtQ(s.rUnits)});\n")
                is Rect -> sb.append("    \\draw ").append(toTikz(s.a)).append(" rectangle ").append(toTikz(s.b)).append(";\n")
                is Dot -> sb.append("    \\fill ").append(toTikz(s.p)).append(" circle (2pt);\n")
                is Label -> sb.append("    \\node at ").append(toTikz(s.p)).append(" {").append(latexEscape(s.text)).append("};\n")
            }
        }
    }

    /** Exports a tikzpicture containing only shapes (no knot). Used when knotPts is empty but shapes exist. */
    private fun exportShapesOnly(): String {
        if (shapes.isEmpty()) return "% No content."
        val sb = StringBuilder()
        sb.append("    \\begin{tikzpicture}[use Hobby shortcut]\n")
        appendShapesToTikz(sb)
        sb.append("    \\end{tikzpicture}\n")
        return sb.toString().trimEnd()
    }

    // ---------- math / utils ----------
    private enum class EditMode { ADD, MOVE, DELETE }
    private enum class Tool { KNOT, LINE, CIRCLE, RECTANGLE, DOT, TEXT }

    // helper to print clean numbers like 0.13 or 4
    private fun formatQ(x: Double): String {
        val xi = x.toInt()
        return if (abs(x - xi) < 1e-9) xi.toString() else "%.6g".format(x)
    }

    private fun snap(p: Point): Point {
        val cx = canvas.width / 2; val cy = canvas.height / 2
        val sx = round((p.x - cx).toDouble() / SUB).toInt() * SUB
        val sy = round((p.y - cy).toDouble() / SUB).toInt() * SUB
        return Point(cx + sx, cy + sy)
    }

    private fun fromUnits(u: Double): Int = (u * STEP).roundToInt()
    private fun toUnits(px: Int): Double = px.toDouble() / STEP

    private fun quantQ(u: Double): Double = round(u * 4.0) / 4.0                    // 0.25
    private fun fmtQ(u: Double): String = if (abs(u - u.roundToInt()) < 1e-9) "%d".format(u.roundToInt()) else "%.2f".format(u)

    private fun toGrid(p: Point): Pair<Double, Double> {
        val cx = canvas.width / 2; val cy = canvas.height / 2
        return Pair(toUnits(p.x - cx), -toUnits(p.y - cy))
    }

    private fun nearest(list: List<Point>, raw: Point): Int? {
        if (list.isEmpty()) return null
        var best = -1; var bestD = Int.MAX_VALUE
        for (i in list.indices) {
            val d2 = list[i].distanceSq(raw).toInt()
            if (d2 < bestD) { bestD = d2; best = i }
        }
        return if (best >= 0 && bestD <= PICK_R * PICK_R * 4) best else null
    }

    private fun pickShape(raw: Point): Int? {
        val tol2 = PICK_R * PICK_R
        for (i in shapes.indices.reversed()) {
            if (shapes[i].hit(raw, tol2) != null) return i
        }
        return null
    }

    // Returns index of nearest knot point within MOVE_TOL2, else null
    private fun nearestKnot(raw: Point): Int? {
        if (knotPts.isEmpty()) return null
        var bestIdx = -1
        var best = Int.MAX_VALUE
        for (i in knotPts.indices) {
            val d2 = knotPts[i].distanceSq(raw).toInt()
            if (d2 < best) { best = d2; bestIdx = i }
        }
        return if (best <= MOVE_TOL2) bestIdx else null
    }

    /** Returns segment index i (between knotPts[i] and knotPts[i+1]) if click is on that segment, else null. */
    private fun segmentAt(raw: Point): Int? {
        if (knotPts.size < 2) return null
        val tol2 = PICK_R * PICK_R
        var bestIdx = -1
        var bestD2 = Int.MAX_VALUE
        for (i in 0 until knotPts.size - 1) {
            val d2 = segmentDist2(raw, knotPts[i], knotPts[i + 1])
            if (d2 <= tol2 && d2 < bestD2) {
                bestD2 = d2
                bestIdx = i
            }
        }
        return if (bestIdx >= 0) bestIdx else null
    }

    // Add point if not duplicate of last
    private fun addKnotIfNotDuplicate(p: Point) {
        val last = knotPts.lastOrNull()
        if (last == null || last != p) {
            knotPts += p
            markDirty()
        }
    }


    private fun latexEscape(s: String): String =
        s.replace("\\", "\\textbackslash{}")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("%", "\\%")
            .replace("#", "\\#")
            .replace("_", "\\_")
            .replace("&", "\\&")
            .replace("^", "\\^{}")
            .replace("~", "\\~{}")

    private fun markDirty() { dirty = true }


    // Translate all geometry by dx, dy (canvas center shift)
    private fun translateAll(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        // knot points
        for (i in 0 until knotPts.size) {
            val p = knotPts[i]
            knotPts[i] = Point(p.x + dx, p.y + dy)
        }
        // shapes
        for (s in shapes) {
            when (s) {
                is Dot -> s.p = Point(s.p.x + dx, s.p.y + dy)
                is Label -> s.p = Point(s.p.x + dx, s.p.y + dy)
                is LineSeg -> {
                    s.a = Point(s.a.x + dx, s.a.y + dy)
                    s.b = Point(s.b.x + dx, s.b.y + dy)
                }
                is Rect -> {
                    s.a = Point(s.a.x + dx, s.a.y + dy)
                    s.b = Point(s.b.x + dx, s.b.y + dy)
                }
                is Circ -> s.c = Point(s.c.x + dx, s.c.y + dy)
            }
        }
        // previews
        tmpA = tmpA?.let { Point(it.x + dx, it.y + dy) }
        linePreview = linePreview?.let { LineSeg(Point(it.a.x + dx, it.a.y + dy), Point(it.b.x + dx, it.b.y + dy)) }
        rectPreview = rectPreview?.let { Rect(Point(it.a.x + dx, it.a.y + dy), Point(it.b.x + dx, it.b.y + dy)) }
        circlePreview = circlePreview?.let { Circ(Point(it.c.x + dx, it.c.y + dy), it.rUnits) }
        textEditPending = textEditPending?.let { Point(it.x + dx, it.y + dy) }
        textEditField?.let { tf ->
            tf.setBounds(tf.x + dx, tf.y + dy, tf.width, tf.height)
        }
        canvas.repaint()
    }

    private fun showInlineTextEditor(p: Point) {
        cancelInlineText()
        val tf = JTextField("", 16).apply {
            setBounds(p.x, p.y, 180, 24)
            font = Font(font.name, font.style, 14)
            addActionListener { commitInlineText() }
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) {
                    if (!e.isTemporary) commitInlineText()
                }
            })
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) cancelInlineText()
                }
            })
        }
        textEditField = tf
        textEditPending = p
        canvas.add(tf)
        tf.requestFocusInWindow()
        canvas.revalidate()
        canvas.repaint()
    }

    private fun commitInlineText() {
        val pos = textEditPending
        val txt = textEditField?.text?.trim()?.takeIf { it.isNotEmpty() }
        textEditField?.let { canvas.remove(it) }
        textEditField = null
        textEditPending = null
        canvas.revalidate()
        canvas.repaint()
        if (pos != null && txt != null) {
            shapes += Label(pos, txt)
            markDirty()
            canvas.repaint()
        }
    }

    private fun cancelInlineText() {
        textEditField?.let { canvas.remove(it) }
        textEditField = null
        textEditPending = null
        canvas.revalidate()
        canvas.repaint()
    }

    private fun showKnotOptionsModal() {
        val owner = rootPanel.topLevelAncestor as? Window ?: return
        val dlg = JDialog(owner, "Knot options", Dialog.ModalityType.APPLICATION_MODAL)
        dlg.layout = BorderLayout()
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        val twistRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        twistRow.add(twoStrandBox)
        twistRow.add(JLabel("Amp")); twistRow.add(spAmp)
        twistRow.add(JLabel("Twists")); twistRow.add(spTurns)
        twistRow.add(JLabel("Samples")); twistRow.add(spSamples)
        twistRow.add(JLabel("Width")); twistRow.add(tfWth)
        twistRow.add(JLabel("Core")); twistRow.add(tfCore)
        twistRow.add(JLabel("Mask")); twistRow.add(tfMask)
        twistRow.add(JLabel("Color A")); twistRow.add(tfClrA)
        twistRow.add(JLabel("Color B")); twistRow.add(tfClrB)

        val knotRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        knotRow.add(JLabel("Knot color:"))
        knotRow.add(knotColor)
        knotRow.add(Box.createHorizontalStrut(12))
        knotRow.add(JLabel("Flip crossings:"))
        knotRow.add(flipField)
        knotRow.add(Box.createHorizontalStrut(12))
        knotRow.add(showPointsBox)
        knotRow.add(Box.createHorizontalStrut(12))
        knotRow.add(JLabel("Opacity:"))
        knotRow.add(bgOpacitySpinner)

        content.add(twistRow)
        content.add(knotRow)

        val okBtn = JButton("Close")
        okBtn.addActionListener { dlg.dispose() }
        val south = JPanel(FlowLayout(FlowLayout.RIGHT))
        south.add(okBtn)

        dlg.add(content, BorderLayout.CENTER)
        dlg.add(south, BorderLayout.SOUTH)
        dlg.pack()
        dlg.setLocationRelativeTo(rootPanel)
        dlg.isVisible = true
    }

    private fun updateFlipCrossingsForNewPoint() {
        val n = knotPts.size
        if (n < 2) return
        val evenList = (2..(n + 2) step 2).joinToString(",")
        flipField.text = evenList
    }

    private fun loadBackgroundImage() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Select background image for tracing"
        }
        if (chooser.showOpenDialog(rootPanel) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            try {
                val img = ImageIO.read(file)
                if (img != null) {
                    backgroundImage = img
                    canvas.repaint()
                } else {
                    JOptionPane.showMessageDialog(rootPanel, "Could not load image.", "Load Background", JOptionPane.WARNING_MESSAGE)
                }
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(rootPanel, "Error loading image: ${e.message}", "Load Background", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun clearBackgroundImage() {
        backgroundImage = null
        canvas.repaint()
    }

    private fun saveSetupToFile() {
        val chooser = JFileChooser()
        if (chooser.showSaveDialog(rootPanel) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            val props = java.util.Properties()
            // Save knot points
            props["knot.count"] = knotPts.size.toString()
            knotPts.forEachIndexed { i, p ->
                props["knot.$i.x"] = p.x.toString()
                props["knot.$i.y"] = p.y.toString()
            }
            // Save settings
            props["amp"] = spAmp.value.toString()
            props["turns"] = spTurns.value.toString()
            props["samples"] = spSamples.value.toString()
            props["wth"] = tfWth.text
            props["core"] = tfCore.text
            props["mask"] = tfMask.text
            props["clrA"] = tfClrA.text
            props["clrB"] = tfClrB.text
            props["flip"] = flipField.text
            props["knotColor"] = knotColor.selectedItem?.toString() ?: ""
            props["showPoints"] = showPointsBox.isSelected.toString()
            props["twin"] = twinBox.isSelected.toString()
            props["twinOffset"] = twinOffset.value.toString()
            props.store(java.io.FileOutputStream(file), "Tikz Setup")
        }
    }

    private fun loadSetupFromFile() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(rootPanel) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            val props = java.util.Properties()
            props.load(java.io.FileInputStream(file))
            // Load knot points
            val count = props.getProperty("knot.count")?.toIntOrNull() ?: 0
            knotPts.clear()
            for (i in 0 until count) {
                val x = props.getProperty("knot.$i.x")?.toIntOrNull() ?: 0
                val y = props.getProperty("knot.$i.y")?.toIntOrNull() ?: 0
                knotPts += Point(x, y)
            }
            // Load settings
            spAmp.value = props.getProperty("amp")?.toDoubleOrNull() ?: spAmp.value
            spTurns.value = props.getProperty("turns")?.toDoubleOrNull() ?: spTurns.value
            spSamples.value = props.getProperty("samples")?.toIntOrNull() ?: spSamples.value
            tfWth.text = props.getProperty("wth") ?: tfWth.text
            tfCore.text = props.getProperty("core") ?: tfCore.text
            tfMask.text = props.getProperty("mask") ?: tfMask.text
            tfClrA.text = props.getProperty("clrA") ?: tfClrA.text
            tfClrB.text = props.getProperty("clrB") ?: tfClrB.text
            flipField.text = props.getProperty("flip") ?: flipField.text
            knotColor.selectedItem = props.getProperty("knotColor") ?: knotColor.selectedItem
            showPointsBox.isSelected = props.getProperty("showPoints")?.toBoolean() ?: showPointsBox.isSelected
            twinBox.isSelected = props.getProperty("twin")?.toBoolean() ?: twinBox.isSelected
            twinOffset.value = props.getProperty("twinOffset")?.toDoubleOrNull() ?: twinOffset.value
            canvas.repaint()
        }
    }
}