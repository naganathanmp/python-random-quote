package com.tareimbursement.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.core.Shares
import com.tareimbursement.app.core.sign.PdfSigner
import com.tareimbursement.app.core.sign.Signing
import com.tareimbursement.app.data.TravellerEntity
import com.tareimbursement.app.service.sync.SupabaseClient
import com.tareimbursement.app.service.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun shareText(ctx: android.content.Context, subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    safeLaunch(ctx) { ctx.startActivity(Intent.createChooser(i, "Send invitation")) }
}

/** "TA1:" + base64(url|key): lets a team member set up the same server by pasting one line. */
object ServerCode {
    fun encode(url: String, key: String) = "TA1:" + android.util.Base64.encodeToString("$url|$key".toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    fun decode(code: String): Pair<String, String>? = try {
        val raw = String(android.util.Base64.decode(code.trim().removePrefix("TA1:"), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE))
        raw.split('|', limit = 2).takeIf { it.size == 2 && it[0].startsWith("https://") }?.let { it[0] to it[1] }
    } catch (e: Exception) { null }
}

// ===================================================================== Team sharing (sign in)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(c.prefs.serverUrl) }
    var key by rememberSaveable { mutableStateOf(c.prefs.serverKey) }
    var code by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf(c.prefs.profile.value.email) }
    var otp by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(c.supabase.signedIn) }
    val syncErr by c.sync.lastError.collectAsState()
    val syncing by c.sync.syncing.collectAsState()

    fun run(block: suspend () -> Unit) = scope.launch(Dispatchers.Main.immediate) {
        busy = true; msg = null
        try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { msg = e.message ?: "Failed" }
        busy = false
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Team sharing") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Share a project with your team: everyone adds their own bills from their phone, sees the team's spend, and discusses bills in the project chat. " +
                    "Only shared projects go to the team server; other claims stay on this phone.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                SectionCard(title = "1. Team server") {
                    Text("Your firm's Supabase project (set up once by the admin - see the user guide). Paste the team server code from an invitation, or enter the URL and public anon key.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { v -> code = v.trim(); ServerCode.decode(code)?.let { (u, k) -> url = u; key = k } }, label = { Text("Team server code (TA1:…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("Project URL (https://….supabase.co)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it.trim() }, label = { Text("Public anon key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = {
                            if (!com.tareimbursement.app.service.sync.ServerConfig(url.trimEnd('/'), key).valid) { msg = "Enter an https:// URL and the anon key"; return@TextButton }
                            if (key.contains("service_role")) { msg = "That is the secret service key - use the public anon key instead."; return@TextButton }
                            c.prefs.serverUrl = url; c.prefs.serverKey = key; Toast.makeText(ctx, "Server saved", Toast.LENGTH_SHORT).show()
                        }) { Text("Save server") }
                        if (c.prefs.serverUrl.isNotBlank()) TextButton(onClick = { shareText(ctx, "TA Reimburse team server", ServerCode.encode(c.prefs.serverUrl, c.prefs.serverKey)) }) { Text("Share code") }
                    }
                }
            }
            item {
                SectionCard(title = "2. Sign in") {
                    val s = c.supabase.session
                    if (signedIn && s != null) {
                        Text("Signed in as ${s.contact}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row {
                            TextButton(enabled = !syncing, onClick = { run { SyncWorker.notify(ctx, c.sync.syncAll()) } }) { Text(if (syncing) "Syncing…" else "Sync now") }
                            TextButton(onClick = { run { c.supabase.signOut() }; SyncWorker.cancel(ctx); signedIn = false; sent = false }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
                        }
                        syncErr?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text("Use the e-mail (or mobile number) your project owner invited. A 6-digit code is sent to it - no password.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(contact, { contact = it }, label = { Text("E-mail or mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        if (sent) OutlinedTextField(otp, { v -> if (v.length <= 8 && v.all { it.isDigit() }) otp = v }, label = { Text("6-digit code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        Row {
                            Button(enabled = !busy && SupabaseClient.isValidContact(contact), onClick = {
                                if (!c.supabase.let { com.tareimbursement.app.service.sync.ServerConfig(c.prefs.serverUrl, c.prefs.serverKey).valid }) { msg = "Save the team server first"; return@Button }
                                run { c.supabase.sendCode(SupabaseClient.normalizeContact(contact)); sent = true; msg = "Code sent to ${SupabaseClient.normalizeContact(contact)}" }
                            }) { Text(if (sent) "Resend code" else "Send code") }
                            Spacer(Modifier.width(8.dp))
                            if (sent) Button(enabled = !busy && otp.length >= 6, onClick = {
                                run {
                                    c.supabase.verifyCode(SupabaseClient.normalizeContact(contact), otp)
                                    signedIn = true; otp = ""
                                    SyncWorker.schedule(ctx)
                                    c.sync.syncAll()
                                }
                            }) { Text("Verify") }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Code sent")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }
            item {
                Text("Security: the app only holds the public key; the server's row-level security lets each person see only the projects they own or were invited to. Bills and chat travel over HTTPS.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ===================================================================== Team of a project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(tripId: Long, nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val trip by remember(tripId) { c.repo.trips.observe(tripId) }.collectAsState(initial = null)
    val people by remember(tripId) { c.repo.travellers.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val expenses by remember(tripId) { c.repo.expenses.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var inviteFor by remember { mutableStateOf<TravellerEntity?>(null) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    val t = trip
    val owner = t != null && (!t.shared || t.myRole == "OWNER")
    val signedIn = c.supabase.signedIn

    fun run(block: suspend () -> Unit) = scope.launch(Dispatchers.Main.immediate) {
        busy = true; msg = null
        try { block() } catch (e: Exception) { msg = e.message ?: "Failed" }
        busy = false
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Team") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { pad ->
        if (t == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!t.shared) item {
                SectionCard(title = "Share this project") {
                    Text("Invite the people travelling with you by e-mail or mobile number. Each of them adds their own bills on their phone; everyone sees the team's spend and can chat about bills. You stay the owner: you generate and submit the TA statement.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!signedIn) OutlinedButton(onClick = { nav.navigate(Routes.ACCOUNT) }) { Text("Set up team sharing first") }
                    else Button(enabled = !busy, onClick = { run { c.sync.shareTrip(tripId); msg = "Shared. Now invite your team below." } }) { Text("Share with team") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("People on this claim", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (owner) TextButton(onClick = { addOpen = true }) { Text("Add person") }
                }
            }
            items(people, key = { it.id }) { p ->
                val share = expenses.sumOf { e -> Shares.parse(e.shares)[p.id] ?: if (people.size <= 1) (e.amount ?: 0.0) else 0.0 }
                val added = expenses.count { it.addedByUid.isNotBlank() && it.addedByUid == p.userUid }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label + if (p.isSelf) " (you)" else "", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    p.userUid.isNotBlank() && t.ownerUid == p.userUid -> "Owner"
                                    p.inviteStatus == "JOINED" -> "On the app"
                                    p.inviteStatus == "INVITED" -> "Invited: ${p.contact}"
                                    else -> "Not invited"
                                } + " · spend ${Fmt.inr(share)}" + if (added > 0) " · added $added bill(s)" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (owner && t.shared && !p.isSelf && p.inviteStatus != "JOINED") TextButton(onClick = { inviteFor = p }) { Text(if (p.inviteStatus == "INVITED") "Re-send" else "Invite") }
                        if (owner && t.shared && !p.isSelf && p.inviteStatus == "JOINED") TextButton(onClick = { run { c.sync.removeMember(tripId, p.id) } }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (t.shared) item {
                OutlinedButton(onClick = { nav.navigate(Routes.chat(tripId)) }, modifier = Modifier.fillMaxWidth()) { Text("Open team chat") }
            }
            item {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Shared")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        }
    }

    inviteFor?.let { p ->
        var contact by remember(p.id) { mutableStateOf(p.contact) }
        AlertDialog(
            onDismissRequest = { inviteFor = null },
            title = { Text("Invite ${p.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Their e-mail or mobile number. They sign in with it in TA Reimburse and the project appears on their phone.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(contact, { contact = it }, label = { Text("E-mail or mobile") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = SupabaseClient.isValidContact(contact), onClick = {
                    inviteFor = null
                    run {
                        val text = c.sync.invite(tripId, p.id, contact)
                        shareText(ctx, "TA Reimburse invitation", text + "\nTeam server code: " + ServerCode.encode(c.prefs.serverUrl, c.prefs.serverKey))
                    }
                }) { Text("Invite & send") }
            },
            dismissButton = { TextButton(onClick = { inviteFor = null }) { Text("Cancel") } },
        )
    }

    if (addOpen) {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("Add person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(code, { code = it }, label = { Text("Employee code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    addOpen = false
                    run {
                        val prof = c.prefs.profile.value
                        c.repo.saveTravellers(tripId, prof.name, prof.employeeCode, people.filter { !it.isSelf } + TravellerEntity(tripId = tripId, name = name.trim(), employeeCode = code.trim()))
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } },
        )
    }
}

// ===================================================================== Chat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(tripId: Long, billUid: String, nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val trip by remember(tripId) { c.repo.trips.observe(tripId) }.collectAsState(initial = null)
    val messages by remember(tripId) { c.repo.messages.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val expenses by remember(tripId) { c.repo.expenses.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    var text by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf(billUid) }
    var error by remember { mutableStateOf<String?>(null) }
    val me = c.supabase.session?.userId
    val list = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH) }

    // Fresh while open: check for new messages every few seconds.
    LaunchedEffect(tripId) {
        while (true) {
            try { c.sync.syncTrip(tripId); error = null } catch (e: Exception) { error = e.message }
            c.repo.trips.get(tripId)?.let { c.repo.trips.update(it.copy(chatReadAt = System.currentTimeMillis())) }
            delay(5000)
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }

    fun billLabel(uid: String) = expenses.firstOrNull { it.uid == uid }?.let { "${it.vendor.ifBlank { it.description.take(30) }} · ${Fmt.date(it.date)} · ${Fmt.inr(it.amount)}" }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Team chat · ${trip?.title.orEmpty()}", maxLines = 1) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp)) {
                    if (about.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("About: ${billLabel(about) ?: "a bill"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { about = "" }) { Text("×") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(text, { if (it.length <= 4000) text = it }, placeholder = { Text("Message the team") }, modifier = Modifier.weight(1f), maxLines = 4)
                        IconButton(enabled = text.isNotBlank() && me != null, onClick = {
                            val body = text; val bill = about; text = ""; about = ""
                            scope.launch(Dispatchers.Main.immediate) { try { c.sync.sendMessage(tripId, body, bill) } catch (e: Exception) { error = e.message } }
                        }) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (me == null) item { Text("Sign in to Team sharing (Settings) to chat.", color = MaterialTheme.colorScheme.error) }
            error?.let { item { Text("Offline: $it - messages will be sent when you're back online.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
            if (messages.isEmpty()) item { Text("No messages yet. Ask about a bill, a booking or a policy question - everyone on the project sees it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(messages, key = { it.id }) { m ->
                val mine = m.authorUid == me
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 300.dp).background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(10.dp),
                    ) {
                        if (!mine) Text(m.authorName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (m.expenseUid.isNotBlank()) Text("Re: ${billLabel(m.expenseUid) ?: "a bill"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.clickable { expenses.firstOrNull { it.uid == m.expenseUid }?.let { nav.navigate(Routes.expense(it.id)) } })
                        Text(m.body, style = MaterialTheme.typography.bodyMedium)
                        Text(fmt.format(Date(m.createdAt)) + if (!m.sent) " · sending…" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ===================================================================== Verify a document

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<Pair<String, Signing.Verification>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun nameOf(u: Uri): String = try {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null } ?: u.lastPathSegment.orEmpty()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.Main.immediate) {
            busy = true
            results = withContext(Dispatchers.IO) {
                val files = uris.map { nameOf(it) to it }
                val read = { u: Uri -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0) }
                files.filter { !it.first.endsWith(".p7s", true) }.map { (name, u) ->
                    try {
                        val bytes = read(u)
                        val v = if (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF") {
                            val sig = files.firstOrNull { it.first.equals("$name.p7s", true) }
                            if (sig != null) Signing.verifyDetached(bytes, read(sig.second)) else PdfSigner.verify(bytes)
                        } else {
                            val sig = files.firstOrNull { it.first.equals("$name.p7s", true) } ?: files.firstOrNull { it.first.endsWith(".p7s", true) }
                            if (sig == null) Signing.Verification(false, null, null, message = "Select the matching signature file \"$name.p7s\" together with this file.")
                            else Signing.verifyDetached(bytes, read(sig.second))
                        }
                        name to v
                    } catch (e: Throwable) { name to Signing.Verification(false, null, null, message = "Could not read the file.") }
                }
            }
            busy = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Verify a document") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Checks the digital signature of a TA statement made by TA Reimburse: the combined PDF (signature inside the PDF), or the Excel statement together with its \".p7s\" signature file. A valid signature means the file is exactly as the person signed it.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item { Button(onClick = { safeLaunch(ctx) { picker.launch(arrayOf("*/*")) } }, enabled = !busy) { Text("Choose file(s)") } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(results) { (name, v) ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Verified, null, tint = if (v.valid && v.coversWholeFile) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(v.message, style = MaterialTheme.typography.bodyMedium, color = if (v.valid && v.coversWholeFile) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    v.signer?.let { Text("Signed by: $it", style = MaterialTheme.typography.bodySmall) }
                    v.signedAt?.let { Text("Signed on: ${it.toLocalDate()} ${it.toLocalTime().withNano(0)}", style = MaterialTheme.typography.bodySmall) }
                    if (v.valid) Text("Note: the certificate is issued by the person's own phone (not a government DSC), so it proves the file is unchanged since that phone signed it.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { HorizontalDivider() }
        }
    }
}
