package com.tareimbursement.app.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class CategorySuggestion(
    val category: ExpenseCategory,
    val reason: String,
    /** Other plausible heads the user may prefer (e.g. food: Boarding vs Expenses during travel). */
    val alternatives: List<ExpenseCategory> = emptyList(),
    /** Nature of expense detected, shown to the user ("Cab ride", "Restaurant bill"...). */
    val nature: String,
    val confident: Boolean,
)

data class ParsedReceipt(
    val date: LocalDate?,
    val dateConfident: Boolean,
    val amount: Double?,
    val amountConfident: Boolean,
    val vendor: String?,
    val billNo: String?,
    val suggestion: CategorySuggestion,
    /** Detected foreign currency code (e.g. "USD"); null = INR / not detected. */
    val currency: String? = null,
)

/**
 * Extracts date, amount, vendor, bill number and a suggested accounting head from OCR text of an
 * invoice / receipt / screenshot. Purely heuristic and on-device; every value is reviewed by the
 * user before it goes into the statement.
 */
object ReceiptParser {

    private val MONTHS = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9, "oct" to 10,
        "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
    )
    private const val MON = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

    private val numericDate = Regex("(?<![0-9])([0-3]?[0-9])\\s?[/\\-.]\\s?([01]?[0-9])\\s?[/\\-.]\\s?((?:19|20)?[0-9]{2})(?![0-9])")
    private val isoDate = Regex("(?<![0-9])((?:19|20)[0-9]{2})[/\\-.]([01]?[0-9])[/\\-.]([0-3]?[0-9])(?![0-9])")
    private val dayMonYear = Regex("(?<![0-9])([0-3]?[0-9])(?:st|nd|rd|th)?[\\s\\-/.,]*(?:of\\s+)?$MON\\.?[\\s\\-/.,']*((?:19|20)?[0-9]{2})(?![0-9])", RegexOption.IGNORE_CASE)
    private val monDayYear = Regex("(?<![a-z])$MON\\.?\\s+([0-3]?[0-9])(?:st|nd|rd|th)?,?\\s+((?:19|20)[0-9]{2})(?![0-9])", RegexOption.IGNORE_CASE)

    private val positiveDateLabels = listOf(
        "invoice date", "bill date", "inv date", "date of issue", "issue date", "booking date",
        "travel date", "date of journey", "journey date", "ride", "trip", "dated", "date", "txn", "transaction",
        "check in", "check-in", "checkout", "check-out", "paid on", "order placed", "departure",
    )
    private val negativeDateLabels = listOf("due", "valid", "expiry", "expires", "dob", "birth", "since", "established", "fssai", "license", "licence", "warranty")

    data class DateCandidate(val date: LocalDate, val score: Int, val index: Int)

    fun parse(text: String, tripStart: LocalDate? = null, tripEnd: LocalDate? = null, today: LocalDate = LocalDate.now()): ParsedReceipt {
        val clean = normalise(text)
        val lines = clean.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val dates = findDates(lines, tripStart, tripEnd, today)
        val best = dates.maxWithOrNull(compareBy<DateCandidate> { it.score }.thenByDescending { -it.index })
        val amount = findAmount(lines)
        val vendor = findVendor(lines)
        return ParsedReceipt(
            date = best?.date,
            dateConfident = best != null && best.score >= 3,
            amount = amount?.first,
            amountConfident = amount?.second ?: false,
            vendor = vendor,
            billNo = findBillNo(lines),
            suggestion = suggestCategory(clean, vendor),
            currency = Currencies.detect(text),
        )
    }

    /** Fixes the most common OCR confusions in Indian receipts (₹ read as "Rs", "Z", "%" etc.). */
    internal fun normalise(text: String): String =
        fixDigitConfusions(text.replace(' ', ' '))
            .replace("₹", " ₹ ")
            .replace(Regex("(?i)\\bINR\\b"), " ₹ ")
            .replace(Regex("(?i)\\bRs\\.?\\s*"), " ₹ ")
            // Foreign currency markers act as "currency" markers for amount detection too.
            .replace(Regex("(?i)\\b(USD|EUR|GBP|AED|SAR|QAR|OMR|KWD|BHD|SGD|MYR|THB|IDR|JPY|CNY|HKD|AUD|NZD|CAD|CHF|ZAR|LKR|NPR|BDT|KES)\\b"), " ₹ ")
            .replace(Regex("[$€£¥฿]"), " ₹ ")
            .replace(Regex("[ \t]+"), " ")

    /**
     * Typical OCR misreads on blurred / faded bills:
     *  - letters inside numbers: "6,2O5.0O" → "6,205.00", "1l5" → "115", "2S0" → "250"
     *  - the ₹ sign read as "%", "Z", "?" or "2" glued to the amount after a total label
     *  - spaces around decimal points / commas: "6 275 . 00" → "6275.00" when next to an amount label
     */
    internal fun fixDigitConfusions(text: String): String {
        var t = text
        // Letter look-alikes between digits (or a digit and . ,)
        repeat(2) {
            t = t.replace(Regex("(?<=[0-9.,])[Oo](?=[0-9.,])"), "0")
                .replace(Regex("(?<=[0-9])[Oo](?=[/\\-][0-9Oo])"), "0")
                .replace(Regex("(?<=[0-9][/\\-])[Oo](?=[0-9])"), "0")
                .replace(Regex("(?<=[0-9])[lI|](?=[0-9.,])"), "1")
                .replace(Regex("(?<=[0-9.,])[lI|](?=[0-9])"), "1")
                .replace(Regex("(?<=[0-9])[S](?=[0-9])"), "5")
                .replace(Regex("(?<=[0-9])[B](?=[0-9])"), "8")
        }
        // Trailing ".0O" / ".O0"
        t = t.replace(Regex("(?<=[0-9])\\.[Oo0]{2}(?![0-9])")) { ".00" }
        // ₹ misread as %, Z, ? or a stray "F" immediately before an amount
        t = t.replace(Regex("(?<![A-Za-z0-9])[%Z?F]\\s?(?=[0-9][0-9,]*\\.[0-9]{2}(?![0-9]))"), "₹ ")
        t = t.replace(Regex("(?<![A-Za-z0-9])[%Z]\\s?(?=[0-9]{2,})"), "₹ ")
        // Broken decimals "1 250 . 00" after a total label
        t = t.replace(Regex("(?i)((?:grand\\s+)?total|net amount|amount)([^0-9\\n]{0,12})([0-9]{1,3})\\s+([0-9]{3})\\s*\\.\\s*([0-9]{2})(?![0-9])")) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}${m.groupValues[4]}.${m.groupValues[5]}"
        }
        return t
    }

    internal fun findDates(lines: List<String>, tripStart: LocalDate?, tripEnd: LocalDate?, today: LocalDate): List<DateCandidate> {
        val out = mutableListOf<DateCandidate>()
        var idx = 0
        lines.forEachIndexed { li, line ->
            val lower = line.lowercase(Locale.ROOT)
            val prev = if (li > 0) lines[li - 1].lowercase(Locale.ROOT) else ""
            fun add(d: LocalDate?, base: Int) {
                if (d == null) return
                if (d.year < 2000 || d.isAfter(today.plusDays(2))) return
                var score = base
                if (positiveDateLabels.any { lower.contains(it) }) score += 3
                else if (positiveDateLabels.any { prev.endsWith(it) || prev.endsWith("$it:") }) score += 2
                if (negativeDateLabels.any { lower.contains(it) }) score -= 5
                if (tripStart != null && tripEnd != null) {
                    if (!d.isBefore(tripStart.minusDays(3)) && !d.isAfter(tripEnd.plusDays(3))) score += 2
                    else if (abs(d.toEpochDay() - tripStart.toEpochDay()) > 120) score -= 2
                }
                if (li < 12) score += 1
                out += DateCandidate(d, score, idx++)
            }
            dayMonYear.findAll(line).forEach { m ->
                add(safeDate(year(m.groupValues[3]), monthOf(m.groupValues[2]), m.groupValues[1].toInt()), 2)
            }
            monDayYear.findAll(line).forEach { m ->
                add(safeDate(year(m.groupValues[3]), monthOf(m.groupValues[1]), m.groupValues[2].toInt()), 2)
            }
            isoDate.findAll(line).forEach { m ->
                add(safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()), 1)
            }
            numericDate.findAll(line).forEach { m ->
                // Skip if this "date" is actually part of an ISO date already captured.
                val d1 = m.groupValues[1].toInt(); val m1 = m.groupValues[2].toInt()
                val y = year(m.groupValues[3])
                // Indian receipts are day-first; fall back to month-first only when day-first is impossible.
                val d = safeDate(y, m1, d1) ?: safeDate(y, d1, m1)
                add(d, 1)
            }
        }
        return out
    }

    private fun monthOf(s: String): Int {
        val l = s.lowercase(Locale.ROOT)
        return MONTHS[l] ?: MONTHS[l.take(3)] ?: 0
    }

    private fun year(s: String): Int { val y = s.toInt(); return if (y < 100) 2000 + y else y }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        try { if (m in 1..12 && d in 1..31) LocalDate.of(y, m, d) else null } catch (e: Exception) { null }

    // ---------------------------------------------------------------- amount

    private val amountRegex = Regex("(?<![0-9A-Za-z])(₹\\s*)?(-?[0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|-?[0-9]+(?:\\.[0-9]{1,2})?)(?![0-9])(\\s*/-)?")

    /** Ordered by priority: the first label group that yields an amount wins. */
    private val amountLabels = listOf(
        listOf("grand total", "net amount", "net payable", "amount payable", "total payable", "net total", "invoice total", "invoice value", "bill total", "total bill"),
        listOf("total amount", "amount paid", "total paid", "you paid", "paid amount", "selected price", "total price", "total charges", "final amount", "bill amount", "amount received", "received"),
        listOf("total", "total fare", "trip fare", "paid", "fare", "amount"),
    )
    private val amountExclusions = listOf(
        "sub total", "subtotal", "sub-total", "taxable", "tax amount", "total tax", "gst", "cgst", "sgst", "igst", "discount",
        "saving", "saved", "items", "qty", "quantity", "round", "cashback", "wallet balance", "balance", "change", "tip",
        "km", "kms", "distance", "mins", "minutes", "hsn", "sac", "rate", "%",
    )

    internal fun numbersIn(line: String): List<Pair<Double, Boolean>> =
        amountRegex.findAll(line).mapNotNull { m ->
            val raw = m.groupValues[2].replace(",", "")
            val v = raw.toDoubleOrNull() ?: return@mapNotNull null
            if (v <= 0 || v > 5_000_000) return@mapNotNull null
            val hasCurrency = m.groupValues[1].isNotBlank() || m.groupValues[3].isNotBlank()
            // Ignore bare 4-digit years and long digit strings (phone numbers, PNRs).
            if (!hasCurrency && !raw.contains('.') && (raw.length >= 7 || (raw.length == 4 && raw.toInt() in 2000..2099))) return@mapNotNull null
            v to hasCurrency
        }.toList()

    /** Returns (amount, confident). */
    internal fun findAmount(lines: List<String>): Pair<Double, Boolean>? {
        val lower = lines.map { it.lowercase(Locale.ROOT) }
        for ((priority, group) in amountLabels.withIndex()) {
            val found = mutableListOf<Double>()
            lower.forEachIndexed { i, l ->
                val label = group.firstOrNull { lab -> Regex("(?<![a-z])${Regex.escape(lab)}(?![a-z])").containsMatchIn(l) } ?: return@forEachIndexed
                if (amountExclusions.any { ex -> l.contains(ex) && !group.any { it.contains(ex) } && !l.contains("grand total") && !l.contains("net amount") }) return@forEachIndexed
                // Take the number after the label on the same line, else the next line(s).
                val after = lines[i].substring(minOf(lines[i].length, l.indexOf(label) + label.length))
                var nums = numbersIn(after)
                if (nums.isEmpty()) {
                    for (j in 1..2) {
                        if (i + j >= lines.size) break
                        val nl = lower[i + j]
                        if (amountExclusions.any { nl.contains(it) }) break
                        nums = numbersIn(lines[i + j]); if (nums.isNotEmpty()) break
                    }
                }
                if (nums.isNotEmpty()) found += (nums.lastOrNull { it.second } ?: nums.last()).first
            }
            if (found.isNotEmpty()) {
                // "Grand total" style labels: last occurrence is the final figure; generic "total": the largest.
                val value = if (priority == 0) found.last() else found.max()
                return value to (priority <= 1)
            }
        }
        // Fallback: the largest currency-marked figure, else the largest decimal amount.
        val all = lines.flatMap { l -> if (amountExclusions.take(6).any { l.lowercase(Locale.ROOT).contains(it) }) emptyList() else numbersIn(l) }
        all.filter { it.second }.maxByOrNull { it.first }?.let { return it.first to false }
        lines.flatMap { numbersIn(it) }.filter { it.first % 1.0 != 0.0 }.maxByOrNull { it.first }?.let { return it.first to false }
        return null
    }

    // ---------------------------------------------------------------- vendor / bill no

    private val knownBrands = listOf(
        "Uber", "Ola", "Rapido", "Namma Yatri", "BluSmart", "Quick Ride", "Meru", "IndiGo", "Air India Express", "Air India",
        "Akasa Air", "SpiceJet", "Vistara", "Fly91", "Alliance Air", "Star Air", "Emirates", "IRCTC", "redBus", "AbhiBus",
        "Swiggy", "Zomato", "EatSure", "Dineout", "MakeMyTrip", "Goibibo", "Cleartrip", "EaseMyTrip", "Yatra", "ixigo",
        "OYO", "Treebo", "FabHotels", "Taj", "Marriott", "Hyatt", "ITC", "Lemon Tree", "Ginger", "Novotel", "Radisson",
        "Airtel", "Jio", "Vodafone Idea", "BSNL", "Starbucks", "Cafe Coffee Day", "McDonald's", "Domino's", "KFC",
        "Zoomcar", "Savaari", "Revv", "Amazon Pay", "CRED", "Paytm", "PhonePe", "Google Pay", "Chaayos", "Haldiram",
        "Barbeque Nation", "Metro", "FASTag", "Tata AIG", "ICICI Lombard", "HDFC Ergo", "VFS Global", "BLS International",
    )
    private val vendorNoise = listOf("tax invoice", "invoice", "receipt", "bill of supply", "cash memo", "original", "duplicate", "gstin", "gst no", "phone", "ph:", "tel", "mob", "www.", "http", "date", "page", "copy", "welcome", "thank")

    internal fun findVendor(lines: List<String>): String? {
        val joined = lines.joinToString(" \n ")
        knownBrands.firstOrNull { b -> Regex("(?i)(?<![a-z])${Regex.escape(b)}(?![a-z])").containsMatchIn(joined) }?.let { return it }
        return lines.take(8).firstOrNull { l ->
            val ll = l.lowercase(Locale.ROOT)
            l.count { it.isLetter() } >= 3 && vendorNoise.none { n -> Regex("(?<![a-z])" + Regex.escape(n)).containsMatchIn(ll) } &&
                !numericDate.containsMatchIn(l) && l.count { it.isDigit() } < l.length / 3
        }?.take(60)?.trim(' ', ':', '-', ',', '.')
    }

    private val billNoRegex = Regex("(?i)(?:invoice|inv|bill|receipt|folio|booking|order|ticket|txn|transaction)\\s*(?:no|number|num|#|id)\\.?\\s*[:#\\-]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-]{2,24})|(?i)\\bPNR\\b\\s*(?:no\\.?)?\\s*[:#\\-]?\\s*([A-Z0-9]{5,10})")

    internal fun findBillNo(lines: List<String>): String? {
        for (l in lines) {
            val m = billNoRegex.find(l) ?: continue
            val v = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (v.isNotEmpty() && v.any { it.isDigit() }) return v
        }
        return null
    }

    // ---------------------------------------------------------------- category

    private data class Rule(val category: ExpenseCategory, val nature: String, val words: List<String>)

    private val rules = listOf(
        Rule(ExpenseCategory.AIR_FARE, "Flight ticket", listOf("indigo", "air india", "akasa", "spicejet", "vistara", "fly91", "alliance air", "emirates", "boarding pass", "flight", "airline", "e-ticket", "aircraft", "baggage", "departure terminal", "fare summary")),
        Rule(ExpenseCategory.TRAIN_FARE, "Train ticket", listOf("irctc", "train", "berth", "coach", "railway", "electronic reservation slip", "tatkal", "3a", "2a", "sleeper", "chair car")),
        Rule(ExpenseCategory.BUS_FARE, "Bus ticket", listOf("redbus", "abhibus", "bus", "ksrtc", "tnstc", "msrtc", "apsrtc", "volvo", "travels", "seat no", "boarding point")),
        Rule(ExpenseCategory.CAR_HIRE, "Car hire / rental", listOf("car rental", "car hire", "zoomcar", "savaari", "revv", "outstation", "chauffeur", "self drive", "driver bata")),
        Rule(ExpenseCategory.CAR_PARKING, "Parking", listOf("parking", "park fee", "parking fee")),
        Rule(ExpenseCategory.LOCAL_CONVEYANCE, "Cab / auto / metro ride", listOf("uber", "ola", "rapido", "namma yatri", "blusmart", "quick ride", "meru", "auto", "metro", "taxi", "cab", "ride", "toll", "fastag", "bike taxi", "trip fare", "pickup", "drop")),
        Rule(ExpenseCategory.BOARDING, "Hotel stay", listOf("hotel", "room", "check-in", "check in", "checkout", "check-out", "room tariff", "room charges", "stay", "lodge", "resort", "inn", "oyo", "treebo", "guest house", "folio", "night", "accommodation", "suite")),
        Rule(ExpenseCategory.LAUNDRY, "Laundry", listOf("laundry", "dry clean", "dryclean", "ironing", "wash & fold", "washing", "press")),
        Rule(ExpenseCategory.TELEPHONE, "Mobile / telephone", listOf("recharge", "prepaid", "postpaid", "airtel", "jio", "vodafone", "bsnl", "data pack", "talktime", "mobile bill", "telephone")),
        Rule(ExpenseCategory.VISA, "Visa charges", listOf("visa", "vfs", "embassy", "consulate", "bls international")),
        Rule(ExpenseCategory.INSURANCE, "Travel insurance", listOf("insurance", "policy no", "premium", "tata aig", "icici lombard", "hdfc ergo", "sum insured")),
    )
    private val foodWords = listOf("restaurant", "cafe", "café", "coffee", "swiggy", "zomato", "eatsure", "dineout", "food", "dine", "meal", "lunch", "dinner", "breakfast", "kitchen", "biryani", "bar", "bakery", "canteen", "catering", "caterers", "dhaba", "mess", "tiffin", "snacks", "juice", "tea", "chai", "pizza", "burger", "kfc", "mcdonald", "domino", "starbucks", "barbeque", "bbq", "thali", "veg", "paneer", "dosa", "idli", "hotel food", "kot", "table no", "covers", "steward")

    fun suggestCategory(text: String, vendor: String? = null): CategorySuggestion {
        val t = " " + text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") + " "
        fun hits(words: List<String>) = words.filter { w -> Regex("(?<![a-z])${Regex.escape(w)}(?![a-z])").containsMatchIn(t) }
        val scored = rules.map { r -> r to hits(r.words) }.filter { it.second.isNotEmpty() }
            .map { (r, h) -> Triple(r, h, h.size + if (vendor != null && h.any { vendor.lowercase(Locale.ROOT).contains(it) }) 3 else 0) }
            .sortedByDescending { it.third }
        val food = hits(foodWords)
        val airportish = hits(listOf("airport", "terminal", "railway station", "bus stand", "kia", "kempegowda", "aerodrome"))

        val top = scored.firstOrNull()
        // Food bills: user wants to be asked each time — suggest, but offer both heads.
        if (food.size >= 2 && (top == null || top.first.category == ExpenseCategory.BOARDING && top.third <= food.size || top.third < 2)) {
            return CategorySuggestion(
                ExpenseCategory.BOARDING,
                "Looks like a food/restaurant bill (found: ${food.take(3).joinToString()}). Choose Boarding (Stay & Food) or Expenses during travel as per your policy.",
                listOf(ExpenseCategory.EXPENSES_DURING_TRAVEL), "Food / restaurant bill", confident = false,
            )
        }
        if (top == null) {
            if (food.isNotEmpty()) return CategorySuggestion(ExpenseCategory.BOARDING, "Possibly a food bill (found: ${food.joinToString()}). Please confirm.", listOf(ExpenseCategory.EXPENSES_DURING_TRAVEL, ExpenseCategory.OTHER), "Food / restaurant bill", false)
            return CategorySuggestion(ExpenseCategory.OTHER, "Could not detect the nature of this expense. Please choose the right head.", listOf(ExpenseCategory.EXPENSES_DURING_TRAVEL, ExpenseCategory.LOCAL_CONVEYANCE), "Unknown", false)
        }
        val (rule, words, _) = top
        if (rule.category == ExpenseCategory.LOCAL_CONVEYANCE && airportish.isNotEmpty()) {
            return CategorySuggestion(ExpenseCategory.PICKUP_DROP, "Cab ride to/from ${airportish.first()} (found: ${words.take(3).joinToString()}). Airport/station transfers usually go under Pick up & Drop.", listOf(ExpenseCategory.LOCAL_CONVEYANCE), "Airport / station transfer", true)
        }
        val alternatives = scored.drop(1).map { it.first.category }.filter { it != rule.category }.distinct().take(2).toMutableList()
        if (rule.category == ExpenseCategory.LOCAL_CONVEYANCE) alternatives.add(0, ExpenseCategory.PICKUP_DROP)
        return CategorySuggestion(rule.category, "${rule.nature} (found: ${words.take(3).joinToString()}).", alternatives.distinct().take(3), rule.nature, confident = words.size >= 2 || (vendor != null && top.third >= 3))
    }

    val displayDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
}
