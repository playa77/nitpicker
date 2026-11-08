package com.nitpicker.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nitpicker.di.AppContainer
import com.nitpicker.ui.*

@Composable private inline fun <reified T : ViewModel> injected(key: String, crossinline factory: () -> T): T = viewModel(key = key, factory = object : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V { @Suppress("UNCHECKED_CAST") return factory() as V }
})
@Composable fun AppNav(c: AppContainer) {
    var route by rememberSaveable { mutableStateOf("start") }
    var sessionId by rememberSaveable { mutableLongStateOf(0L) }
    val active by c.activeSession.collectAsState()
    LaunchedEffect(active) { active?.let { sessionId = it; route = "live" } }
    BackHandler(route != "start") { route = "start" }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row {
                TextButton(onClick = { route = "start" }) { Text("Nitpicker") }
                TextButton(onClick = { route = "list" }) { Text("History") }
                TextButton(onClick = { route = "settings" }) { Text("Settings") }
                if (active != null) TextButton(onClick = { sessionId = active!!; route = "live" }) { Text("Live") }
            }
            when (route) {
                "start" -> StartScreen(injected("start") { StartViewModel(c) })
                "settings" -> Column(Modifier.verticalScroll(rememberScrollState())) { SettingsScreen(injected("settings") { SettingsViewModel(c) }) }
                "live" -> LiveScreen(injected("live-$sessionId") { LiveViewModel(c, sessionId) })
                "list" -> ListScreen(injected("list") { ListViewModel(c) }) { sessionId = it; route = "detail" }
                "detail" -> DetailScreen(injected("detail-$sessionId") { DetailViewModel(c, sessionId) }) { route = "list" }
            }
        }
    }
}
