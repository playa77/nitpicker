package com.nitpicker.audio

import com.nitpicker.domain.*
import org.junit.Assert.*
import org.junit.Test

class ChunkerTest {
    private class Feed(val chunker: Chunker) {
        var offset = 0L
        val out = mutableListOf<AudioChunkData>()

        fun frames(count: Int, amplitude: Short) {
            repeat(count) {
                out += chunker.offer(PcmFrame(ShortArray(320) { amplitude }, 320, offset))
                offset += 20
            }
        }
    }

    @Test
    fun silenceClosesAfterHangoverWithExactBytesAndOffsets() {
        val f = Feed(Chunker(42, vadSilenceMs = 400))
        f.frames(60, 2000)
        f.frames(19, 0)
        assertTrue(f.out.isEmpty())
        f.frames(1, 0)
        val c = f.out.single()
        assertEquals(1600L, c.durationMs)
        assertEquals(0L, c.startedAtOffsetMs)
        assertEquals(42L, c.sessionId)
        assertEquals(51200, c.pcm16Le.size)
        f.frames(1, 2000)
        val tail = f.chunker.drain()!!
        assertEquals(1L, tail.seq)
        assertEquals(1600L, tail.startedAtOffsetMs)
        assertNull(f.chunker.drain())
    }

    @Test
    fun hardCapBoundsSpeechAndSilenceAndPreservesEverySample() {
        for (amplitude in listOf(0.toShort(), 2000.toShort())) {
            val f = Feed(Chunker(1, chunkMaxSec = 5))
            f.frames(251, amplitude)
            assertEquals(5000L, f.out.single().durationMs)
            assertEquals(20L, f.chunker.drain()!!.durationMs)
        }
    }

    @Test
    fun shortSpeechMergesIntoFollowingUtterance() {
        val f = Feed(Chunker(1, vadSilenceMs = 400))
        f.frames(30, 2000)
        f.frames(30, 0)
        assertTrue(f.out.isEmpty())
        f.frames(30, 2000)
        f.frames(20, 0)
        assertEquals(2200L, f.out.single().durationMs)
    }

    @Test
    fun thresholdUsesSilentEmaWithoutLearningSpeechAsNoise() {
        val f = Feed(Chunker(1))
        f.frames(100, 100)
        assertEquals(99.4, f.chunker.currentNoiseFloor, 1.0)
        val floor = f.chunker.currentNoiseFloor
        f.frames(60, 2000)
        assertEquals(floor, f.chunker.currentNoiseFloor, 0.0)
        f.frames(45, 0)
        assertEquals(1, f.out.size)
    }

    @Test
    fun rejectsMisorderedOrPartialFrames() {
        val c = Chunker(1)
        c.offer(PcmFrame(ShortArray(320), 320, 0))
        try {
            c.offer(PcmFrame(ShortArray(320), 320, 0))
            fail()
        } catch (_: IllegalArgumentException) {}
        try {
            c.offer(PcmFrame(ShortArray(10), 10, 20))
            fail()
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun wavIsByteExactLittleEndianAndDeterministic() {
        val chunk =
            AudioChunkData(
                1,
                0,
                byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte()),
                startedAtOffsetMs = 0,
                durationMs = 0,
            )
        val expected =
            byteArrayOf(
                82,
                73,
                70,
                70,
                42,
                0,
                0,
                0,
                87,
                65,
                86,
                69,
                102,
                109,
                116,
                32,
                16,
                0,
                0,
                0,
                1,
                0,
                1,
                0,
                0x80.toByte(),
                0x3e,
                0,
                0,
                0,
                0x7d,
                0,
                0,
                2,
                0,
                16,
                0,
                100,
                97,
                116,
                97,
                6,
                0,
                0,
                0,
            ) + chunk.pcm16Le
        assertArrayEquals(expected, WavEncoder.toWav(chunk))
        assertArrayEquals(expected, WavEncoder.toWav(chunk))
    }
}
