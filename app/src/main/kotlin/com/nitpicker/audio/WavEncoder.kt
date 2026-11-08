package com.nitpicker.audio

import com.nitpicker.domain.AudioChunkData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun toWav(chunk: AudioChunkData): ByteArray {
        require(chunk.sampleRateHz > 0 && chunk.pcm16Le.size % 2 == 0)
        return ByteBuffer.allocate(44 + chunk.pcm16Le.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + chunk.pcm16Le.size)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(chunk.sampleRateHz)
                putInt(chunk.sampleRateHz * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(chunk.pcm16Le.size)
                put(chunk.pcm16Le)
            }
            .array()
    }
}
