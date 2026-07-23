package com.omariskandarani.livelatex.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.omariskandarani.livelatex.tables.*
import java.awt.*
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent

class TableWizardDialog(
    project: Project?,
    private val seedDataFromSelection: List<List<String>> = emptyList()
) : DialogWrapper(project, true) {

    private val bodyRowsSpinner = JSpinner(
        SpinnerNumberModel(
            if (seedDataFromSelection.isNotEmpty()) (seedDataFromSelection.size - 1).coerceAtLeast(1) else 3,
            1,
            999,
            1,
        )
    )
    private val colsSpinner = JSpinner(
        SpinnerNumberModel(
            if (seedDataFromSelection.isNotEmpty()) seedDataFromSelection.maxOf { it.size } else 3,
            1,
            20,
            1,
        )
    )
    private val placementCombo = JComboBox(
        arrayOf("htbp", "t", "b", "h", "p", "ht", "hb", "!h", "H")
    ).apply {
        selectedItem = "htbp"
        toolTipText = "Float placement: h=here, t=top, b=bottom, p=page of floats; " +
            "htbp = try in that order. ! = ignore some constraints; H = here (float package)."
    }
    private val captionField = JTextField("")
    private val labelField = JTextField("")
    private val booktabsCheck = JCheckBox("Booktabs (top/mid/bottomrule)", true)
    private val tableEnvCheck = JCheckBox("Wrap in \\begin{table} ...", true)
    private val outerRulesCheck = JCheckBox("Add outer vertical rules (| ... |)", false)

    private val importBtn = JButton("Import selection")
    private val previewArea = JTextArea(14, 80).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        lineWrap = false
        isEditable = false
    }

    private val colSpecs = mutableListOf<ColSpec>()
    private val alignRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    private val tableMock = JPanel()

    private var currentData: List<List<String>> = seedOrDefault()

    init {
        title = "Generate LaTeX Table"
        init()
        syncColSpecsToSpinner()
        rebuildAlignRow()
        rebuildTableMock()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(10, 10))

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

        val dims = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("Body rows:"))
            add(bodyRowsSpinner)
            add(JLabel("Cols:"))
            add(colsSpinner)
        }
        row("", dims)
        row("Placement:", placementCombo)
        row("Caption:", captionField)
        row("Label:", labelField)
        row("", booktabsCheck, 0.0)
        row("", tableEnvCheck, 0.0)
        row("", outerRulesCheck, 0.0)

        val mid = JPanel(BorderLayout(6, 6))
        mid.border = BorderFactory.createTitledBorder("Table")
        val floating = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("Align")
            add(alignRow, BorderLayout.CENTER)
        }
        mid.add(floating, BorderLayout.NORTH)
        mid.add(JScrollPane(tableMock), BorderLayout.CENTER)
        mid.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply { add(importBtn) }, BorderLayout.SOUTH)

        val bottom = JPanel(BorderLayout())
        bottom.border = BorderFactory.createTitledBorder("Live LaTeX Preview")
        bottom.add(JScrollPane(previewArea), BorderLayout.CENTER)

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

        bodyRowsSpinner.addChangeListener {
            syncColSpecsToSpinner()
            currentData = ensureDataSize(currentData, totalRows(), cols())
            rebuildAlignRow()
            rebuildTableMock()
            regen()
        }
        colsSpinner.addChangeListener {
            syncColSpecsToSpinner()
            currentData = ensureDataSize(currentData, totalRows(), cols())
            rebuildAlignRow()
            rebuildTableMock()
            regen()
        }
        placementCombo.addItemListener(itemL)
        captionField.document.addDocumentListener(simpleDocListener(docL))
        labelField.document.addDocumentListener(simpleDocListener(docL))
        booktabsCheck.addItemListener(itemL)
        tableEnvCheck.addItemListener(itemL)
        outerRulesCheck.addItemListener(itemL)

        importBtn.addActionListener {
            if (seedDataFromSelection.isNotEmpty()) {
                currentData = seedOrDefault()
                bodyRowsSpinner.value = (currentData.size - 1).coerceAtLeast(1)
                colsSpinner.value = currentData.maxOf { it.size }
                syncColSpecsToSpinner()
                rebuildAlignRow()
                rebuildTableMock()
                regen()
            } else {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "No selection was provided to this dialog.\nInvoke the action with a selection to import.",
                    "No Selection",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
    }

    private fun bodyRows() = (bodyRowsSpinner.value as Number).toInt()
    private fun totalRows() = 1 + bodyRows()
    private fun cols() = (colsSpinner.value as Number).toInt()

    private fun syncColSpecsToSpinner() {
        val n = cols()
        when {
            n > colSpecs.size -> repeat(n - colSpecs.size) { colSpecs += ColSpec() }
            n < colSpecs.size -> repeat(colSpecs.size - n) { colSpecs.removeLast() }
        }
    }

    private fun rebuildAlignRow() {
        alignRow.removeAll()
        colSpecs.forEachIndexed { idx, spec ->
            val combo = JComboBox(arrayOf("l", "c", "r", "p{width}")).apply {
                selectedItem = when (spec.align) {
                    ColAlign.L -> "l"
                    ColAlign.C -> "c"
                    ColAlign.R -> "r"
                    ColAlign.P -> "p{width}"
                }
                preferredSize = Dimension(88, preferredSize.height)
            }
            val widthField = JTextField(spec.width ?: "").apply {
                columns = 8
                isEnabled = spec.align == ColAlign.P
                toolTipText = "Width for p{…}, e.g. 3cm or 0.2\\linewidth"
            }
            combo.addItemListener { e ->
                if (e.stateChange != ItemEvent.SELECTED) return@addItemListener
                val s = (combo.selectedItem as? String)?.lowercase().orEmpty()
                spec.align = when {
                    s.startsWith("p") -> ColAlign.P
                    s.startsWith("c") -> ColAlign.C
                    s.startsWith("r") -> ColAlign.R
                    else -> ColAlign.L
                }
                widthField.isEnabled = spec.align == ColAlign.P
                updatePreview()
            }
            widthField.document.addDocumentListener(simpleDocListener {
                spec.width = widthField.text.takeIf { it.isNotBlank() }
                updatePreview()
            })
            alignRow.add(JLabel("C${idx + 1}"))
            alignRow.add(combo)
            alignRow.add(widthField)
        }
        alignRow.revalidate()
        alignRow.repaint()
    }

    private fun rebuildTableMock() {
        val r = totalRows()
        val c = cols()
        currentData = ensureDataSize(currentData, r, c)
        tableMock.removeAll()
        tableMock.layout = GridLayout(r, c, 1, 1)
        tableMock.border = BorderFactory.createLineBorder(Color.GRAY)
        for (i in 0 until r) {
            for (j in 0 until c) {
                val text = currentData.getOrNull(i)?.getOrNull(j).orEmpty()
                val cell = JLabel(text, SwingConstants.CENTER).apply {
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                        BorderFactory.createEmptyBorder(4, 6, 4, 6),
                    )
                    if (i == 0) {
                        font = font.deriveFont(Font.BOLD)
                        background = Color(245, 245, 245)
                        isOpaque = true
                    }
                }
                tableMock.add(cell)
            }
        }
        tableMock.revalidate()
        tableMock.repaint()
    }

    private fun ensureDataSize(data: List<List<String>>, r: Int, c: Int): List<List<String>> {
        if (data.isEmpty()) return placeholderData(r, c)
        return MutableList(r) { i ->
            val row = data.getOrNull(i).orEmpty()
            MutableList(c) { j -> row.getOrNull(j) ?: if (i == 0) "Header ${j + 1}" else "Row$i Col${j + 1}" }
        }
    }

    private fun placeholderData(r: Int, c: Int): List<List<String>> =
        List(r) { i ->
            List(c) { j ->
                if (i == 0) "Header ${j + 1}" else "Row$i Col${j + 1}"
            }
        }

    private fun seedOrDefault(): List<List<String>> {
        if (seedDataFromSelection.isNotEmpty()) return seedDataFromSelection
        return placeholderData(totalRows(), cols())
    }

    private fun gatherOptions(): TableOptions {
        val columns = colSpecs.map { spec ->
            when (spec.align) {
                ColAlign.P -> Col(ColAlign.P, width = spec.width)
                else -> Col(spec.align)
            }
        }
        return TableOptions(
            withTableEnv = tableEnvCheck.isSelected,
            placement = (placementCombo.selectedItem as? String)?.ifBlank { "htbp" } ?: "htbp",
            caption = captionField.text.takeIf { it.isNotBlank() },
            label = labelField.text.takeIf { it.isNotBlank() },
            booktabs = booktabsCheck.isSelected,
            headerRows = 1,
            addOuterRules = outerRulesCheck.isSelected,
            cols = columns,
        )
    }

    private fun updatePreview() {
        val opts = gatherOptions()
        currentData = ensureDataSize(currentData, totalRows(), cols())
        previewArea.text = generateLatexTable(currentData, opts)
        previewArea.caretPosition = 0
    }

    fun resultLatex(): String = previewArea.text

    private fun simpleDocListener(onChange: (DocumentEvent) -> Unit) =
        object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onChange(e)
            override fun removeUpdate(e: DocumentEvent) = onChange(e)
            override fun changedUpdate(e: DocumentEvent) = onChange(e)
        }

    private data class ColSpec(
        var align: ColAlign = ColAlign.L,
        var width: String? = null,
    )
}
