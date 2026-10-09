package io.github.kingsleydon.abldualboot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Translucent screen the Quick Settings tile opens: pick a Linux system and restart into it. */
class ChooserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) { Chooser(onDone = ::finish) }
        }
    }
}

@Composable
private fun Chooser(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var targets by remember { mutableStateOf<List<Target>?>(null) }
    var selected by remember { mutableStateOf<Target?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) { if (Root.available()) Device.targets(context) else emptyList() }
        if (found.isEmpty()) error = Root.NO_ROOT
        val last = Settings.lastTarget(context)
        targets = found
        selected = found.firstOrNull { it.location == last?.location } ?: found.firstOrNull()
    }

    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(painterResource(R.drawable.ic_tile), null) },
        title = { Text(stringResource(R.string.choose_title)) },
        text = {
            when {
                error != null -> Text(error!!)
                targets == null -> CircularProgressIndicator()
                else -> Column(Modifier.selectableGroup()) {
                    targets!!.forEach { t -> TargetOption(t, t == selected) { selected = t } }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        error = Device.reboot(context, selected!!)
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.restart)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun TargetOption(target: Target, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(16.dp))
        Column {
            Text(target.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (target.location == "usb") "USB drive · plug in first" else target.where,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
