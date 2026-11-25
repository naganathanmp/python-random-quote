package com.tareimbursement.app.ui

import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Verified

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.BuildConfig
import com.tareimbursement.app.MainActivity
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ExpenseCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(nav: NavHostController, modifier: Modifier) {
    val ctx = LocalContext.current
    var crashLogExists by remember { mutableStateOf(com.tareimbursement.app.service.CrashLog.exists(ctx)) }
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val profile by c.prefs.profile.collectAsState()
    val limits by c.prefs.limits.collectAsState()
    var limitsOpen by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var restoreConfirm by remember { mutableStateOf<android.net.Uri?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            busy = "Creating backup…"
            try { withContext(Dispatchers.IO) { c.backup.backup(uri) }; Toast.makeText(ctx, "Backup saved", Toast.LENGTH_LONG).show() }
            catch (e: Exception) { Toast.makeText(ctx, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show() }
            busy = null
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreConfirm = uri }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            SectionCard(title = "Profile") {
                SettingRow(Icons.Outlined.Person, profile.name, "${profile.employeeCode}${if (profile.designation.isNotBlank()) " · ${profile.designation}" else ""}\nClaims e-mailed to: ${profile.approverEmail.ifBlank { "not set" }}") { nav.navigate("profile") }
            }
        }
        item { AiSettingsCard() }
        item {
            SectionCard(title = "Team & documents") {
                SettingRow(Icons.Outlined.Group, "Team sharing",
                    c.supabase.session?.let { "Signed in as ${it.contact}" } ?: "Share projects with your team, sync bills and chat") { nav.navigate(Routes.ACCOUNT) }
                SettingRow(Icons.Outlined.Verified, "Verify a document", "Check the digital signature of a TA statement PDF or Excel") { nav.navigate(Routes.VERIFY) }
            }
        }
        item {
            SectionCard(title = "Policy limits") {
                SettingRow(Icons.Outlined.Gavel, "Per-bill limits & own-vehicle rate", "Own vehicle ₹${Fmt.amountInput(c.prefs.mileageRate)}/km · " + if (limits.isEmpty()) "no per-bill limits set" else limits.entries.joinToString { "${it.key.shortLabel} ${Fmt.inr(it.value)}" }) { limitsOpen = true }
            }
        }
        item {
            SectionCard(title = "Backup & restore") {
                SettingRow(Icons.Outlined.Backup, "Back up all data", "Saves every claim, bill, output and setting into one ZIP file. It contains client data — keep it in a private place.") {
                    safeLaunch(ctx) { backupLauncher.launch("TA-Reimburse-backup-${LocalDate.now()}.zip") }
                }
                SettingRow(Icons.Outlined.Restore, "Restore from backup", "Replaces the data on this phone with a backup file.") { safeLaunch(ctx) { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } }
                busy?.let { Text(it, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
        item {
            SectionCard(title = "Privacy") {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Bills are read on this phone (text scanner + optional on-device AI); no AI service or cloud reads them. Claims stay in the app's private storage. Only if you turn on Team sharing for a project are that project's bills and chat sent to the team server you set up, over HTTPS. Otherwise files leave the phone only when you choose Download, E-mail or Share.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (crashLogExists) item {
            SectionCard(title = "Error report") {
                Text("The app recorded an unexpected error (technical details only — no claim data).", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = {
                        if (!com.tareimbursement.app.service.Sharing.share(ctx, listOf(com.tareimbursement.app.service.CrashLog.file(ctx)), "TA Reimburse error report", "Error report from TA Reimburse ${BuildConfig.VERSION_NAME}"))
                            Toast.makeText(ctx, "No app available to share", Toast.LENGTH_LONG).show()
                    }) { Text("Share report") }
                    TextButton(onClick = { com.tareimbursement.app.service.CrashLog.clear(ctx); crashLogExists = false }) { Text("Clear") }
                }
            }
        }
        item { Text("TA Reimburse ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)) }
    }

    if (limitsOpen) {
        val edits = remember { mutableStateMapOf<ExpenseCategory, String>().apply { ExpenseCategory.entries.forEach { put(it, Fmt.amountInput(limits[it])) } } }
        var rateText by remember { mutableStateOf(Fmt.amountInput(c.prefs.mileageRate)) }
        AlertDialog(
            onDismissRequest = { limitsOpen = false },
            title = { Text("Policy settings") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MoneyField("Own vehicle rate per km", rateText, { rateText = it }, supporting = "Used for new own-vehicle trips (km × rate + toll).")
                    Text("Per-bill limits — leave blank for no limit.", style = MaterialTheme.typography.bodySmall)
                    ExpenseCategory.entries.forEach { cat -> MoneyField(cat.label, edits[cat] ?: "", { edits[cat] = it }) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ExpenseCategory.entries.forEach { cat -> c.prefs.saveLimit(cat, parseMoney(edits[cat] ?: "")) }
                    parseMoney(rateText)?.takeIf { it > 0 }?.let { c.prefs.mileageRate = it }
                    limitsOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { limitsOpen = false }) { Text("Cancel") } },
        )
    }

    restoreConfirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreConfirm = null },
            title = { Text("Restore this backup?") },
            text = { Text("All current claims and settings on this phone will be replaced by the backup. The app will restart.") },
            confirmButton = {
                TextButton(onClick = {
                    restoreConfirm = null
                    scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        busy = "Restoring…"
                        try {
                            withContext(Dispatchers.IO) {
                                ctx.contentResolver.openInputStream(uri)?.use { input -> c.backup.restore(input) { c.db.close() } } ?: throw IllegalStateException("Cannot read file")
                            }
                            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            ctx.startActivity(i)
                            exitProcess(0)
                        } catch (e: Exception) {
                            busy = null
                            Toast.makeText(ctx, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { restoreConfirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
