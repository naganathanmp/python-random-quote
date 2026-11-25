package com.tareimbursement.app.core.ai

import com.tareimbursement.app.core.CategorySuggestion
import com.tareimbursement.app.core.Currencies
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.ParsedReceipt
import com.tareimbursement.app.core.ReceiptParser
import com.tareimbursement.app.core.TravelMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Which prompt format the on-device model expects. */
enum class ModelFamily { QWEN, GEMMA }

/** What the on-device model read from a bill (after validation). */
data class BillAiResult(
    val date: LocalDate?,
    val amount: Double?,
    val currency: String?,
    val vendor: String?,
    val billNo: String?,
    val category: ExpenseCategory?,
    val modeOfTravel: String?,
    val remarks: String?,
    val nature: String?,
    /** Fields the validator removed because they were not supported by the bill text. */
    val rejected: List<String> = emptyList(),
)

/** Extra context the model gets besides the bill text. */
data class BillContext(
    val tripStart: LocalDate? = null,
    val tripEnd: LocalDate? = null,
    val place: String = "",
    /** Date taken from the file's metadata (photo capture date / PDF creation date), if any. */
    val metadataDate: LocalDate? = null,
)

/**
 * Prompting, parsing and validating an on-device LLM for bill reading. The model proposes,
 * the code verifies against the bill text, and the user confirms — the model can never invent
 * an amount that is not printed on the bill.
 */
object BillAi {
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    val categoryList: String = ExpenseCategory.entries.joinToString("; ") { "${it.name}=${it.label}" }

    private const val INSTRUCTIONS = """You read Indian travel bills for a travelling-allowance (TA) claim.
Return ONLY one JSON object with these keys:
"date": bill/invoice/ride date as YYYY-MM-DD, or null if not printed,
"amount": final amount paid (grand total / net payable / total fare) as a number, never a subtotal, tax line or discount,
"currency": ISO code like INR, USD, AED,
"vendor": merchant or company name,
"bill_no": invoice/bill/receipt/PNR number or null,
"category": exactly one code from CATEGORIES,
"mode": one of MODES,
"remarks": 3 to 10 words saying what the expense was and where, e.g. "Uber cab from airport to hotel, Hosur".
Rules: copy numbers and dates exactly as printed on the bill; if a value is not printed use null; never guess a date.
Categories: restaurant, meals, food delivery and hotel room stay are BOARDING; coffee or snacks at an airport/station while travelling are EXPENSES_DURING_TRAVEL; cab, auto, metro, bike taxi and toll are LOCAL_CONVEYANCE, but a cab to or from an airport or railway station is PICKUP_DROP; bus tickets (redBus, KSRTC) are BUS_FARE; train tickets (IRCTC) are TRAIN_FARE; flights are AIR_FARE; laundry is LAUNDRY."""

    fun modes(): String = TravelMode.options.joinToString(", ")

    fun userMessage(ocrText: String, ctx: BillContext): String = buildString {
        append("CATEGORIES: ").append(categoryList).append('\n')
        append("MODES: ").append(modes()).append('\n')
        ctx.metadataDate?.let { append("FILE DATE (from photo/PDF metadata, use only if no date is printed): ${it.format(iso)}\n") }
        append("BILL TEXT:\n")
        append(ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(2200))
    }

    /** Full prompt in the model's chat format. */
    /** Marks where a vision model receives the bill picture. */
    const val IMAGE_SLOT = "<<IMAGE>>"

    fun prompt(ocrText: String, ctx: BillContext, family: ModelFamily, withImage: Boolean = false): String {
        val user = userMessage(ocrText, ctx)
        return when (family) {
            ModelFamily.QWEN -> "<|im_start|>system\n$INSTRUCTIONS<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n{"
            ModelFamily.GEMMA -> if (withImage)
                "<start_of_turn>user\n$INSTRUCTIONS\nThe bill picture:\n$IMAGE_SLOT\nOCR text of the same bill (may have reading errors; trust the picture):\n$user<end_of_turn>\n<start_of_turn>model\n{"
            else "<start_of_turn>user\n$INSTRUCTIONS\n\n$user<end_of_turn>\n<start_of_turn>model\n{"
        }
    }

    /** Parses the model's answer (the prompt already opened the JSON object with "{"). */
    fun parse(response: String): Map<String, Any?>? {
        val t = response.trim().removePrefix("```json").removePrefix("```").trim()
        return MiniJson.firstObject(if (t.startsWith("{")) t else "{$t")
    }

    private fun str(m: Map<String, Any?>, k: String): String? =
        (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("unknown", true) && it != "-" }

    private fun num(m: Map<String, Any?>, k: String): Double? = when (val v = m[k]) {
        is Double -> v
        is String -> v.replace(",", "").replace("₹", "").replace(Regex("(?i)rs\\.?|inr"), "").trim().toDoubleOrNull()
        else -> null
    }

    /** Digits of all numbers printed on the bill, for checking that the model did not invent an amount. */
    internal fun printedAmounts(ocrText: String): Set<Long> {
        val t = ReceiptParser.fixDigitConfusions(ocrText)
        return Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?").findAll(t).mapNotNull { m ->
            m.value.replace(",", "").toDoubleOrNull()?.let { Math.round(it * 100) }
        }.toSet()
    }

    /** Validates model output against the bill text and context. */
    fun validate(raw: Map<String, Any?>, ocrText: String, ctx: BillContext, today: LocalDate = LocalDate.now()): BillAiResult {
        val rejected = mutableListOf<String>()
        val printed = printedAmounts(ocrText)
        var amount = num(raw, "amount")?.takeIf { it > 0 && it < 10_000_000 }
        if (amount != null && Math.round(amount * 100) !in printed) {
            // allow "830" when the bill shows "830.00" (same paise value is already covered); otherwise reject
            rejected += "amount"; amount = null
        }
        var date = str(raw, "date")?.let { d -> runCatching { LocalDate.parse(d.take(10)) }.getOrNull() }
        if (date != null) {
            var supported = dateSupported(date, ocrText, ctx) || date == ctx.metadataDate
            if (!supported) {
                // "26 Jul" printed without a year and the model guessed the wrong year: use the trip's year.
                val fixed = listOfNotNull(ctx.tripStart?.year, ctx.tripEnd?.year).distinct()
                    .mapNotNull { y -> runCatching { date!!.withYear(y) }.getOrNull() }
                    .firstOrNull { dateSupported(it, ocrText, ctx) }
                if (fixed != null) { date = fixed; supported = true }
            }
            val d = date!!
            val tooOldNow = d.isBefore(today.minusYears(2)); val futureNow = d.isAfter(today.plusDays(2))
            val farNow = ctx.tripStart != null && ctx.tripEnd != null && (d.isBefore(ctx.tripStart.minusDays(60)) || d.isAfter(ctx.tripEnd.plusDays(60)))
            if (tooOldNow || futureNow || farNow || !supported) { rejected += "date"; date = null }
        }
        val currency = str(raw, "currency")?.uppercase(Locale.ROOT)?.takeIf { c -> Currencies.all.any { it.code == c } }
        val category = str(raw, "category")?.let { c ->
            ExpenseCategory.entries.firstOrNull { it.name.equals(c, true) || it.label.equals(c, true) || it.templateLabel.trim().equals(c.trim(), true) }
        }
        if (str(raw, "category") != null && category == null) rejected += "category"
        val mode = str(raw, "mode")?.let { m -> TravelMode.options.firstOrNull { it.equals(m, true) } }
        val remarks = str(raw, "remarks")?.replace(Regex("\\s+"), " ")?.take(120)
        val vendor = str(raw, "vendor")?.take(60)
        val billNo = str(raw, "bill_no")?.take(30)?.takeIf { b -> ocrText.contains(b, ignoreCase = true) }
        return BillAiResult(date, amount, currency, vendor, billNo, category, mode, remarks, category?.let { natureOf(it) }, rejected)
    }

    /** True when the bill text contains this date in any common format. */
    internal fun dateSupported(d: LocalDate, ocrText: String, ctx: BillContext = BillContext()): Boolean {
        val heuristic = ReceiptParser.findDates(ReceiptParser.normalise(ocrText).lines().map { it.trim() }.filter { it.isNotEmpty() }, null, null, LocalDate.now().plusDays(3)).map { it.date }
        if (d in heuristic) return true
        // Bills like Swiggy print "26 Jul" without a year: accept when day + month are printed and the date is in the trip.
        val inTrip = ctx.tripStart != null && ctx.tripEnd != null && !d.isBefore(ctx.tripStart.minusDays(3)) && !d.isAfter(ctx.tripEnd.plusDays(3))
        if (!inTrip) return false
        val mon = d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
        val t = ReceiptParser.fixDigitConfusions(ocrText)
        return Regex("(?i)(?<![0-9])0?${d.dayOfMonth}(?:st|nd|rd|th)?\\s*[-/ ]?\\s*$mon").containsMatchIn(t) ||
            Regex("(?i)$mon\\.?\\s+0?${d.dayOfMonth}(?![0-9])").containsMatchIn(t) ||
            Regex("(?<![0-9])0?${d.dayOfMonth}[/.-]0?${d.monthValue}(?![0-9/.-])").containsMatchIn(t)
    }

    /** Drops AI remarks that contradict the category (e.g. "Flight …" for a cab ride). */
    internal fun consistent(remarks: String, c: ExpenseCategory): Boolean {
        val r = remarks.lowercase(Locale.ROOT)
        fun has(vararg w: String) = w.any { Regex("(?<![a-z])$it").containsMatchIn(r) }
        return when (c) {
            ExpenseCategory.LOCAL_CONVEYANCE, ExpenseCategory.PICKUP_DROP, ExpenseCategory.CAR_HIRE -> !has("flight", "train ticket", "hotel stay", "room stay")
            ExpenseCategory.AIR_FARE -> !has("cab", "auto", "train", "bus", "meal", "dinner")
            ExpenseCategory.TRAIN_FARE -> !has("flight", "cab", "hotel")
            ExpenseCategory.BUS_FARE -> !has("flight", "train", "hotel")
            ExpenseCategory.LAUNDRY -> !has("flight", "cab", "meal")
            else -> true
        }
    }

    private fun natureOf(c: ExpenseCategory) = when (c) {
        ExpenseCategory.BOARDING -> "Stay / food"
        ExpenseCategory.LOCAL_CONVEYANCE -> "Cab / auto / metro ride"
        ExpenseCategory.PICKUP_DROP -> "Airport / station transfer"
        ExpenseCategory.AIR_FARE -> "Flight ticket"
        ExpenseCategory.TRAIN_FARE -> "Train ticket"
        ExpenseCategory.BUS_FARE -> "Bus ticket"
        ExpenseCategory.LAUNDRY -> "Laundry"
        else -> c.label
    }

    /**
     * Combines the rule-based reading with the model's reading. Agreement makes a value confident;
     * the model fills gaps the rules missed; its category becomes the suggestion (the user still
     * confirms every bill).
     */
    fun fuse(rules: ParsedReceipt?, ai: BillAiResult?, metadataDate: LocalDate?): ParsedReceipt? {
        if (rules == null && ai == null) {
            return metadataDate?.let { ParsedReceipt(it, false, null, false, null, null, ReceiptParser.suggestCategory(""), null) }
        }
        val base = rules ?: ParsedReceipt(null, false, null, false, null, null, ReceiptParser.suggestCategory(""), null)
        if (ai == null) return base.copy(date = base.date ?: metadataDate, dateConfident = base.date != null && base.dateConfident)
        val amountAgree = base.amount != null && ai.amount != null && abs(base.amount - ai.amount) < 0.005
        // Disagreement: the bill total is the larger printed figure (sub totals and tax lines are smaller).
        val amount = when {
            amountAgree -> base.amount
            ai.amount != null && base.amount == null -> ai.amount
            ai.amount != null && base.amount != null -> maxOf(ai.amount, base.amount)
            else -> base.amount
        }
        val dateAgree = base.date != null && base.date == ai.date
        val date = when {
            dateAgree -> base.date
            ai.date != null && (base.date == null || !base.dateConfident) -> ai.date
            else -> base.date
        } ?: metadataDate
        val rulesSure = base.suggestion.confident && base.suggestion.nature != "Unknown"
        val suggestion = when {
            ai.category == null -> base.suggestion
            ai.category == base.suggestion.category -> base.suggestion.copy(confident = true, reason = base.suggestion.reason + " The on-device AI agrees.")
            rulesSure -> base.suggestion.copy(alternatives = (listOf(ai.category) + base.suggestion.alternatives).distinct().take(3))
            else -> {
                val cat = ai.category
                val alts = (listOf(base.suggestion.category) + base.suggestion.alternatives).filter { it != cat }.distinct().take(2)
                CategorySuggestion(cat, "On-device AI read this as: ${ai.remarks ?: ai.nature ?: cat.label}.", alts, ai.nature ?: base.suggestion.nature, confident = false)
            }
        }
        return base.copy(
            date = date, dateConfident = dateAgree || (base.dateConfident && date == base.date),
            amount = amount, amountConfident = amountAgree || (base.amountConfident && ai.amount == null && amount == base.amount),
            vendor = base.vendor ?: ai.vendor, billNo = base.billNo ?: ai.billNo,
            currency = base.currency ?: ai.currency?.takeIf { it != Currencies.INR },
            suggestion = suggestion,
            aiRemarks = ai.remarks?.takeIf { consistent(it, suggestion.category) }, aiMode = null,
        )
    }
}
