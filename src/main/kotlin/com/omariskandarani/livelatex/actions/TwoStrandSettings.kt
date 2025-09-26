package com.omariskandarani.livelatex.actions

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

data class TwoStrandSettings(
    var amp: Double = 0.13,
    var turns: Double = 4.0,
    var samples: Int = 400,
    var wth: String = "2.5pt",
    var core: String = "black",
    var mask: String = "5.0pt",
    var clrA: String = "black!60!black",
    var clrB: String = "red!70!black"
)

/**
 * App-level persistent settings.
 * No plugin.xml entry needed when using @Service + @State.
 */
@Service(Service.Level.APP)
@State(
    name = "LiveLatexTwoStrandSettings",
    storages = [Storage("LiveLatexTwoStrandSettings.xml")] // app config dir
)
class TwoStrandSettingsService : PersistentStateComponent<TwoStrandSettings> {

    private var state = TwoStrandSettings()

    override fun getState(): TwoStrandSettings = state

    override fun loadState(s: TwoStrandSettings) {
        state = s
    }

    companion object {
        @JvmStatic
        fun getInstance(): TwoStrandSettingsService = service()
    }
}