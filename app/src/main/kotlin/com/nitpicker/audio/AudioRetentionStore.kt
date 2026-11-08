package com.nitpicker.audio

import com.nitpicker.data.db.AudioChunkRow
import com.nitpicker.domain.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AudioRetentionStore(private val filesDir: File) {
    private val root
        get() = File(filesDir, "retained")

    @Synchronized
    fun retainIfNeeded(
        sessionId: Long,
        chunkRow: AudioChunkRow,
        blob: ByteArray,
        cfg: SettingsSnapshot,
    ): File? {
        require(chunkRow.sessionId == sessionId && chunkRow.startedAtOffsetMs >= 0)
        if (
            cfg.retentionMinutes <= 0 ||
                chunkRow.startedAtOffsetMs >= cfg.retentionMinutes.coerceAtMost(10) * 60000L
        )
            return null
        val dir = File(root, "session-$sessionId").apply { check(mkdirs() || isDirectory) }
        val output = File(dir, "chunk-${chunkRow.seq}.wav")
        val temp = File.createTempFile("chunk-", ".tmp", dir)
        try {
            temp.outputStream().use {
                it.write(blob)
                it.fd.sync()
            }
            Files.move(
                temp.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temp.delete()
        }
        return output
    }

    @Synchronized
    fun prune(keepLastN: Int = RETAINED_SESSIONS_KEEP) {
        require(keepLastN >= 0)
        root
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.matches(Regex("session-\\d+")) }
            .sortedByDescending {
                it.name.removePrefix("session-").toLongOrNull() ?: Long.MIN_VALUE
            }
            .drop(keepLastN)
            .forEach { check(it.deleteRecursively()) }
    }

    @Synchronized
    fun deleteForSession(sessionId: Long) {
        check(File(root, "session-$sessionId").let { !it.exists() || it.deleteRecursively() })
    }

    @Synchronized
    fun filesForSession(sessionId: Long): List<File> =
        File(root, "session-$sessionId")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.matches(Regex("chunk-\\d+\\.wav")) }
            .sortedBy { it.name.removePrefix("chunk-").removeSuffix(".wav").toLong() }
}
