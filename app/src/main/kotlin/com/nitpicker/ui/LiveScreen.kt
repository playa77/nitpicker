package com.nitpicker.ui

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.nitpicker.domain.*
import com.nitpicker.export.*
import com.nitpicker.ui.common.StatusPanel

@Composable fun ProtocolRows(model: ExportModel, state: LazyListState, modifier: Modifier = Modifier) {
    LazyColumn(state = state, modifier = modifier) {
        items(model.statements, key = { it.id }) { s ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("${MarkdownExporter.timestamp(s.startedAtOffsetMs)} · ${SpeakerNames.resolve(s.speakerTag, model.mappings)}", style = MaterialTheme.typography.labelLarge)
                Text(s.text + if (s.state == StatementState.PROVISIONAL) "…" else "")
                model.annotations.filter { it.statementId == s.id }.forEach { a ->
                    Text("${if (a.family == AnnotationFamily.FACT_CHECK) "Fact-check" else "Logic"}: ${a.subType} — ${a.note}", fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}

@Composable fun LiveScreen(vm: LiveViewModel) {
    val model by vm.model.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val error by vm.serviceError.collectAsState()
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(list) {
        snapshotFlow { Triple(list.isScrollInProgress, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }.collect { (scrolling, index, offset) ->
            if (scrolling && (index < previousIndex || index == previousIndex && offset < previousOffset)) follow = false
            previousIndex = index; previousOffset = offset
        }
    }
    LaunchedEffect(model, follow) { if (follow && !list.isScrollInProgress && !model?.statements.isNullOrEmpty()) list.scrollToItem(model!!.statements.lastIndex) }
    Column {
        Text("Live protocol", style = MaterialTheme.typography.headlineMedium)
        StatusPanel(metrics)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::retryExport) { Text("Retry export") } }
        val status = model?.session?.status
        if (status == SessionStatus.STOPPING) {
            Text("Finalizing…")
            TextButton(onClick = vm::forceFinalize) { Text("Force finalize partial protocol") }
        } else if (status == SessionStatus.ACTIVE) Button(onClick = vm::stop) { Text("Stop meeting") }
        else if (status == SessionStatus.FAILED) TextButton(onClick = vm::forceFinalize) { Text("Export partial protocol") }
        else if (status == SessionStatus.COMPLETED) Text("Protocol saved: ${model?.session?.exportUri.orEmpty()}")
        if (!follow) TextButton(onClick = { follow = true }) { Text("Jump to latest") }
        model?.let { ProtocolRows(it, list, Modifier.weight(1f)) }
    }
}
