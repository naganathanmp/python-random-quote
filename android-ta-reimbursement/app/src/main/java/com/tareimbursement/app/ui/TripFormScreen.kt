package com.tareimbursement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.data.BillingEntity
import com.tareimbursement.app.data.SplitMode
import com.tareimbursement.app.data.TravellerEntity
import com.tareimbursement.app.data.TripEntity
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripFormScreen(tripId: Long?, nav: NavHostController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(tripId == null) }
    var title by rememberSaveable { mutableStateOf("") }
    var client by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("Internal Audit") }
    var onward by rememberSaveable { mutableStateOf("Self Booking") }
    var returnBy by rememberSaveable { mutableStateOf("Self Booking") }
    var comments by rememberSaveable { mutableStateOf("") }
    var budget by rememberSaveable { mutableStateOf("") }
    var dailyLimit by rememberSaveable { mutableStateOf("") }
    var limitPerPerson by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable { mutableStateOf<Long?>(LocalDate.now().toEpochDay()) }
    var end by rememberSaveable { mutableStateOf<Long?>(LocalDate.now().toEpochDay()) }
    var advance by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("INR") }
    var coTravellers by rememberSaveable(stateSaver = EditRowsSaver) { mutableStateOf(listOf<EditRow>()) }
    var splitOn by rememberSaveable { mutableStateOf(false) }
    var splitAuto by rememberSaveable { mutableStateOf(true) }
    var entityRows by rememberSaveable(stateSaver = EditRowsSaver) { mutableStateOf(listOf(EditRow(), EditRow())) }
    val profile by c.prefs.profile.collectAsState()
    var tried by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        if (tripId != null && !loaded) {
            c.repo.trips.get(tripId)?.let { t ->
                title = t.title; client = t.clientName; code = t.projectCode; location = t.location; purpose = t.purpose
                start = t.startEpochDay; end = t.endEpochDay; advance = Fmt.amountInput(t.advance.takeIf { it > 0 })
                currency = t.currency
                onward = t.onwardBookedBy; returnBy = t.returnBookedBy; comments = t.notes
                budget = Fmt.amountInput(t.budget); dailyLimit = Fmt.amountInput(t.dailyLimit); limitPerPerson = t.dailyLimitPerPerson
                coTravellers = c.repo.travellers.forTrip(t.id).filter { !it.isSelf }.map { EditRow(it.id, it.name, it.employeeCode) }
                val ents = c.repo.entities.forTrip(t.id)
                splitOn = t.split != SplitMode.NONE
                splitAuto = t.split != SplitMode.MANUAL
                if (ents.isNotEmpty()) entityRows = ents.map { EditRow(it.id, it.name, it.code, if (it.sharePercent > 0) Fmt.amountInput(it.sharePercent) else "") }
            }
            loaded = true
        }
    }
    val periodError = start != null && end != null && end!! < start!!
    val advanceValue = if (advance.isBlank()) 0.0 else parseMoney(advance)
    val namedEntities = entityRows.filter { it.a.isNotBlank() }
    val pcts = namedEntities.map { it.c.toDoubleOrNull() ?: 0.0 }
    val splitError = when {
        !splitOn -> null
        namedEntities.size < 2 -> "Add at least two client entities to split the billing."
        splitAuto && pcts.any { it > 0 } && (pcts.any { it <= 0 } || kotlin.math.abs(pcts.sum() - 100.0) > 0.5) -> "Percentages must be set for every entity and add up to 100 (now ${"%.1f".format(pcts.sum())})."
        else -> null
    }
    val budgetValue = if (budget.isBlank()) null else parseMoney(budget)
    val dailyValue = if (dailyLimit.isBlank()) null else parseMoney(dailyLimit)
    val budgetError = budget.isNotBlank() && budgetValue == null
    val dailyError = dailyLimit.isNotBlank() && dailyValue == null
    val valid = !budgetError && !dailyError && title.isNotBlank() && client.isNotBlank() && code.isNotBlank() && location.isNotBlank() && purpose.isNotBlank() && start != null && end != null && !periodError && advanceValue != null && splitError == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tripId == null) "New TA claim" else "Edit claim details") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            OutlinedTextField(title, { title = it }, label = { Text("Project / assignment name *") }, singleLine = true, isError = tried && title.isBlank(), keyboardOptions = words, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(client, { client = it }, label = { Text("Client name *") }, singleLine = true, isError = tried && client.isBlank(), keyboardOptions = words, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("Project / engagement code *") }, singleLine = true, isError = tried && code.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(location, { location = it }, label = { Text("Client location visited *") }, singleLine = true, isError = tried && location.isBlank(), keyboardOptions = words, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Nature of audit *") }, singleLine = true, isError = tried && purpose.isBlank(),
                keyboardOptions = words, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("From *", start?.let { LocalDate.ofEpochDay(it) }, { start = it.toEpochDay(); if (end == null || end!! < start!!) end = start }, Modifier.weight(1f))
                DateField("To *", end?.let { LocalDate.ofEpochDay(it) }, { end = it.toEpochDay() }, Modifier.weight(1f), isError = periodError, supporting = if (periodError) "Before start" else null)
            }
            MoneyField("Advance received (if any)", advance, { advance = it }, isError = advanceValue == null, supporting = "Deducted to show the net amount payable / refundable.")
            MoneyField("Approved budget (optional)", budget, { budget = it }, isError = budgetError,
                supporting = "Overall amount approved by the partner — tracked against actual spend.")
            MoneyField("Daily spend limit (optional)", dailyLimit, { dailyLimit = it }, isError = dailyError,
                supporting = "Days that cross the limit are flagged.")
            if (dailyLimit.isNotBlank() && coTravellers.any { it.a.isNotBlank() }) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(limitPerPerson, { limitPerPerson = it })
                Text("Limit applies per traveller (each person's share per day)", style = MaterialTheme.typography.bodyMedium)
            }
            CurrencyDropdown(currency, { currency = it }, label = "Default bill currency",
                modifier = Modifier.fillMaxWidth())
            if (currency != "INR") Text("New bills default to $currency; each bill can have its own currency and exchange rate. The statement is in ₹.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("To & fro tickets booked by", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BookedByField("Onward", onward, { onward = it }, Modifier.weight(1f))
                BookedByField("Return", returnBy, { returnBy = it }, Modifier.weight(1f))
            }
            OutlinedTextField(comments, { comments = it }, label = { Text("Comments (optional)") },
                supportingText = { Text("E.g. a team member joined directly from another client location.") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth(), minLines = 2)

            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Travellers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("You: ${profile.name} (${profile.employeeCode}). Add everyone travelling — the claim covers all, and each bill records every person's share.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TravellerRows(coTravellers) { coTravellers = it }

            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Split billing across client entities", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("For clients that bill different entities / project codes for the same trip.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(splitOn, { splitOn = it })
            }
            if (splitOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(splitAuto, { splitAuto = true }, label = { Text("Auto split") })
                    androidx.compose.material3.FilterChip(!splitAuto, { splitAuto = false }, label = { Text("I'll assign each bill") })
                }
                Text(if (splitAuto) "Bills are distributed as evenly as possible (or by the % you set) without splitting any invoice. You can still fix a bill to an entity."
                     else "Choose the client entity on each bill.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EntityRows(entityRows, showShare = splitAuto) { entityRows = it }
                splitError?.let { if (tried) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (tried && !valid) Text("Please fill the required fields (*).", color = MaterialTheme.colorScheme.error)
            Button(
                enabled = loaded && !saving,
                onClick = {
                    tried = true
                    if (!valid) return@Button
                    saving = true
                    scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        if (tripId == null) {
                            val id = c.repo.createTrip(TripEntity(title = title.trim(), clientName = client.trim(), projectCode = code.trim(), location = location.trim(), purpose = purpose.trim(),
                                startEpochDay = start!!, endEpochDay = end!!, advance = advanceValue ?: 0.0, currency = currency,
                                onwardBookedBy = onward, returnBookedBy = returnBy, notes = comments.trim(),
                                budget = budgetValue?.takeIf { it > 0 }, dailyLimit = dailyValue?.takeIf { it > 0 }, dailyLimitPerPerson = limitPerPerson), profile.name, profile.employeeCode)
                            saveExtras(c, id, profile.name, profile.employeeCode, coTravellers, splitOn, splitAuto, entityRows)
                            val shared = c.pendingShared.value
                            if (shared.isNotEmpty()) { c.jobs.startImport(id, shared); c.pendingShared.value = emptyList() }
                            nav.navigate(Routes.trip(id)) { popUpTo(Routes.NEW_TRIP) { inclusive = true } }
                        } else {
                            c.repo.trips.get(tripId)?.let { t ->
                                c.repo.updateTrip(t.copy(title = title.trim(), clientName = client.trim(), projectCode = code.trim(), location = location.trim(), purpose = purpose.trim(),
                                    startEpochDay = start!!, endEpochDay = end!!, advance = advanceValue ?: 0.0, currency = currency,
                                    onwardBookedBy = onward, returnBookedBy = returnBy, notes = comments.trim(),
                                    budget = budgetValue?.takeIf { it > 0 }, dailyLimit = dailyValue?.takeIf { it > 0 }, dailyLimitPerPerson = limitPerPerson))
                                saveExtras(c, t.id, profile.name, profile.employeeCode, coTravellers, splitOn, splitAuto, entityRows)
                            }
                            nav.popBackStack()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (tripId == null) "Create claim" else "Save changes") }
        }
    }
}

private suspend fun saveExtras(
    c: com.tareimbursement.app.AppContainer, tripId: Long, selfName: String, selfCode: String,
    co: List<EditRow>, splitOn: Boolean, auto: Boolean, entities: List<EditRow>,
) {
    c.repo.saveTravellers(tripId, selfName, selfCode, co.filter { it.a.isNotBlank() }.map { TravellerEntity(id = it.id, tripId = tripId, name = it.a, employeeCode = it.b) })
    val mode = if (!splitOn) SplitMode.NONE else if (auto) SplitMode.AUTO else SplitMode.MANUAL
    c.repo.saveEntities(tripId, mode, entities.filter { it.a.isNotBlank() }.map { BillingEntity(id = it.id, tripId = tripId, name = it.a, code = it.b, sharePercent = if (auto) it.c.toDoubleOrNull() ?: 0.0 else 0.0) })
}
