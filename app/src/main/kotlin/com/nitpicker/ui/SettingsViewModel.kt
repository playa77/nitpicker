package com.nitpicker.ui
import com.nitpicker.di.AppContainer
import com.nitpicker.domain.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
class SettingsViewModel(c: AppContainer) : ScreenModel(c) {
    val settings = MutableStateFlow(SettingsSnapshot())
    init { perform { settings.value = c.settings.snapshot() } }
    fun save(model: String, retention: Int, chunk: Int, vad: Int) = perform {
        c.settings.setAsrModel(model); c.settings.setRetentionMinutes(retention)
        c.settings.setChunkMaxSec(chunk); c.settings.setVadSilenceMs(vad)
        settings.value = c.settings.snapshot()
    }
    fun saveKey(key: String) = perform { c.settings.setApiKey(key) }
}
