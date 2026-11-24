package com.tareimbursement.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.data.AttachmentEntity
import com.tareimbursement.app.data.BillingEntity
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.data.SplitMode
import com.tareimbursement.app.data.TravellerEntity
import com.tareimbursement.app.core.Currencies
import com.tareimbursement.app.core.Shares
import com.tareimbursement.app.core.TravelMode
import com.tareimbursement.app.service.JobState
import kotlinx.coroutines.launch
import java.time.LocalDate

private val paymentModes = listOf("UPI", "Card", "Cash", "Net banking", "Wallet", "Company paid")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseScreen(expenseId: Long, nav: NavHostController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val expense by remember(expenseId) { c.repo.expenses.observe(expenseId) }.collectAsState(initial = null)
    val atts by remember(expenseId) { c.repo.attachments.observeForExpense(expenseId) }.collectAsState(initial = emptyList())
    val limits by c.prefs.limits.collectAsState()
    val jobs by c.jobs.states.collectAsState()

    // Editable fields are loaded once, then kept in saveable state.
    var loaded by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf<Long?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var vendor by rememberSaveable { mutableStateOf("") }
    var billNo by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var payment by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable { mutableStateOf("") }
    var perKm by rememberSaveable { mutableStateOf("") }
    var toll by rememberSaveable { mutableStateOf("") }
    var route by rememberSaveable { mutableStateOf("") }
    var remarks by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var mergeOpen by rememberSaveable { mutableStateOf(false) }
    var moveAtt by rememberSaveable { mutableStateOf<Long?>(null) }
    var others by remember { mutableStateOf<List<ExpenseEntity>>(emptyList()) }
    var dupOf by remember { mutableStateOf<ExpenseEntity?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    // Foreign currency, billing entity and travellers' shares.
    var currency by rememberSaveable { mutableStateOf("INR") }
    var foreignAmt by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var entityId by rememberSaveable { mutableStateOf<Long?>(null) }
    var shareText by rememberSaveable { mutableStateOf("") }
    var sharesInit by rememberSaveable { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<TravellerEntity>>(emptyList()) }
    var ents by remember { mutableStateOf<List<BillingEntity>>(emptyList()) }
    var split by remember { mutableStateOf(SplitMode.NONE) }

    val e = expense
    LaunchedEffect(e?.id, e != null) {
        if (e != null && !loaded) {
            date = e.dateEpochDay; amount = Fmt.amountInput(e.amount); vendor = e.vendor; billNo = e.billNo
            description = e.description.ifBlank { TravelMode.defaultRemark(e.categoryEnum ?: e.suggestedEnum, e.nature, e.vendor) }
            mode = e.modeOfTravel
            km = Fmt.amountInput(e.mileageKm?.takeIf { it > 0 }); perKm = Fmt.amountInput(e.mileageRate); toll = Fmt.amountInput(e.tollAmount?.takeIf { it > 0 })
            category = e.category; payment = e.paymentMode; remarks = e.remarks
            currency = e.currency; foreignAmt = Fmt.amountInput(e.foreignAmount); rate = e.exchangeRate?.let { Fmt.amountInput(it) }.orEmpty()
            // Only a user-fixed entity is shown as chosen; auto-assigned bills show "Auto".
            entityId = if (e.entityLocked) e.entityId else null
            if (e.shares.isNotBlank()) { shareText = ShareEdit.fromStored(e.shares); sharesInit = true }
            loaded = true
        }
    }
    LaunchedEffect(e?.duplicateOfId, e?.tripId, atts.size) {
        if (e == null) return@LaunchedEffect
        others = c.repo.expenses.forTrip(e.tripId).filter { it.id != e.id }
        dupOf = e.duplicateOfId?.takeIf { it > 0 }?.let { c.repo.expenses.get(it) }
        val trip = c.repo.trips.get(e.tripId)
        readOnly = trip?.isArchived == true
        split = trip?.split ?: SplitMode.NONE
        ents = c.repo.entities.forTrip(e.tripId)
        people = c.repo.travellers.forTrip(e.tripId)
    }
    // If the expense was deleted / merged, leave the screen.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(e) { if (e != null) seen = true else if (seen) nav.popBackStack() }

    val supportCamera = rememberCamera { uri -> e?.let { c.jobs.startImport(it.tripId, listOf(uri), it.id) } }
    val supportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) e?.let { c.jobs.startImport(it.tripId, uris, it.id) }
    }

    val isForeign = currency != "INR"
    val foreignValue = parseMoney(foreignAmt)
    val rateValue = rate.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
    val isMileage = e?.isMileage == true
    val kmValue = km.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
    val perKmValue = perKm.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
    val tollValue = parseMoney(toll)
    val amountValue = when {
        isMileage -> if (kmValue != null && perKmValue != null) TravelMode.mileageAmount(kmValue, perKmValue, tollValue) else null
        isForeign -> if (foreignValue != null && rateValue != null) Currencies.toInr(foreignValue, rateValue) else null
        else -> parseMoney(amount)
    }
    val multi = people.size > 1
    // Default: the bill is shared equally by everyone on the claim.
    LaunchedEffect(people, loaded) {
        if (loaded && multi && !sharesInit) { shareText = ShareEdit.equal(amountValue ?: 0.0, people.map { it.id }); sharesInit = true }
    }
    val shareMap = ShareEdit.values(shareText)
    val sharesOk = !multi || (amountValue != null && shareMap.isNotEmpty() && Shares.isBalanced(shareMap, amountValue))
    val cat = ExpenseCategory.fromName(category)
    val dateValue = date?.let { LocalDate.ofEpochDay(it) }
    val limit = cat?.let { limits[it] }

    fun build(confirm: Boolean): ExpenseEntity? = e?.copy(
        dateEpochDay = date, amount = amountValue?.let { Math.round(it * 100) / 100.0 }, vendor = vendor.trim(), billNo = billNo.trim(),
        description = description.trim(), category = category, paymentMode = payment, remarks = remarks.trim(),
        confirmed = if (confirm) true else e.confirmed && category != null && amountValue != null && date != null,
        currency = currency, foreignAmount = if (isForeign) foreignValue else null, exchangeRate = if (isForeign) rateValue else null,
        entityId = entityId, entityLocked = entityId != null,
        shares = if (multi) Shares.format(shareMap) else "",
        modeOfTravel = mode.trim().ifBlank { ExpenseCategory.fromName(category)?.let { TravelMode.suggest(it, e.ocrText, vendor) }.orEmpty() },
        mileageKm = if (isMileage) kmValue ?: 0.0 else null, mileageRate = if (isMileage) perKmValue else null, tollAmount = if (isMileage) tollValue ?: 0.0 else null,
    )

    androidx.activity.compose.BackHandler(enabled = loaded && !readOnly && e != null) {
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { build(false)?.let { c.repo.saveExpense(it) }; nav.popBackStack() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (readOnly) "Expense" else if (e?.confirmed == true) "Edit expense" else "Review bill") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!readOnly && loaded) scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { build(false)?.let { c.repo.saveExpense(it) }; nav.popBackStack() } else nav.popBackStack()
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = {
                    if (!readOnly) {
                        if (others.isNotEmpty()) IconButton(onClick = { mergeOpen = true }) { Icon(Icons.Outlined.CallMerge, "Merge into another expense") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete expense") }
                    }
                },
            )
        },
        bottomBar = {
            if (!readOnly) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { build(false)?.let { c.repo.saveExpense(it) }; nav.popBackStack() } }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Save draft") }
                    Button(
                        onClick = {
                            tried = true
                            // An untouched equal split follows amount changes automatically.
                            if (multi && amountValue != null && !sharesOk && ShareEdit.isEqual(shareMap)) {
                                shareText = ShareEdit.equal(amountValue, shareMap.keys.ifEmpty { people.map { it.id }.toSet() }.toList())
                                return@Button
                            }
                            if (amountValue == null || amountValue <= 0 || date == null || category == null || !sharesOk || description.isBlank()) return@Button
                            if (mode.isBlank()) { mode = TravelMode.suggest(ExpenseCategory.fromName(category), e?.ocrText.orEmpty(), vendor); if (mode.isBlank()) return@Button }
                            if (split == SplitMode.MANUAL && ents.size >= 2 && ents.none { it.id == entityId }) return@Button
                            scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                val updated = build(true) ?: return@launch
                                c.repo.saveExpense(updated)
                                // Jump to the next bill that still needs review.
                                val next = c.repo.expenses.forTrip(updated.tripId).firstOrNull { !it.confirmed && it.id != updated.id }
                                if (next != null) nav.navigate(Routes.expense(next.id)) { popUpTo(Routes.expense(updated.id)) { inclusive = true } }
                                else nav.popBackStack()
                            }
                        },
                        modifier = Modifier.weight(1.4f).height(50.dp),
                    ) { Text("Confirm") }
                }
            }
        },
    ) { pad ->
        if (e == null || !loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --------------- documents
            Text("Bill & supporting documents", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(atts, key = { it.id }) { a -> AttachmentTile(a, readOnly, c.repo.fileOf(a), onOpen = { nav.navigate(Routes.viewer(a.id)) },
                    onPrimary = { scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.setPrimary(a.id) } }, onMove = { moveAtt = a.id }, onDelete = { scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.deleteAttachment(a.id) } }, canMove = others.isNotEmpty()) }
                if (!readOnly) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = supportCamera, modifier = Modifier.width(150.dp)) { Icon(Icons.Outlined.AddAPhoto, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Photo") }
                        OutlinedButton(onClick = { supportPicker.launch(arrayOf("*/*")) }, modifier = Modifier.width(150.dp)) { Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Files") }
                    }
                }
            }
            if (jobs[c.jobs.importKey(e.tripId)] is JobState.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (atts.isEmpty()) Text("No bill attached — this will be shown as a self-declared expense. Add a photo or file if you have one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // --------------- duplicate warning
            dupOf?.let { d ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer); Spacer(Modifier.width(8.dp))
                            Text("Possible duplicate of “${d.vendor.ifBlank { "another bill" }}” (${Fmt.date(d.date)}, ${Fmt.inr(d.amount)}). If this is another photo/copy of the same bill, merge it as a supporting document.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        if (!readOnly) Row(Modifier.align(Alignment.End)) {
                            TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { build(false)?.let { c.repo.saveExpense(it.copy(duplicateOfId = 0)) } } }) { Text("Not a duplicate") }
                            TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.mergeExpense(e.id, d.id); nav.navigate(Routes.expense(d.id)) { popUpTo(Routes.expense(e.id)) { inclusive = true } } } }) { Text("Merge") }
                        }
                    }
                }
            }

            // --------------- details
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("Bill date *", dateValue, { if (!readOnly) date = it.toEpochDay() }, Modifier.weight(1f), isError = tried && date == null,
                    supporting = if (!e.dateConfident && e.dateEpochDay != null && !e.confirmed) "Please verify" else null)
                CurrencyDropdown(currency, { new ->
                    if (new != currency) {
                        // Switching INR <-> foreign: carry the typed number over.
                        if (new != "INR" && currency == "INR") { foreignAmt = amount; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { rate = c.repo.lastRate(e.tripId, new)?.let { Fmt.amountInput(it) }.orEmpty() } }
                        if (new == "INR" && currency != "INR") amount = Fmt.amountInput(amountValue)
                        if (new != "INR" && currency != "INR") scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { rate = c.repo.lastRate(e.tripId, new)?.let { Fmt.amountInput(it) } ?: rate }
                        currency = new
                    }
                }, Modifier.weight(1f), enabled = !readOnly)
            }
            if (isMileage) {
                Text("Own vehicle (no bill)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(route, { route = it }, label = { Text("Route (e.g. Rammurthy Nagar, BLR to Hosur)") }, singleLine = true, readOnly = readOnly,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(km, { v -> if (!readOnly && v.length <= 8 && v.all { it.isDigit() || it == '.' }) km = v }, label = { Text("Total km *") }, singleLine = true,
                        isError = tried && kmValue == null, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(perKm, { v -> if (!readOnly && v.length <= 6 && v.all { it.isDigit() || it == '.' }) perKm = v }, label = { Text("₹ per km *") }, singleLine = true,
                        isError = tried && perKmValue == null, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    MoneyField("Toll", toll, { if (!readOnly) toll = it }, Modifier.weight(1f))
                }
                Text(if (amountValue != null) "= ${Fmt.inr(amountValue)}  (km × rate + toll; shown as a formula in the Annexure)" else "Enter km and rate per km.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (!readOnly && kmValue != null && perKmValue != null) TextButton(onClick = { description = TravelMode.mileageRemark(route.trim(), kmValue, perKmValue, tollValue) }) { Text("Fill remarks from these details") }
            } else if (!isForeign) {
                MoneyField("Amount *", amount, { if (!readOnly) amount = it }, isError = (tried && (amountValue == null || amountValue <= 0)) || (amount.isNotBlank() && amountValue == null),
                    supporting = when {
                        limit != null && (amountValue ?: 0.0) > limit -> "Above limit ${Fmt.inr(limit)}"
                        !e.amountConfident && e.amount != null && !e.confirmed -> "Please verify"
                        else -> null
                    })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(foreignAmt, { v -> if (!readOnly && v.length <= 14 && v.all { it.isDigit() || it == '.' || it == ',' }) foreignAmt = v },
                        label = { Text("Amount in $currency *") }, prefix = { Text("$currency ") }, singleLine = true, readOnly = readOnly,
                        isError = tried && foreignValue == null, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        supportingText = if (!e.amountConfident && e.foreignAmount != null && !e.confirmed) { { Text("Please verify") } } else null,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(rate, { v -> if (!readOnly && v.length <= 12 && v.all { it.isDigit() || it == '.' }) rate = v },
                        label = { Text("Rate: ₹ per 1 $currency *") }, singleLine = true, readOnly = readOnly, isError = tried && rateValue == null,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                Text(
                    if (amountValue != null) "= ${Fmt.inr(amountValue)} in the statement" + (if (limit != null && amountValue > limit) " · above limit ${Fmt.inr(limit)}" else "")
                    else "Enter the amount and the exchange rate (e.g. from your card statement or forex receipt).",
                    style = MaterialTheme.typography.bodySmall, color = if (amountValue != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(vendor, { vendor = it }, label = { Text("Vendor / particulars") }, singleLine = true, readOnly = readOnly,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(billNo, { billNo = it }, label = { Text("Bill / invoice no.") }, singleLine = true, readOnly = readOnly, modifier = Modifier.weight(1f))
                PaymentModeField(payment, { if (!readOnly) payment = it }, Modifier.weight(1f))
            }
            OutlinedTextField(description, { description = it }, label = { Text("Remarks – mandatory (e.g. Uber Bangalore to Hosur)") }, readOnly = readOnly,
                isError = tried && description.isBlank(), supportingText = if (tried && description.isBlank()) { { Text("Remarks are mandatory in the TA annexure") } } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())

            // --------------- accounting head
            HorizontalDivider()
            Text("Accounting head *", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val suggested = e.suggestedEnum
            if (suggested != null || e.suggestionReason.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            if (e.nature.isNotBlank()) Text("Nature of expense: ${e.nature}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(e.suggestionReason.ifBlank { "Suggested head: ${suggested?.label}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (suggested != null) Text("Suggested: ${suggested.label}. How would you like to classify this expense?", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            val quick = (listOfNotNull(suggested) + e.alternatives).distinct()
            if (quick.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { q ->
                    FilterChip(selected = category == q.name, onClick = { if (!readOnly) { category = q.name; if (mode.isBlank()) mode = TravelMode.suggest(q, e.ocrText, vendor) } },
                        label = { Text(q.label + if (q == suggested) "  (suggested)" else "") }, leadingIcon = { Icon(categoryIcon(q), null, Modifier.size(18.dp)) })
                }
            }
            CategoryDropdown(cat, { if (!readOnly) { category = it.name; if (mode.isBlank()) mode = TravelMode.suggest(it, e.ocrText, vendor) } }, isError = tried && category == null)
            cat?.takeIf { it.restricted }?.let { Text("Note: ${it.guidance.trim()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            ModeOfTravelField(mode, { if (!readOnly) mode = it }, isError = tried && mode.isBlank() && category != null && TravelMode.suggest(ExpenseCategory.fromName(category), e.ocrText, vendor).isBlank())
            if (tried && category == null) Text("Choose how this expense should be classified.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            if (split != SplitMode.NONE && ents.size >= 2) {
                HorizontalDivider()
                Text("Billed to client entity" + if (split == SplitMode.MANUAL) " *" else "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (split == SplitMode.AUTO) FilterChip(entityId == null, { if (!readOnly) entityId = null }, label = { Text("Auto (balanced)") })
                    ents.forEach { en -> FilterChip(entityId == en.id, { if (!readOnly) entityId = en.id }, label = { Text(en.label) }) }
                }
                if (split == SplitMode.MANUAL && tried && ents.none { it.id == entityId }) Text("Choose the entity this bill is billed to.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (split == SplitMode.AUTO) Text("Auto: the app balances bills across entities when you generate. Pick an entity to fix this bill to it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (multi) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Travellers' shares", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (!readOnly) TextButton(onClick = {
                        val ids = shareMap.keys.ifEmpty { people.map { it.id }.toSet() }.toList()
                        shareText = ShareEdit.equal(amountValue ?: 0.0, people.map { it.id }.filter { it in ids })
                    }) { Text("Split equally") }
                }
                people.forEach { pe ->
                    val included = ShareEdit.has(shareText, pe.id)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(included, { on ->
                            if (readOnly) return@Checkbox
                            val ids = (if (on) shareMap.keys + pe.id else shareMap.keys - pe.id).let { set -> people.map { it.id }.filter { it in set } }
                            shareText = ShareEdit.equal(amountValue ?: 0.0, ids)
                        })
                        Text(pe.label + if (pe.isSelf) " (you)" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (included) OutlinedTextField(ShareEdit.text(shareText, pe.id), { v -> if (!readOnly && v.length <= 12 && v.all { it.isDigit() || it == '.' }) shareText = ShareEdit.set(shareText, pe.id, v) },
                            prefix = { Text("₹ ") }, singleLine = true, readOnly = readOnly, modifier = Modifier.width(140.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    }
                }
                val sum = shareMap.values.sum()
                Text("Shares total ${Fmt.inr(sum)} of ${Fmt.inr(amountValue)}" + if (!sharesOk && amountValue != null) " — must match the bill amount" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (sharesOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }

            OutlinedTextField(remarks, { remarks = it }, label = { Text("Remarks (shown in Annexure)") }, readOnly = readOnly, modifier = Modifier.fillMaxWidth())

            if (e.ocrText.isNotBlank()) {
                Row(Modifier.fillMaxWidth().clickable { showText = !showText }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Text read from the bill", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(if (showText) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (showText) Text(e.ocrText.take(4000), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this expense?") },
        text = { Text("The expense and its ${atts.size} file(s) will be removed.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.deleteExpense(expenseId) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (mergeOpen) PickExpenseDialog("Merge into…", "This bill's files become supporting documents of the expense you choose, and this entry is removed.", others, { mergeOpen = false }) { target ->
        mergeOpen = false
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.mergeExpense(expenseId, target.id); nav.navigate(Routes.expense(target.id)) { popUpTo(Routes.expense(expenseId)) { inclusive = true } } }
    }
    moveAtt?.let { aid ->
        PickExpenseDialog("Move document to…", "Choose the expense this document belongs to.", others, { moveAtt = null }) { target ->
            moveAtt = null; scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { c.repo.moveAttachment(aid, target.id) }
        }
    }
}

@Composable
private fun AttachmentTile(a: AttachmentEntity, readOnly: Boolean, file: java.io.File, onOpen: () -> Unit, onPrimary: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, canMove: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(120.dp)) {
        Box {
            Thumbnail(file, a.kindEnum, Modifier.size(120.dp, 150.dp).clickable(onClick = onOpen))
            if (!readOnly) Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Document options", tint = MaterialTheme.colorScheme.onSurface) }
                DropdownMenu(menu, { menu = false }) {
                    if (!a.isPrimary) DropdownMenuItem(text = { Text("Set as main invoice") }, leadingIcon = { Icon(Icons.Outlined.Star, null) }, onClick = { menu = false; onPrimary() })
                    if (canMove) DropdownMenuItem(text = { Text("Move to another expense") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Text(if (a.isPrimary) "Invoice" else "Supporting", style = MaterialTheme.typography.labelMedium, color = if (a.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(a.originalName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDropdown(value: ExpenseCategory?, onChange: (ExpenseCategory) -> Unit, isError: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(
            value = value?.label ?: "", onValueChange = {}, readOnly = true, isError = isError,
            label = { Text("All accounting heads") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            leadingIcon = value?.let { { Icon(categoryIcon(it), null) } },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(open, { open = false }) {
            ExpenseCategory.entries.forEach { cat ->
                DropdownMenuItem(text = { Text(cat.label) }, leadingIcon = { Icon(categoryIcon(cat), null) }, onClick = { onChange(cat); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentModeField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text("Paid by") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            singleLine = true, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            (listOf("") + paymentModes).forEach { m -> DropdownMenuItem(text = { Text(m.ifEmpty { "—" }) }, onClick = { onChange(m); open = false }) }
        }
    }
}

@Composable
fun PickExpenseDialog(title: String, body: String, options: List<ExpenseEntity>, onDismiss: () -> Unit, onPick: (ExpenseEntity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                options.forEach { o ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 10.dp)) {
                        Text(o.vendor.ifBlank { o.nature.ifBlank { "Expense" } }, style = MaterialTheme.typography.bodyLarge)
                        Text("${Fmt.date(o.date)} · ${Fmt.inr(o.amount)} · ${o.categoryEnum?.label ?: "No head"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Editable traveller shares kept as text "id=value;…" so partially typed numbers survive. */
object ShareEdit {
    fun fromStored(s: String) = Shares.parse(s).entries.joinToString(";") { "${it.key}=${Fmt.amountInput(it.value)}" }
    fun equal(amount: Double, ids: List<Long>) = Shares.equal(amount, ids).entries.joinToString(";") { "${it.key}=${Fmt.amountInput(it.value)}" }
    private fun entries(s: String) = s.split(';').filter { it.contains('=') }.map { it.substringBefore('=').toLong() to it.substringAfter('=') }
    fun values(s: String): Map<Long, Double> = entries(s).associate { it.first to (it.second.toDoubleOrNull() ?: 0.0) }
    fun has(s: String, id: Long) = entries(s).any { it.first == id }
    fun text(s: String, id: Long) = entries(s).firstOrNull { it.first == id }?.second.orEmpty()
    fun set(s: String, id: Long, v: String) = entries(s).joinToString(";") { if (it.first == id) "$id=$v" else "${it.first}=${it.second}" }
    fun isEqual(m: Map<Long, Double>) = m.isNotEmpty() && (m.values.max() - m.values.min()) <= 0.011
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeOfTravelField(value: String, onChange: (String) -> Unit, isError: Boolean) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(value, onChange, label = { Text("Mode of travel – mandatory") }, singleLine = true, isError = isError,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable))
        ExposedDropdownMenu(open, { open = false }) {
            TravelMode.options.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { onChange(m); open = false }) }
        }
    }
}
