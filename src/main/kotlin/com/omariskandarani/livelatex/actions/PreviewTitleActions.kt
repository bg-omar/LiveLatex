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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.omariskandarani.livelatex.core.LiveLatexSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.HierarchyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/** Manual refresh when auto-preview is off. */
class PreviewRefreshAction(private val project: Project) : AnAction("Refresh", "Refresh LaTeX preview", AllIcons.Actions.Refresh) {
    override fun actionPerformed(e: AnActionEvent) {
        project.getService(LatexPreviewService::class.java).requestRefresh()
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
            preferredSize = Dimension(200, 28)
            minimumSize = Dimension(120, 28)
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
                if (sections.isEmpty()) {
                    combo.model = DefaultComboBoxModel(arrayOf(SectionItem("", "Sections")))
                    combo.isEnabled = false
                    combo.selectedIndex = 0
                    return
                }
                val items = sections.map { (id, label) -> SectionItem(id, label) }
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
        svc.addSectionsUiListener(listener)
        combo.putClientProperty("livelatex.sectionsListener", listener)
        combo.addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && !combo.isShowing) {
                val l = combo.getClientProperty("livelatex.sectionsListener") as? LatexPreviewService.SectionsUiListener
                if (l != null) {
                    svc.removeSectionsUiListener(l)
                    combo.putClientProperty("livelatex.sectionsListener", null)
                }
            }
        }

        applyState(svc.lastSections, svc.activeSectionId)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(combo, BorderLayout.CENTER)
            preferredSize = Dimension(200, 28)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
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
            add(object : ToggleAction("Auto scroll editor", "Move editor caret to follow preview scroll", null) {
                override fun isSelected(e2: AnActionEvent) = settings.autoScrollEditor
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.autoScrollEditor = state
                    svc.evalJs("try { localStorage.setItem('ll_auto_scroll_editor', $state); } catch(e){}")
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
            add(object : AnAction("Export preview HTML…", "Write preview HTML next to the .tex file (for development or sharing issues)", null) {
                override fun actionPerformed(e2: AnActionEvent) {
                    val confirmed = Messages.showOkCancelDialog(
                        project,
                        "This writes the current preview as an .html file next to your .tex source.\n\n" +
                            "Use it for development or when sharing issues — not for normal editing.",
                        "Export preview HTML",
                        "Export",
                        "Cancel",
                        Messages.getWarningIcon(),
                    )
                    if (confirmed != Messages.OK) return
                    if (!svc.exportPreviewHtmlBesideSource()) {
                        Messages.showWarningDialog(
                            project,
                            "No preview HTML available yet.\nOpen a .tex file and wait until the preview has loaded.",
                            "Export preview HTML",
                        )
                    }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
            add(Separator.getInstance())
            add(object : AnAction("Clear cache for this document") {
                override fun actionPerformed(e2: AnActionEvent) {
                    svc.requestClearCache()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
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
