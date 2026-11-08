package com.nitpicker.ui.common
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.nitpicker.domain.PipelineSnapshot
@Composable fun StatusPanel(s: PipelineSnapshot) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text("${s.networkState} · ASR ${s.asrQueueDepth} · Analysis ${s.analysisQueueDepth} · Status ${if (expanded) "▴" else "▾"}") }
        s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (expanded) {
            Text("Session: ${s.sessionState ?: "—"} · ${s.sessionClockMs / 1000}s")
            Text("Latency: ASR ${s.lastAsrLatencyMs ?: "—"}ms · LLM ${s.lastAnalysisLatencyMs ?: "—"}ms")
            Text("Calls: ASR ${s.asrCalls} · LLM ${s.llmCalls}")
            Text("Failures: ASR ${s.asrFailures} · LLM ${s.llmFailures}")
        }
    }
}
