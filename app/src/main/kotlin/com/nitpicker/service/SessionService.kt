package com.nitpicker.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nitpicker.CompanionApp
import com.nitpicker.audio.*
import com.nitpicker.domain.*
import com.nitpicker.pipeline.PipelineOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

class SessionService : Service() {
    private val container get() = (application as CompanionApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Intent>(Channel.UNLIMITED)
    private var workers: Job? = null
    private var capture: AudioCaptureEngine? = null
    private var pipeline: PipelineOrchestrator? = null
    private var drain: Job? = null
    private var id: Long? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("meeting", "Meeting recording", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        val notification = NotificationCompat.Builder(this, "meeting")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Nitpicker")
            .setContentText("Meeting session in progress").setOngoing(true).setSilent(true).build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        scope.launch {
            for (intent in commands) try {
                when (intent.action) {
                    ACTION_START -> if (id == null) start(Language.valueOf(intent.getStringExtra("language") ?: "DE"))
                    ACTION_RESUME -> if (id == null) resume(intent.getLongExtra("sessionId", -1))
                    ACTION_STOP -> stopRecording()
                    ACTION_FORCE_FINALIZE -> forceFinalize()
                    ACTION_RETRY_EXPORT -> {
                        require(capture == null && !container.repo.hasPending(id ?: return@launch)) { "Stop and drain the session before exporting" }
                        finishSession()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                container.sessionError.value = e.message ?: e.javaClass.simpleName
                if (intent.action in listOf(ACTION_START, ACTION_RESUME)) {
                    capture?.release(); capture = null
                    id?.let { container.repo.setStatus(it, SessionStatus.FAILED) }
                    container.metrics.update { it.copy(sessionState = SessionStatus.FAILED) }
                    releaseLocks()
                    if (id == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) commands.trySend(intent)
        return START_NOT_STICKY
    }
    private suspend fun start(language: Language) {
        check(!container.settings.apiKey().isNullOrBlank()) { "Set an API key in Settings" }
        check(container.connectivity.state.value == NetworkState.ONLINE) { "Network required to start" }
        check(container.repo.activeOrStopping().isEmpty()) { "Recover the existing session first" }
        val cfg = container.settings.snapshot()
        val sessionId = container.repo.createSession(SessionParams(language, cfg.asrModel,
            cfg.retentionMinutes, cfg.chunkMaxSec, cfg.vadSilenceMs))
        container.retention.prune()
        launchSession(sessionId, false)
    }
    private suspend fun resume(sessionId: Long) {
        val session = container.repo.sessionForExport(sessionId).session
        require(session.status in listOf(SessionStatus.ACTIVE, SessionStatus.STOPPING))
        launchSession(sessionId, true)
        if (session.status == SessionStatus.STOPPING) stopRecording()
    }
    private suspend fun launchSession(sessionId: Long, recovering: Boolean) {
        id = sessionId
        container.activeSession.value = sessionId
        container.sessionError.value = null
        val model = container.repo.sessionForExport(sessionId)
        val session = model.session
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nitpicker:meeting").apply { acquire() }
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nitpicker:meeting").apply { acquire() }
        pipeline = PipelineOrchestrator(container.repo, container.asr, container.segmentation,
            container.analysis, container.retention, container.connectivity, container.metrics,
            container.log, container.settings).also { it.recoverInFlight(sessionId) }
        workers = SupervisorJob(scope.coroutineContext[Job])
        pipeline!!.startWorkers(sessionId, CoroutineScope(scope.coroutineContext + workers!!))
        container.metrics.update { PipelineSnapshot(sessionState = session.status, networkState = container.connectivity.state.value) }
        scope.launch {
            while (id == sessionId) {
                container.metrics.update { it.copy(sessionClockMs = System.currentTimeMillis() - session.startedAt) }
                delay(1000)
            }
        }
        if (session.status == SessionStatus.ACTIVE) {
            val handler = CoroutineExceptionHandler { _, e ->
                scope.launch {
                    container.sessionError.value = "Microphone lost: ${e.javaClass.simpleName}"
                    container.repo.setStatus(sessionId, SessionStatus.FAILED)
                    container.metrics.update { it.copy(sessionState = SessionStatus.FAILED) }
                    releaseLocks()
                }
            }
            capture = AudioCaptureEngine(sessionId, session.chunkMaxSec, { chunk ->
                container.repo.enqueueChunk(sessionId, chunk.copy(pcm16Le = WavEncoder.toWav(chunk)))
                container.log.log(sessionId, "INFO", "CAPTURE", "chunk ${chunk.seq} +${chunk.startedAtOffsetMs} ${chunk.durationMs}ms")
            }, session.vadSilenceMs,
                if (recovering) System.currentTimeMillis() - session.startedAt else 0,
                (model.chunks.maxOfOrNull { it.seq } ?: -1) + 1)
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw MicLostException("Microphone permission is required")
            capture!!.start(CoroutineScope(scope.coroutineContext + handler))
        }
    }
    private suspend fun stopRecording() {
        val sessionId = id ?: return
        if (drain?.isActive == true) return
        container.repo.setStatus(sessionId, SessionStatus.STOPPING)
        container.metrics.update { it.copy(sessionState = SessionStatus.STOPPING) }
        capture?.stop(); capture = null
        drain = scope.launch {
            try {
                // Consult durable state as well as the flow, preventing a stale 'empty' observation.
                while (container.repo.hasPending(sessionId)) delay(100)
                pipeline!!.drainComplete.first { it }
                finishSession()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { container.sessionError.value = "Export failed; retry: ${e.javaClass.simpleName}" }
        }
    }
    private suspend fun forceFinalize() {
        val sessionId = id ?: return
        drain?.cancelAndJoin()
        capture?.stop(); capture = null
        workers?.cancelAndJoin()
        container.repo.forceFailPending(sessionId)
        container.repo.sessionForExport(sessionId).chunks.filter { it.state == ChunkState.FAILED && it.audioBlob != null }.forEach {
            container.retention.retainIfNeeded(sessionId, it, it.audioBlob!!, container.settings.snapshot())
            container.repo.nullBlob(it.id)
        }
        finishSession()
    }
    private suspend fun finishSession() {
        val sessionId = id ?: return
        require(capture == null) { "Recording is still active" }
        container.repo.finalizeSession(sessionId)
        val result = container.exporter.export(sessionId)
        container.repo.setExportUri(sessionId, result.uri)
        container.repo.setStatus(sessionId, SessionStatus.COMPLETED)
        container.log.log(sessionId, "INFO", "EXPORT", "${result.statementCount} statements")
        container.metrics.update { it.copy(sessionState = SessionStatus.COMPLETED) }
        workers?.cancelAndJoin()
        id = null
        container.activeSession.value = null
        container.sessionError.value = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun releaseLocks() { if (wake?.isHeld == true) wake?.release(); if (wifi?.isHeld == true) wifi?.release() }
    override fun onDestroy() { capture?.release(); scope.cancel(); releaseLocks(); super.onDestroy() }
    companion object {
        const val ACTION_START = "com.nitpicker.START"
        const val ACTION_STOP = "com.nitpicker.STOP"
        const val ACTION_RESUME = "com.nitpicker.RESUME"
        const val ACTION_FORCE_FINALIZE = "com.nitpicker.FORCE_FINALIZE"
        const val ACTION_RETRY_EXPORT = "com.nitpicker.RETRY_EXPORT"
    }
}
