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
 * Toolbar above the LaTeX preview: options menu, sections dropdown, zoom −/+, LiveRender.
 * Replaces the in-page .ll-topbar.
 */
class PreviewToolbarPanel(
    private val project: com.intellij.openapi.project.Project
) : JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)) {

    companion object {
        /** Progress bar only when many figures (TikZ + SST), to avoid flicker. */
        private const val MIN_STEPS_TO_SHOW_LIVERENDER_PROGRESS = 10
    }

    private val hamburgerBtn = JButton("☰").apply {
        toolTipText = "Options"
        addActionListener { showOptionsMenu() }
    }

    val sectionsCombo = JComboBox<String>().apply {
        toolTipText = "Jump to section"
        maximumRowCount = 20
        isEnabled = false
        preferredSize = java.awt.Dimension(180, 28)
    }

    private val zoomOutBtn = JButton("−").apply {
        toolTipText = "Zoom out"
        addActionListener { requestZoomOut() }
    }

    private val zoomInBtn = JButton("+").apply {
        toolTipText = "Zoom in"
        addActionListener { requestZoomIn() }
    }

    private val renderTikzCheck = JBCheckBox("LiveRender", false).apply {
        toolTipText = "Compile TikZ automatically in the preview (off = lighter; per figure: \"LiveRender\" button)"
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

    /** Sections: (id, label). Combo shows label; selection uses id. */
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

    /** EDT: LiveRender build progress (visible only from [MIN_STEPS_TO_SHOW_LIVERENDER_PROGRESS] steps). */
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

    /** Called by LatexPreviewService when the page provides the section list. */
    fun setSections(items: List<Pair<String, String>>) {
        updatingSections = true
        sectionItems = items.map { (id, label) -> SectionItem(id, label) }
        sectionsCombo.removeAllItems()
        sectionItems.forEach { sectionsCombo.addItem(it.label) }
        sectionsCombo.isEnabled = sectionItems.isNotEmpty()
        updatingSections = false
    }

    /** Selected section id (for jump). */
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

    /** Callback for section selection (set by LatexPreviewService). */
    fun setSectionSelectionCallback(callback: (String) -> Unit) {
        sectionSelectionCallback = callback
    }

    /** Select section from the page (scroll-spy) without triggering a jump. */
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
        val invertScrollH = javax.swing.JCheckBoxMenuItem("Inverted scroll-h", settings.invertScrollHorizontal)
        val invertScrollV = javax.swing.JCheckBoxMenuItem("Inverted scroll-v", settings.invertScrollVertical)
        val svc = project.getService(LatexPreviewService::class.java)

        popup.add(autoScrollPreview)
        popup.add(autoScrollEditor)
        popup.add(invertScrollH)
        popup.add(invertScrollV)
        autoScrollPreview.addActionListener {
            settings.autoScrollPreview = autoScrollPreview.isSelected
            svc.evalJs("try { localStorage.setItem('ll_auto_scroll', " + autoScrollPreview.isSelected + "); } catch(e){}")
        }
        autoScrollEditor.addActionListener {
            settings.autoScrollEditor = autoScrollEditor.isSelected
            svc.evalJs("try { localStorage.setItem('ll_auto_scroll_editor', " + autoScrollEditor.isSelected + "); } catch(e){}")
        }
        invertScrollH.addActionListener {
            settings.invertScrollHorizontal = invertScrollH.isSelected
            svc.evalJs(
                "try { " +
                    "localStorage.setItem('ll_invert_scroll_h', " + invertScrollH.isSelected + "); " +
                    "var cbH=document.getElementById('ll-invert-scroll-h'); if(cbH) cbH.checked=" + invertScrollH.isSelected + "; " +
                "} catch(e){}"
            )
        }
        invertScrollV.addActionListener {
            settings.invertScrollVertical = invertScrollV.isSelected
            svc.evalJs(
                "try { " +
                    "localStorage.setItem('ll_invert_scroll_v', " + invertScrollV.isSelected + "); " +
                    "var cbV=document.getElementById('ll-invert-scroll-v'); if(cbV) cbV.checked=" + invertScrollV.isSelected + "; " +
                "} catch(e){}"
            )
        }
        popup.add(JSeparator())
        popup.add("Export preview HTML…").addActionListener {
            val confirmed = javax.swing.JOptionPane.showConfirmDialog(
                hamburgerBtn,
                "This writes the current preview as an .html file next to your .tex source.\n\n" +
                    "Use it for development or when sharing issues — not for normal editing.",
                "Export preview HTML",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE,
            )
            if (confirmed == javax.swing.JOptionPane.OK_OPTION) {
                svc.exportPreviewHtmlBesideSource()
            }
        }
        popup.add(JSeparator())
        popup.add("Clear cache for this document").addActionListener {
            svc.requestClearCache()
        }
        popup.add("Clear all cache").addActionListener {
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

    /** Sync LiveRender checkbox with the setting (when opening the window). */
    fun syncRenderTikzFromSettings() {
        renderTikzCheck.isSelected = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java).renderTikzInPreview
    }
}