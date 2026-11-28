package com.tareimbursement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tareimbursement.app.container
import com.tareimbursement.app.data.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onboarding: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalContext.current.container
    val p by c.prefs.profile.collectAsState()
    var name by rememberSaveable { mutableStateOf(p.name) }
    var code by rememberSaveable { mutableStateOf(p.employeeCode) }
    var designation by rememberSaveable { mutableStateOf(p.designation) }
    var department by rememberSaveable { mutableStateOf(p.department) }
    var base by rememberSaveable { mutableStateOf(p.baseLocation) }
    var org by rememberSaveable { mutableStateOf(p.organisation) }
    var email by rememberSaveable { mutableStateOf(p.email) }
    var approver by rememberSaveable { mutableStateOf(p.approverEmail) }
    var cc by rememberSaveable { mutableStateOf(p.ccEmail) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val emailOk = { s: String -> s.isBlank() || s.split(',').all { Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it.trim()) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onboarding) "Welcome" else "Your profile") },
                navigationIcon = { if (!onboarding) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onboarding) {
                Text("TA Reimburse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Snap or upload your bills, review what was read, and get a ready-to-submit TA statement (Excel), one combined invoices PDF and renamed receipts. Everything is processed and stored only on this phone.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text("These details appear on every TA statement.", style = MaterialTheme.typography.titleSmall)
            }
            fun cap() = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            OutlinedTextField(name, { name = it }, label = { Text("Full name *") }, singleLine = true, isError = tried && name.isBlank(), keyboardOptions = cap(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("Employee code *") }, singleLine = true, isError = tried && code.isBlank(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(designation, { designation = it }, label = { Text("Designation") }, singleLine = true, keyboardOptions = cap(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(department, { department = it }, label = { Text("Department / Division") }, singleLine = true, keyboardOptions = cap(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(base, { base = it }, label = { Text("Base location") }, singleLine = true, keyboardOptions = cap(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(org, { org = it }, label = { Text("Organisation / firm name (statement heading)") }, singleLine = true, keyboardOptions = cap(), modifier = Modifier.fillMaxWidth())
            val mailKb = KeyboardOptions(keyboardType = KeyboardType.Email)
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Your e-mail") }, singleLine = true, isError = !emailOk(email), keyboardOptions = mailKb, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(approver, { approver = it.trim() }, label = { Text("Send claims to (approver / accounts e-mail)") }, singleLine = true, isError = !emailOk(approver), keyboardOptions = mailKb,
                supportingText = { Text("Pre-filled when you e-mail a statement. Separate several with commas.") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cc, { cc = it.trim() }, label = { Text("CC (optional)") }, singleLine = true, isError = !emailOk(cc), keyboardOptions = mailKb, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    tried = true
                    if (name.isNotBlank() && code.isNotBlank() && emailOk(email) && emailOk(approver) && emailOk(cc)) {
                        c.prefs.saveProfile(Profile(name, code, designation, department, base, org, email, approver, cc))
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (onboarding) "Get started" else "Save") }
            if (tried && (name.isBlank() || code.isBlank())) Text("Name and employee code are required.", color = MaterialTheme.colorScheme.error)
        }
    }
}
