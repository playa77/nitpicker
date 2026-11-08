package com.nitpicker.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitpicker.di.AppContainer
import com.nitpicker.domain.*
import com.nitpicker.service.SessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

open class ScreenModel(protected val c: AppContainer) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    protected fun perform(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) {
        try { block(); error.value = null } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error.value = e.message ?: e.javaClass.simpleName }
    } }
    protected fun command(action: String, id: Long? = null, language: Language? = null) {
        val intent = Intent(c.context, SessionService::class.java).setAction(action)
        id?.let { intent.putExtra("sessionId", it) }; language?.let { intent.putExtra("language", it.name) }
        if (action == SessionService.ACTION_START || action == SessionService.ACTION_RESUME)
            c.context.startForegroundService(intent) else c.context.startService(intent)
    }
    fun protocol(id: Long): StateFlow<ExportModel?> = c.repo.workChanges().map { c.repo.sessionForDisplay(id) }
        .catch { error.value = it.message }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
