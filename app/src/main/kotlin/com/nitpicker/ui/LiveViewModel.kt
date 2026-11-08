package com.nitpicker.ui
import com.nitpicker.di.AppContainer
import com.nitpicker.service.SessionService
class LiveViewModel(c: AppContainer, id: Long) : ScreenModel(c) {
    val model = protocol(id)
    val metrics = c.metrics.snapshot
    val serviceError = c.sessionError
    fun stop() = perform { command(SessionService.ACTION_STOP) }
    fun forceFinalize() = perform { command(SessionService.ACTION_FORCE_FINALIZE) }
    fun retryExport() = perform { command(SessionService.ACTION_RETRY_EXPORT) }
}
