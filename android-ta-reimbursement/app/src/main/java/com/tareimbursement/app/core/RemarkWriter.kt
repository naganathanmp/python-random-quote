package com.tareimbursement.app.core

import java.time.LocalTime
import java.util.Locale

/**
 * Writes the mandatory annexure remark the way the firm's own TA annexures do (learned from past
 * statements): the nature of the expense in a few words, plus a short explanation for exceptions.
 *
 *  Local conveyance  "Rapido Bike from 148 Halasuru to Bommasandra", "Uber Go from A to B (toll incl.)",
 *                    "NHAI Electronic City Toll - Bangalore", "Bangalore Metro recharge via PhonePe"
 *  Pick up & drop    "UberXL from IGI Delhi Airport to Dharamshila Hospital"
 *  Boarding (food)   "Dinner - Hotel Mangalam ( Hosur )"
 *  During travel     "Lunch at Shrinidhi Upchar, Bommasandra", "Late-night dinner via Swiggy - Kolkata"
 *  Laundry           "Hotel Narayani - Laundry bill"
 *  Exceptions        "Lunch - food bill not provided, hence attached payment receipt (PhonePe UPI)"
 */
object RemarkWriter {

    data class Ride(val provider: String, val service: String, val from: String?, val to: String?, val tollIncluded: Boolean)

    private fun low(s: String) = s.lowercase(Locale.ROOT)
    private fun has(t: String, vararg w: String) = w.any { Regex("(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(t) }

    // ------------------------------------------------------------------ rides

    private val providers = listOf(
        "rapido" to "Rapido", "uber" to "Uber", "ola" to "Ola", "namma yatri" to "Namma Yatri", "blusmart" to "BluSmart",
        "quickride" to "QuickRide", "quick ride" to "QuickRide", "meru" to "Meru", "indrive" to "inDrive", "yulu" to "Yulu", "bounce" to "Bounce",
    )

    fun ride(text: String, vendor: String = ""): Ride? {
        val t = low("$vendor\n$text")
        val provider = providers.firstOrNull { has(t, it.first) || t.contains(it.first + "x") }?.second ?: return null
        val service = when {
            Regex("uber ?xl").containsMatchIn(t) -> "XL"
            has(t, "uber go", "go sedan") -> "Go"
            has(t, "premier") -> "Premier"
            has(t, "bike saver") -> "Bike Saver"
            has(t, "moto", "bike", "two wheeler", "2 wheeler") -> "Bike"
            has(t, "auto", "rickshaw") -> "Auto"
            has(t, "cab economy", "non ac", "non-ac") -> "Cab Non-AC"
            has(t, "cab xl", "suv", "xl") -> "Cab XL"
            has(t, "mini", "sedan", "prime", "cab", "car") -> "Cab"
            else -> ""
        }
        val (from, to) = route(text)
        val toll = Regex("(?i)\\btoll\\b[^\\n]{0,30}?(?:₹|rs\\.?|inr)?\\s*[1-9]\\d*(?:\\.\\d{1,2})?").containsMatchIn(text)
        return Ride(provider, service, from, to, toll)
    }

    /** Pickup and drop places from a ride receipt: labelled lines, else the first two address-like lines. */
    fun route(text: String): Pair<String?, String?> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun after(rx: Regex): String? {
            lines.forEachIndexed { i, l ->
                val m = rx.find(l) ?: return@forEachIndexed
                val rest = l.substring(m.range.last + 1).trim(' ', ':', '-', '|')
                val cand = if (rest.length >= 4) rest else lines.getOrNull(i + 1)
                if (cand != null && looksLikePlace(cand)) return shortPlace(cand)
            }
            return null
        }
        val from = after(Regex("(?i)^(pick ?up( location| address)?|from|start(ing)? (point|location)|source|origin)\\b"))
        val to = after(Regex("(?i)^(drop( ?off)?( location| address)?|to|destination|end (point|location))\\b"))
        if (from != null && to != null && from != to) return from to to
        // Uber / Rapido layouts: "10:15 AM | 148, Halasuru, Bengaluru…" or an address right after a time.
        val places = mutableListOf<String>()
        lines.forEachIndexed { i, l ->
            val timed = Regex("^\\d{1,2}[:.]\\d{2}\\s*(am|pm|AM|PM)?\\s*[|·•-]?\\s*(.+)$").find(l)?.groupValues?.get(2)
            val cand = when {
                timed != null && looksLikePlace(timed) -> timed
                Regex("^\\d{1,2}[:.]\\d{2}\\s*(am|pm|AM|PM)?$").matches(l) && lines.getOrNull(i + 1)?.let(::looksLikePlace) == true -> lines[i + 1]
                else -> null
            }
            if (cand != null) shortPlace(cand).let { if (it !in places) places += it }
        }
        return if (places.size >= 2) places[0] to places[1] else (from to to)
    }

    private val noise = Regex("(?i)(total|fare|amount|paid|payment|gst|tax|invoice|receipt|rating|trip id|ride id|booking|distance|duration|minutes|mins|\\bkm\\b|thanks|thank you|₹|rs\\.)")

    private fun looksLikePlace(s: String): Boolean {
        val t = s.trim()
        if (t.length < 4 || t.length > 160 || noise.containsMatchIn(t)) return false
        val letters = t.count { it.isLetter() }
        return letters >= 4 && letters >= t.length / 3
    }

    /** "148, Halasuru, Bengaluru, Karnataka 560008, India" → "148 Halasuru". */
    fun shortPlace(s: String): String {
        val drop = Regex("(?i)^(india|karnataka|tamil nadu|maharashtra|delhi|new delhi|west bengal|telangana|kerala|uttar pradesh|haryana|\\d{6}|.*\\b\\d{6}\\b.*)$")
        val parts = s.split(',').map { it.trim().trim('.', '-') }.filter { it.isNotEmpty() && !drop.matches(it) }
        // House number + street/area, or area + road: the first two parts, without the city.
        val cities = Regex("(?i)^(bengaluru|bangalore|chennai|mumbai|new delhi|delhi|kolkata|hyderabad|pune|noida|gurugram|gurgaon|hosur|kochi|ahmedabad|ncr)$")
        val keep = parts.filterIndexed { i, p -> i == 0 || !cities.matches(p) }.take(2)
        return keep.joinToString(" ").replace(Regex("\\s+"), " ").take(60).trim()
    }

    // ------------------------------------------------------------------ meals

    /** Breakfast / Lunch / Snacks / Dinner / Late-night dinner from the time printed on the bill. */
    fun meal(text: String): String? {
        val t = billTime(text) ?: return null
        return when {
            t.isBefore(LocalTime.of(4, 0)) -> "Late-night dinner"
            t.isBefore(LocalTime.of(11, 0)) -> "Breakfast"
            t.isBefore(LocalTime.of(16, 0)) -> "Lunch"
            t.isBefore(LocalTime.of(18, 30)) -> "Snacks"
            t.isBefore(LocalTime.of(22, 30)) -> "Dinner"
            else -> "Late-night dinner"
        }
    }

    fun billTime(text: String): LocalTime? {
        val ampm = Regex("(?i)\\b(\\d{1,2})[:.](\\d{2})(?::\\d{2})?\\s*([ap])\\.?m\\b").find(text)
        if (ampm != null) {
            val (h, m, ap) = ampm.destructured
            var hh = h.toInt() % 12; if (ap.equals("p", true)) hh += 12
            return runCatching { LocalTime.of(hh, m.toInt()) }.getOrNull()
        }
        val h24 = Regex("(?i)(?:time|at|\\d{4}|\\d{2})\\s*[:,]?\\s*([01]?\\d|2[0-3]):([0-5]\\d)(?::[0-5]\\d)?\\b").find(text) ?: return null
        return runCatching { LocalTime.of(h24.groupValues[1].toInt(), h24.groupValues[2].toInt()) }.getOrNull()
    }

    private val deliveryApps = listOf("swiggy" to "Swiggy", "zomato" to "Zomato", "eatsure" to "EatSure", "magicpin" to "magicpin")
    private val payApps = listOf("phonepe" to "PhonePe", "google pay" to "Google Pay", "gpay" to "Google Pay", "paytm" to "Paytm", "amazon pay" to "Amazon Pay", "bhim" to "BHIM", "cred" to "CRED")

    /** A UPI / wallet payment screenshot instead of the merchant's bill. */
    fun paymentProofApp(text: String): String? {
        val t = low(text)
        val app = payApps.firstOrNull { has(t, it.first) }?.second ?: if (has(t, "upi transaction id", "upi ref", "utr")) "UPI" else return null
        val isBill = has(t, "invoice", "tax invoice", "bill no", "gstin", "cgst", "sgst", "item", "qty")
        val isPayment = has(t, "paid to", "payment successful", "transaction successful", "sent to", "debited", "upi transaction id", "transaction id")
        return if (isPayment && !isBill) app else null
    }

    // ------------------------------------------------------------------ main

    /**
     * The remark for one bill. [place] is the claim's client location. Never returns an empty string
     * when anything is known; the person can always edit it.
     */
    fun write(category: ExpenseCategory?, text: String, vendor: String, place: String, nature: String = ""): String {
        val t = low(text)
        val v = cleanVendor(vendor)
        val city = place.trim()
        val payProof = paymentProofApp(text)
        return when (category) {
            ExpenseCategory.LOCAL_CONVEYANCE, ExpenseCategory.PICKUP_DROP -> {
                if (has(t, "toll", "fastag", "toll plaza") && ride(text, vendor) == null) {
                    val plaza = Regex("(?i)([A-Za-z][A-Za-z .]{2,30}?)\\s+toll\\s*plaza").find(text)?.groupValues?.get(1)?.trim()
                    val two = if (has(t, "two wheeler", "2 wheeler", "two-wheeler", "motorcycle")) " (two-wheeler)" else ""
                    return listOfNotNull("NHAI".takeIf { has(t, "nhai") }, plaza, "Toll$two").joinToString(" ") + (if (city.isNotBlank()) " - $city" else "")
                }
                if (has(t, "metro", "bmrcl", "dmrc", "kmrl", "cmrl")) {
                    val what = if (has(t, "recharge", "top up", "top-up")) "recharge" else "ticket"
                    val via = payApps.firstOrNull { has(t, it.first) }?.second?.let { " via $it" }.orEmpty()
                    return listOf(city.ifBlank { "" }, "Metro $what$via").filter { it.isNotBlank() }.joinToString(" ")
                }
                val r = ride(text, vendor)
                if (r != null) {
                    val head = listOf(r.provider, r.service).filter { it.isNotBlank() }.joinToString(if (r.provider == "Uber" && r.service == "XL") "" else " ")
                    val route = when {
                        r.from != null && r.to != null -> " from ${r.from} to ${r.to}"
                        r.to != null -> " to ${r.to}"
                        city.isNotBlank() -> " in $city"
                        else -> ""
                    }
                    return head + route + (if (r.tollIncluded) " (toll incl.)" else "")
                }
                val mode = when { has(t, "auto", "rickshaw") -> "Auto"; has(t, "bike") -> "Bike taxi"; else -> "Cab" }
                listOf(mode, if (city.isNotBlank()) "in $city" else "").filter { it.isNotBlank() }.joinToString(" ")
            }
            ExpenseCategory.BOARDING, ExpenseCategory.EXPENSES_DURING_TRAVEL -> {
                val stay = has(t, "room", "check-in", "check in", "checkout", "check-out", "night", "nights", "accommodation", "room tariff", "lodging") &&
                    !has(t, "restaurant", "dinner", "lunch", "breakfast", "meal", "kot")
                if (stay) {
                    val nights = Regex("(?i)(\\d{1,2})\\s*nights?").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    return "Hotel stay" + (if (v.isNotBlank()) " - $v" else "") + (if (city.isNotBlank()) " ( $city )" else "") +
                        (nights?.let { " - $it night${if (it > 1) "s" else ""}" } ?: "")
                }
                val meal = meal(text) ?: if (has(t, "coffee", "tea", "snack", "juice")) "Snacks" else "Meal"
                val app = deliveryApps.firstOrNull { has(t, it.first) }?.second
                if (payProof != null) {
                    return "$meal - food bill not provided, hence attached payment receipt ($payProof${if (payProof == "UPI") "" else " UPI"})" +
                        (if (city.isNotBlank()) " - $city" else "")
                }
                if (category == ExpenseCategory.BOARDING) {
                    when {
                        app != null -> "$meal - $app" + (if (v.isNotBlank() && !low(v).contains(low(app))) " ($v)" else "") + (if (city.isNotBlank()) " ( $city )" else "")
                        v.isNotBlank() -> "$meal - $v" + (if (city.isNotBlank()) " ( $city )" else "")
                        else -> meal + (if (city.isNotBlank()) " ( $city )" else "")
                    }
                } else {
                    when {
                        app != null -> "$meal via $app" + (if (city.isNotBlank()) " - $city" else "")
                        v.isNotBlank() -> "$meal at $v" + (if (city.isNotBlank() && !low(v).contains(low(city))) ", $city" else "")
                        else -> meal + (if (city.isNotBlank()) " at $city" else "")
                    }
                }
            }
            ExpenseCategory.LAUNDRY -> (if (v.isNotBlank()) "$v - " else "") + "Laundry bill"
            ExpenseCategory.AIR_FARE -> {
                val codes = Regex("\\b([A-Z]{3})\\s*(?:-|→|to|->|–)\\s*([A-Z]{3})\\b").find(text)
                val airline = listOf("IndiGo", "Air India", "Akasa", "SpiceJet", "Vistara", "Air India Express", "Emirates").firstOrNull { has(t, low(it)) }
                "Flight" + (codes?.let { " ${it.groupValues[1]} to ${it.groupValues[2]}" } ?: "") + (airline?.let { " ($it)" } ?: "")
            }
            ExpenseCategory.TRAIN_FARE -> {
                val r = route(text)
                "Train" + (if (r.first != null && r.second != null) " from ${r.first} to ${r.second}" else "") + (if (has(t, "irctc")) " (IRCTC)" else "")
            }
            ExpenseCategory.BUS_FARE -> {
                val r = route(text)
                "Bus" + (if (r.first != null && r.second != null) " from ${r.first} to ${r.second}" else "") + (if (has(t, "redbus")) " (redBus)" else "")
            }
            else -> listOf(nature.takeIf { it.isNotBlank() && it != "Unknown" } ?: category?.shortLabel.orEmpty(), v).filter { it.isNotBlank() }.joinToString(" - ")
        }.replace(Regex("\\s+"), " ").trim()
    }

    /** Short mode of travel in the firm's style: Rapido / Cab / Bike / Auto / Metro / Food / NA / Laundry… */
    fun mode(category: ExpenseCategory?, text: String, vendor: String): String {
        val t = low("$vendor\n$text")
        return when (category) {
            ExpenseCategory.LOCAL_CONVEYANCE, ExpenseCategory.PICKUP_DROP -> {
                val r = ride(text, vendor)
                when {
                    has(t, "metro", "bmrcl", "dmrc") -> "Metro"
                    r?.service?.startsWith("Bike") == true || (r == null && has(t, "bike", "two wheeler", "two-wheeler")) -> "Bike"
                    r?.service == "Auto" || (r == null && has(t, "auto", "rickshaw")) -> "Auto"
                    has(t, "own vehicle", "own car", "per km") -> "Car - Own"
                    else -> "Cab"
                }
            }
            ExpenseCategory.BOARDING, ExpenseCategory.EXPENSES_DURING_TRAVEL ->
                if (write(category, text, vendor, "").startsWith("Hotel stay")) "NA" else "Food"
            null -> ""
            else -> TravelMode.suggest(category, text, vendor)
        }
    }

    private fun cleanVendor(v: String): String = v.trim().trim('.', ',', '-', '|').replace(Regex("(?i)\\b(pvt\\.?|private|ltd\\.?|limited|llp)\\b"), "").replace(Regex("\\s+"), " ").trim().take(50)
}
