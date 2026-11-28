package com.tareimbursement.app.data

import androidx.room.withTransaction
import com.tareimbursement.app.core.AmountWords
import com.tareimbursement.app.core.BillingSplit
import com.tareimbursement.app.core.BudgetTracker
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.core.Currencies
import com.tareimbursement.app.core.Shares
import com.tareimbursement.app.core.TravelMode
import com.tareimbursement.app.core.RemarkWriter
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.FileNamer
import com.tareimbursement.app.core.InsightExpense
import com.tareimbursement.app.core.InsightTrip
import com.tareimbursement.app.core.ParsedReceipt
import com.tareimbursement.app.core.trust.BillPrint
import com.tareimbursement.app.core.trust.Duplicates
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object EventType {
    const val CREATED = "CREATED"; const val UPDATED = "UPDATED"; const val FILES = "FILES"; const val GENERATED = "GENERATED"
    const val SUBMITTED = "SUBMITTED"; const val APPROVED = "APPROVED"; const val PAID = "PAID"; const val REJECTED = "REJECTED"
    const val REOPENED = "REOPENED"; const val SHARED = "SHARED"; const val SAVED = "SAVED"
}

data class ValidationIssue(val expenseId: Long?, val message: String, val blocking: Boolean)

class Repository(val db: AppDatabase, val filesDir: File) {
    val trips = db.trips()
    val expenses = db.expenses()
    val attachments = db.attachments()
    val events = db.events()
    val travellers = db.travellers()
    val entities = db.billingEntities()
    val messages = db.messages()

    /** Called after local changes on a shared claim (schedules a sync). Set by the app. */
    @Volatile var onLocalChange: (Long) -> Unit = {}

    suspend fun touchA(a: AttachmentEntity): AttachmentEntity =
        if (trips.get(a.tripId)?.shared == true) { onLocalChange(a.tripId); a.copy(dirty = true) } else a

    /** Stamps a changed claim; team claims are marked for upload. */
    fun touchTrip(t: TripEntity): TripEntity {
        if (t.shared) onLocalChange(t.id)
        return t.copy(updatedAt = System.currentTimeMillis(), dirty = t.dirty || t.shared)
    }

    /** Stamps a changed bill; on team claims it is marked for upload. */
    suspend fun touch(e: ExpenseEntity): ExpenseEntity {
        val shared = trips.get(e.tripId)?.shared == true
        if (shared) onLocalChange(e.tripId)
        return e.copy(updatedAt = System.currentTimeMillis(), dirty = e.dirty || shared)
    }

    fun tripDir(tripId: Long) = File(filesDir, "trips/$tripId")
    fun attachmentDir(tripId: Long) = File(tripDir(tripId), "att").apply { mkdirs() }
    fun outputDir(tripId: Long) = File(tripDir(tripId), "outputs").apply { mkdirs() }
    fun fileOf(a: AttachmentEntity) = File(filesDir, a.storedPath)

    private fun log(tripId: Long, type: String, msg: String) = TripEventEntity(tripId = tripId, type = type, message = msg)

    suspend fun createTrip(t: TripEntity, selfName: String = "", selfCode: String = ""): Long = db.withTransaction {
        val prefix = "TA-" + t.startDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-"
        val n = trips.countClaimPrefix(prefix) + 1
        val id = trips.insert(t.copy(claimNumber = prefix + n.toString().padStart(3, '0'), status = ClaimStatus.DRAFT.name))
        if (selfName.isNotBlank()) travellers.insert(TravellerEntity(tripId = id, name = selfName.trim(), employeeCode = selfCode.trim(), isSelf = true))
        events.insert(log(id, EventType.CREATED, "TA claim created for ${t.title}"))
        id
    }

    // ------------------------------------------------------------ travellers

    /**
     * Replaces the claim's co-travellers. [others] are (existing id or 0, name, code). The claimant
     * (isSelf) is kept/created from the profile. Shares referring to removed people are re-split.
     */
    suspend fun saveTravellers(tripId: Long, selfName: String, selfCode: String, others: List<TravellerEntity>) = db.withTransaction {
        val existing = travellers.forTrip(tripId)
        val self = existing.firstOrNull { it.isSelf }
        if (self == null) { if (selfName.isNotBlank()) travellers.insert(TravellerEntity(tripId = tripId, name = selfName.trim(), employeeCode = selfCode.trim(), isSelf = true)) }
        else if (selfName.isNotBlank() && (self.name != selfName.trim() || self.employeeCode != selfCode.trim())) travellers.update(self.copy(name = selfName.trim(), employeeCode = selfCode.trim()))
        val keepIds = others.filter { it.id != 0L }.map { it.id }.toSet()
        existing.filter { !it.isSelf && it.id !in keepIds }.forEach { travellers.delete(it) }
        others.forEachIndexed { i, o ->
            if (o.name.isBlank()) return@forEachIndexed
            if (o.id == 0L) travellers.insert(o.copy(tripId = tripId, isSelf = false, sortOrder = i + 1, name = o.name.trim(), employeeCode = o.employeeCode.trim()))
            else travellers.update(o.copy(tripId = tripId, isSelf = false, sortOrder = i + 1, name = o.name.trim(), employeeCode = o.employeeCode.trim()))
        }
        resplitShares(tripId)
        trips.get(tripId)?.let { if (it.shared) trips.update(touchTrip(it)) }
    }

    /** Drops shares of removed travellers; bills with no valid shares are split equally among everyone. */
    suspend fun resplitShares(tripId: Long) {
        val people = travellers.forTrip(tripId)
        val ids = people.map { it.id }
        expenses.forTrip(tripId).forEach { e ->
            val newShares = normalizedShares(e, ids)
            if (newShares != e.shares) expenses.update(touch(e.copy(shares = newShares)))
        }
    }

    /** Shares for [e] given the claim's travellers: empty for a single traveller, else valid & balanced if possible. */
    fun normalizedShares(e: ExpenseEntity, travellerIds: List<Long>): String {
        if (travellerIds.size <= 1 || e.amount == null) return ""
        val current = Shares.parse(e.shares).filterKeys { it in travellerIds }
        if (current.isEmpty()) return Shares.format(Shares.equal(e.amount, travellerIds))
        if (Shares.isBalanced(current, e.amount)) return Shares.format(current)
        // Amount changed: if the old split was equal among its people, keep it equal; otherwise leave it for the user.
        val vals = current.values
        return if ((vals.maxOrNull() ?: 0.0) - (vals.minOrNull() ?: 0.0) <= 0.011) Shares.format(Shares.equal(e.amount, current.keys.toList())) else Shares.format(current)
    }

    // ------------------------------------------------------------ billing entities

    suspend fun saveEntities(tripId: Long, mode: SplitMode, list: List<BillingEntity>) = db.withTransaction {
        val t = trips.get(tripId) ?: return@withTransaction
        trips.update(touchTrip(t.copy(splitMode = mode.name)))
        val existing = entities.forTrip(tripId)
        val keep = list.filter { it.id != 0L }.map { it.id }.toSet()
        existing.filter { it.id !in keep }.forEach { entities.unassign(it.id); entities.delete(it) }
        list.forEachIndexed { i, en ->
            if (en.name.isBlank()) return@forEachIndexed
            val clean = en.copy(tripId = tripId, sortOrder = i, name = en.name.trim(), code = en.code.trim(), sharePercent = en.sharePercent.coerceIn(0.0, 100.0))
            if (en.id == 0L) entities.insert(clean) else entities.update(clean)
        }
    }

    /** Auto-assigns unlocked bills to entities (balanced or by share %). Returns null if the claim is not split. */
    suspend fun autoAllocate(tripId: Long): BillingSplit.Result? = db.withTransaction {
        val t = trips.get(tripId) ?: return@withTransaction null
        val ents = entities.forTrip(tripId)
        if (t.split == SplitMode.NONE || ents.size < 2) return@withTransaction null
        val list = expenses.forTrip(tripId)
        val items = list.map { e ->
            BillingSplit.Item(e.id, e.amount ?: 0.0, if (e.entityLocked || t.split == SplitMode.MANUAL) ents.indexOfFirst { it.id == e.entityId }.takeIf { it >= 0 } else null)
        }
        val result = BillingSplit.allocate(items, ents.map { it.sharePercent })
        if (t.split == SplitMode.AUTO) list.forEach { e ->
            val idx = result.assignment[e.id] ?: return@forEach
            if (e.entityId != ents[idx].id) expenses.update(touch(e.copy(entityId = ents[idx].id)))
        }
        result
    }

    /** Most recent exchange rate used for [currency] on this claim (pre-fills the next bill). */
    suspend fun lastRate(tripId: Long, currency: String): Double? =
        expenses.forTrip(tripId).filter { it.currency == currency && it.exchangeRate != null }.maxByOrNull { it.createdAt }?.exchangeRate

    suspend fun updateTrip(t: TripEntity) {
        trips.update(touchTrip(t))
        events.insert(log(t.id, EventType.UPDATED, "Claim details updated"))
    }

    suspend fun deleteTrip(id: Long) {
        trips.get(id)?.let { trips.delete(it) }
        tripDir(id).deleteRecursively()
    }

    /** Finds an existing expense in the trip with the same date and rounded amount. */
    suspend fun findDuplicate(tripId: Long, date: LocalDate?, amount: Double?, excludeId: Long = -1): ExpenseEntity? {
        if (date == null || amount == null) return null
        return expenses.forTrip(tripId).firstOrNull {
            it.id != excludeId && it.dateEpochDay == date.toEpochDay() && it.amount != null &&
                FileNamer.roundAmount(it.amount) == FileNamer.roundAmount(amount)
        }
    }

    suspend fun createExpenseFromReceipt(tripId: Long, parsed: ParsedReceipt?, ocrText: String): Long {
        val trip = trips.get(tripId)
        // Currency: detected on the bill, else the claim's default currency.
        val currency = parsed?.currency ?: trip?.currency ?: Currencies.INR
        val foreign = Currencies.isForeign(currency)
        val parsedAmount = parsed?.amount?.let { Math.round(it * 100) / 100.0 }
        val rate = if (foreign) lastRate(tripId, currency) else null
        val inr = if (foreign) (if (parsedAmount != null && rate != null) Currencies.toInr(parsedAmount, rate) else null) else parsedAmount
        val dup = findDuplicate(tripId, parsed?.date, inr)
        val s = parsed?.suggestion
        val people = travellers.forTrip(tripId).map { it.id }
        val id = expenses.insert(
            ExpenseEntity(
                tripId = tripId,
                dateEpochDay = parsed?.date?.toEpochDay(),
                amount = inr,
                currency = currency,
                foreignAmount = if (foreign) parsedAmount else null,
                exchangeRate = rate,
                vendor = parsed?.vendor.orEmpty(),
                billNo = parsed?.billNo.orEmpty(),
                // The suggested head is filled in straight away; the person still confirms every bill.
                category = s?.category?.takeIf { s.nature != "Unknown" || parsed?.amount != null }?.name,
                suggestedCategory = s?.category?.name,
                suggestionReason = s?.reason.orEmpty(),
                suggestionAlternatives = s?.alternatives?.joinToString(",") { it.name }.orEmpty(),
                nature = s?.nature.orEmpty(),
                description = RemarkWriter.write(s?.category, ocrText, parsed?.vendor.orEmpty(), trip?.location.orEmpty(), s?.nature.orEmpty()),
                modeOfTravel = RemarkWriter.mode(s?.category, ocrText, parsed?.vendor.orEmpty()),
                duplicateOfId = dup?.id,
                dateConfident = parsed?.dateConfident ?: false,
                amountConfident = parsed?.amountConfident ?: false,
                ocrText = ocrText.take(20000),
            )
        )
        expenses.get(id)?.let { e -> normalizedShares(e, people).takeIf { it != e.shares }?.let { expenses.update(touch(e.copy(shares = it))) } }
        return id
    }

    /** Own vehicle trip without a bill: amount = km × rate + toll (template style). */
    suspend fun addMileageExpense(tripId: Long, date: LocalDate?, ratePerKm: Double): Long {
        val id = expenses.insert(ExpenseEntity(tripId = tripId, dateEpochDay = date?.toEpochDay(), noBill = true, nature = "Own vehicle",
            category = ExpenseCategory.LOCAL_CONVEYANCE.name, suggestedCategory = ExpenseCategory.LOCAL_CONVEYANCE.name, modeOfTravel = "Car - Own",
            mileageKm = 0.0, mileageRate = ratePerKm, tollAmount = 0.0, amount = 0.0,
            suggestionReason = "Own vehicle: enter the kilometres, rate per km and toll; the amount is calculated."))
        return id
    }

    suspend fun addManualExpense(tripId: Long, date: LocalDate?): Long {
        val cur = trips.get(tripId)?.currency ?: Currencies.INR
        return expenses.insert(ExpenseEntity(tripId = tripId, dateEpochDay = date?.toEpochDay(), noBill = true, nature = "Manual entry", currency = cur,
            exchangeRate = if (Currencies.isForeign(cur)) lastRate(tripId, cur) else null,
            suggestionReason = "Manual entry without bill — choose the accounting head."))
    }

    suspend fun saveExpense(e: ExpenseEntity) {
        val dup = findDuplicate(e.tripId, e.date, e.amount, e.id)
        // duplicateOfId: null = none, 0 = user confirmed "not a duplicate", >0 = possible duplicate of that expense.
        val base = e.copy(
            amount = if (e.isMileage) TravelMode.mileageAmount(e.mileageKm!!, e.mileageRate!!, e.tollAmount) else e.amount,
            modeOfTravel = e.modeOfTravel.ifBlank { if (e.categoryEnum != null) RemarkWriter.mode(e.categoryEnum, e.ocrText, e.vendor) else "" },
        )
        val withShares = base.copy(shares = normalizedShares(base, travellers.forTrip(e.tripId).map { it.id }))
        expenses.update(touch(withShares.copy(duplicateOfId = if (e.duplicateOfId == 0L) 0L else dup?.id)))
    }

    suspend fun deleteExpense(id: Long) {
        val e = expenses.get(id) ?: return
        attachments.forExpense(id).forEach { fileOf(it).delete() }
        expenses.clearDuplicateRefs(id)
        // Team claims keep a tombstone so the deletion reaches everyone's phone.
        if (trips.get(e.tripId)?.shared == true) expenses.update(touch(e.copy(deleted = true)))
        else expenses.delete(e)
    }

    // ------------------------------------------------------------ duplicates (this and other claims)

    suspend fun billPrints(): List<BillPrint> {
        val titles = trips.all().associate { it.id to it.title }
        val byExpense = attachments.all().groupBy { it.expenseId }
        return expenses.all().flatMap { e ->
            val atts = byExpense[e.id].orEmpty()
            val base = BillPrint(e.id, e.tripId, titles[e.tripId].orEmpty(), null, null, e.date, e.amount, e.vendor.ifBlank { null }, e.billNo.ifBlank { null })
            if (atts.isEmpty()) listOf(base) else atts.map { a -> base.copy(sha256 = a.sha256.ifBlank { null }, dHash = a.dHash) }
        }
    }

    /** Re-checks one bill against every other bill on the phone; returns the note shown to the person. */
    suspend fun refreshDuplicates(expenseId: Long, prints: List<BillPrint>? = null): String {
        val e = expenses.get(expenseId) ?: return ""
        val all = prints ?: billPrints()
        val mine = all.filter { it.expenseId == e.id }
        val hits = mine.flatMap { Duplicates.find(it, all) }.sortedByDescending { it.strong }
        val note = hits.firstOrNull()?.reason.orEmpty()
        val strong = hits.firstOrNull()?.strong == true
        if (note != e.dupNote || strong != e.dupStrong) expenses.update(touch(e.copy(dupNote = note, dupStrong = strong, trustAck = if (note.isBlank() && e.trustLevel != "WARN") "" else e.trustAck)))
        return note
    }

    /** Keeps a flagged bill (trust warning or duplicate) with the person's reason. */
    suspend fun acknowledgeTrust(expenseId: Long, reason: String) {
        val e = expenses.get(expenseId) ?: return
        expenses.update(touch(e.copy(trustAck = reason.trim())))
        logEvent(e.tripId, EventType.UPDATED, "Flagged bill kept: ${reason.trim()}")
    }

    /** Merges [sourceId] into [targetId]: its files become supporting documents of the target. */
    suspend fun mergeExpense(sourceId: Long, targetId: Long) = db.withTransaction {
        val src = expenses.get(sourceId) ?: return@withTransaction
        val tgt = expenses.get(targetId) ?: return@withTransaction
        attachments.forExpense(sourceId).forEach { attachments.update(touchA(it.copy(expenseId = targetId, isPrimary = false))) }
        expenses.clearDuplicateRefs(sourceId)
        if (trips.get(src.tripId)?.shared == true) expenses.update(touch(src.copy(deleted = true))) else expenses.delete(src)
        expenses.update(touch(tgt.copy(
            vendor = tgt.vendor.ifBlank { src.vendor }, billNo = tgt.billNo.ifBlank { src.billNo },
            dateEpochDay = tgt.dateEpochDay ?: src.dateEpochDay, amount = tgt.amount ?: src.amount,
            ocrText = (tgt.ocrText + "\n" + src.ocrText).take(20000),
        )))
    }

    suspend fun moveAttachment(attachmentId: Long, toExpenseId: Long) {
        val a = attachments.get(attachmentId) ?: return
        val from = a.expenseId
        attachments.update(touchA(a.copy(expenseId = toExpenseId, isPrimary = attachments.forExpense(toExpenseId).none { it.isPrimary })))
        // Keep exactly one primary document on the source expense if any remain.
        val rest = attachments.forExpense(from)
        if (rest.isNotEmpty() && rest.none { it.isPrimary }) attachments.update(touchA(rest.first().copy(isPrimary = true)))
    }

    suspend fun setPrimary(attachmentId: Long) {
        val a = attachments.get(attachmentId) ?: return
        attachments.forExpense(a.expenseId).forEach { attachments.update(touchA(it.copy(isPrimary = it.id == attachmentId))) }
    }

    suspend fun deleteAttachment(attachmentId: Long) {
        val a = attachments.get(attachmentId) ?: return
        fileOf(a).delete()
        if (trips.get(a.tripId)?.shared == true) attachments.update(touchA(a.copy(deleted = true))) else attachments.delete(a)
        val rest = attachments.forExpense(a.expenseId)
        if (rest.isNotEmpty() && rest.none { it.isPrimary }) attachments.update(touchA(rest.first().copy(isPrimary = true)))
    }

    suspend fun logEvent(tripId: Long, type: String, message: String) { events.insert(log(tripId, type, message)) }

    /** Checks before generating the statement. Blocking issues must be fixed first. */
    suspend fun validate(tripId: Long, limits: Map<ExpenseCategory, Double>, homeCity: String = ""): List<ValidationIssue> {
        val trip = trips.get(tripId) ?: return listOf(ValidationIssue(null, "Claim not found", true))
        val list = expenses.forTrip(tripId)
        val out = mutableListOf<ValidationIssue>()
        if (list.isEmpty()) out += ValidationIssue(null, "Add at least one expense before generating the statement.", true)
        if (homeCity.isNotBlank() && trip.location.trim().equals(homeCity.trim(), ignoreCase = true))
            out += ValidationIssue(null, "This claim is in your home city ($homeCity). Check your travel policy - local expenses are often not claimable as TA.", false)
        val people = travellers.forTrip(tripId)
        val ents = entities.forTrip(tripId)
        if (trip.split != SplitMode.NONE && ents.size < 2) out += ValidationIssue(null, "Billing split is on: add at least two client entities in claim details, or turn the split off.", true)
        val pct = ents.sumOf { it.sharePercent }
        if (trip.split == SplitMode.AUTO && ents.any { it.sharePercent > 0 } && (ents.any { it.sharePercent <= 0 } || abs(pct - 100.0) > 0.5))
            out += ValidationIssue(null, "Entity share percentages should all be set and add up to 100% (now ${"%.1f".format(pct)}%).", true)
        list.forEachIndexed { i, e ->
            val label = "#${i + 1} ${e.vendor.ifBlank { e.nature.ifBlank { "Expense" } }}"
            if (e.isForeign && (e.exchangeRate == null || e.exchangeRate <= 0)) out += ValidationIssue(e.id, "$label: exchange rate for ${e.currency} is missing.", true)
            else if (e.isForeign && (e.foreignAmount == null || e.foreignAmount <= 0)) out += ValidationIssue(e.id, "$label: ${e.currency} amount is missing.", true)
            if (e.amount == null || e.amount <= 0) { if (!e.isForeign) out += ValidationIssue(e.id, "$label: amount is missing.", true) }
            else if (people.size > 1) {
                val sh = Shares.parse(e.shares).filterKeys { k -> people.any { it.id == k } }
                if (sh.isEmpty() || !Shares.isBalanced(sh, e.amount)) out += ValidationIssue(e.id, "$label: travellers' shares (₹${"%.2f".format(sh.values.sum())}) don't add up to the bill amount (₹${"%.2f".format(e.amount)}).", true)
            }
            if (e.description.isBlank()) out += ValidationIssue(e.id, "$label: remarks are mandatory (e.g. \"Uber Bangalore to Hosur\").", true)
            if (e.modeOfTravel.isBlank()) out += ValidationIssue(e.id, "$label: mode of travel is mandatory.", true)
            if (e.isMileage && (e.mileageKm ?: 0.0) <= 0) out += ValidationIssue(e.id, "$label: enter the kilometres for the own-vehicle trip.", true)
            if (trip.split == SplitMode.MANUAL && ents.size >= 2 && ents.none { it.id == e.entityId }) out += ValidationIssue(e.id, "$label: choose which client entity this bill is billed to.", true)
            if (e.date == null) out += ValidationIssue(e.id, "$label: date is missing.", true)
            if (e.categoryEnum == null) out += ValidationIssue(e.id, "$label: accounting head not chosen.", true)
            else if (!e.confirmed) out += ValidationIssue(e.id, "$label: not reviewed yet — open and confirm.", true)
            e.date?.let { d ->
                if (d.isBefore(trip.startDate.minusDays(1)) || d.isAfter(trip.endDate.plusDays(1)))
                    out += ValidationIssue(e.id, "$label: date ${d.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))} is outside the travel period (e.g. advance booking or payment date) — please verify.", false)
            }
            when {
                e.trustBlocked -> out += ValidationIssue(e.id, "$label: this file looks AI-generated (${e.trust.summary}). It can't be claimed - delete it and upload the original bill.", true)
                e.needsTrustAck -> out += ValidationIssue(e.id, "$label: ${listOf(e.trust.summary.takeIf { e.trustLevel == "WARN" }, e.dupNote.takeIf { e.dupStrong }).filterNotNull().joinToString(" ")} Open the bill and confirm it with a reason.", true)
                e.dupNote.isNotBlank() && !e.dupStrong && e.trustAck.isBlank() -> out += ValidationIssue(e.id, "$label: ${e.dupNote} Please check it is not claimed twice.", false)
                e.dupNote.isBlank() && (e.duplicateOfId ?: 0) > 0 -> out += ValidationIssue(e.id, "$label: possible duplicate of another bill with the same date & amount.", false)
            }
            if (!e.isMileage && (e.noBill || attachments.forExpense(e.id).isEmpty())) out += ValidationIssue(e.id, "$label: no bill attached (Sup Bills = N) — give clear details in the remarks.", false)
            val limit = e.categoryEnum?.let { limits[it] }
            if (limit != null && (e.amount ?: 0.0) > limit) out += ValidationIssue(e.id, "$label: ₹${e.amount} exceeds your policy limit of ₹$limit for ${e.categoryEnum!!.label}.", false)
            if (e.amount != null && abs(e.amount - Math.round(e.amount)) > 0.001) out += ValidationIssue(e.id, "$label: fractional amount ₹${e.amount} — file name uses ₹${Math.round(e.amount)}.", false)
        }
        // Budget and daily limit (warnings only — the claim can still be submitted).
        val b = budgetStatus(tripId)
        if (b != null) {
            if (b.level == BudgetTracker.Level.OVER) out += ValidationIssue(null, "Over budget: spent ${AmountWords.inr(b.spent)} against the approved ${AmountWords.inr(b.budget!!)} (${AmountWords.inr(-b.remaining!!)} over). Keep the partner's approval for the excess.", false)
            val names = people.associate { it.id to it.name }
            b.daysOver.forEach { d ->
                val who = d.person?.let { names[it] }?.let { "$it: " }.orEmpty()
                out += ValidationIssue(null, "${who}${d.date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))} spend ${AmountWords.inr(d.spent)} is above the daily limit of ${AmountWords.inr(d.limit)}.", false)
            }
        }
        return out
    }

    /** Budget vs actual and daily-limit status; null when the claim has neither. */
    suspend fun budgetStatus(tripId: Long): BudgetTracker.Status? {
        val t = trips.get(tripId) ?: return null
        if ((t.budget ?: 0.0) <= 0 && (t.dailyLimit ?: 0.0) <= 0) return null
        val multi = travellers.forTrip(tripId).size > 1
        val spends = expenses.forTrip(tripId).filter { it.amount != null }.map {
            BudgetTracker.Spend(it.date, it.amount!!, if (multi) Shares.parse(it.shares) else emptyMap())
        }
        return BudgetTracker.status(spends, t.budget, t.dailyLimit, t.dailyLimitPerPerson && multi, t.startDate, t.endDate)
    }

    suspend fun submit(tripId: Long) {
        val t = trips.get(tripId) ?: return
        trips.update(touchTrip(t.copy(status = ClaimStatus.SUBMITTED.name, submittedAt = System.currentTimeMillis())))
        events.insert(log(tripId, EventType.SUBMITTED, "Statement completed, submitted and archived"))
    }

    suspend fun markApproved(tripId: Long, on: LocalDate) {
        val t = trips.get(tripId) ?: return
        trips.update(touchTrip(t.copy(status = ClaimStatus.APPROVED.name, approvedAt = millis(on))))
        events.insert(log(tripId, EventType.APPROVED, "Claim approved"))
    }

    suspend fun markPaid(tripId: Long, amount: Double, on: LocalDate) {
        val t = trips.get(tripId) ?: return
        trips.update(touchTrip(t.copy(status = ClaimStatus.PAID.name, paidAt = millis(on), paidAmount = amount, approvedAt = t.approvedAt ?: millis(on))))
        events.insert(log(tripId, EventType.PAID, "Reimbursement of ₹%.2f received".format(amount)))
    }

    suspend fun markRejected(tripId: Long, reason: String) {
        val t = trips.get(tripId) ?: return
        trips.update(touchTrip(t.copy(status = ClaimStatus.REJECTED.name, notes = if (reason.isBlank()) t.notes else (t.notes + "\nRejected: " + reason).trim())))
        events.insert(log(tripId, EventType.REJECTED, "Claim returned/rejected" + if (reason.isNotBlank()) ": $reason" else ""))
    }

    suspend fun reopen(tripId: Long) {
        val t = trips.get(tripId) ?: return
        trips.update(touchTrip(t.copy(status = ClaimStatus.DRAFT.name, approvedAt = null, paidAt = null, paidAmount = null)))
        events.insert(log(tripId, EventType.REOPENED, "Claim reopened for editing"))
    }

    suspend fun insightData(): Pair<List<InsightTrip>, List<InsightExpense>> {
        val ts = trips.all().map { t ->
            InsightTrip(t.id, t.title, t.clientName, t.projectCode, t.startDate, t.endDate, t.claimStatus, t.advance,
                t.submittedAt?.let { toDate(it) }, t.paidAt?.let { toDate(it) }, t.paidAmount, t.budget)
        }
        val es = expenses.all().filter { it.amount != null }.map {
            InsightExpense(it.tripId, it.date, it.amount!!, it.categoryEnum ?: it.suggestedEnum ?: ExpenseCategory.OTHER, it.vendor)
        }
        return ts to es
    }

    companion object {
        fun toDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        fun millis(d: LocalDate): Long = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
