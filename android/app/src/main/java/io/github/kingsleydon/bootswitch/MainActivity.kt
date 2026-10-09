package io.github.kingsleydon.bootswitch

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) { BootSwitchScreen(onAddTile = ::requestTile) }
        }
    }

    private fun requestTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, BootTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BootSwitchScreen(onAddTile: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Status>(Status.Loading) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun refresh() = scope.launch { status = Status.Loading; status = BootSwitch.status(context) }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Boot Switch") },
                actions = { IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(status)
            error?.let { ErrorCard(it) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { confirming = true },
                enabled = status is Status.Ready,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Icon(painterResource(R.drawable.ic_tile), null)
                Spacer(Modifier.size(12.dp))
                Text("Reboot to Linux", style = MaterialTheme.typography.titleMedium)
            }
            FilledTonalButton(onClick = onAddTile, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.size(8.dp))
                Text("Add Quick Settings tile")
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(painterResource(R.drawable.ic_tile), null) },
            title = { Text("Reboot to Linux?") },
            text = { Text("Sets Linux (SD card) as the default boot target and restarts now.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch { error = BootSwitch.rebootToLinux(context) }
                }) { Text("Reboot") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(status: Status) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                Status.Loading -> CircularProgressIndicator(Modifier.size(32.dp))
                is Status.Error -> Icon(Icons.Filled.Warning, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.error)
                is Status.Ready -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(16.dp))
            Column {
                Text("Default boot", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (status) {
                        Status.Loading -> "Checking…"
                        is Status.Error -> "Unavailable"
                        is Status.Ready -> when (status.target) {
                            BootTarget.ANDROID -> "Android"
                            BootTarget.LINUX -> "Linux (SD card)"
                            BootTarget.UNKNOWN -> "Unknown"
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (status is Status.Error) {
                    Text(status.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
