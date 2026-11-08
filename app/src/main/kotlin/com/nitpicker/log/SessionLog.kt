package com.nitpicker.log

import com.nitpicker.domain.LOG_FILE_CAP
import java.io.File
import java.util.Locale

class SessionLog(
    private val filesDir: File,
    private val clockMs: () -> Long = { System.nanoTime() / 1000000 },
) {
    private val starts = mutableMapOf<Long, Long>()

    @Synchronized
    fun log(sessionId: Long, level: String, tag: String, msg: String) {
        val now = clockMs()
        val elapsed = (now - starts.getOrPut(sessionId) { now }).coerceAtLeast(0) / 1000
        val line =
            String.format(
                    Locale.ROOT,
                    "[+%02d:%02d] %s %s %s\n",
                    elapsed / 60,
                    elapsed % 60,
                    oneLine(level),
                    oneLine(tag),
                    oneLine(msg),
                )
                .toByteArray(Charsets.UTF_8)
        val dir = File(filesDir, "logs").apply { check(mkdirs() || isDirectory) }
        val file = File(dir, "session-$sessionId.log")
        file.appendBytes(line)
        if (file.length() > LOG_FILE_CAP) {
            val bytes = file.readBytes()
            var start = bytes.size - LOG_FILE_CAP
            val newline = (start until bytes.lastIndex).firstOrNull { bytes[it] == 10.toByte() }
            if (newline != null) start = newline + 1
            else while (start < bytes.size && bytes[start].toInt() and 0xc0 == 0x80) start++
            file.writeBytes(bytes.copyOfRange(start, bytes.size))
        }
    }

    private fun oneLine(v: String) = v.replace('\n', ' ').replace('\r', ' ')

    @Synchronized
    fun deleteForSession(sessionId: Long) {
        File(filesDir, "logs/session-$sessionId.log").let { check(!it.exists() || it.delete()) }
        starts.remove(sessionId)
    }
}
