package com.tareimbursement.app.core

import java.util.Locale
import kotlin.math.roundToLong

/** Indian-system amount formatting (lakh / crore) and amount-in-words. */
object AmountWords {
    private val ones = arrayOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
    private val tens = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

    private fun twoDigits(n: Int): String = if (n < 20) ones[n] else (tens[n / 10] + if (n % 10 != 0) " " + ones[n % 10] else "")

    private fun threeDigits(n: Int): String {
        val h = n / 100; val r = n % 100
        return listOfNotNull(if (h > 0) "${ones[h]} Hundred" else null, if (r > 0) twoDigits(r) else null).joinToString(" ")
    }

    fun words(n: Long): String {
        if (n == 0L) return "Zero"
        var x = n
        val parts = mutableListOf<String>()
        val crore = x / 10_000_000; x %= 10_000_000
        val lakh = x / 100_000; x %= 100_000
        val thousand = x / 1000; x %= 1000
        if (crore > 0) parts += words(crore) + " Crore"
        if (lakh > 0) parts += twoDigits(lakh.toInt()) + " Lakh"
        if (thousand > 0) parts += twoDigits(thousand.toInt()) + " Thousand"
        if (x > 0) parts += threeDigits(x.toInt())
        return parts.joinToString(" ")
    }

    fun rupees(amount: Double): String {
        val paiseTotal = (amount * 100).roundToLong()
        val r = paiseTotal / 100; val p = (paiseTotal % 100).toInt()
        return "Rupees ${words(r)}" + (if (p > 0) " and ${twoDigits(p)} Paise" else "") + " Only"
    }

    /** App-wide number style (Settings → Number format). Defaults: Indian grouping with paise. */
    @Volatile var indianGrouping: Boolean = true
    @Volatile var showPaise: Boolean = true

    /** "₹1,23,456.00" (Indian) or "₹123,456.00" (international); paise optional. Computed by hand so it is identical on every device. */
    fun inr(amount: Double, symbol: String = "₹", indian: Boolean = indianGrouping, paiseShown: Boolean = showPaise): String {
        val neg = amount < 0
        val paise = Math.abs((amount * 100).roundToLong())
        val rupees = (paise / 100).toString(); val ps = (paise % 100).toString().padStart(2, '0')
        val whole = if (paiseShown) rupees else ((paise + 50) / 100).toString()
        val grouped = when {
            whole.length <= 3 -> whole
            !indian -> whole.reversed().chunked(3).joinToString(",").reversed()
            else -> {
                val last3 = whole.takeLast(3); var rest = whole.dropLast(3)
                val sb = StringBuilder()
                while (rest.length > 2) { sb.insert(0, "," + rest.takeLast(2)); rest = rest.dropLast(2) }
                rest + sb.toString() + "," + last3
            }
        }
        return (if (neg) "-" else "") + symbol + grouped + (if (paiseShown) ".$ps" else "")
    }

    /** Whole-rupee compact form for charts: ₹1.2L, ₹45K. */
    fun compact(amount: Double): String {
        val a = Math.abs(amount)
        return when {
            a >= 10_000_000 -> "₹%.1fCr".format(Locale.ENGLISH, amount / 10_000_000)
            a >= 100_000 -> "₹%.1fL".format(Locale.ENGLISH, amount / 100_000)
            a >= 1000 -> "₹%.1fK".format(Locale.ENGLISH, amount / 1000)
            else -> "₹" + amount.roundToLong()
        }
    }
}
