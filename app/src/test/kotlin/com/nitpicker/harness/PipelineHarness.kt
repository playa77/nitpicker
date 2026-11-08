package com.nitpicker.harness

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nitpicker.audio.*
import com.nitpicker.data.db.*
import com.nitpicker.data.repository.SessionRepository
import com.nitpicker.data.settings.SettingsStore
import com.nitpicker.domain.*
import com.nitpicker.export.MarkdownExporter
import com.nitpicker.fakes.*
import com.nitpicker.llm.*
import com.nitpicker.log.SessionLog
import com.nitpicker.pipeline.*
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import org.robolectric.RuntimeEnvironment

class PipelineHarness {
    val dir = Files.createTempDirectory("pipeline").toFile()
    val db = AppDatabase.inMemoryBuilder(RuntimeEnvironment.getApplication()).build()
    var repo = SessionRepository(db)
        private set
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val data = PreferenceDataStoreFactory.create(scope = settingsScope, produceFile = { java.io.File(dir, "settings.preferences_pb") })
    val settings = SettingsStore(data) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    val transport = ScriptedChatTransport(awaitTurn = true)
    var analysisBarrier: CompletableDeferred<Unit>? = null
    val analysisEntered = CompletableDeferred<Unit>()
    private val admittedTransport = object : ChatTransport {
        override suspend fun postTranscription(req: TranscriptionRequest): TranscriptionResponse = transport.postTranscription(req)
        override suspend fun postChat(req: ChatRequest): ChatResponse {
            if (req.messages.first().content.toString().contains("You analyze")) {
                analysisEntered.complete(Unit)
                analysisBarrier?.await()
            }
            return transport.postChat(req)
        }
    }
    val gate = FakeNetworkGate()
    val retention = AudioRetentionStore(dir)
    val sink = InMemoryDocumentSink()
    var exporter = MarkdownExporter(repo, sink)
        private set
    val metrics = PipelineMetrics()
    val log = SessionLog(dir)
    private var workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var orchestrator: PipelineOrchestrator
        private set
    var id = 0L
        private set
    private lateinit var chunker: Chunker
    private var offset = 0L
    suspend fun session(retentionMinutes: Int = 0): Long {
        settings.setRetentionMinutes(retentionMinutes)
        id = repo.createSession(SessionParams(Language.EN, SettingsStore.ASR_MODELS.first(), retentionMinutes, 5, 400))
        chunker = Chunker(id, 5, 400); offset = 0
        return id
    }
    suspend fun start() {
        orchestrator = PipelineOrchestrator(repo, AsrClient(admittedTransport, settings), StatementProcessor(admittedTransport),
            AnalysisEngine(admittedTransport), retention, gate, metrics, log, settings, retryDelayMs = 1)
        orchestrator.recoverInFlight(id)
        orchestrator.startWorkers(id, workerScope)
    }
    suspend fun killWorkers() { workerScope.coroutineContext[Job]!!.cancelAndJoin() }
    suspend fun restart() {
        workerScope.coroutineContext[Job]!!.cancelAndJoin()
        workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = SessionRepository(db); exporter = MarkdownExporter(repo, sink)
        start()
    }
    suspend fun pcmChunk(): Long {
        var chunkId = 0L
        // Exactly 1.2s speech and 0.4s silence: closed by the real VAD, never DAO fixtures.
        repeat(80) { frame ->
            val pcm = ShortArray(320) { if (frame < 60) (if (it % 2 == 0) 5000 else -5000).toShort() else 0 }
            chunker.offer(PcmFrame(pcm, 320, offset)).forEach {
                chunkId = repo.enqueueChunk(id, it.copy(pcm16Le = WavEncoder.toWav(it)))
            }
            offset += 20
        }
        check(chunkId > 0)
        return chunkId
    }
    fun script(text: String = "Berlin is the capital of France.", continuation: Boolean = false,
               provisional: Boolean = false, seq: Long = 1, note: String = "Berlin is Germany's capital.", tag: String = "S1",
               extraAnalysis: Boolean = false) {
        transport.enqueue(TranscriptionResponse(text))
        transport.enqueue(chat("""{"statements":[{"text":${json(text)},"continuesPrevious":$continuation,"speakerTag":${json(tag)},"endsProvisionally":$provisional}]}"""))
        fun analysis() = chat("""{"annotations":[{"statementSeq":$seq,"family":"FACT_CHECK","subType":"mismatch","note":${json(note)},"quote":null}],"claims":["S1: Berlin is capital"]}""")
        transport.enqueue(analysis())
        if (extraAnalysis) transport.enqueue(analysis())
    }
    suspend fun await(predicate: suspend () -> Boolean) { withTimeout(15000) { while (!predicate()) delay(10) } }
    suspend fun drain() { await { !repo.hasPending(id) }; await { orchestrator.drainComplete.value }; check(transport.remaining == 0) }
    suspend fun complete(): ExportOutcome {
        repo.setStatus(id, SessionStatus.STOPPING)
        drain(); repo.finalizeSession(id)
        val result = exporter.export(id)
        repo.setExportUri(id, result.uri); repo.setStatus(id, SessionStatus.COMPLETED)
        return result
    }
    suspend fun close() {
        workerScope.coroutineContext[Job]!!.cancelAndJoin()
        settingsScope.coroutineContext[Job]!!.cancelAndJoin()
        db.close(); dir.deleteRecursively()
    }
    companion object {
        fun chat(content: String) = ChatResponse(listOf(Choice(RespMessage(content))))
        fun json(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    }
}
