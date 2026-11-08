package com.nitpicker.ui
import androidx.lifecycle.viewModelScope
import com.nitpicker.di.AppContainer
import com.nitpicker.data.db.SessionRow
import com.nitpicker.service.SessionService
import kotlinx.coroutines.flow.*
class ListViewModel(c: AppContainer) : ScreenModel(c) {
    val sessions = flow { emitAll(c.repo.sessionsFlow()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<SessionRow>())
    fun recover(id: Long) = perform { command(SessionService.ACTION_RESUME, id) }
}
