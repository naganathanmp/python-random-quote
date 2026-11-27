package com.tareimbursement.app.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tareimbursement.app.container
import com.tareimbursement.app.core.AmountWords
import com.tareimbursement.app.service.AutoBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SettingLine(title: String, sub: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segments(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(selected = v == selected, onClick = { onPick(v) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1) }
        }
    }
}

/** Settings → Appearance: theme, accent colour, text size, motion. */
@Composable
fun AppearanceCard() {
    val c = LocalContext.current.container
    val ui by c.prefs.ui.collectAsState()
    SectionCard(title = "Appearance") {
        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        Segments(listOf("LIGHT" to "Light", "DARK" to "Dark", "SYSTEM" to "System"), ui.theme) { v -> c.prefs.updateUi { it.copy(theme = v) } }
        Text(if (ui.theme == "SYSTEM") "Follows your phone's dark mode setting." else "Always ${ui.theme.lowercase()}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))

        SettingLine("Accent colour", Accent.of(ui.accent).label)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            Accent.entries.filter { it != Accent.WALLPAPER || Build.VERSION.SDK_INT >= 31 }.forEach { a ->
                val on = ui.accent == a.name
                Box(
                    Modifier.size(34.dp).background(a.fill, CircleShape)
                        .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { c.prefs.updateUi { it.copy(accent = a.name) } }
                        .semantics { contentDescription = a.label; selected = on },
                    contentAlignment = Alignment.Center,
                ) { if (a == Accent.WALLPAPER) Text("W", color = a.onFill, style = MaterialTheme.typography.labelMedium) }
            }
        }

        val size = when { ui.textScale < 0.95f -> "Small"; ui.textScale < 1.05f -> "Default"; ui.textScale < 1.15f -> "Large"; else -> "Extra large" }
        SettingLine("Text size", "$size - inside this app only")
        Slider(value = ui.textScale, onValueChange = { v -> c.prefs.updateUi { it.copy(textScale = (Math.round(v * 10) / 10f)) } }, valueRange = 0.9f..1.3f, steps = 3,
            modifier = Modifier.semantics { contentDescription = "Text size" })

        SettingLine("Reduce motion", if (ui.reduceMotion) "Screens change instantly" else "Quick 90 ms fades") {
            Switch(ui.reduceMotion, { v -> c.prefs.updateUi { it.copy(reduceMotion = v) } }, modifier = Modifier.semantics { contentDescription = "Reduce motion" })
        }
    }
}

/** Settings → Numbers & defaults: number format, default currency, home city, batch scanning, reminders. */
@Composable
fun DefaultsCard() {
    val c = LocalContext.current.container
    val ui by c.prefs.ui.collectAsState()
    var city by remember { mutableStateOf(ui.homeCity) }
    SectionCard(title = "Numbers & defaults") {
        Text("Number format", style = MaterialTheme.typography.bodyLarge)
        Segments(listOf("IN" to "1,23,456", "INTL" to "123,456"), if (ui.indianGrouping) "IN" else "INTL") { v -> c.prefs.updateUi { it.copy(indianGrouping = v == "IN") } }
        SettingLine("Show paise", "Example: " + AmountWords.inr(123456.5, indian = ui.indianGrouping, paiseShown = ui.showPaise) + " · also in the PDF and Excel") {
            Switch(ui.showPaise, { v -> c.prefs.updateUi { it.copy(showPaise = v) } }, modifier = Modifier.semantics { contentDescription = "Show paise" })
        }
        CurrencyDropdown(ui.defaultCurrency, { v -> c.prefs.updateUi { it.copy(defaultCurrency = v) } }, Modifier.fillMaxWidth().padding(top = 6.dp), label = "Default currency for new claims")
        OutlinedTextField(city, { city = it.take(40); c.prefs.updateUi { u -> u.copy(homeCity = city) } }, label = { Text("Home city (e.g. Bengaluru)") },
            supportingText = { Text("Pre-fills new claims; a claim in your home city gets a reminder to check your travel policy.") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        SettingLine("Batch scanning", "After each bill photo, the camera opens again for the next one") {
            Switch(ui.batchScan, { v -> c.prefs.updateUi { it.copy(batchScan = v) } }, modifier = Modifier.semantics { contentDescription = "Batch scanning" })
        }
        SettingLine("Reminders", "Claim not submitted 7 days after the trip · bills still to review") {
            Switch(ui.reminders, { v -> c.prefs.updateUi { it.copy(reminders = v) } }, modifier = Modifier.semantics { contentDescription = "Reminders" })
        }
    }
}

/** Settings → Automatic backup: weekly ZIP into a folder the person picks. */
@Composable
fun AutoBackupCard() {
    val ctx = LocalContext.current
    val c = ctx.container
    val ui by c.prefs.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
            c.prefs.updateUi { it.copy(backupFolder = uri.toString()) }
            Toast.makeText(ctx, "Weekly backup turned on", Toast.LENGTH_SHORT).show()
        }
    }
    SectionCard(title = "Automatic backup") {
        val on = ui.backupFolder.isNotBlank()
        Text(
            if (!on) "Off. Choose a folder (phone storage or Google Drive) and the app saves a backup ZIP there every week, keeping the last 4."
            else "On · every week · " + (if (ui.lastAutoBackup > 0) "last backup ${Fmt.dateTime(ui.lastAutoBackup)}" else "first backup within a day"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = { safeLaunch(ctx) { pick.launch(null) } }) { Text(if (on) "Change folder" else "Choose folder") }
            if (on) TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { val r = withContext(Dispatchers.IO) { runCatching { AutoBackup.run(ctx) } }; busy = false
                    Toast.makeText(ctx, r.fold({ "Saved $it" }, { "Backup failed: ${it.message}" }), Toast.LENGTH_LONG).show() }
            }) { Text(if (busy) "Backing up…" else "Back up now") }
            if (on) TextButton(onClick = { c.prefs.updateUi { it.copy(backupFolder = "") } }) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
        }
        Text("Backups contain client data - choose a private folder.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
