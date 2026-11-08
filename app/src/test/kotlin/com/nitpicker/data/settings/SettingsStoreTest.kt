package com.nitpicker.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nitpicker.domain.SettingsSnapshot
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val dir = java.nio.file.Files.createTempDirectory("settings-test").toFile()
    private val file = java.io.File(dir, "settings.preferences_pb")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store
        get() = SettingsStore(data) { key }

    @After
    fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        dir.deleteRecursively()
        Unit
    }

    @Before
    fun reset() = runBlocking {
        store.setApiKey("")
        store.setAsrModel(SettingsStore.ASR_MODELS.first())
        store.setRetentionMinutes(10)
        store.setChunkMaxSec(8)
        store.setVadSilenceMs(900)
    }

    @Test
    fun defaultsAndTypedSnapshotRoundTripAndClamps() = runBlocking {
        assertEquals(SettingsSnapshot(), store.snapshot())
        store.setAsrModel(SettingsStore.ASR_MODELS.last())
        store.setRetentionMinutes(-1)
        store.setChunkMaxSec(99)
        store.setVadSilenceMs(0)
        assertEquals(
            SettingsSnapshot(SettingsStore.ASR_MODELS.last(), 0, 15, 400),
            store.snapshot(),
        )
        store.setRetentionMinutes(99)
        store.setChunkMaxSec(0)
        store.setVadSilenceMs(9999)
        store.setAsrModel("unsupported")
        assertEquals(
            SettingsSnapshot(SettingsStore.ASR_MODELS.first(), 10, 5, 2000),
            store.snapshot(),
        )
    }

    @Test
    fun keyPersistsEncryptedAcrossStoreInstancesAndClearUpdatesGate() = runBlocking {
        assertFalse(store.apiKeySet.first())
        assertNull(store.apiKey())
        val secret = "test-key-with-distinct-marker"
        store.setApiKey(secret)
        assertTrue(store.apiKeySet.first())
        assertEquals(secret, store.apiKey())
        assertTrue(file.exists())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        val before = file.readBytes()
        store.setApiKey(secret)
        assertFalse(before.contentEquals(file.readBytes()))
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        assertEquals(secret, store.apiKey())
        store.setApiKey("")
        assertFalse(store.apiKeySet.first())
        assertNull(store.apiKey())
    }
}
