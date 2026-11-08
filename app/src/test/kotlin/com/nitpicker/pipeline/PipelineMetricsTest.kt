package com.nitpicker.pipeline

import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class PipelineMetricsTest {
    @Test
    fun concurrentUpdatesDoNotLoseCounters() {
        val metrics = PipelineMetrics()
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool
                .invokeAll(
                    List(4) {
                        java.util.concurrent.Callable {
                            repeat(1000) { metrics.update { it.copy(asrCalls = it.asrCalls + 1) } }
                        }
                    }
                )
                .forEach { it.get() }
            assertEquals(4000, metrics.snapshot.value.asrCalls)
        } finally {
            pool.shutdownNow()
        }
    }
}
