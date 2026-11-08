package com.nitpicker.audio

import com.nitpicker.domain.*
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

object Vad {
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_MS / 1000
    const val NOISE_ALPHA = 0.05
    const val NOISE_MULTIPLIER = 8.0
    const val FIXED_FLOOR = 300.0
}

class Chunker(private val sessionId: Long, chunkMaxSec: Int = 8, vadSilenceMs: Int = 900) {
    private val maximumMs = chunkMaxSec.coerceIn(5, 15) * 1000
    private val silenceLimitMs = vadSilenceMs.coerceIn(400, 2000)
    private val buffer = ByteArrayOutputStream()
    private var startMs = 0L
    private var seq = 0L
    private var speechMs = 0
    private var silenceMs = 0
    private var inSpeech = false
    private var noiseFloor = 0.0
    private var lastOffset: Long? = null
    val currentNoiseFloor: Double
        get() = noiseFloor

    fun offer(frame: PcmFrame): List<AudioChunkData> {
        require(frame.samples == Vad.FRAME_SAMPLES && frame.samples <= frame.pcm.size) {
            "Expected one 20 ms PCM frame"
        }
        require(
            frame.atOffsetMs >= 0 &&
                (lastOffset == null || frame.atOffsetMs == lastOffset!! + Vad.FRAME_MS)
        ) {
            "Frames must be contiguous and ordered"
        }
        lastOffset = frame.atOffsetMs
        if (buffer.size() == 0) startMs = frame.atOffsetMs
        repeat(frame.samples) { i ->
            val v = frame.pcm[i].toInt()
            buffer.write(v and 255)
            buffer.write((v shr 8) and 255)
        }
        val rms =
            sqrt(
                (0 until frame.samples).sumOf {
                    val v = frame.pcm[it].toDouble()
                    v * v
                } / frame.samples
            )
        if (rms > max(noiseFloor * Vad.NOISE_MULTIPLIER, Vad.FIXED_FLOOR)) {
            inSpeech = true
            speechMs += Vad.FRAME_MS
            silenceMs = 0
        } else {
            noiseFloor += Vad.NOISE_ALPHA * (rms - noiseFloor)
            if (inSpeech) silenceMs += Vad.FRAME_MS
        }
        val duration = buffer.size() / 2 * 1000 / SAMPLE_RATE_HZ
        // The hard cap always wins. Short utterances remain buffered across their
        // following silence, merging into the next utterance without losing bytes.
        if (
            duration >= maximumMs ||
                (inSpeech && silenceMs >= silenceLimitMs && speechMs >= MIN_SPEECH_MERGE_MS)
        )
            return listOf(close())
        return emptyList()
    }

    fun drain(): AudioChunkData? = if (buffer.size() == 0) null else close()

    private fun close(): AudioChunkData {
        val bytes = buffer.toByteArray()
        val result =
            AudioChunkData(
                sessionId,
                seq++,
                bytes,
                startedAtOffsetMs = startMs,
                durationMs = bytes.size.toLong() / 2 * 1000 / SAMPLE_RATE_HZ,
            )
        buffer.reset()
        speechMs = 0
        silenceMs = 0
        inSpeech = false
        return result
    }
}
