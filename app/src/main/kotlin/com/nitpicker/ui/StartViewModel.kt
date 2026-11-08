package com.nitpicker.ui
import com.nitpicker.di.AppContainer
import com.nitpicker.domain.Language
import com.nitpicker.service.SessionService
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
class StartViewModel(c: AppContainer) : ScreenModel(c) {
    val keySet = c.settings.apiKeySet.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val network = c.connectivity.state
    val activeSession = c.activeSession
    val serviceError = c.sessionError
    fun start(language: Language) = perform { command(SessionService.ACTION_START, language = language) }
}
