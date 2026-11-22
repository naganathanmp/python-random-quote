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
import com.tareimbursement.app.data.TripEntity
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
    var purpose by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf<Long?>(LocalDate.now().toEpochDay()) }
    var end by rememberSaveable { mutableStateOf<Long?>(LocalDate.now().toEpochDay()) }
    var advance by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        if (tripId != null && !loaded) {
            c.repo.trips.get(tripId)?.let { t ->
                title = t.title; client = t.clientName; code = t.projectCode; location = t.location; purpose = t.purpose
                start = t.startEpochDay; end = t.endEpochDay; advance = Fmt.amountInput(t.advance.takeIf { it > 0 })
            }
            loaded = true
        }
    }
    val periodError = start != null && end != null && end!! < start!!
    val advanceValue = if (advance.isBlank()) 0.0 else parseMoney(advance)
    val valid = title.isNotBlank() && client.isNotBlank() && code.isNotBlank() && start != null && end != null && !periodError && advanceValue != null

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
            OutlinedTextField(location, { location = it }, label = { Text("Place(s) of visit") }, singleLine = true, keyboardOptions = words, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose of visit") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("From *", start?.let { LocalDate.ofEpochDay(it) }, { start = it.toEpochDay(); if (end == null || end!! < start!!) end = start }, Modifier.weight(1f))
                DateField("To *", end?.let { LocalDate.ofEpochDay(it) }, { end = it.toEpochDay() }, Modifier.weight(1f), isError = periodError, supporting = if (periodError) "Before start" else null)
            }
            MoneyField("Advance received (if any)", advance, { advance = it }, isError = advanceValue == null, supporting = "Deducted to show the net amount payable / refundable.")
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
                                startEpochDay = start!!, endEpochDay = end!!, advance = advanceValue ?: 0.0))
                            val shared = c.pendingShared.value
                            if (shared.isNotEmpty()) { c.jobs.startImport(id, shared); c.pendingShared.value = emptyList() }
                            nav.navigate(Routes.trip(id)) { popUpTo(Routes.NEW_TRIP) { inclusive = true } }
                        } else {
                            c.repo.trips.get(tripId)?.let { t ->
                                c.repo.updateTrip(t.copy(title = title.trim(), clientName = client.trim(), projectCode = code.trim(), location = location.trim(), purpose = purpose.trim(),
                                    startEpochDay = start!!, endEpochDay = end!!, advance = advanceValue ?: 0.0))
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
