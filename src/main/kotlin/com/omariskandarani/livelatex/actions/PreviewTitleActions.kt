package com.omariskandarani.livelatex.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.omariskandarani.livelatex.core.LiveLatexSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/** Clear document cache and refresh preview. */
class PreviewRefreshAction(private val project: Project) :
    AnAction("Refresh", "Clear cache for this document and refresh preview", AllIcons.Actions.Refresh) {
    override fun actionPerformed(e: AnActionEvent) {
        project.getService(LatexPreviewService::class.java).requestClearCache()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Cancel an in-flight preview build. */
class PreviewCancelRenderAction(private val project: Project) : AnAction("Cancel", "Cancel preview render", AllIcons.Actions.Suspend) {
    override fun actionPerformed(e: AnActionEvent) {
        project.getService(LatexPreviewService::class.java).cancelPreviewBuild()
    }

    override fun update(e: AnActionEvent) {
        val building = project.getService(LatexPreviewService::class.java).isPreviewBuilding
        e.presentation.isEnabled = building
        e.presentation.isVisible = building
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/**
 * Chapter/section combo in the tool window title bar.
 * Swing ComboBox (not ComboBoxAction) so scroll-spy updates and user selection work reliably.
 */
class PreviewChapterComboAction(private val project: Project) :
    AnAction("Sections", "Jump to section", null),
    CustomComponentAction,
    DumbAware {

    private data class SectionItem(val id: String, val label: String) {
        override fun toString(): String = label
    }

    override fun actionPerformed(e: AnActionEvent) {
        // Selection is handled by the ComboBox ActionListener.
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val svc = project.getService(LatexPreviewService::class.java)
        val combo = ComboBox<SectionItem>().apply {
            toolTipText = "Jump to section"
            maximumRowCount = 20
            isSwingPopup = true
            minimumSize = Dimension(COMBO_MIN_WIDTH, COMBO_HEIGHT)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = (value as? SectionItem)?.label ?: "Sections"
                    return c
                }
            }
        }

        var updating = false
        fun applyState(sections: List<Pair<String, String>>, activeId: String?) {
            updating = true
            try {
                val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
                val display = SectionDropdownHelpers.displaySections(
                    sections,
                    showSubsections = settings.showDropdownSubsections,
                    showSubsubsections = settings.showDropdownSubsubsections,
                )
                if (display.isEmpty()) {
                    combo.model = DefaultComboBoxModel(arrayOf(SectionItem("", "Sections")))
                    combo.isEnabled = false
                    combo.selectedIndex = 0
                    return
                }
                val items = display.map { (id, label) -> SectionItem(id, label) }
                combo.model = DefaultComboBoxModel(items.toTypedArray())
                combo.isEnabled = true
                val idx = items.indexOfFirst { it.id == activeId }
                combo.selectedIndex = if (idx >= 0) idx else -1
            } finally {
                updating = false
            }
        }

        combo.addActionListener {
            if (updating) return@addActionListener
            val item = combo.selectedItem as? SectionItem ?: return@addActionListener
            if (item.id.isBlank()) return@addActionListener
            svc.requestJumpToSection(item.id)
        }

        val listener = LatexPreviewService.SectionsUiListener { sections, activeId ->
            applyState(sections, activeId)
        }
        fun ensureSectionsListener() {
            val existing = combo.getClientProperty("livelatex.sectionsListener") as? LatexPreviewService.SectionsUiListener
            if (existing == null) {
                svc.addSectionsUiListener(listener)
                combo.putClientProperty("livelatex.sectionsListener", listener)
            }
        }
        fun dropSectionsListener() {
            val l = combo.getClientProperty("livelatex.sectionsListener") as? LatexPreviewService.SectionsUiListener
            if (l != null) {
                svc.removeSectionsUiListener(l)
                combo.putClientProperty("livelatex.sectionsListener", null)
            }
        }
        ensureSectionsListener()
        combo.addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) == 0L) return@addHierarchyListener
            if (combo.isShowing) {
                // Re-bind after hide: updates that arrived while hidden must re-apply.
                ensureSectionsListener()
                applyState(svc.lastSections, svc.activeSectionId)
            } else {
                dropSectionsListener()
            }
        }

        applyState(svc.lastSections, svc.activeSectionId)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(combo, BorderLayout.CENTER)
            minimumSize = Dimension(COMBO_MIN_WIDTH, COMBO_HEIGHT)

            fun toolContentWidth(): Int {
                val tw = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview")
                val twW = tw?.component?.width ?: 0
                if (twW > COMBO_MIN_WIDTH) return twW
                // Fallback: widest ancestor (content / tool window host).
                var c: Component? = this
                var best = 0
                while (c != null) {
                    if (c.width > best) best = c.width
                    c = c.parent
                }
                return if (best > COMBO_MIN_WIDTH) best else COMBO_FALLBACK_WIDTH
            }

            fun applyWidthFromTool() {
                val w = PreviewChapterComboWidths.clampFromToolWidth(toolContentWidth())
                val dim = Dimension(w, COMBO_HEIGHT)
                preferredSize = dim
                maximumSize = dim
                combo.preferredSize = dim
                combo.maximumSize = dim
                revalidate()
            }

            applyWidthFromTool()

            var attachedTool: Component? = null
            val toolResizeListener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    applyWidthFromTool()
                }
            }

            fun attachToolResizeListener() {
                val twComp = ToolWindowManager.getInstance(project).getToolWindow("LaTeX Preview")?.component
                if (twComp === attachedTool) {
                    applyWidthFromTool()
                    return
                }
                attachedTool?.removeComponentListener(toolResizeListener)
                attachedTool = twComp
                twComp?.addComponentListener(toolResizeListener)
                applyWidthFromTool()
            }

            addHierarchyListener { e ->
                val flags = e.changeFlags
                if ((flags and HierarchyEvent.PARENT_CHANGED.toLong()) != 0L ||
                    (flags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L
                ) {
                    if (isShowing) attachToolResizeListener()
                }
            }
            // Also listen to immediate parent (title strip) as a secondary signal.
            addHierarchyListener { e ->
                if ((e.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong()) == 0L) return@addHierarchyListener
                parent?.addComponentListener(toolResizeListener)
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private const val COMBO_HEIGHT = 28
        private const val COMBO_MIN_WIDTH = 160
        private const val COMBO_FALLBACK_WIDTH = 420
    }
}

/**
 * Width for the sections combo from the tool window / HTML content width:
 * `(toolWidth - chromeReserve) * 0.75`, floored at [MIN_WIDTH].
 */
object PreviewChapterComboWidths {
    const val MIN_WIDTH = 160
    const val FALLBACK_WIDTH = 420
    /** Title text + other title-actions + IDE strip (LiveRender, refresh, zoom, options, …). */
    const val CHROME_RESERVE = 320
    const val REMAINING_FRACTION = 0.75

    fun clampFromToolWidth(
        toolWidth: Int,
        chromeReserve: Int = CHROME_RESERVE,
        fraction: Double = REMAINING_FRACTION,
    ): Int {
        val remaining = (toolWidth - chromeReserve).coerceAtLeast(0)
        val target = (remaining * fraction).toInt()
        val upper = remaining.coerceAtLeast(MIN_WIDTH)
        return target.coerceAtLeast(MIN_WIDTH).coerceAtMost(upper)
    }
}

/** Zoom out (−) in the tool window title bar. */
class PreviewZoomOutAction(private val project: Project) : AnAction("−", "Zoom out", AllIcons.General.Remove) {
    override fun actionPerformed(e: AnActionEvent) {
        project.getService(LatexPreviewService::class.java).requestZoomOut()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Zoom in (+) in the tool window title bar. */
class PreviewZoomInAction(private val project: Project) : AnAction("+", "Zoom in", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        project.getService(LatexPreviewService::class.java).requestZoomIn()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Options (gear) in the tool window title bar: auto-scroll, one-shot HTML export, cache clear, etc. */
class PreviewOptionsAction(private val project: Project) : AnAction("Options", "Options", AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        val svc = project.getService(LatexPreviewService::class.java)
        val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
        val group = DefaultActionGroup().apply {
            add(object : ToggleAction("Auto preview", "Refresh preview on edit and tab switch", null) {
                override fun isSelected(e2: AnActionEvent) = settings.autoPreview
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.autoPreview = state
                    if (state) {
                        svc.requestRefresh()
                    }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : ToggleAction("Auto scroll preview", "Scroll preview to follow the editor caret", null) {
                override fun isSelected(e2: AnActionEvent) = settings.autoScrollPreview
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.autoScrollPreview = state
                    svc.evalJs("try { localStorage.setItem('ll_auto_scroll', $state); } catch(e){}")
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : ToggleAction("Show subsections", "Include subsections in the Sections dropdown", null) {
                override fun isSelected(e2: AnActionEvent) = settings.showDropdownSubsections
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.showDropdownSubsections = state
                    svc.refreshSectionsUi()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : ToggleAction("Show subsubsections", "Include subsubsections/paragraphs in the Sections dropdown", null) {
                override fun isSelected(e2: AnActionEvent) = settings.showDropdownSubsubsections
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.showDropdownSubsubsections = state
                    svc.refreshSectionsUi()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : ToggleAction("Inverted scroll-h", "Invert horizontal scrolling (JCEF/Chromium)", null) {
                override fun isSelected(e2: AnActionEvent) = settings.invertScrollHorizontal
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.invertScrollHorizontal = state
                    svc.evalJs(
                        "try { " +
                            "localStorage.setItem('ll_invert_scroll_h', $state); " +
                            "var cbH=document.getElementById('ll-invert-scroll-h'); if(cbH) cbH.checked=$state; " +
                        "} catch(e){}"
                    )
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(object : ToggleAction("Inverted scroll-v", "Invert vertical scrolling", null) {
                override fun isSelected(e2: AnActionEvent) = settings.invertScrollVertical
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.invertScrollVertical = state
                    svc.evalJs(
                        "try { " +
                            "localStorage.setItem('ll_invert_scroll_v', $state); " +
                            "var cbV=document.getElementById('ll-invert-scroll-v'); if(cbV) cbV.checked=$state; " +
                        "} catch(e){}"
                    )
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(Separator.getInstance())
            add(object : ToggleAction(
                "Debug Mode",
                "Show the preview scroll debug HUD and auto-export preview HTML next to the .tex file",
                null,
            ) {
                override fun isSelected(e2: AnActionEvent) = settings.debugScrollLog
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.debugScrollLog = state
                    svc.setDebugMode(state)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(Separator.getInstance())
            add(object : AnAction("Clear all cache") {
                override fun actionPerformed(e2: AnActionEvent) {
                    svc.requestClearAllCache()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Options",
            group,
            e.dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
        val place = e.inputEvent?.component as? JComponent ?: return
        popup.show(RelativePoint.getSouthWestOf(place))
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
