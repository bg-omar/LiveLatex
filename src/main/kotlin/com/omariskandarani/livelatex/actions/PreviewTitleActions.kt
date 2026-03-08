package com.omariskandarani.livelatex.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.omariskandarani.livelatex.core.LatexPreviewService
import com.omariskandarani.livelatex.core.LiveLatexSettings
import com.intellij.openapi.application.ApplicationManager
import javax.swing.JComponent

/** Secties-dropdown in de tool window-titelbalk: toont sectielijst uit de preview, springt bij selectie. */
class PreviewSectionsAction(private val project: Project) : AnAction("Secties", "Ga naar sectie", AllIcons.Toolwindows.ToolWindowStructure) {
    override fun actionPerformed(e: AnActionEvent) {
        val svc = project.getService(LatexPreviewService::class.java)
        val sections = svc.lastSections
        if (sections.isEmpty()) {
            JBPopupFactory.getInstance().createMessage("Geen secties beschikbaar.\nWacht tot de preview geladen is of controleer of het document \\section-koppen heeft.")
                .show(RelativePoint.getSouthWestOf(e.inputEvent?.component as? JComponent ?: return))
            return
        }
        val labels = sections.map { it.second }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setItemChosenCallback { label ->
                val idx = labels.indexOf(label)
                if (idx in sections.indices) svc.requestJumpToSection(sections[idx].first)
            }
            .createPopup()
            .show(RelativePoint.getSouthWestOf(e.inputEvent?.component as? JComponent ?: return))
    }

    override fun update(e: AnActionEvent) {
        val sections = project.getService(LatexPreviewService::class.java).lastSections
        e.presentation.isEnabled = true
        e.presentation.description = if (sections.isEmpty()) "Ga naar sectie (nog geen secties geladen)" else "Ga naar sectie (${sections.size} secties)"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Zoom uit (−) in de tool window-titelbalk. */
class PreviewZoomOutAction(private val project: Project) : AnAction("−", "Zoom uit", AllIcons.General.Remove) {
    override fun actionPerformed(e: AnActionEvent) {
        project.getService(LatexPreviewService::class.java).requestZoomOut()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Zoom in (+) in de tool window-titelbalk. */
class PreviewZoomInAction(private val project: Project) : AnAction("+", "Zoom in", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        project.getService(LatexPreviewService::class.java).requestZoomIn()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/** Opties (☰) in de tool window-titelbalk: Auto scroll preview/editor (met vinkje), Cache legen. */
class PreviewOptionsAction(private val project: Project) : AnAction("☰", "Opties", AllIcons.General.GearPlain) {
    override fun actionPerformed(e: AnActionEvent) {
        val svc = project.getService(LatexPreviewService::class.java)
        val settings = ApplicationManager.getApplication().getService(LiveLatexSettings::class.java)
        val group = DefaultActionGroup().apply {
            add(object : ToggleAction("Auto scroll preview", "Scroll preview mee met cursor", null) {
                override fun isSelected(e2: AnActionEvent) = settings.autoScrollPreview
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.autoScrollPreview = state
                    svc.evalJs("try { localStorage.setItem('ll_auto_scroll', $state); } catch(e){}")
                }
            })
            add(object : ToggleAction("Auto scroll editor", "Scroll editor mee met preview", null) {
                override fun isSelected(e2: AnActionEvent) = settings.autoScrollEditor
                override fun setSelected(e2: AnActionEvent, state: Boolean) {
                    settings.autoScrollEditor = state
                    svc.evalJs("try { localStorage.setItem('ll_auto_scroll_editor', $state); } catch(e){}")
                }
            })
            add(Separator.getInstance())
            add(object : AnAction("Cache legen voor dit document") {
                override fun actionPerformed(e2: AnActionEvent) {
                    svc.requestClearCache()
                }
            })
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Opties",
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
