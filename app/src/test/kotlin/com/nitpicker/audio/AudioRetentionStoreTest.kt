package com.nitpicker.audio

import com.nitpicker.data.db.AppDatabase
import com.nitpicker.data.repository.SessionRepository
import com.nitpicker.domain.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AudioRetentionStoreTest {
    private val dir = Files.createTempDirectory("retention-test").toFile()
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private val retention = AudioRetentionStore(dir)

    @Before
    fun setup() {
        db = AppDatabase.inMemoryBuilder(RuntimeEnvironment.getApplication()).build()
        repo = SessionRepository(db)
    }

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun row(offset: Long): com.nitpicker.data.db.AudioChunkRow {
        val id = repo.createSession(SessionParams(Language.EN, "test", 2, 8, 900))
        val chunker = Chunker(id)
        chunker.offer(PcmFrame(ShortArray(320) { 1000 }, 320, offset))
        val pcm = chunker.drain()!!
        val chunk = pcm.copy(pcm16Le = WavEncoder.toWav(pcm))
        repo.enqueueChunk(id, chunk)
        return repo.sessionForExport(id).chunks.single()
    }

    @Test
    fun strictWindowZeroModeAndExactOverwrite() = runBlocking {
        val before = row(119999)
        val at = row(120000)
        val file =
            retention.retainIfNeeded(
                before.sessionId,
                before,
                before.audioBlob!!,
                SettingsSnapshot(retentionMinutes = 2),
            )!!
        assertArrayEquals(before.audioBlob, file.readBytes())
        assertEquals("chunk-0.wav", file.name)
        assertNull(
            retention.retainIfNeeded(
                at.sessionId,
                at,
                at.audioBlob!!,
                SettingsSnapshot(retentionMinutes = 2),
            )
        )
        assertNull(
            retention.retainIfNeeded(
                before.sessionId,
                before,
                byteArrayOf(9),
                SettingsSnapshot(retentionMinutes = 0),
            )
        )
        retention.retainIfNeeded(
            before.sessionId,
            before,
            byteArrayOf(8),
            SettingsSnapshot(retentionMinutes = 2),
        )
        assertArrayEquals(byteArrayOf(8), file.readBytes())
        assertEquals(1, retention.filesForSession(before.sessionId).size)
        retention.deleteForSession(before.sessionId)
        assertTrue(retention.filesForSession(before.sessionId).isEmpty())
    }

    @Test
    fun pruneKeepsTenNewestSessionIdsEvenWhenOldAudioIsRewritten() = runBlocking {
        val rows = (0..11).map { row(0) }
        rows.forEach {
            retention.retainIfNeeded(it.sessionId, it, it.audioBlob!!, SettingsSnapshot())
        }
        retention.retainIfNeeded(
            rows.first().sessionId,
            rows.first(),
            byteArrayOf(1),
            SettingsSnapshot(),
        )
        retention.prune()
        rows.take(2).forEach { assertTrue(retention.filesForSession(it.sessionId).isEmpty()) }
        rows.drop(2).forEach { assertEquals(1, retention.filesForSession(it.sessionId).size) }
        retention.prune(0)
        assertTrue(dir.resolve("retained").listFiles()!!.isEmpty())
    }
}
