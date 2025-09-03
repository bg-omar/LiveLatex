package com.omariskandarani.livelatex.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.omariskandarani.livelatex.tables.*
import java.awt.*
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

class TableWizardDialog(
    project: Project?,
    private val seedDataFromSelection: List<List<String>> = emptyList()
) : DialogWrapper(project, true) {

    private val rowsSpinner = JSpinner(SpinnerNumberModel( if (seedDataFromSelection.isNotEmpty()) seedDataFromSelection.size else 3, 1, 999, 1))
    private val colsSpinner = JSpinner(SpinnerNumberModel( if (seedDataFromSelection.isNotEmpty()) seedDataFromSelection.maxOf { it.size } else 3, 1, 20, 1))
    private val headerSpinner = JSpinner(SpinnerNumberModel(1, 0, 999, 1))
    private val placementField = JTextField("htbp")
    private val captionField = JTextField("")
    private val labelField = JTextField("")
    private val booktabsCheck = JCheckBox("Booktabs (top/mid/bottomrule)", true)
    private val tableEnvCheck = JCheckBox("Wrap in \\begin{table} ...", true)
    private val outerRulesCheck = JCheckBox("Add outer vertical rules (| ... |)", false)

    private val importBtn = JButton("Import selection")
    private val previewArea = JTextArea(18, 80).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        lineWrap = false
        isEditable = false
    }

    private val colModel = ColumnSpecModel()
    private val colTable = JTable(colModel).apply {
        setFillsViewportHeight(true)
        rowHeight = 24
        preferredScrollableViewportSize = Dimension(520, 140)
        // Render Alignment as combo
        val combo = JComboBox(arrayOf("l", "c", "r", "p{width}"))
        columnModel.getColumn(0).cellEditor = DefaultCellEditor(combo)
    }

    // Data that will be used to generate the table (either imported or placeholder)
    private var currentData: List<List<String>> = seedOrDefault()

    init {
        title = "Generate LaTeX Table"
        init()
        updateColModelToSpinner()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(10, 10))

        // Top: basic settings
        val top = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        var y = 0
        fun row(label: String, comp: JComponent, weightx: Double = 1.0) {
            c.gridx = 0; c.gridy = y; c.weightx = 0.0
            top.add(JLabel(label), c)
            c.gridx = 1; c.gridy = y; c.weightx = weightx
            top.add(comp, c)
            y++
        }

        row("Rows:", rowsSpinner)
        row("Cols:", colsSpinner)
        row("Header rows:", headerSpinner)
        row("Placement:", placementField)
        row("Caption:", captionField)
        row("Label:", labelField)
        row("", booktabsCheck, 0.0)
        row("", tableEnvCheck, 0.0)
        row("", outerRulesCheck, 0.0)

        // Middle: columns spec editor + import button
        val mid = JPanel(BorderLayout(6, 6))
        mid.border = BorderFactory.createTitledBorder("Columns")
        mid.add(JScrollPane(colTable), BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6))
        buttons.add(importBtn)
        mid.add(buttons, BorderLayout.SOUTH)

        // Bottom: preview
        val bottom = JPanel(BorderLayout())
        bottom.border = BorderFactory.createTitledBorder("Live LaTeX Preview")
        bottom.add(JScrollPane(previewArea), BorderLayout.CENTER)

        // Assemble
        val center = JPanel(BorderLayout(10, 10))
        center.add(top, BorderLayout.NORTH)
        center.add(mid, BorderLayout.CENTER)
        center.add(bottom, BorderLayout.SOUTH)

        root.add(center, BorderLayout.CENTER)
        wireEvents()
        return root
    }

    private fun wireEvents() {
        fun regen() = updatePreview()

        val changeL: (ChangeEvent) -> Unit = { regen() }
        val docL: (DocumentEvent) -> Unit = { regen() }
        val itemL: (ItemEvent) -> Unit = { regen() }

        rowsSpinner.addChangeListener {
            syncHeaderMax()
            updateColModelToSpinner()
            currentData = ensureDataSize(currentData, rows(), cols())
            regen()
        }
        colsSpinner.addChangeListener {
            updateColModelToSpinner()
            currentData = ensureDataSize(currentData, rows(), cols())
            regen()
        }
        headerSpinner.addChangeListener(changeL)
        placementField.document.addDocumentListener(simpleDocListener(docL))
        captionField.document.addDocumentListener(simpleDocListener(docL))
        labelField.document.addDocumentListener(simpleDocListener(docL))
        booktabsCheck.addItemListener(itemL)
        tableEnvCheck.addItemListener(itemL)
        outerRulesCheck.addItemListener(itemL)

        importBtn.addActionListener {
            // keep current rows/cols but replace data if importable
            // Caller should pass selection in constructor; for convenience we allow re-seed here:
            if (seedDataFromSelection.isNotEmpty()) {
                currentData = seedOrDefault()
                rowsSpinner.value = currentData.size
                colsSpinner.value = currentData.maxOf { it.size }
                updateColModelToSpinner()
                syncHeaderMax()
                regen()
            } else {
                JOptionPane.showMessageDialog(contentPanel,
                    "No selection was provided to this dialog.\nInvoke the action with a selection to import.",
                    "No Selection", JOptionPane.INFORMATION_MESSAGE)
            }
        }
    }

    private fun rows() = (rowsSpinner.value as Number).toInt()
    private fun cols() = (colsSpinner.value as Number).toInt()

    private fun syncHeaderMax() {
        val r = rows()
        val model = headerSpinner.model as SpinnerNumberModel
        val current = (headerSpinner.value as Number).toInt().coerceAtMost(r)
        model.maximum = r
        headerSpinner.value = current
    }

    private fun updateColModelToSpinner() {
        colModel.setColumnCount(cols())
    }

    private fun ensureDataSize(data: List<List<String>>, r: Int, c: Int): List<List<String>> {
        if (data.isEmpty()) return placeholderData(r, c)
        val out = MutableList(r) { i ->
            val row = data.getOrNull(i).orEmpty()
            MutableList(c) { j -> row.getOrNull(j) ?: "Row${i+1} Col${j+1}" }
        }
        return out
    }

    private fun placeholderData(r: Int, c: Int): List<List<String>> =
        List(r) { i ->
            List(c) { j ->
                if (i == 0) "Header ${j+1}" else "Row${i} Col${j+1}"
            }
        }

    private fun seedOrDefault(): List<List<String>> {
        if (seedDataFromSelection.isNotEmpty()) return seedDataFromSelection
        return placeholderData(rows(), cols())
    }

    private fun gatherOptions(): TableOptions {
        val columns = (0 until cols()).map { idx ->
            val spec = colModel.getSpec(idx)
            when (spec.align) {
                ColAlign.P -> Col(ColAlign.P, width = spec.width, verticalLeft = spec.vLeft, verticalRight = spec.vRight)
                else       -> Col(spec.align, verticalLeft = spec.vLeft, verticalRight = spec.vRight)
            }
        }
        return TableOptions(
            withTableEnv = tableEnvCheck.isSelected,
            placement = placementField.text.ifBlank { "htbp" },
            caption = captionField.text.takeIf { it.isNotBlank() },
            label = labelField.text.takeIf { it.isNotBlank() },
            booktabs = booktabsCheck.isSelected,
            headerRows = (headerSpinner.value as Number).toInt().coerceIn(0, rows()),
            addOuterRules = outerRulesCheck.isSelected,
            cols = columns
        )
    }

    private fun updatePreview() {
        val opts = gatherOptions()
        val r = rows()
        val c = cols()
        currentData = ensureDataSize(currentData, r, c)
        val latex = generateLatexTable(currentData, opts)
        previewArea.text = latex
        previewArea.caretPosition = 0
    }

    fun resultLatex(): String = previewArea.text

    /* ---------------------- helpers ---------------------- */

    private fun simpleDocListener(onChange: (DocumentEvent) -> Unit) =
        object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onChange(e)
            override fun removeUpdate(e: DocumentEvent) = onChange(e)
            override fun changedUpdate(e: DocumentEvent) = onChange(e)
        }

    /* -------- column table model -------- */

    private data class ColSpecRow(
        var align: ColAlign = ColAlign.L,
        var width: String? = null,
        var vLeft: Boolean = false,
        var vRight: Boolean = false
    )

    private inner class ColumnSpecModel : AbstractTableModel() {
        private val rows = mutableListOf<ColSpecRow>()
        private val cols = arrayOf("Align", "p{width}", "│ left", "│ right")

        fun setColumnCount(n: Int) {
            val old = rowCount
            when {
                n > old -> repeat(n - old) { rows += ColSpecRow() }
                n < old -> repeat(old - n) { rows.removeLast() }
            }
            fireTableDataChanged()
        }

        fun getSpec(i: Int): ColSpecRow = rows[i]

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = cols.size
        override fun getColumnName(column: Int): String = cols[column]
        override fun getColumnClass(columnIndex: Int): Class<*> =
            when (columnIndex) {
                0 -> String::class.java
                1 -> String::class.java
                2,3 -> Boolean::class.java
                else -> String::class.java
            }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> when (r.align) { ColAlign.L -> "l"; ColAlign.C -> "c"; ColAlign.R -> "r"; ColAlign.P -> "p{width}" }
                1 -> r.width ?: ""
                2 -> r.vLeft
                3 -> r.vRight
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val r = rows[rowIndex]
            when (columnIndex) {
                0 -> {
                    val s = (aValue as? String)?.lowercase()?.trim() ?: "l"
                    r.align = when {
                        s.startsWith("p") -> ColAlign.P
                        s.startsWith("c") -> ColAlign.C
                        s.startsWith("r") -> ColAlign.R
                        else              -> ColAlign.L
                    }
                }
                1 -> r.width = (aValue as? String)?.takeIf { it.isNotBlank() }
                2 -> r.vLeft  = (aValue as? Boolean) ?: false
                3 -> r.vRight = (aValue as? Boolean) ?: false
            }
            fireTableRowsUpdated(rowIndex, rowIndex)
            updatePreview()
        }
    }
}
