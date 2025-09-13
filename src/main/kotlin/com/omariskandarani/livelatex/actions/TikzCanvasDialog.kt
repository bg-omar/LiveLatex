package com.omariskandarani.livelatex.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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


class TikzCanvasDialog(
    private val project: Project,
    private val initialTikz: String? = null
) : DialogWrapper(project, true) {

    private companion object {
        const val STEP = 100          // 1 unit
        const val SUB = STEP / 4      // 0.25 unit
        private const val GRID_UNITS = 4
        private const val PICK_R = 10
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


    // Knot points are edited separately (tool=KNOT)
    private val knotPts = mutableListOf<Point>()
    // export buffer the action reads after OK
    var resultTikz: String? = null
        private set


    // --------- services / state ----------
    private val store = project.getService(TikzKnotStore::class.java)
    private val shapes = mutableListOf<Shape>()
    private var mode = EditMode.ADD
    private var tool = Tool.KNOT
    private var dirty = false

    // in-progress
    private var tmpA: Point? = null   // for line/circle first click
    private var circlePreview: Circ? = null
    private var linePreview: LineSeg? = null

    // dragging
    private data class DragCtx(val idx: Int, val hit: Shape.Hit, val start: Point, val orig: Any)
    private var drag: DragCtx? = null
    private var dragKnotIndex: Int = -1
    private var dragKind: String = ""  // for circle radius, etc.

    // --------- UI controls ----------
    private lateinit var rootPanel: JPanel

    private val titleCombo = JComboBox(DefaultComboBoxModel(store.names().toTypedArray())).apply {
        isEditable = true
        preferredSize = Dimension(220, preferredSize.height)
        toolTipText = "Saved knots (MRU, max 9). Type a new title to save."
    }
    private val saveBtn = JButton("Save")
    private val loadBtn = JButton("Load")
    private val deleteBtn = JButton("Delete")
    private val newBtn = JButton("New")

    private val flipField = JTextField().apply { columns = 14 }
    private val showPointsBox = JCheckBox("Guides", true)

    private val knotColor = JComboBox(arrayOf("black","red","blue","green","teal","orange","purple","gray")).apply {
        selectedItem = "black"
        toolTipText = "Knot color"
        preferredSize = Dimension(100, preferredSize.height)
    }
    private val textDefault = JTextField("Text").apply {
        columns = 12
        toolTipText = "Default text for Text tool"
    }

    private val toolGroup = ButtonGroup()
    private val rbKnot = JRadioButton("Knot", true)
    private val rbLine = JRadioButton("Line")
    private val rbCircle = JRadioButton("Circle")
    private val rbDot = JRadioButton("Dot")
    private val rbText = JRadioButton("Text")

    private val modeGroup = ButtonGroup()
    private val rbAdd = JRadioButton("Add", true)
    private val rbMove = JRadioButton("Move")
    private val rbDel = JRadioButton("Delete")

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
        init {
            background = JBColor(Color(0xF8F9FB), Color(0x23272E))
            preferredSize = Dimension(1200, 800)
            border = BorderFactory.createLineBorder(JBColor(Color(0xD0D7DE), Color(0x23272E)), 1)
            val ma = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = onPress(e)
                override fun mouseDragged(e: MouseEvent) = onDrag(e)
                override fun mouseReleased(e: MouseEvent) = onRelease(e)
            }
            addMouseListener(ma); addMouseMotionListener(ma)
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val cx = width / 2; val cy = height / 2

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
        }
    }

    init {
        title = "TikZ Knot"
        initUI()
        init()
        setSize(980, 680)
        initialTikz?.let { importCoordinates(it) }
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
        listOf(rbKnot, rbLine, rbCircle, rbDot, rbText).forEach { toolGroup.add(it) }
        rbKnot.addActionListener { tool = Tool.KNOT }
        rbLine.addActionListener { tool = Tool.LINE }
        rbCircle.addActionListener { tool = Tool.CIRCLE }
        rbDot.addActionListener { tool = Tool.DOT }
        rbText.addActionListener { tool = Tool.TEXT }

        val toolsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Tool:"))
            add(rbKnot); add(rbLine); add(rbCircle); add(rbDot); add(rbText)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Knot color:")); add(knotColor)
            add(Box.createHorizontalStrut(12))
            add(JLabel("flip crossings:")); add(flipField)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Text:")); add(textDefault)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Guides:")); add(showPointsBox)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Twin:")); add(twinBox); add(twinOffset)
        }

        // mode row
        listOf(rbAdd, rbMove, rbDel).forEach { modeGroup.add(it) }
        rbAdd.addActionListener { mode = EditMode.ADD }
        rbMove.addActionListener { mode = EditMode.MOVE }
        rbDel.addActionListener { mode = EditMode.DELETE }
        val modeRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("Edit:")); add(rbAdd); add(rbMove); add(rbDel)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Title:")); add(titleCombo)
            add(saveBtn); add(loadBtn); add(newBtn); add(deleteBtn)
        }

        saveBtn.addActionListener { doSave(true) }
        loadBtn.addActionListener { doLoadSelected() }
        deleteBtn.addActionListener { doDeleteSelected() }
        newBtn.addActionListener { doNew() }

        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.add(toolsRow)
        header.add(modeRow)

        rootPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            add(header, BorderLayout.NORTH)
            add(JScrollPane(canvas), BorderLayout.CENTER)
        }

        setOKButtonText("Export & Insert")
        setCancelButtonText("Cancel")
    }

    override fun createCenterPanel(): JComponent = rootPanel

    // ---------- Mouse handling ----------
    private fun onPress(e: MouseEvent) {
        val p = snap(e.point)
        when (tool) {
            Tool.KNOT -> when (mode) {
                EditMode.ADD -> { knotPts += p; markDirty() }
                EditMode.DELETE -> {
                    val i = nearest(knotPts, e.point) ?: return
                    knotPts.removeAt(i); markDirty()
                }
                EditMode.MOVE -> {
                    dragKnotIndex = nearest(knotPts, e.point) ?: -1
                }
            }
            Tool.DOT -> when (mode) {
                EditMode.ADD -> { shapes += Dot(p); markDirty() }
                EditMode.DELETE, EditMode.MOVE -> startShapeDrag(e, listOf("center"))
            }
            Tool.TEXT -> when (mode) {
                EditMode.ADD -> {
                    val text = JOptionPane.showInputDialog(rootPanel, "Text:", textDefault.text) ?: return
                    shapes += Label(p, text); markDirty()
                }
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
        }
    }

    private fun onDrag(e: MouseEvent) {
        val p = snap(e.point)
        when (tool) {
            Tool.KNOT -> if (mode == EditMode.MOVE && dragKnotIndex >= 0) {
                knotPts[dragKnotIndex] = p; markDirty()
            }
            Tool.LINE -> if (mode == EditMode.ADD) {
                linePreview?.let { it.b = p; canvas.repaint() }
            } else if (mode == EditMode.MOVE) {
                handleShapeDrag(p)
            }
            Tool.CIRCLE -> if (mode == EditMode.ADD) {
                circlePreview?.let {
                    it.rUnits = quantQ(p.distance(it.c) / STEP)
                    canvas.repaint()
                }
            } else if (mode == EditMode.MOVE) {
                handleShapeDrag(p)
            }
            Tool.DOT, Tool.TEXT -> if (mode == EditMode.MOVE) {
                handleShapeDrag(p)
            }
        }
    }

    private fun onRelease(e: MouseEvent) {
        val p = snap(e.point)
        when (tool) {
            Tool.LINE -> if (mode == EditMode.ADD) {
                linePreview?.let { shapes += LineSeg(it.a, p) }
                linePreview = null; tmpA = null; markDirty()
            } else drag = null
            Tool.CIRCLE -> if (mode == EditMode.ADD) {
                circlePreview?.let {
                    shapes += Circ(it.c, max(0.0, quantQ(p.distance(it.c) / STEP)))
                }
                circlePreview = null; tmpA = null; markDirty()
            } else drag = null
            Tool.KNOT -> dragKnotIndex = -1
            Tool.DOT, Tool.TEXT -> drag = null
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
        val names = store.names().toTypedArray()
        titleCombo.model = DefaultComboBoxModel(names)
        if (select != null && names.contains(select)) titleCombo.selectedItem = select
    }
    private fun autoTitle(): String {
        val base = "Untitled"
        val names = store.names().toSet()
        if (base !in names) return base
        var i = 2; while ("$base $i" in names) i++
        return "$base $i"
    }
    private fun doSave(explicit: Boolean) {
        var title = currentTitle()
        if (title.isBlank()) { title = autoTitle(); titleCombo.editor.item = title }
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
        val title = currentTitle()
        if (title.isBlank()) { JOptionPane.showMessageDialog(rootPanel, "Choose a title to load."); return }
        val pts = store.load(title) ?: run {
            JOptionPane.showMessageDialog(rootPanel, "No saved knot named \"$title\"."); return
        }
        knotPts.clear(); knotPts.addAll(pts.map { Point(it) }); dirty = false; canvas.repaint()
    }
    private fun doDeleteSelected() {
        val title = currentTitle(); if (title.isBlank()) return
        if (JOptionPane.showConfirmDialog(rootPanel, "Delete \"$title\"?", "Delete",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            store.delete(title); refreshTitlesCombo(); titleCombo.editor.item = ""
        }
    }
    private fun doNew() {
        maybeAutoSave()
        titleCombo.editor.item = ""; knotPts.clear(); dirty = false; canvas.repaint()
    }

    // ---------- import/export ----------
    private fun importCoordinates(tikz: String) {
        val rx = Regex("""\\coordinate\s*\(P(\d+)\)\s*at\s*\(\s*([+-]?\d+(?:\.\d+)?)\s*,\s*([+-]?\d+(?:\.\d+)?)\s*\)\s*;""")
        val pairs = rx.findAll(tikz)
            .map { it.groupValues[1].toInt() to Pair(it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
        if (pairs.isEmpty()) return
        val cx = canvas.width / 2; val cy = canvas.height / 2
        knotPts.clear()
        knotPts.addAll(pairs.map { (x, y) -> Point(cx + fromUnits(x), cy - fromUnits(y)) })
        dirty = true; canvas.repaint()
    }

    override fun doOKAction() {
        resultTikz = exportBody(
            pts = knotPts, // see #3 below
            showPoints = showPointsBox.isSelected,
            flipCrossings = flipField.text.trim(),
            knotColor = null,         // or e.g. colorField.text.takeIf { it.isNotBlank() }
            wrapInFigure = false
        )
        super.doOKAction()
    }

    private fun exportBody(
        pts: List<Point>,
        showPoints: Boolean,
        flipCrossings: String,
        knotColor: String? = null,        // optional: e.g. "red" or "blue!60"
        wrapInFigure: Boolean = false     // optional: wrap with \begin{figure}...\end{figure}
    ): String {
        if (pts.isEmpty()) return "% No knot points."

        // Canvas grid -> integer grid coordinates
        val step = STEP
        val cx = canvas.width / 2
        val cy = canvas.height / 2
        data class G(val x: Int, val y: Int)
        fun toGrid(p: Point) = G((p.x - cx) / step, -((p.y - cy) / step))

        // De-duplicate consecutive duplicates to avoid ...P5..P5...
        val dedup = ArrayList<Point>(pts.size)
        for (p in pts) if (dedup.isEmpty() || dedup.last() != p) dedup += p
        val gpts = dedup.map(::toGrid)
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

        // Coordinates P1..Pn and close with P{n+1}=P1
        for (i in 0 until n) {
            val q = gpts[i]
            sb.append("    \\coordinate (P${i + 1}) at (${q.x}, ${q.y});\n")
        }
        val q1 = gpts.first()
        sb.append("    \\coordinate (P${n + 1}) at (${q1.x}, ${q1.y}); % = P1\n\n")

        // Knot options (include flip crossings if provided)
        val opts = buildList {
            add("consider self intersections")
            add("clip width=5pt, clip radius=3pt")
            add("ignore endpoint intersections=false")
            if (flipCrossings.isNotBlank()) add("flip crossing/.list={$flipCrossings}")
            add("draft mode=crossings % uncomment to see numbers")
        }
        sb.append(
            "    \\begin{knot}[\n" +
                    "        " + opts.joinToString(",\n        ") + "\n" +
                    "    ]\n"
        )

        // Strand: ([closed] P1)..(P2).. ... ..(Pn)..(P{n+1});
        val names = (1..(n + 1)).map { "P$it" }
        val tail = names.drop(1).joinToString("..") { "($it)" }
        sb.append("    \\strand\n")
        sb.append("    ([closed] ${names.first()})..$tail;\n")

        // ✅ Always close the environment
        sb.append("    \\end{knot}\n")

        // Optional guides — use n+1 because we emitted P{n+1}=P1
        if (showPoints) {
            sb.append("    \\SSTGuidesPoints{P}{${n + 1}}\n")
        }

        // TODO (optional): append exported primitives (labels, dots, lines, circles) here
        // appendPrimitives(sb)

        sb.append("    \\end{tikzpicture}\n")
        if (wrapInFigure) sb.append("\\end{figure}\n")

        return sb.toString().trimEnd()
    }


    // ---------- math / utils ----------
    private enum class EditMode { ADD, MOVE, DELETE }
    private enum class Tool { KNOT, LINE, CIRCLE, DOT, TEXT }

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

    // tiny extension helpers
    private fun Point.distance(o: Point): Double {
        val dx = (x - o.x).toDouble(); val dy = (y - o.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }
}
