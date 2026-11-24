package com.tareimbursement.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.service.ClaimOutputs
import com.tareimbursement.app.service.JobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Archived / submitted claim: outputs, approval & payment tracking, full history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaimScreen(tripId: Long, nav: NavHostController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val trip by remember(tripId) { c.repo.trips.observe(tripId) }.collectAsState(initial = null)
    val expenses by remember(tripId) { c.repo.expenses.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val atts by remember(tripId) { c.repo.attachments.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val events by remember(tripId) { c.repo.events.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val people by remember(tripId) { c.repo.travellers.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val budget by produceState<com.tareimbursement.app.core.BudgetTracker.Status?>(null, expenses, people) { value = c.repo.budgetStatus(tripId) }
    val jobs by c.jobs.states.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var paidDialog by rememberSaveable { mutableStateOf(false) }
    var approveDialog by rememberSaveable { mutableStateOf(false) }
    var rejectDialog by rememberSaveable { mutableStateOf(false) }
    var reopenDialog by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    val t = trip
    LaunchedEffect(t?.status) { if (t != null && !t.isArchived) nav.navigate(Routes.trip(tripId)) { popUpTo(Routes.claim(tripId)) { inclusive = true } } }
    val gen = jobs[c.jobs.generateKey(tripId)]
    val outputs by produceState<ClaimOutputs?>(null, t?.outputsGeneratedAt, gen) {
        value = t?.let { tr -> withContext(Dispatchers.IO) { c.exporter.outputsFor(tr).takeIf { it.exist() } } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Reopen for editing") }, leadingIcon = { Icon(Icons.Outlined.LockOpen, null) }, onClick = { menu = false; reopenDialog = true })
                        DropdownMenuItem(text = { Text("Delete claim") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; deleteDialog = true })
                    }
                },
            )
        },
    ) { pad ->
        if (t == null) return@Scaffold
        val total = expenses.sumOf { it.amount ?: 0.0 }
        val net = total - t.advance
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.claimNumber, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        StatusChip(t.claimStatus)
                    }
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Client", t.clientName); KeyValue("Project code", t.projectCode)
                    KeyValue("Period", Fmt.range(t.startDate, t.endDate)); KeyValue("Place of visit", t.location); KeyValue("Purpose", t.purpose)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Stat("Claimed", Fmt.inr(total), Modifier.weight(1f))
                        Stat(if (net >= 0) "Net payable" else "Refundable", Fmt.inr(kotlin.math.abs(net)), Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.paidAmount != null) Stat("Received", Fmt.inr(t.paidAmount), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                    }
                    if (t.notes.isNotBlank()) Text(t.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                }
            }
            budget?.let { b -> item { BudgetCard(b, people.associate { it.id to it.name }) } }
            item {
                when (t.claimStatus) {
                    ClaimStatus.SUBMITTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { approveDialog = true }, modifier = Modifier.weight(1f)) { Text("Mark approved") }
                        Button(onClick = { paidDialog = true }, modifier = Modifier.weight(1f)) { Text("Mark reimbursed") }
                        OutlinedButton(onClick = { rejectDialog = true }) { Text("Rejected") }
                    }
                    ClaimStatus.APPROVED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { paidDialog = true }, modifier = Modifier.weight(1f)) { Text("Mark reimbursed") }
                        OutlinedButton(onClick = { rejectDialog = true }) { Text("Rejected") }
                    }
                    ClaimStatus.REJECTED -> OutlinedButton(onClick = { reopenDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Reopen, correct and resubmit") }
                    else -> {}
                }
            }
            item {
                val o = outputs
                when {
                    gen is JobState.Running -> SectionCard { Text(gen.message); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                    o != null -> OutputsPanel(c, t, o)
                    else -> SectionCard(title = "Outputs") {
                        Text("The output files are missing (e.g. after a restore). Regenerate them from the archived bills.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { c.jobs.startGenerate(tripId, c.prefs.profile.value.toEmployee()) }) { Text("Regenerate") }
                    }
                }
            }
            item { Text("Bills (${expenses.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(expenses, key = { it.id }) { e -> ExpenseRow(e, atts.count { it.expenseId == e.id }) { nav.navigate(Routes.expense(e.id)) } }
            item { Text("History", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(events, key = { "ev${it.id}" }) { ev ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(10.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(ev.message, style = MaterialTheme.typography.bodyMedium)
                        Text(Fmt.dateTime(ev.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (paidDialog && t != null) {
        val suggested = (expenses.sumOf { it.amount ?: 0.0 } - t.advance).coerceAtLeast(0.0)
        AmountDateDialog("Reimbursement received", "Amount received", Fmt.amountInput(suggested), { paidDialog = false }) { amt, d ->
            paidDialog = false; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.markPaid(tripId, amt, d) }
        }
    }
    if (approveDialog) DateOnlyDialog("Claim approved on", { approveDialog = false }) { d -> approveDialog = false; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.markApproved(tripId, d) } }
    if (rejectDialog) {
        var reason by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectDialog = false },
            title = { Text("Claim returned / rejected") },
            text = { OutlinedTextField(reason, { reason = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { rejectDialog = false; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.markRejected(tripId, reason.trim()) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rejectDialog = false }) { Text("Cancel") } },
        )
    }
    if (reopenDialog) AlertDialog(
        onDismissRequest = { reopenDialog = false },
        title = { Text("Reopen this claim?") },
        text = { Text("The claim moves back to your open claims so you can edit bills and regenerate the statement. Approval/payment status will be cleared.") },
        confirmButton = { TextButton(onClick = { reopenDialog = false; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.reopen(tripId) } }) { Text("Reopen") } },
        dismissButton = { TextButton(onClick = { reopenDialog = false }) { Text("Cancel") } },
    )
    if (deleteDialog) AlertDialog(
        onDismissRequest = { deleteDialog = false },
        title = { Text("Delete this claim permanently?") },
        text = { Text("All bills, outputs and history for this claim will be removed from this phone. Files you downloaded or e-mailed are not affected.") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.deleteTrip(tripId); nav.popBackStack() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
    )
}

@Composable
fun AmountDateDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onSave: (Double, LocalDate) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(initial) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val v = parseMoney(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(label, amount, { amount = it }, isError = v == null)
                DateField("Date", LocalDate.ofEpochDay(date), { date = it.toEpochDay() })
            }
        },
        confirmButton = { TextButton(enabled = v != null, onClick = { onSave(v!!, LocalDate.ofEpochDay(date)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DateOnlyDialog(title: String, onDismiss: () -> Unit, onSave: (LocalDate) -> Unit) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { DateField("Date", LocalDate.ofEpochDay(date), { date = it.toEpochDay() }) },
        confirmButton = { TextButton(onClick = { onSave(LocalDate.ofEpochDay(date)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private fun submittedDate(millis: Long?) = millis?.let { Repository.toDate(it) }
