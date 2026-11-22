package com.tareimbursement.app.data

import androidx.room.withTransaction
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.FileNamer
import com.tareimbursement.app.core.InsightExpense
import com.tareimbursement.app.core.InsightTrip
import com.tareimbursement.app.core.ParsedReceipt
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

    fun tripDir(tripId: Long) = File(filesDir, "trips/$tripId")
    fun attachmentDir(tripId: Long) = File(tripDir(tripId), "att").apply { mkdirs() }
    fun outputDir(tripId: Long) = File(tripDir(tripId), "outputs").apply { mkdirs() }
    fun fileOf(a: AttachmentEntity) = File(filesDir, a.storedPath)

    private fun log(tripId: Long, type: String, msg: String) = TripEventEntity(tripId = tripId, type = type, message = msg)

    suspend fun createTrip(t: TripEntity): Long = db.withTransaction {
        val prefix = "TA-" + t.startDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-"
        val n = trips.countClaimPrefix(prefix) + 1
        val id = trips.insert(t.copy(claimNumber = prefix + n.toString().padStart(3, '0'), status = ClaimStatus.DRAFT.name))
        events.insert(log(id, EventType.CREATED, "TA claim created for ${t.title}"))
        id
    }

    suspend fun updateTrip(t: TripEntity) {
        trips.update(t)
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
        val dup = findDuplicate(tripId, parsed?.date, parsed?.amount)
        val s = parsed?.suggestion
        return expenses.insert(
            ExpenseEntity(
                tripId = tripId,
                dateEpochDay = parsed?.date?.toEpochDay(),
                amount = parsed?.amount?.let { Math.round(it * 100) / 100.0 },
                vendor = parsed?.vendor.orEmpty(),
                billNo = parsed?.billNo.orEmpty(),
                category = null,
                suggestedCategory = s?.category?.name,
                suggestionReason = s?.reason.orEmpty(),
                suggestionAlternatives = s?.alternatives?.joinToString(",") { it.name }.orEmpty(),
                nature = s?.nature.orEmpty(),
                description = s?.nature?.takeIf { it != "Unknown" }.orEmpty(),
                duplicateOfId = dup?.id,
                dateConfident = parsed?.dateConfident ?: false,
                amountConfident = parsed?.amountConfident ?: false,
                ocrText = ocrText.take(20000),
            )
        )
    }

    suspend fun addManualExpense(tripId: Long, date: LocalDate?): Long =
        expenses.insert(ExpenseEntity(tripId = tripId, dateEpochDay = date?.toEpochDay(), noBill = true, nature = "Manual entry", suggestionReason = "Manual entry without bill — choose the accounting head."))

    suspend fun saveExpense(e: ExpenseEntity) {
        val dup = findDuplicate(e.tripId, e.date, e.amount, e.id)
        // duplicateOfId: null = none, 0 = user confirmed "not a duplicate", >0 = possible duplicate of that expense.
        expenses.update(e.copy(duplicateOfId = if (e.duplicateOfId == 0L) 0L else dup?.id))
    }

    suspend fun deleteExpense(id: Long) {
        val e = expenses.get(id) ?: return
        attachments.forExpense(id).forEach { fileOf(it).delete() }
        expenses.clearDuplicateRefs(id)
        expenses.delete(e)
    }

    /** Merges [sourceId] into [targetId]: its files become supporting documents of the target. */
    suspend fun mergeExpense(sourceId: Long, targetId: Long) = db.withTransaction {
        val src = expenses.get(sourceId) ?: return@withTransaction
        val tgt = expenses.get(targetId) ?: return@withTransaction
        attachments.moveAll(sourceId, targetId)
        expenses.clearDuplicateRefs(sourceId)
        expenses.delete(src)
        expenses.update(tgt.copy(
            vendor = tgt.vendor.ifBlank { src.vendor }, billNo = tgt.billNo.ifBlank { src.billNo },
            dateEpochDay = tgt.dateEpochDay ?: src.dateEpochDay, amount = tgt.amount ?: src.amount,
            ocrText = (tgt.ocrText + "\n" + src.ocrText).take(20000),
        ))
    }

    suspend fun moveAttachment(attachmentId: Long, toExpenseId: Long) {
        val a = attachments.get(attachmentId) ?: return
        val from = a.expenseId
        attachments.update(a.copy(expenseId = toExpenseId, isPrimary = attachments.forExpense(toExpenseId).none { it.isPrimary }))
        // Keep exactly one primary document on the source expense if any remain.
        val rest = attachments.forExpense(from)
        if (rest.isNotEmpty() && rest.none { it.isPrimary }) attachments.update(rest.first().copy(isPrimary = true))
    }

    suspend fun setPrimary(attachmentId: Long) {
        val a = attachments.get(attachmentId) ?: return
        attachments.forExpense(a.expenseId).forEach { attachments.update(it.copy(isPrimary = it.id == attachmentId)) }
    }

    suspend fun deleteAttachment(attachmentId: Long) {
        val a = attachments.get(attachmentId) ?: return
        fileOf(a).delete()
        attachments.delete(a)
        val rest = attachments.forExpense(a.expenseId)
        if (rest.isNotEmpty() && rest.none { it.isPrimary }) attachments.update(rest.first().copy(isPrimary = true))
    }

    suspend fun logEvent(tripId: Long, type: String, message: String) { events.insert(log(tripId, type, message)) }

    /** Checks before generating the statement. Blocking issues must be fixed first. */
    suspend fun validate(tripId: Long, limits: Map<ExpenseCategory, Double>): List<ValidationIssue> {
        val trip = trips.get(tripId) ?: return listOf(ValidationIssue(null, "Claim not found", true))
        val list = expenses.forTrip(tripId)
        val out = mutableListOf<ValidationIssue>()
        if (list.isEmpty()) out += ValidationIssue(null, "Add at least one expense before generating the statement.", true)
        list.forEachIndexed { i, e ->
            val label = "#${i + 1} ${e.vendor.ifBlank { e.nature.ifBlank { "Expense" } }}"
            if (e.amount == null || e.amount <= 0) out += ValidationIssue(e.id, "$label: amount is missing.", true)
            if (e.date == null) out += ValidationIssue(e.id, "$label: date is missing.", true)
            if (e.categoryEnum == null) out += ValidationIssue(e.id, "$label: accounting head not chosen.", true)
            else if (!e.confirmed) out += ValidationIssue(e.id, "$label: not reviewed yet — open and confirm.", true)
            e.date?.let { d ->
                if (d.isBefore(trip.startDate.minusDays(1)) || d.isAfter(trip.endDate.plusDays(1)))
                    out += ValidationIssue(e.id, "$label: date ${d.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))} is outside the travel period (e.g. advance booking or payment date) — please verify.", false)
            }
            if ((e.duplicateOfId ?: 0) > 0) out += ValidationIssue(e.id, "$label: possible duplicate of another bill with the same date & amount.", false)
            if (e.noBill || attachments.forExpense(e.id).isEmpty()) out += ValidationIssue(e.id, "$label: no bill attached (will be marked self-declared).", false)
            val limit = e.categoryEnum?.let { limits[it] }
            if (limit != null && (e.amount ?: 0.0) > limit) out += ValidationIssue(e.id, "$label: ₹${e.amount} exceeds your policy limit of ₹$limit for ${e.categoryEnum!!.label}.", false)
            if (e.amount != null && abs(e.amount - Math.round(e.amount)) > 0.001) out += ValidationIssue(e.id, "$label: fractional amount ₹${e.amount} — file name uses ₹${Math.round(e.amount)}.", false)
        }
        return out
    }

    suspend fun submit(tripId: Long) {
        val t = trips.get(tripId) ?: return
        trips.update(t.copy(status = ClaimStatus.SUBMITTED.name, submittedAt = System.currentTimeMillis()))
        events.insert(log(tripId, EventType.SUBMITTED, "Statement completed, submitted and archived"))
    }

    suspend fun markApproved(tripId: Long, on: LocalDate) {
        val t = trips.get(tripId) ?: return
        trips.update(t.copy(status = ClaimStatus.APPROVED.name, approvedAt = millis(on)))
        events.insert(log(tripId, EventType.APPROVED, "Claim approved"))
    }

    suspend fun markPaid(tripId: Long, amount: Double, on: LocalDate) {
        val t = trips.get(tripId) ?: return
        trips.update(t.copy(status = ClaimStatus.PAID.name, paidAt = millis(on), paidAmount = amount, approvedAt = t.approvedAt ?: millis(on)))
        events.insert(log(tripId, EventType.PAID, "Reimbursement of ₹%.2f received".format(amount)))
    }

    suspend fun markRejected(tripId: Long, reason: String) {
        val t = trips.get(tripId) ?: return
        trips.update(t.copy(status = ClaimStatus.REJECTED.name, notes = if (reason.isBlank()) t.notes else (t.notes + "\nRejected: " + reason).trim()))
        events.insert(log(tripId, EventType.REJECTED, "Claim returned/rejected" + if (reason.isNotBlank()) ": $reason" else ""))
    }

    suspend fun reopen(tripId: Long) {
        val t = trips.get(tripId) ?: return
        trips.update(t.copy(status = ClaimStatus.DRAFT.name, approvedAt = null, paidAt = null, paidAmount = null))
        events.insert(log(tripId, EventType.REOPENED, "Claim reopened for editing"))
    }

    suspend fun insightData(): Pair<List<InsightTrip>, List<InsightExpense>> {
        val ts = trips.all().map { t ->
            InsightTrip(t.id, t.title, t.clientName, t.projectCode, t.startDate, t.endDate, t.claimStatus, t.advance,
                t.submittedAt?.let { toDate(it) }, t.paidAt?.let { toDate(it) }, t.paidAmount)
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
