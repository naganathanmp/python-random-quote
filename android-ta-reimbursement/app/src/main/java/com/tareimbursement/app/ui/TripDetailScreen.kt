package com.tareimbursement.app.ui

import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.automirrored.outlined.Chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Airlines
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CarRental
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.LocalTaxi
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.service.JobState
import com.tareimbursement.app.service.Sharing
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

fun categoryIcon(c: ExpenseCategory?): ImageVector = when (c) {
    ExpenseCategory.AIR_FARE -> Icons.Outlined.FlightTakeoff
    ExpenseCategory.BUS_FARE -> Icons.Outlined.DirectionsBus
    ExpenseCategory.TRAIN_FARE -> Icons.Outlined.Train
    ExpenseCategory.PICKUP_DROP -> Icons.Outlined.Airlines
    ExpenseCategory.BOARDING -> Icons.Outlined.Hotel
    ExpenseCategory.EXPENSES_DURING_TRAVEL -> Icons.Outlined.Restaurant
    ExpenseCategory.LAUNDRY -> Icons.Outlined.LocalLaundryService
    ExpenseCategory.LOCAL_CONVEYANCE -> Icons.Outlined.LocalTaxi
    ExpenseCategory.TELEPHONE -> Icons.Outlined.Phone
    ExpenseCategory.VISA -> Icons.Outlined.Badge
    ExpenseCategory.INSURANCE -> Icons.Outlined.Security
    ExpenseCategory.CAR_HIRE -> Icons.Outlined.CarRental
    ExpenseCategory.CAR_PARKING -> Icons.Outlined.LocalParking
    ExpenseCategory.OTHER -> Icons.Outlined.TravelExplore
    null -> Icons.Outlined.ReceiptLong
}

/** Camera capture helper shared by the claim and expense screens. */
@Composable
fun rememberCamera(onCaptured: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pending; pending = null
        if (ok && path != null && File(path).length() > 0) onCaptured(Uri.fromFile(File(path)))
        else path?.let { File(it).delete() }
    }
    return remember {
        {
            val f = File(ctx.cacheDir, "camera/Camera_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
            pending = f.path
            try { launcher.launch(Sharing.uriFor(ctx, f)) } catch (e: Exception) {
                pending = null; Toast.makeText(ctx, "No camera app available", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(tripId: Long, nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val trip by remember(tripId) { c.repo.trips.observe(tripId) }.collectAsState(initial = null)
    val expenses by remember(tripId) { c.repo.expenses.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val atts by remember(tripId) { c.repo.attachments.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val people by remember(tripId) { c.repo.travellers.observeForTrip(tripId) }.collectAsState(initial = emptyList())
    val budget by androidx.compose.runtime.produceState<com.tareimbursement.app.core.BudgetTracker.Status?>(null, expenses, trip, people) { value = c.repo.budgetStatus(tripId) }
    val jobs by c.jobs.states.collectAsState()
    val importState = jobs[c.jobs.importKey(tripId)]
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var lastCapturedFlow by rememberSaveable { mutableStateOf(false) }
    var supportFor by rememberSaveable { mutableStateOf<Long?>(null) }

    val t = trip
    val manage = t == null || com.tareimbursement.app.service.sync.SyncEngine.canManage(t)
    val myUid = c.supabase.session?.userId.orEmpty()
    val unread by androidx.compose.runtime.produceState(0, t?.chatReadAt, t?.syncedAt) {
        value = c.repo.messages.forTrip(tripId).count { it.createdAt > (t?.chatReadAt ?: 0) && it.authorUid != myUid }
    }
    // Team claims stay fresh while open.
    LaunchedEffect(t?.shared) {
        while (t?.shared == true) { try { c.sync.syncTrip(tripId) } catch (_: Exception) { }; kotlinx.coroutines.delay(30_000) }
    }
    LaunchedEffect(t?.status) { if (t != null && t.isArchived) { nav.navigate(Routes.claim(tripId)) { popUpTo(Routes.trip(tripId)) { inclusive = true } } } }

    val supportCamera = rememberCamera { uri -> supportFor?.let { c.jobs.startImport(tripId, listOf(uri), it) } }
    val camera = rememberCamera { uri -> lastCapturedFlow = true; c.jobs.startImport(tripId, listOf(uri)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { lastCapturedFlow = false; c.jobs.startImport(tripId, uris) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (t?.shared == true) IconButton(onClick = { nav.navigate(Routes.chat(tripId)) }) {
                        androidx.compose.material3.BadgedBox(badge = { if (unread > 0) androidx.compose.material3.Badge { Text("$unread") } }) {
                            Icon(Icons.AutoMirrored.Outlined.Chat, "Team chat")
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.team(tripId)) }) { Icon(Icons.Outlined.Group, "Team") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        if (manage) DropdownMenuItem(text = { Text("Edit claim details") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; nav.navigate(Routes.editTrip(tripId)) })
                        DropdownMenuItem(text = { Text("Add expense without bill") }, leadingIcon = { Icon(Icons.Outlined.AddCircleOutline, null) }, onClick = {
                            menu = false
                            scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { val id = c.repo.addManualExpense(tripId, t?.startDate); nav.navigate(Routes.expense(id)) }
                        })
                        DropdownMenuItem(text = { Text("Own vehicle trip (km × rate)") }, leadingIcon = { Icon(Icons.Outlined.DirectionsCar, null) }, onClick = {
                            menu = false
                            scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { val id = c.repo.addMileageExpense(tripId, t?.startDate, c.prefs.mileageRate); nav.navigate(Routes.expense(id)) }
                        })
                        DropdownMenuItem(text = { Text(if (manage) "Delete claim" else "Remove from this phone") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        bottomBar = {
            if (expenses.isNotEmpty() && !manage) Surface(tonalElevation = 3.dp) {
                Text("You're a team member: add and review your bills here. The project owner generates and submits the TA statement.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
            } else if (expenses.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { nav.navigate(Routes.generate(tripId)) }, enabled = importState !is JobState.Running,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(50.dp),
                ) { Text("Generate TA statement") }
            }
        },
    ) { pad ->
        if (t == null) return@Scaffold
        val total = expenses.sumOf { it.amount ?: 0.0 }
        val toReview = expenses.count { !it.confirmed }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Text("${t.clientName} · ${t.projectCode}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Fmt.range(t.startDate, t.endDate)}${if (t.location.isNotBlank()) " · ${t.location}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Claim ${t.claimNumber}" + if (t.shared) " · Team project (${people.count { it.inviteStatus == "JOINED" }} on the app)" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Stat("Total", Fmt.inr(total), Modifier.weight(1f))
                        Stat("Advance", Fmt.inr(t.advance), Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant)
                        val net = total - t.advance
                        Stat(if (net >= 0) "Net payable" else "Refundable", Fmt.inr(kotlin.math.abs(net)), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            budget?.let { b -> item { BudgetCard(b, people.associate { it.id to it.name }) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = camera, modifier = Modifier.weight(1f).height(52.dp), enabled = importState !is JobState.Running) {
                        Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Scan bill")
                    }
                    FilledTonalButton(onClick = { safeLaunch(ctx) { picker.launch(arrayOf("*/*")) } }, modifier = Modifier.weight(1f).height(52.dp), enabled = importState !is JobState.Running) {
                        Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Upload files")
                    }
                }
                Text("Photos, PDFs, screenshots, documents or ZIP — select many at once.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
            }
            item { ImportStatus(importState, onDismiss = { c.jobs.clear(c.jobs.importKey(tripId)) }) }
            if (expenses.isEmpty() && importState !is JobState.Running) {
                item { EmptyState(Icons.Outlined.ReceiptLong, "No bills yet", "Scan an invoice with the camera or upload files. Each file becomes an expense you can review; add supporting documents to any expense.") }
            } else if (expenses.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Expenses (${expenses.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (toReview > 0) Pill("$toReview to review", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        else Pill("All reviewed", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                items(expenses, key = { it.id }) { e ->
                    ExpenseRow(e, atts.count { it.expenseId == e.id }, addedBy = if (t.shared && e.addedByUid.isNotBlank() && e.addedByUid != myUid) e.addedBy else null) { nav.navigate(Routes.expense(e.id)) }
                }
            }
        }
    }

    // After a camera capture: offer to add supporting photos or scan the next bill.
    val done = importState as? JobState.ImportDone
    if (lastCapturedFlow && done != null && done.result.createdExpenseIds.size == 1) {
        val newId = done.result.createdExpenseIds.first()
        AlertDialog(
            onDismissRequest = { lastCapturedFlow = false },
            title = { Text("Bill captured") },
            text = { Text("Add a supporting document (e.g. payment screenshot, boarding pass) to this bill, or scan the next bill?") },
            confirmButton = { TextButton(onClick = { lastCapturedFlow = false; c.jobs.clear(c.jobs.importKey(tripId)); camera() }) { Text("Scan next bill") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { lastCapturedFlow = false; supportFor = newId; c.jobs.clear(c.jobs.importKey(tripId)); supportCamera() }) { Text("Add supporting") }
                    TextButton(onClick = { lastCapturedFlow = false; nav.navigate(Routes.expense(newId)) }) { Text("Review") }
                }
            },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this claim?") },
        text = { Text("All ${expenses.size} expenses and their files will be permanently removed from this phone." + if (t?.shared == true && manage) " The team project is closed for everyone." else if (t?.shared == true) " You will stop receiving this team project." else "") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { if (t?.shared == true || t?.myRole == "REMOVED") c.sync.forget(tripId); c.repo.deleteTrip(tripId); nav.popBackStack() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun ImportStatus(state: JobState?, onDismiss: () -> Unit) {
    when (state) {
        is JobState.Running -> SectionCard {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            if (state.total > 0) LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Reading the bills on this phone.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        is JobState.ImportDone -> {
            val r = state.result
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    val parts = buildList {
                        if (r.createdExpenseIds.isNotEmpty()) add("${r.createdExpenseIds.size} bill(s) added")
                        if (r.attachedCount > 0) add("${r.attachedCount} supporting document(s) attached")
                        if (r.duplicates > 0) add("${r.duplicates} possible duplicate(s)")
                    }
                    Text(parts.joinToString(" · ").ifEmpty { "No files were added" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (r.createdExpenseIds.isNotEmpty()) Text("Open each bill to confirm the details and accounting head.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    r.skipped.forEach { Text("Skipped: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("OK") }
                }
            }
        }
        is JobState.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Could not add files: ${state.message}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
        else -> {}
    }
}

@Composable
fun ExpenseRow(e: ExpenseEntity, attachmentCount: Int, addedBy: String? = null, onClick: () -> Unit) {
    val cat = e.categoryEnum
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.5.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(categoryIcon(cat ?: e.suggestedEnum), null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.vendor.ifBlank { e.nature.ifBlank { "Unreadable bill" } }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Fmt.date(e.date)} · ${cat?.label ?: "Choose accounting head"}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (cat == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (!e.confirmed) Pill("Review", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    if (e.trustBlocked) Pill("AI-made?", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    else if (e.needsTrustAck) Pill("Check", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    else if ((e.duplicateOfId ?: 0) > 0 || e.dupNote.isNotBlank() && e.trustAck.isBlank()) Pill("Duplicate?", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    if (e.aiState == "QUEUED") Pill("AI reading…", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    addedBy?.takeIf { it.isNotBlank() }?.let { Pill("By $it", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (attachmentCount == 0) Pill("No bill", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (attachmentCount > 1) Pill("$attachmentCount files", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.inr(e.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (e.amount == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (e.isForeign && e.foreignAmount != null) Text(com.tareimbursement.app.core.Currencies.format(e.foreignAmount, e.currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.isMileage) Text("Own vehicle", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!e.confirmed && (e.amount == null || e.date == null)) Icon(Icons.Outlined.Warning, "Missing details", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
