package com.nitpicker.data.settings

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.nitpicker.domain.SettingsSnapshot
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore
internal constructor(
    private val store: DataStore<Preferences>,
    private val keyProvider: () -> SecretKey,
) {
    constructor(
        context: Context
    ) : this(context.applicationContext.settingsDataStore, { androidKey() })

    private val cipherKey = stringPreferencesKey("api_key_cipher")
    private val ivKey = stringPreferencesKey("api_key_iv")
    private val asrKey = stringPreferencesKey("asr_model")
    private val retentionKey = intPreferencesKey("retention_minutes")
    private val chunkKey = intPreferencesKey("chunk_max_sec")
    private val vadKey = intPreferencesKey("vad_silence_ms")
    // Ciphertext fingerprint changes when a key is replaced or cleared; never exposes the key.
    val credentialVersion: Flow<Int> = store.data.map { (it[cipherKey] to it[ivKey]).hashCode() }.distinctUntilChanged()
    val apiKeySet: Flow<Boolean> =
        store.data
            .map { !it[cipherKey].isNullOrBlank() && !it[ivKey].isNullOrBlank() }
            .distinctUntilChanged()

    // The specification's synchronous reads use DataStore's serialized disk state.
    // Callers that may hit disk (gateway/workers) execute off the main thread.
    fun apiKey(): String? {
        val p = runBlocking { store.data.first() }
        val encrypted = p[cipherKey] ?: return null
        val iv = p[ivKey] ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                keyProvider(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    fun snapshot(): SettingsSnapshot {
        val p = runBlocking { store.data.first() }
        return SettingsSnapshot(
            p[asrKey]?.takeIf { it in ASR_MODELS } ?: ASR_MODELS.first(),
            (p[retentionKey] ?: 10).coerceIn(0, 10),
            (p[chunkKey] ?: 8).coerceIn(5, 15),
            (p[vadKey] ?: 900).coerceIn(400, 2000),
        )
    }

    suspend fun setApiKey(v: String) {
        if (v.isBlank()) {
            store.edit {
                it.remove(cipherKey)
                it.remove(ivKey)
            }
            return
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, keyProvider())
            }
        val encrypted = cipher.doFinal(v.toByteArray(Charsets.UTF_8))
        store.edit {
            it[cipherKey] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            it[ivKey] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        }
    }

    suspend fun setAsrModel(v: String) {
        store.edit { it[asrKey] = v.takeIf { it in ASR_MODELS } ?: ASR_MODELS.first() }
    }

    suspend fun setRetentionMinutes(v: Int) {
        store.edit { it[retentionKey] = v.coerceIn(0, 10) }
    }

    suspend fun setChunkMaxSec(v: Int) {
        store.edit { it[chunkKey] = v.coerceIn(5, 15) }
    }

    suspend fun setVadSilenceMs(v: Int) {
        store.edit { it[vadKey] = v.coerceIn(400, 2000) }
    }

    companion object {
        val ASR_MODELS = listOf("openai/whisper-large-v3-turbo", "microsoft/mai-transcribe-2")
        private const val ALIAS = "nitpicker.openrouter"

        @Synchronized
        private fun androidKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let {
                return it
            }
            fun generate(strong: Boolean): SecretKey {
                val spec =
                    KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                if (Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(strong)
                return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply { init(spec.build()) }
                    .generateKey()
            }
            return try {
                generate(true)
            } catch (_: StrongBoxUnavailableException) {
                generate(false)
            }
        }
    }
}
