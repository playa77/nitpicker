package com.nitpicker.log

import com.nitpicker.domain.LOG_FILE_CAP
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SessionLogTest {
    @Test
    fun timestampsAreSessionRelativeAndDeleteIsScoped() {
        val dir = Files.createTempDirectory("log-test").toFile()
        try {
            var now = 1000L
            val log = SessionLog(dir) { now }
            log.log(1, "INFO", "ASR", "first")
            now += 61000
            log.log(1, "WARN", "ASR", "next\nline")
            log.log(2, "INFO", "TEST", "other")
            assertEquals(
                "[+00:00] INFO ASR first\n[+01:01] WARN ASR next line\n",
                dir.resolve("logs/session-1.log").readText(),
            )
            log.deleteForSession(1)
            assertFalse(dir.resolve("logs/session-1.log").exists())
            assertTrue(dir.resolve("logs/session-2.log").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun capDropsOldestBytesAndKeepsValidUtf8AndLatestLine() {
        val dir = Files.createTempDirectory("log-cap").toFile()
        try {
            val log = SessionLog(dir)
            repeat(6) { log.log(1, "INFO", "CAP", "message-$it " + "ä".repeat(100000)) }
            log.log(1, "INFO", "CAP", "latest")
            val file = dir.resolve("logs/session-1.log")
            assertTrue(file.length() <= LOG_FILE_CAP)
            assertFalse(file.readText().contains("message-0"))
            assertTrue(file.readText().endsWith("latest\n"))
            assertFalse(file.readText().contains('\uFFFD'))
        } finally {
            dir.deleteRecursively()
        }
    }
}
