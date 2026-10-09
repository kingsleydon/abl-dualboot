package io.github.kingsleydon.bootswitch

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) { App(onAddTile = ::requestTile) }
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

private sealed interface Dialog {
    data object Reboot : Dialog
    data class InstallAbl(val soc: String, val restore: Boolean) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(onAddTile: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<DeviceState>(DeviceState.Loading) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(Settings.linuxSource(context)) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun refresh() = scope.launch { state = DeviceState.Loading; state = Device.load(context) }
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
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedContent(state, label = "state") { s ->
                when (s) {
                    DeviceState.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                    is DeviceState.NoRoot -> NoRootCard(s.manager) { refresh() }
                    is DeviceState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BootCard(s)
                        BootloaderCard(s.abl) { restore -> dialog = Dialog.InstallAbl(s.abl.soc, restore) }
                        log?.let { LogCard(it) }
                        SourcePicker(source) { source = it; Settings.setLinuxSource(context, it) }
                        Button(
                            onClick = { dialog = Dialog.Reboot },
                            enabled = s.abl.installed && !busy,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_tile), null, Modifier.size(24.dp))
                            Spacer(Modifier.size(12.dp))
                            Text("Reboot to Linux", style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalButton(onClick = onAddTile, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.size(8.dp))
                            Text("Add Quick Settings tile")
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        Dialog.Reboot -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(painterResource(R.drawable.ic_tile), null) },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null; busy = true
                    scope.launch { log = Device.rebootToLinux(context, source)?.let { "! $it" }; busy = false }
                }) { Text(stringResource(R.string.reboot)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is Dialog.InstallAbl -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text(if (d.restore) "Restore Linux boot menu?" else "Install ROCKNIX ABL?") },
            text = {
                Text(
                    (if (d.restore) "Re-installs the ROCKNIX bootloader (${d.soc}) that a system update replaced."
                    else "Replaces your device's bootloader with the ROCKNIX ABL for ${d.soc}. A wrong or interrupted flash can leave the device unbootable.") +
                        "\n\nYour stock bootloader is backed up to ${Device.BACKUP_DIR} first. Every write is verified.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null; busy = true
                    scope.launch { log = Device.installAbl(context, d.soc).output; busy = false; refresh() }
                }) { Text(if (d.restore) "Restore" else "Install") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusRow(icon: ImageVector?, tint: Color, label: String, value: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(32.dp), tint = tint)
        else Icon(painterResource(R.drawable.ic_tile), null, Modifier.size(32.dp), tint = tint)
        Spacer(Modifier.size(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun BootCard(s: DeviceState.Ready) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(20.dp)) {
            StatusRow(
                icon = null,
                tint = MaterialTheme.colorScheme.primary,
                label = "Default boot",
                value = when (s.target) {
                    BootTarget.ANDROID -> "Android"
                    BootTarget.LINUX -> "Linux" + when (s.source) { BootSource.SD -> " · SD card"; BootSource.INTERNAL -> " · Internal"; else -> "" }
                    BootTarget.UNKNOWN -> "Unknown"
                },
                detail = s.switchError,
            )
        }
    }
}

@Composable
private fun BootloaderCard(abl: AblStatus, onInstall: (restore: Boolean) -> Unit) {
    val warn = !abl.installed
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = if (warn) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.elevatedCardColors(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                abl.installed -> StatusRow(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary,
                    "Bootloader", "ROCKNIX ABL", "${abl.soc} · both slots")
                !abl.supported -> StatusRow(Icons.Filled.Warning, MaterialTheme.colorScheme.error,
                    "Bootloader", "Not supported", "This SoC has no ROCKNIX ABL build")
                abl.replacedByUpdate -> {
                    StatusRow(Icons.Filled.Warning, MaterialTheme.colorScheme.error,
                        "Bootloader", "Linux boot menu removed", "A system update restored the stock bootloader")
                    Button(onClick = { onInstall(true) }, Modifier.fillMaxWidth()) { Text("Restore") }
                }
                else -> {
                    StatusRow(Icons.Filled.Warning, MaterialTheme.colorScheme.error,
                        "Bootloader", "Stock bootloader", "Install the ROCKNIX ABL to dual-boot Linux (${abl.soc})")
                    OutlinedButton(onClick = { onInstall(false) }, Modifier.fillMaxWidth()) { Text("Install ROCKNIX ABL…") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(selected: BootSource, onSelect: (BootSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Linux is installed on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(BootSource.SD to "SD card", BootSource.INTERNAL to "Internal storage").forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun NoRootCard(manager: Root.Manager?, onRetry: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusRow(Icons.Filled.Lock, MaterialTheme.colorScheme.error, "Root access", "Needed",
                manager?.let { "${it.label} detected. ${it.grantSteps}" }
                    ?: "No root manager found. Install Magisk, KernelSU or APatch, then allow Boot Switch.")
            Button(onClick = onRetry, Modifier.fillMaxWidth()) { Text("Try again") }
        }
    }
}

@Composable
private fun LogCard(text: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
