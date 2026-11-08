package com.nitpicker.pipeline

import com.nitpicker.audio.AudioRetentionStore
import com.nitpicker.data.repository.SessionRepository
import com.nitpicker.data.settings.SettingsStore
import com.nitpicker.data.db.AudioChunkRow
import com.nitpicker.domain.*
import com.nitpicker.llm.*
import com.nitpicker.log.SessionLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PipelineOrchestrator(private val repo: SessionRepository,
    private val asr: AsrClient, private val seg: StatementProcessor,
    private val ana: AnalysisEngine, private val retention: AudioRetentionStore,
    private val gate: ConnectivityMonitor, private val metrics: PipelineMetrics,
    private val log: SessionLog, private val settings: SettingsStore,
    private val retryDelayMs: Long = 2000) {
    private val terminalLock = Mutex()
    private val drained = MutableStateFlow(false)
    val drainComplete = drained.asStateFlow()

    suspend fun recoverInFlight(sessionId: Long) {
        repo.recoverInFlight(sessionId)
        cleanup(sessionId)
        drained.value = !repo.hasPending(sessionId)
    }

    private suspend fun terminal(chunk: AudioChunkRow, discarded: Boolean) = terminalLock.withLock {
        while (true) {
            try {
                val current = repo.chunkMetadata(chunk.sessionId).first { it.id == chunk.id }
                val blob = repo.loadBlob(chunk.id)
                if (blob != null) {
                    val file = retention.retainIfNeeded(chunk.sessionId, current, blob, settings.snapshot())
                    if (discarded) repo.markDiscarded(chunk.id, file?.absolutePath ?: current.retainedPath)
                    repo.nullBlob(chunk.id)
                    log.log(chunk.sessionId, "INFO", "RETENTION", "chunk ${chunk.seq} ${if (file != null) "retained" else "cleared"}")
                } else if (discarded && current.state != ChunkState.DISCARDED) {
                    repo.markDiscarded(chunk.id, current.retainedPath)
                }
                break
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                metrics.update { it.copy(lastError = "Audio retention: ${e.javaClass.simpleName}") }
                delay(maxOf(retryDelayMs, 1000))
            }
        }
    }

    private suspend fun cleanup(id: Long) {
        repo.terminalChunks(id).forEach { terminal(it, it.state == ChunkState.ANALYZED) }
    }

    fun startWorkers(sessionId: Long, scope: CoroutineScope) {
        drained.value = false
        fun launchWorker(work: suspend () -> Boolean) = scope.launch(Dispatchers.IO) {
            combine(repo.workChanges(), gate.state) { _, network -> network }.collect {
                cleanup(sessionId)
                drained.value = !repo.hasPending(sessionId)
                while (isActive && gate.state.value == NetworkState.ONLINE) {
                    if (!work()) break
                }
                cleanup(sessionId)
                drained.value = !repo.hasPending(sessionId)
            }
        }
        launchWorker {
            val chunk = repo.claimNextChunkForAsr(sessionId) ?: return@launchWorker false
            val session = repo.sessionMetadata(sessionId)
            val credentialVersion = settings.credentialVersion.first()
            try {
                val start = System.nanoTime()
                metrics.update { it.copy(asrCalls = it.asrCalls + 1, lastError = null) }
                log.log(sessionId, "INFO", "ASR", "dispatch chunk ${chunk.seq} attempt ${chunk.attemptCount}")
                gate.state.first { it == NetworkState.ONLINE }
                repo.saveTranscript(asr.transcribe(chunk, session.language, session.asrModel))
                log.log(sessionId, "INFO", "ASR", "chunk ${chunk.seq} transcript saved in ${(System.nanoTime() - start) / 1_000_000}ms")
                metrics.update { it.copy(
                    lastAsrLatencyMs = (System.nanoTime() - start) / 1_000_000) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                metrics.update { it.copy(asrFailures = it.asrFailures + 1, lastError = e.javaClass.simpleName) }
                log.log(sessionId, "WARN", "ASR", e.javaClass.simpleName)
                if (e is OpenRouterAuthException) {
                    repo.requeueChunk(chunk.id, "Authentication required")
                    settings.credentialVersion.first { it != credentialVersion }
                } else if (chunk.attemptCount < 3 && retryable(e)) {
                    delay(retryDelayMs * chunk.attemptCount)
                    repo.requeueChunk(chunk.id, e.javaClass.simpleName)
                } else {
                    // Retain bytes before the terminal row is cleared. Recovery repeats idempotently.
                    repo.markChunkFailed(chunk.id, e.javaClass.simpleName)
                    terminal(chunk, false)
                }
            }
            true
        }
        launchWorker {
            val chunk = repo.claimNextChunkForSegmentation(sessionId) ?: return@launchWorker false
            val session = repo.sessionMetadata(sessionId)
            val (tail, tags) = repo.segmentationContext(sessionId)
            var done = false
            var attempts = chunk.attemptCount
            while (!done) {
                gate.state.first { it == NetworkState.ONLINE }
                val credentialVersion = settings.credentialVersion.first()
                try {
                    metrics.update { it.copy(llmCalls = it.llmCalls + 1, lastError = null) }
                    log.log(sessionId, "INFO", "SEGMENT", "dispatch chunk ${chunk.seq} attempt $attempts")
                    val result = seg.segment(chunk, tail, tags, session.language)
                    repo.applySegmentation(chunk.id, sessionId, result)
                    log.log(sessionId, "INFO", "SEGMENT", "chunk ${chunk.seq} ${result.statements.size} statements")
                    done = true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    log.log(sessionId, "WARN", "SEGMENT", "chunk ${chunk.seq}: ${e.javaClass.simpleName}")
                    metrics.update { it.copy(llmFailures = it.llmFailures + 1, lastError = e.javaClass.simpleName) }
                    if (e is OpenRouterAuthException) settings.credentialVersion.first { it != credentialVersion }
                    else if (attempts < 3 && retryable(e)) {
                        delay(retryDelayMs * attempts)
                        attempts = repo.segmentationAttempt(chunk.id)
                    }
                    else { repo.markChunkFailed(chunk.id, e.javaClass.simpleName); terminal(chunk, false); done = true }
                }
            }
            true
        }
        launchWorker {
            val ctx = repo.claimNextAnalysisJob(sessionId) ?: return@launchWorker false
            val credentialVersion = settings.credentialVersion.first()
            try {
                val start = System.nanoTime()
                metrics.update { it.copy(llmCalls = it.llmCalls + 1, lastError = null) }
                log.log(sessionId, "INFO", "ANALYSIS", "dispatch job ${ctx.job.id} ${ctx.job.reason} attempt ${ctx.job.attemptCount}")
                gate.state.first { it == NetworkState.ONLINE }
                repo.saveAnalysis(ctx.job.id, sessionId, ana.analyze(ctx))
                log.log(sessionId, "INFO", "ANALYSIS", "job ${ctx.job.id} saved in ${(System.nanoTime() - start) / 1_000_000}ms")
                metrics.update { it.copy(
                    lastAnalysisLatencyMs = (System.nanoTime() - start) / 1_000_000) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log.log(sessionId, "WARN", "ANALYSIS", e.javaClass.simpleName)
                metrics.update { it.copy(llmFailures = it.llmFailures + 1, lastError = e.javaClass.simpleName) }
                if (e is OpenRouterAuthException || (ctx.job.attemptCount < 3 && retryable(e))) {
                    if (e is OpenRouterAuthException) settings.credentialVersion.first { it != credentialVersion }
                    else delay(retryDelayMs * ctx.job.attemptCount)
                    repo.requeueAnalysis(ctx.job.id, e.javaClass.simpleName)
                } else {
                    repo.markAnalysisFailed(ctx.job.id, e.javaClass.simpleName)
                    if (ctx.job.reason == AnalysisJobReason.CHUNK) {
                        repo.chunkMetadata(sessionId).find { it.seq == ctx.job.chunkSeq }?.let {
                            // Transcription remains valid even when annotations fail.
                            terminal(it, true)
                        }
                    }
                }
            }
            cleanup(sessionId)
            true
        }
        scope.launch(Dispatchers.IO) {
            combine(repo.queueDepthsFlow(sessionId), gate.state) { depths, network -> depths to network }
                .collect { (depths, network) -> metrics.update { it.copy(networkState = network,
                    asrQueueDepth = depths.first, analysisQueueDepth = depths.second) } }
        }
    }

    private fun retryable(e: Exception): Boolean = e is java.io.IOException &&
        (e !is OpenRouterHttpException || e.status == 429 || e.status >= 500)
}
