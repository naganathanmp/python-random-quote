package com.tareimbursement.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.AppContainer
import com.tareimbursement.app.container
import com.tareimbursement.app.data.EventType
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.data.ValidationIssue
import com.tareimbursement.app.service.ClaimOutputs
import com.tareimbursement.app.service.JobState
import com.tareimbursement.app.service.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(tripId: Long, nav: NavHostController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val trip by remember(tripId) { c.repo.trips.observe(tripId) }.collectAsState(initial = null)
    val expenses by remember(tripId) { c.repo.expenses.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val limits by c.prefs.limits.collectAsState()
    val jobs by c.jobs.states.collectAsState()
    val gen = jobs[c.jobs.generateKey(tripId)]
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val issues by produceState<List<ValidationIssue>?>(null, expenses, limits) { value = c.repo.validate(tripId, limits) }
    val entities by remember(tripId) { c.repo.entities.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    // Auto-split: preview the balanced allocation (stable: re-running changes nothing).
    LaunchedEffect(expenses.size, expenses.sumOf { it.amount ?: 0.0 }, entities, trip?.splitMode) {
        if (trip?.split == com.tareimbursement.app.data.SplitMode.AUTO) c.repo.autoAllocate(tripId)
    }
    val t = trip

    // Outputs from an earlier run are shown if they exist and nothing changed since.
    val existing by produceState<ClaimOutputs?>(null, t?.outputsGeneratedAt, gen) {
        value = t?.let { tr -> withContext(Dispatchers.IO) { c.exporter.outputsFor(tr).takeIf { it.exist() && tr.outputsGeneratedAt != null } } }
    }
    val outputs = (gen as? JobState.GenerateDone)?.outputs ?: existing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TA statement") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        if (t == null) return@Scaffold
        val blocking = issues?.filter { it.blocking }.orEmpty()
        val warnings = issues?.filter { !it.blocking }.orEmpty()
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val total = expenses.sumOf { it.amount ?: 0.0 }
            SectionCard(title = "${t.title} · ${t.claimNumber}") {
                Row {
                    Stat("Bills", expenses.size.toString(), Modifier.weight(0.6f))
                    Stat("Total", Fmt.inr(total), Modifier.weight(1f))
                    Stat(if (total - t.advance >= 0) "Net payable" else "Refundable", Fmt.inr(kotlin.math.abs(total - t.advance)), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                }
            }

            if (t.split != com.tareimbursement.app.data.SplitMode.NONE && entities.size >= 2) SectionCard(title = "Billing split by client entity") {
                entities.forEach { en ->
                    val sum = expenses.filter { it.entityId == en.id }.sumOf { it.amount ?: 0.0 }
                    val n = expenses.count { it.entityId == en.id }
                    KeyValue(en.label, "${Fmt.inr(sum)} · $n bill${if (n == 1) "" else "s"}" + if (total > 0) " · ${"%.1f".format(sum / total * 100)}%" else "")
                }
                val unassigned = expenses.count { e -> entities.none { it.id == e.entityId } }
                if (unassigned > 0) Text("$unassigned bill(s) not yet assigned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(if (t.split == com.tareimbursement.app.data.SplitMode.AUTO) "Auto split keeps every invoice whole; each entity gets its own statement and PDF."
                     else "Assigned manually on each bill; each entity gets its own statement and PDF.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (issues == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (blocking.isNotEmpty()) SectionCard(title = "Fix before generating") {
                blocking.forEach { IssueRow(it, true) { it.expenseId?.let { id -> nav.navigate(Routes.expense(id)) } } }
            }
            if (warnings.isNotEmpty()) SectionCard(title = "Please double-check") {
                warnings.forEach { IssueRow(it, false) { it.expenseId?.let { id -> nav.navigate(Routes.expense(id)) } } }
            }
            if (issues != null && issues!!.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(8.dp))
                Text("All bills reviewed — ready to generate.", style = MaterialTheme.typography.bodyMedium)
            }

            when (gen) {
                is JobState.Running -> SectionCard { Text(gen.message); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is JobState.Failed -> Text("Could not generate: ${gen.message}", color = MaterialTheme.colorScheme.error)
                else -> {}
            }

            Button(
                onClick = { c.jobs.startGenerate(tripId, c.prefs.profile.value.toEmployee()) },
                enabled = issues != null && blocking.isEmpty() && gen !is JobState.Running,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (outputs != null) "Regenerate outputs" else "Generate Excel, PDF & receipts") }

            if (outputs != null && gen !is JobState.Running) {
                OutputsPanel(c, t, outputs)
                Button(
                    onClick = { confirmArchive = true }, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                ) { Icon(Icons.Outlined.Archive, null); Spacer(Modifier.width(8.dp)); Text("Mark completed & archive") }
                Text("Archiving locks the claim and keeps all outputs in the Archive for future reference. You can track approval & payment there.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        title = { Text("Complete this TA claim?") },
        text = { Text("The claim will be marked as submitted and moved to the Archive with its Excel statement, combined PDF and receipts. You can reopen it later if changes are needed.") },
        confirmButton = {
            TextButton(onClick = {
                confirmArchive = false
                scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    c.repo.submit(tripId); c.jobs.clear(c.jobs.generateKey(tripId))
                    nav.navigate(Routes.claim(tripId)) { popUpTo(Routes.HOME) }
                }
            }) { Text("Complete & archive") }
        },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Not yet") } },
    )
}

@Composable
private fun IssueRow(issue: ValidationIssue, blocking: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = issue.expenseId != null, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(if (blocking) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, tint = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(issue.message, style = MaterialTheme.typography.bodySmall)
    }
}

fun emailSubject(t: TripEntity) = "TA Claim ${t.claimNumber} – ${t.title} (${t.clientName}) – ${Fmt.range(t.startDate, t.endDate)}"

suspend fun emailBody(c: AppContainer, t: TripEntity): String {
    val p = c.prefs.profile.value
    val ex = c.repo.expenses.forTrip(t.id)
    val total = ex.sumOf { it.amount ?: 0.0 }
    val net = total - t.advance
    return buildString {
        appendLine("Dear Sir/Madam,")
        appendLine()
        appendLine("Please find attached my travelling allowance (TA) claim for your approval.")
        appendLine()
        appendLine("Employee: ${p.name} (${p.employeeCode})")
        appendLine("Client / Project: ${t.clientName} / ${t.title} [${t.projectCode}]")
        appendLine("Period: ${Fmt.range(t.startDate, t.endDate)}${if (t.location.isNotBlank()) "  ·  ${t.location}" else ""}")
        appendLine("Number of bills: ${ex.size}")
        appendLine("Total claimed: ${Fmt.inr(total)}")
        if (t.advance > 0) appendLine("Less advance: ${Fmt.inr(t.advance)}")
        appendLine((if (net >= 0) "Net payable: " else "Refundable: ") + Fmt.inr(kotlin.math.abs(net)))
        appendLine()
        appendLine("Attachments: TA statement (Excel), combined invoices & supporting documents (PDF), renamed receipts (ZIP).")
        appendLine()
        appendLine("Regards,")
        appendLine(p.name)
    }
}

/** Open / download / e-mail / share actions for the three generated files. */
@Composable
fun OutputsPanel(c: AppContainer, t: TripEntity, outputs: ClaimOutputs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionCard(title = "Generated outputs") {
        listOf(
            Triple(outputs.excel, Icons.Outlined.TableChart, "TA statement (Excel, PKF format)"),
            Triple(outputs.pdf, Icons.Outlined.PictureAsPdf, "All invoices & supporting (single PDF)"),
            Triple(outputs.zip, Icons.Outlined.FolderZip, "Renamed receipts by head (ZIP)"),
        ).plus(outputs.extras.map { f ->
            val entity = f.nameWithoutExtension.substringAfter("${t.claimNumber}_", f.nameWithoutExtension)
            if (f.extension.equals("xlsx", true)) Triple(f, Icons.Outlined.TableChart, "TA statement – $entity")
            else Triple(f, Icons.Outlined.PictureAsPdf, "Invoices PDF – $entity")
        }).forEach { (f, icon, label) -> OutputRow(f, icon, label) { if (!Sharing.open(ctx, f)) Toast.makeText(ctx, "No app installed to open ${f.extension.uppercase()} files", Toast.LENGTH_LONG).show() } }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    try {
                        val path = withContext(Dispatchers.IO) { Sharing.saveToDownloads(ctx, outputs.all, t.claimNumber) }
                        c.repo.logEvent(t.id, EventType.SAVED, "Saved to $path")
                        Toast.makeText(ctx, "Saved to $path", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) { Toast.makeText(ctx, "Could not save: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Download") }
            FilledTonalButton(onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    val p = c.prefs.profile.value
                    val ok = Sharing.email(ctx, outputs.all, p.approverEmail.split(','), p.ccEmail.split(','), emailSubject(t), emailBody(c, t))
                    if (ok) c.repo.logEvent(t.id, EventType.SHARED, "E-mail drafted to ${p.approverEmail.ifBlank { "recipient" }}")
                    else Toast.makeText(ctx, "No e-mail app found", Toast.LENGTH_LONG).show()
                }
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Email, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("E-mail") }
            OutlinedButton(onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { Sharing.share(ctx, outputs.all, emailSubject(t), emailBody(c, t)); c.repo.logEvent(t.id, EventType.SHARED, "Shared outputs") }
            }) { Icon(Icons.Outlined.Share, "Share", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun OutputRow(f: File, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${f.name} · ${size(f.length())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

fun size(b: Long): String = when {
    b >= 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
