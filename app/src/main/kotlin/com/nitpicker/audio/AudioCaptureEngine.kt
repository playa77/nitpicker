package com.nitpicker.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.nitpicker.domain.*
import kotlinx.coroutines.*

class MicLostException(message: String) : Exception(message)
class AudioCaptureEngine(private val sessionId: Long, chunkMaxSec: Int,
    private val onChunkClosed: suspend (AudioChunkData) -> Unit,
    vadSilenceMs: Int = 900, private val startOffsetMs: Long = 0, private val startSeq: Long = 0) {
    private val chunker = Chunker(sessionId, chunkMaxSec, vadSilenceMs)
    private var recorder: AudioRecord? = null
    private var job: Job? = null
    @Volatile private var stopping = false
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope) {
        check(job == null)
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 320 * 2 * 8))
        recorder = record
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); throw MicLostException("Microphone unavailable") }
        record.startRecording()
        job = scope.launch(Dispatchers.IO) {
            var offset = 0L
            val frame = ShortArray(320)
            try {
                while (isActive && !stopping) {
                    var read = 0
                    while (read < frame.size && !stopping) {
                        val n = record.read(frame, read, frame.size - read, AudioRecord.READ_BLOCKING)
                        if (n <= 0) { if (stopping) break; throw MicLostException("Microphone read failed") }
                        read += n
                    }
                    if (read == frame.size) {
                        chunker.offer(PcmFrame(frame, frame.size, offset)).forEach { deliver(it) }
                        offset += 20
                    }
                }
            } finally { record.release(); recorder = null }
        }
    }
    private suspend fun deliver(chunk: AudioChunkData) = onChunkClosed(chunk.copy(
        seq = chunk.seq + startSeq, startedAtOffsetMs = chunk.startedAtOffsetMs + startOffsetMs))
    suspend fun stop() {
        stopping = true
        try { recorder?.stop() } catch (_: IllegalStateException) { }
        job?.join()
        chunker.drain()?.let { deliver(it) }
    }
    fun release() { stopping = true; try { recorder?.stop() } catch (_: IllegalStateException) {} ; job?.cancel() }
}
