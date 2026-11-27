package com.tareimbursement.app.service.ai

import android.content.Context
import com.tareimbursement.app.core.CategorySuggestion
import com.tareimbursement.app.core.Currencies
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.ParsedReceipt
import com.tareimbursement.app.core.TravelMode
import com.tareimbursement.app.core.ai.AiModel
import com.tareimbursement.app.core.ai.BillAi
import com.tareimbursement.app.core.ai.BillAiResult
import com.tareimbursement.app.core.ai.BillContext
import com.tareimbursement.app.data.AttachmentKind
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.service.Media
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

/**
 * Second reading of new bills by the on-device model, after the quick OCR reading. Runs in the
 * background one bill at a time; the model is unloaded afterwards to give the memory back.
 * Never touches a bill the person already edited or confirmed.
 */
class AiRunner(
    private val context: Context,
    private val repo: Repository,
    private val models: ModelManager,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val engineFactory: (AiModel, File) -> LlmEngine = { m, f -> MediaPipeEngine.load(context, m, f) },
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<String?>(null)
    /** "AI reading bill 2 of 5…" while running, else null. */
    val status: StateFlow<String?> = _status

    fun kick() { scope.launch(Dispatchers.Default) { runQueue() } }

    suspend fun runQueue(): Int = mutex.withLock {
        val queue = repo.expenses.aiQueue()
        if (queue.isEmpty()) return 0
        val model = models.installed()
        if (!enabled() || model == null) {
            // Nothing to run with: release the queue so the bills don't show "AI reading…" forever.
            queue.forEach { e -> repo.expenses.update(e.copy(aiState = "")) }
            return 0
        }
        val engine = try { engineFactory(model, models.fileFor(model)) } catch (t: Throwable) {
            // Technical details go to the error report (Settings); the bill shows a plain note.
            try { com.tareimbursement.app.service.CrashLog.record(context, com.tareimbursement.app.BuildConfig.VERSION_NAME, "ai-load", t) } catch (_: Throwable) { }
            val msg = if (t.message?.contains("64-bit ARM") == true) "This phone's processor can't run the AI helper - text-scan reading kept."
                else "$LOAD_FAILED Text-scan reading kept; it will retry automatically."
            queue.forEach { e -> repo.expenses.update(e.copy(aiState = "FAILED", aiNote = msg)) }
            _status.value = null
            return 0
        }
        var done = 0
        try {
            queue.forEachIndexed { i, e0 ->
                _status.value = "On-device AI reading bill ${i + 1} of ${queue.size}…"
                try {
                    val trip = repo.trips.get(e0.tripId) ?: return@forEachIndexed
                    val ctx = BillContext(trip.startDate, trip.endDate, trip.location, metadataDate = if (e0.dateFromMetadata) e0.date else null)
                    val image = if (model.vision) primaryImage(e0.id) else null
                    val prompt = BillAi.prompt(e0.ocrText, ctx, model.family, withImage = image != null)
                    val answer = try { engine.generate(prompt, image) } finally { image?.recycle() }
                    val ai = BillAi.parse(answer)?.let { BillAi.validate(it, e0.ocrText, ctx, LocalDate.now()) }
                    // Re-read: the person may have edited the bill while the model was thinking.
                    val cur = repo.expenses.get(e0.id) ?: return@forEachIndexed
                    repo.expenses.update(repo.touch(apply(cur, ai, trip.location)))
                    repo.refreshDuplicates(cur.id)
                    done++
                } catch (t: Throwable) {
                    try { com.tareimbursement.app.service.CrashLog.record(context, com.tareimbursement.app.BuildConfig.VERSION_NAME, "ai-read", t) } catch (_: Throwable) { }
                    repo.expenses.get(e0.id)?.let { repo.expenses.update(it.copy(aiState = "FAILED", aiNote = "AI helper couldn't read this bill - text-scan reading kept.")) }
                }
            }
        } finally {
            engine.close()
            _status.value = null
        }
        done
    }

    private suspend fun primaryImage(expenseId: Long): android.graphics.Bitmap? {
        val a = repo.attachments.forExpense(expenseId).firstOrNull() ?: return null
        val f = repo.fileOf(a)
        return when (a.kindEnum) {
            AttachmentKind.IMAGE -> Media.decodeSampled(f, 1024)
            AttachmentKind.PDF -> Media.renderPdfPage(f, 0, 1024)
            else -> null
        }
    }

    companion object {
        const val LOAD_FAILED = "AI helper couldn't start."

        /**
         * Merges the model's reading into a draft bill. Values the person typed are never replaced;
         * the result is still a suggestion they confirm.
         */
        fun apply(e: ExpenseEntity, ai: BillAiResult?, place: String = ""): ExpenseEntity {
            if (ai == null) return e.copy(aiState = "FAILED", aiNote = "AI gave no usable answer - OCR reading kept.")
            if (e.userEdited || e.confirmed) {
                val differs = (ai.amount != null && ai.amount != (if (e.isForeign) e.foreignAmount else e.amount)) || (ai.date != null && ai.date != e.date)
                return e.copy(aiState = "DONE", aiNote = if (differs) "AI read ${ai.amount ?: "-"} on ${ai.date ?: "-"}; your values were kept." else "AI agrees with this bill.")
            }
            val oldCat = e.suggestedEnum
            val rules = ParsedReceipt(
                date = if (e.dateFromMetadata) null else e.date, dateConfident = e.dateConfident,
                amount = if (e.isForeign) e.foreignAmount else e.amount, amountConfident = e.amountConfident,
                vendor = e.vendor.ifBlank { null }, billNo = e.billNo.ifBlank { null },
                suggestion = CategorySuggestion(oldCat ?: ExpenseCategory.OTHER, e.suggestionReason, e.alternatives, e.nature.ifBlank { "Unknown" }, confident = oldCat != null && e.suggestionReason.isNotBlank() && !e.suggestionReason.contains("not sure", true)),
                currency = e.currency.takeIf { it != Currencies.INR },
            )
            val f = BillAi.fuse(rules, ai, if (e.dateFromMetadata) e.date else null) ?: return e.copy(aiState = "DONE")
            val newCat = f.suggestion.category
            val defaultOld = com.tareimbursement.app.core.RemarkWriter.write(oldCat, e.ocrText, e.vendor, place, e.nature)
            val remarksUntouched = e.description.isBlank() || e.description == defaultOld || e.description == TravelMode.defaultRemark(oldCat, e.nature, e.vendor)
            val vendor = e.vendor.ifBlank { f.vendor.orEmpty() }
            // House-style remark from the bill text; the AI's own words only when the text gives too little.
            var house = com.tareimbursement.app.core.RemarkWriter.write(newCat, e.ocrText, vendor, place, f.suggestion.nature)
            val aiMeal = f.aiRemarks?.let { Regex("(?i)\\b(breakfast|lunch|dinner|snacks)\\b").find(it)?.value?.lowercase()?.replaceFirstChar { c -> c.uppercase() } }
            if (aiMeal != null && house.startsWith("Meal")) house = aiMeal + house.removePrefix("Meal")
            val thin = house.split(' ').size <= 3
            val description = if (remarksUntouched) (if (thin && f.aiRemarks != null) f.aiRemarks else house) else e.description
            val headUntouched = e.category == null || e.category == e.suggestedCategory
            val amount = f.amount
            val (inr, foreign) = if (e.isForeign) (amount?.let { a -> e.exchangeRate?.let { Currencies.toInr(a, it) } } to amount) else (amount to e.foreignAmount)
            val dateFromMeta = e.dateFromMetadata && f.date == e.date && (ai.date == null || ai.date != e.date)
            val notes = buildList {
                add("Checked by on-device AI.")
                if (amount != null && !f.amountConfident) add("Amount: OCR and AI read different figures - please verify.")
                if (dateFromMeta) add("No date printed - date taken from the file.")
            }
            return e.copy(
                dateEpochDay = f.date?.toEpochDay(), dateConfident = f.dateConfident, dateFromMetadata = dateFromMeta,
                amount = inr, foreignAmount = foreign, amountConfident = f.amountConfident,
                vendor = vendor, billNo = e.billNo.ifBlank { f.billNo.orEmpty() },
                suggestedCategory = newCat.name, suggestionReason = f.suggestion.reason,
                suggestionAlternatives = f.suggestion.alternatives.joinToString(",") { it.name }, nature = f.suggestion.nature,
                description = description,
                category = if (headUntouched) newCat.name else e.category,
                modeOfTravel = if (newCat != oldCat || e.modeOfTravel.isBlank()) com.tareimbursement.app.core.RemarkWriter.mode(newCat, e.ocrText, vendor) else e.modeOfTravel,
                aiState = "DONE", aiNote = notes.joinToString(" "),
            )
        }
    }
}
