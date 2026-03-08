package com.omariskandarani.livelatex.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.omariskandarani.livelatex.core.LiveLatexSettings
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator

/**
 * Toolbar boven de LaTeX-preview: hamburger (opties), sections-dropdown, zoom −/+, Render TikZ.
 * Vervangt de in-page .ll-topbar.
 */
class PreviewToolbarPanel(
    private val project: com.intellij.openapi.project.Project
) : JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)) {

    private val hamburgerBtn = JButton("☰").apply {
        toolTipText = "Opties"
        addActionListener { showOptionsMenu() }
    }

    val sectionsCombo = JComboBox<String>().apply {
        toolTipText = "Ga naar sectie"
        maximumRowCount = 20
        isEnabled = false
        preferredSize = java.awt.Dimension(180, 28)
    }

    private val zoomOutBtn = JButton("−").apply {
        toolTipText = "Zoom uit"
        addActionListener { requestZoomOut() }
    }

    private val zoomInBtn = JButton("+").apply {
        toolTipText = "Zoom in"
        addActionListener { requestZoomIn() }
    }

    private val renderTikzCheck = JBCheckBox("Render TikZ", false).apply {
        toolTipText = "TikZ automatisch renderen (uit = lichter; per figuur kun je op \"Render TikZ\" klikken)"
        isSelected = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview
        addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED || e.stateChange == ItemEvent.DESELECTED) {
                ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview = isSelected
                project.getService(LatexPreviewService::class.java).requestRefresh()
            }
        }
    }

    /** Secties: (id, label). Combo toont label; item = id. */
    private data class SectionItem(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private var sectionItems: List<SectionItem> = emptyList()

    init {
        add(hamburgerBtn)
        add(JSeparator(JSeparator.VERTICAL))
        add(sectionsCombo)
        add(JSeparator(JSeparator.VERTICAL))
        add(zoomOutBtn)
        add(zoomInBtn)
        add(JSeparator(JSeparator.VERTICAL))
        add(renderTikzCheck)
    }

    private var updatingSections = false

    /** Wordt door LatexPreviewService aangeroepen wanneer de pagina de sectielijst doorgeeft. */
    fun setSections(items: List<Pair<String, String>>) {
        updatingSections = true
        sectionItems = items.map { (id, label) -> SectionItem(id, label) }
        sectionsCombo.removeAllItems()
        sectionItems.forEach { sectionsCombo.addItem(it.label) }
        sectionsCombo.isEnabled = sectionItems.isNotEmpty()
        updatingSections = false
    }

    /** Geselecteerde sectie-id (voor jump). */
    fun getSelectedSectionId(): String? {
        val idx = sectionsCombo.selectedIndex
        return if (idx in sectionItems.indices) sectionItems[idx].id else null
    }

    private var sectionSelectionCallback: ((String) -> Unit)? = null

    init {
        sectionsCombo.addActionListener {
            if (!updatingSections) getSelectedSectionId()?.let { sectionSelectionCallback?.invoke(it) }
        }
    }

    /** Callback voor sectie-selectie (LatexPreviewService zet deze). */
    fun setSectionSelectionCallback(callback: (String) -> Unit) {
        sectionSelectionCallback = callback
    }

    /** Selectie van sectie vanaf de pagina (scroll-spy) – zonder jump te triggeren. */
    fun setSelectedSectionId(id: String?) {
        if (id == null) return
        val idx = sectionItems.indexOfFirst { it.id == id }
        if (idx >= 0 && idx != sectionsCombo.selectedIndex) {
            updatingSections = true
            sectionsCombo.selectedIndex = idx
            updatingSections = false
        }
    }

    private fun showOptionsMenu() {
        val popup = JPopupMenu()
        val autoScrollPreview = javax.swing.JCheckBoxMenuItem("Auto scroll preview", true)
        val autoScrollEditor = javax.swing.JCheckBoxMenuItem("Auto scroll editor", true)
        val svc = project.getService(LatexPreviewService::class.java)

        popup.add(autoScrollPreview)
        popup.add(autoScrollEditor)
        autoScrollPreview.addActionListener { svc.evalJs("try { localStorage.setItem('ll_auto_scroll', " + autoScrollPreview.isSelected + "); } catch(e){}") }
        autoScrollEditor.addActionListener { svc.evalJs("try { localStorage.setItem('ll_auto_scroll_editor', " + autoScrollEditor.isSelected + "); } catch(e){}") }
        popup.add(JSeparator())
        popup.add("Cache legen voor dit document").addActionListener {
            svc.requestClearCache()
        }
        popup.show(hamburgerBtn, 0, hamburgerBtn.height)
    }

    private fun requestZoomIn() {
        project.getService(LatexPreviewService::class.java).requestZoomIn()
    }

    private fun requestZoomOut() {
        project.getService(LatexPreviewService::class.java).requestZoomOut()
    }

    /** Sync Render TikZ-checkbox met instelling (bij openen venster). */
    fun syncRenderTikzFromSettings() {
        renderTikzCheck.isSelected = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview
    }
}
