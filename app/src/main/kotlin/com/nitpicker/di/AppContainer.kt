package com.nitpicker.di

import android.content.Context
import com.nitpicker.audio.AudioRetentionStore
import com.nitpicker.data.db.AppDatabase
import com.nitpicker.data.repository.SessionRepository
import com.nitpicker.data.settings.SettingsStore
import com.nitpicker.export.*
import com.nitpicker.llm.*
import com.nitpicker.log.SessionLog
import com.nitpicker.pipeline.*
import com.nitpicker.domain.NetworkState
import kotlinx.coroutines.flow.first

class AppContainer(val context: Context) {
    val activeSession = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val sessionError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val settings = SettingsStore(context)
    val connectivity = SystemConnectivityMonitor(context)
    val database = AppDatabase.builder(context).build()
    val repo = SessionRepository(database)
    val retention = AudioRetentionStore(context.filesDir)
    private val gateway = OpenRouterGateway(GatewayConfig(apiKeyProvider = { settings.apiKey().orEmpty() }))
    val transport: ChatTransport = object : ChatTransport {
        override suspend fun postChat(req: ChatRequest): ChatResponse {
            connectivity.state.first { it == NetworkState.ONLINE }
            return gateway.postChat(req)
        }
        override suspend fun postTranscription(req: TranscriptionRequest): TranscriptionResponse {
            connectivity.state.first { it == NetworkState.ONLINE }
            return gateway.postTranscription(req)
        }
    }
    val asr = AsrClient(transport, settings)
    val segmentation = StatementProcessor(transport)
    val analysis = AnalysisEngine(transport)
    val metrics = PipelineMetrics()
    val log = SessionLog(context.filesDir)
    val exporter = MarkdownExporter(repo, MediaStoreDocumentsSink(context))
    init { analysis.onMalformed = { metrics.update { it.copy(llmFailures = it.llmFailures + 1) } } }
}
