package com.nitpicker.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.nitpicker.domain.SessionStatus
@Composable fun ListScreen(vm: ListViewModel, open: (Long) -> Unit) {
    val sessions by vm.sessions.collectAsState()
    val error by vm.error.collectAsState()
    Column {
        Text("Meeting history", style = MaterialTheme.typography.headlineMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (sessions.isEmpty()) Text("Your meetings will appear here.")
        LazyColumn { items(sessions, key = { it.id }) { s ->
            Row {
                TextButton(onClick = { open(s.id) }) { Text("Meeting ${s.id} · ${s.language} · ${s.status}") }
                if (s.status in listOf(SessionStatus.ACTIVE, SessionStatus.STOPPING)) TextButton(onClick = { vm.recover(s.id) }) { Text("Recover") }
            }
        } }
    }
}
