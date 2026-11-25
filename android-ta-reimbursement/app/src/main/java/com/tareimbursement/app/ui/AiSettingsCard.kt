package com.tareimbursement.app.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tareimbursement.app.AppContainer
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ai.AiModel
import com.tareimbursement.app.core.ai.ModelCatalog
import com.tareimbursement.app.service.ai.ModelDownloadWorker
import com.tareimbursement.app.service.ai.ModelState
import kotlinx.coroutines.launch

/** Settings → On-device AI: which open-source model this phone uses, download / import / delete. */
@Composable
fun AiSettingsCard() {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val state by c.models.state.collectAsState()
    val aiStatus by c.ai.status.collectAsState()
    var enabled by remember { mutableStateOf(c.prefs.aiEnabled) }
    var wifiOnly by remember { mutableStateOf(c.prefs.aiWifiOnly) }
    var chooser by remember { mutableStateOf(false) }
    var tokenDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AiModel?>(null) }
    val ram = c.models.totalRamBytes()
    val recommended = c.models.recommended()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = c.models.importFile(uri)
            Toast.makeText(ctx, r.fold({ "${it.displayName} installed" }, { it.message ?: "Import failed" }), Toast.LENGTH_LONG).show()
            if (r.isSuccess) c.ai.kick()
        }
    }

    fun startDownload(m: AiModel) {
        if (m.gated && c.secrets.get(AppContainer.HF_TOKEN).isNullOrBlank()) { tokenDialog = true; return }
        if (!c.models.hasSpaceFor(m)) { Toast.makeText(ctx, "Not enough free space: ${m.sizeLabel} needed.", Toast.LENGTH_LONG).show(); return }
        if (Build.VERSION.SDK_INT >= 33) safeLaunch(ctx) { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        ModelDownloadWorker.start(ctx, m.id, wifiOnly)
        Toast.makeText(ctx, if (wifiOnly) "Download starts on Wi-Fi (${m.sizeLabel})" else "Downloading ${m.sizeLabel}…", Toast.LENGTH_LONG).show()
    }

    SectionCard(title = "On-device AI") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("An open-source AI model runs on this phone to read each bill a second time (amount, date, nature, remarks) after the text scan. Nothing is sent to any AI service. You still confirm every bill.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use on-device AI", Modifier.weight(1f))
                Switch(enabled, { enabled = it; c.prefs.aiEnabled = it; if (it) c.ai.kick() })
            }
            when (val s = state) {
                is ModelState.Ready -> Text("Installed: ${s.model.displayName} · ${s.model.sizeLabel} · ${s.model.license}", style = MaterialTheme.typography.bodyMedium)
                is ModelState.Downloading -> {
                    Text("Downloading ${s.model.displayName}: ${s.percent}% of ${s.model.sizeLabel}", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is ModelState.Verifying -> { Text("Checking the downloaded file…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is ModelState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ModelState.None -> Text(
                    if (recommended == null) "This phone has ${ModelCatalog.ramLabel(ram)} RAM - too little for the AI model. Bills are read with the built-in text scanner only."
                    else "Recommended for this phone (${ModelCatalog.ramLabel(ram)} RAM): ${recommended.displayName}, ${recommended.sizeLabel} download, one time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            aiStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Download on Wi-Fi only", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(wifiOnly, { wifiOnly = it; c.prefs.aiWifiOnly = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = state is ModelState.Downloading || state is ModelState.Verifying
                if (!busy && state !is ModelState.Ready && recommended != null) OutlinedButton(onClick = { startDownload(recommended) }) { Text("Download") }
                if (busy) OutlinedButton(onClick = { ModelDownloadWorker.cancel(ctx); c.models.refresh() }) { Text("Pause") }
                if (!busy) TextButton(onClick = { chooser = true }) { Text("Choose model") }
                (state as? ModelState.Ready)?.let { r -> TextButton(onClick = { confirmDelete = r.model }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }

    if (chooser) AlertDialog(
        onDismissRequest = { chooser = false },
        title = { Text("Choose AI model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelCatalog.all.forEach { m ->
                    val fits = ram <= 0 || ram / 1e9 + 0.8 >= m.minRamGb
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = m == recommended, onClick = null)
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text("${m.sizeLabel} · needs ${m.minRamGb} GB RAM · ${m.license}" + (if (m.gated) " · needs a free Hugging Face token" else "") + (if (!fits) " · too big for this phone" else ""),
                                style = MaterialTheme.typography.bodySmall, color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                        TextButton(enabled = fits, onClick = { chooser = false; startDownload(m) }) { Text("Get") }
                    }
                }
                TextButton(onClick = { chooser = false; safeLaunch(ctx) { importLauncher.launch(arrayOf("*/*")) } }) { Text("Import a .task model file") }
                TextButton(onClick = { chooser = false; tokenDialog = true }) { Text("Hugging Face token (for Gemma)") }
            }
        },
        confirmButton = { TextButton(onClick = { chooser = false }) { Text("Close") } },
    )

    if (tokenDialog) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { tokenDialog = false },
            title = { Text("Hugging Face token") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Gemma models need a free account at huggingface.co: open the model page, accept Google's licence, then create a \"read\" access token and paste it here. It is stored encrypted on this phone and only sent to huggingface.co.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(token, { token = it.trim() }, label = { Text("hf_…") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = c.secrets.put(AppContainer.HF_TOKEN, token.ifBlank { null })
                    Toast.makeText(ctx, if (ok) "Saved" else "Could not store the token securely", Toast.LENGTH_LONG).show()
                    tokenDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { tokenDialog = false }) { Text("Cancel") } },
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${m.displayName}?") },
            text = { Text("Frees ${m.sizeLabel}. Bills will be read with the text scanner only until you download a model again.") },
            confirmButton = { TextButton(onClick = { c.models.delete(m); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
