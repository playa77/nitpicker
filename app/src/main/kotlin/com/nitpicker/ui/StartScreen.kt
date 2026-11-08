package com.nitpicker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.nitpicker.domain.*

@Composable fun StartScreen(vm: StartViewModel) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(Language.DE) }
    var mic by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    var askedNotifications by remember { mutableStateOf(false) }
    LaunchedEffect(mic) {
        if (mic && !askedNotifications && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true
            permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    val key by vm.keySet.collectAsState()
    val network by vm.network.collectAsState()
    val error by vm.error.collectAsState()
    val serviceError by vm.serviceError.collectAsState()
    Column {
        Text("A quiet second perspective", style = MaterialTheme.typography.headlineSmall)
        Text("Record only with everyone's consent. Audio and statements are sent to OpenRouter.")
        Row { Language.entries.forEach { lang -> FilterChip(selected = language == lang, onClick = { language = lang }, label = { Text(lang.name) }) } }
        if (!mic) Button(onClick = {
            permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
        }) { Text("Allow microphone") }
        if (!key) Text("Set your OpenRouter API key in Settings.")
        if (network == NetworkState.OFFLINE) Text("Connect to the internet to start.")
        Button(onClick = { vm.start(language) }, enabled = key && mic && network == NetworkState.ONLINE) { Text("Start meeting") }
        (error ?: serviceError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
