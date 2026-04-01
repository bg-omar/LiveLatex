package com.omariskandarani.livelatex.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.omariskandarani.livelatex.core.LiveLatexSettings
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JSeparator

/**
 * Toolbar boven de LaTeX-preview: hamburger (opties), sections-dropdown, zoom −/+, LiveRender.
 * Vervangt de in-page .ll-topbar.
 */
class PreviewToolbarPanel(
    private val project: com.intellij.openapi.project.Project
) : JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)) {

    companion object {
        /** Voortgangsbalk alleen bij veel figuren (TikZ + SST), om flikkering te vermijden. */
        private const val MIN_STEPS_TO_SHOW_LIVERENDER_PROGRESS = 10
    }

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

    private val renderTikzCheck = JBCheckBox("LiveRender", false).apply {
        toolTipText = "TikZ automatisch compileren in de preview (uit = lichter; per figuur: knop \"LiveRender\")"
        isSelected = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview
        addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED || e.stateChange == ItemEvent.DESELECTED) {
                ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview = isSelected
                project.getService(LatexPreviewService::class.java).requestRefresh()
            }
        }
    }

    private val liveRenderProgress = JProgressBar(0, 100).apply {
        isVisible = false
        isStringPainted = true
        preferredSize = Dimension(200, 10)
        maximumSize = Dimension(240, 16)
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
        add(liveRenderProgress)
    }

    /** EDT: voortgang tijdens LiveRender-build (alleen zichtbaar vanaf [MIN_STEPS_TO_SHOW_LIVERENDER_PROGRESS] stappen). */
    fun setLiveRenderProgress(current: Int, total: Int, detail: String) {
        if (total < MIN_STEPS_TO_SHOW_LIVERENDER_PROGRESS) return
        liveRenderProgress.isIndeterminate = false
        liveRenderProgress.maximum = total
        liveRenderProgress.value = current.coerceIn(0, total)
        liveRenderProgress.string = "LiveRender $current / $total"
        liveRenderProgress.toolTipText = detail
        if (!liveRenderProgress.isVisible) {
            liveRenderProgress.isVisible = true
            revalidate()
        }
    }

    fun clearLiveRenderProgress() {
        liveRenderProgress.isVisible = false
        liveRenderProgress.value = 0
        liveRenderProgress.string = ""
        liveRenderProgress.toolTipText = null
        revalidate()
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
        val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
        val autoScrollPreview = javax.swing.JCheckBoxMenuItem("Auto scroll preview", settings.autoScrollPreview)
        val autoScrollEditor = javax.swing.JCheckBoxMenuItem("Auto scroll editor", settings.autoScrollEditor)
        val showTikzDebug = javax.swing.JCheckBoxMenuItem("Show TikZ debug", settings.showTikzDebugOverlay)
        val svc = project.getService(LatexPreviewService::class.java)

        popup.add(autoScrollPreview)
        popup.add(autoScrollEditor)
        popup.add(showTikzDebug)
        autoScrollPreview.addActionListener {
            settings.autoScrollPreview = autoScrollPreview.isSelected
            svc.evalJs("try { localStorage.setItem('ll_auto_scroll', " + autoScrollPreview.isSelected + "); } catch(e){}")
        }
        autoScrollEditor.addActionListener {
            settings.autoScrollEditor = autoScrollEditor.isSelected
            svc.evalJs("try { localStorage.setItem('ll_auto_scroll_editor', " + autoScrollEditor.isSelected + "); } catch(e){}")
        }
        showTikzDebug.addActionListener {
            settings.showTikzDebugOverlay = showTikzDebug.isSelected
            svc.evalJs(
                "try { " +
                    "localStorage.setItem('ll_show_tikz_debug', " + showTikzDebug.isSelected + "); " +
                    "if (typeof window.__llSetTikzDebug === 'function') window.__llSetTikzDebug(" + showTikzDebug.isSelected + "); " +
                "} catch(e){}"
            )
        }
        popup.add(JSeparator())
        popup.add("Cache legen voor dit document").addActionListener {
            svc.requestClearCache()
        }
        popup.add("Alle cache legen").addActionListener {
            svc.requestClearAllCache()
        }
        popup.show(hamburgerBtn, 0, hamburgerBtn.height)
    }

    private fun requestZoomIn() {
        project.getService(LatexPreviewService::class.java).requestZoomIn()
    }

    private fun requestZoomOut() {
        project.getService(LatexPreviewService::class.java).requestZoomOut()
    }

    /** Sync LiveRender-checkbox met instelling (bij openen venster). */
    fun syncRenderTikzFromSettings() {
        renderTikzCheck.isSelected = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview
    }
}
