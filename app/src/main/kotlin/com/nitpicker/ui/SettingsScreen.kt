package com.nitpicker.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.nitpicker.data.settings.SettingsStore

@Composable fun SettingsScreen(vm: SettingsViewModel) {
    val cfg by vm.settings.collectAsState()
    var model by remember(cfg) { mutableStateOf(cfg.asrModel) }
    var retention by remember(cfg) { mutableStateOf(cfg.retentionMinutes.toString()) }
    var chunk by remember(cfg) { mutableStateOf(cfg.chunkMaxSec.toString()) }
    var vad by remember(cfg) { mutableStateOf(cfg.vadSilenceMs.toString()) }
    var key by remember { mutableStateOf("") }
    val error by vm.error.collectAsState()
    Column {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SettingsStore.ASR_MODELS.forEach { m -> FilterChip(selected = model == m, onClick = { model = m }, label = { Text(m) }) }
        OutlinedTextField(retention, { retention = it }, label = { Text("Retain audio: 0–10 minutes") })
        OutlinedTextField(chunk, { chunk = it }, label = { Text("Chunk maximum: 5–15 seconds") })
        OutlinedTextField(vad, { vad = it }, label = { Text("Silence threshold: 400–2000 ms") })
        Button(onClick = { vm.save(model, retention.toIntOrNull() ?: cfg.retentionMinutes, chunk.toIntOrNull() ?: cfg.chunkMaxSec, vad.toIntOrNull() ?: cfg.vadSilenceMs) }) { Text("Save settings") }
        OutlinedTextField(key, { key = it }, label = { Text("New OpenRouter API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(onClick = { vm.saveKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Replace key") }
        TextButton(onClick = { vm.saveKey(""); key = "" }) { Text("Clear saved key") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
