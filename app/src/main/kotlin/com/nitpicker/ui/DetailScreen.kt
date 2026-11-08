package com.nitpicker.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.nitpicker.domain.SessionStatus
@Composable fun DetailScreen(vm: DetailViewModel, onDeleted: () -> Unit) {
    val model by vm.model.collectAsState()
    val error by vm.error.collectAsState()
    val deleted by vm.deleted.collectAsState()
    val uri by vm.exportUri.collectAsState()
    var tag by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var target by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) onDeleted() }
    Column {
        Text("Meeting ${model?.session?.id ?: ""}", style = MaterialTheme.typography.headlineMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        uri?.let { Text("Saved: $it") }
        if (model?.session?.status in listOf(SessionStatus.COMPLETED, SessionStatus.FAILED)) {
            OutlinedTextField(tag, { tag = it }, label = { Text("Raw speaker tag, e.g. S1") })
            OutlinedTextField(name, { name = it }, label = { Text("Display name") })
            TextButton(onClick = { vm.rename(tag, name) }) { Text("Rename") }
            OutlinedTextField(target, { target = it }, label = { Text("Merge into unmapped tag") })
            TextButton(onClick = { vm.merge(tag, target) }) { Text("Merge speakers") }
            Row {
                TextButton(onClick = vm::reExport) { Text("Re-export") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete session") }
            }
        }
        model?.let { ProtocolRows(it, rememberLazyListState(), Modifier.weight(1f)) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this session?") }, text = { Text("Removes the local protocol, retained audio and log. Exported files remain.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
