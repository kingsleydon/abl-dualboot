package io.github.kingsleydon.abldualboot

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
    data class Restart(val target: Target) : Dialog
    data class InstallAbl(val soc: String, val restore: Boolean) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(onAddTile: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var state by remember { mutableStateOf<DeviceState>(DeviceState.Loading) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var updating by remember { mutableStateOf(false) }
    var autoUpdate by remember { mutableStateOf(Settings.autoUpdate(context)) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun refresh() = scope.launch { state = DeviceState.Loading; state = Device.load(context) }
    LaunchedEffect(Unit) {
        refresh()
        if (Updater.enabled(context)) release = try { Updater.check() } catch (e: Exception) { null }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("ABL Dual Boot") },
                actions = { IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(state, label = "state") { s ->
                when (s) {
                    DeviceState.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                    is DeviceState.NoRoot -> NoRootCard(s.manager) { Root.retry(); refresh() }
                    is DeviceState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        release?.let { r ->
                            UpdateCard(r, updating) {
                                if (!context.packageManager.canRequestPackageInstalls()) {
                                    // One-time permission so ABL Dual Boot can update itself (Settings > Install unknown apps).
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                                    )
                                } else {
                                    updating = true
                                    scope.launch {
                                        Updater.install(context, r)?.let { snackbar.showSnackbar(it) }
                                        updating = false
                                    }
                                }
                            }
                        }
                        if (!s.abl.installed) BootloaderCard(s.abl) { restore -> dialog = Dialog.InstallAbl(s.abl.soc, restore) }
                        log?.let { LogCard(it) }
                        SectionLabel("Restart into")
                        s.targets.forEach { target ->
                            TargetCard(target, enabled = s.abl.installed && !busy) { dialog = Dialog.Restart(target) }
                        }
                        Text(
                            "Your device normally starts ${if (s.defaultBoot == BootTarget.LINUX) "Linux" else "Android"}. " +
                                "Hold VOL- at power-on for the boot menu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = onAddTile, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.size(8.dp))
                            Text("Add Quick Settings tile")
                        }
                        if (Updater.enabled(context)) ListItem(
                            headlineContent = { Text("Install updates automatically") },
                            supportingContent = { Text("Checks GitHub once a day and installs new releases. Otherwise you get a notification.") },
                            trailingContent = {
                                Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it; Settings.setAutoUpdate(context, it) })
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (s.abl.installed) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "ROCKNIX bootloader installed · ${s.abl.soc}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        is Dialog.Restart -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(painterResource(R.drawable.ic_tile), null) },
            title = { Text("Restart into ${d.target.title}?") },
            text = {
                Text(
                    (if (d.target.location == "usb") "Plug in the USB drive first. " else "") +
                        "Your device restarts now and keeps starting ${d.target.title} until you switch back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null; busy = true
                    scope.launch {
                        Device.reboot(context, d.target)?.let { snackbar.showSnackbar(it) }
                        busy = false
                    }
                }) { Text("Restart") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is Dialog.InstallAbl -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text(if (d.restore) "Restore the Linux boot menu?" else "Install the ROCKNIX bootloader?") },
            text = {
                Text(
                    (if (d.restore) "A system update replaced the ROCKNIX bootloader (${d.soc}). This puts it back."
                    else "Replaces your device's bootloader with the ROCKNIX ABL for ${d.soc}. A wrong or interrupted flash can leave the device unbootable.") +
                        "\n\nThis downloads ROCKNIX ABL ${AblRelease.version} (about 1.4 MB) from github.com/ROCKNIX/abl and checks it against a pinned checksum. " +
                        "Your stock bootloader is backed up to ${Device.BACKUP_DIR} first, and every write is verified.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null; busy = true
                    scope.launch {
                        val r = Device.installAbl(context, d.soc)
                        if (!r.ok) log = r.output
                        snackbar.showSnackbar(if (r.ok) "Done. The boot menu is back." else "Failed - see details")
                        busy = false; refresh()
                    }
                }) { Text(if (d.restore) "Restore" else "Install") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UpdateCard(release: Updater.Release, updating: Boolean, onUpdate: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Version ${release.version} is available", style = MaterialTheme.typography.titleMedium)
            if (release.notes.isNotEmpty()) {
                Text(release.notes.lines().take(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onUpdate, enabled = !updating, modifier = Modifier.fillMaxWidth()) {
                Text(if (updating) "Updating…" else "Update")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun TargetCard(target: Target, enabled: Boolean, onClick: () -> Unit) {
    val usb = target.location == "usb"
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (usb) CardDefaults.elevatedCardColors() else CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_tile), null, Modifier.size(32.dp)) },
            headlineContent = { Text(target.title, style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text(if (usb) "USB drive · plug in before restarting" else target.where) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun BootloaderCard(abl: AblStatus, onInstall: (restore: Boolean) -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val (title, body) = when {
                !abl.supported -> "Device not supported" to "There is no ROCKNIX bootloader for this chip."
                abl.replacedByUpdate -> "Linux boot menu removed" to "A system update put back the stock bootloader. Restore it to switch to Linux again."
                else -> "ROCKNIX bootloader needed" to "Install it to dual-boot Linux (${abl.soc})."
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(body, color = MaterialTheme.colorScheme.onErrorContainer)
            when {
                !abl.supported -> {}
                abl.replacedByUpdate -> Button(onClick = { onInstall(true) }, Modifier.fillMaxWidth()) { Text("Restore") }
                else -> OutlinedButton(onClick = { onInstall(false) }, Modifier.fillMaxWidth()) { Text("Install ROCKNIX bootloader…") }
            }
        }
    }
}

@Composable
private fun NoRootCard(manager: Root.Manager?, onRetry: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(12.dp))
                Text("Root access needed", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                manager?.let { "${it.label} detected. ${it.grantSteps}" }
                    ?: "No root manager found. Install Magisk, KernelSU or APatch, then allow ABL Dual Boot.",
            )
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
