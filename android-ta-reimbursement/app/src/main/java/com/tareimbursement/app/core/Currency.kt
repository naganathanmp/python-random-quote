package com.tareimbursement.app.core

import java.util.Locale
import kotlin.math.roundToLong

data class CurrencyInfo(val code: String, val symbol: String, val name: String)

/** Currencies offered for foreign-trip bills. INR is the default and the reporting currency. */
object Currencies {
    const val INR = "INR"

    val all: List<CurrencyInfo> = listOf(
        CurrencyInfo("INR", "₹", "Indian Rupee"),
        CurrencyInfo("USD", "$", "US Dollar"),
        CurrencyInfo("EUR", "€", "Euro"),
        CurrencyInfo("GBP", "£", "British Pound"),
        CurrencyInfo("AED", "AED", "UAE Dirham"),
        CurrencyInfo("SAR", "SAR", "Saudi Riyal"),
        CurrencyInfo("QAR", "QAR", "Qatari Riyal"),
        CurrencyInfo("OMR", "OMR", "Omani Rial"),
        CurrencyInfo("KWD", "KWD", "Kuwaiti Dinar"),
        CurrencyInfo("BHD", "BHD", "Bahraini Dinar"),
        CurrencyInfo("SGD", "S$", "Singapore Dollar"),
        CurrencyInfo("MYR", "RM", "Malaysian Ringgit"),
        CurrencyInfo("THB", "฿", "Thai Baht"),
        CurrencyInfo("IDR", "Rp", "Indonesian Rupiah"),
        CurrencyInfo("JPY", "¥", "Japanese Yen"),
        CurrencyInfo("CNY", "CN¥", "Chinese Yuan"),
        CurrencyInfo("HKD", "HK$", "Hong Kong Dollar"),
        CurrencyInfo("AUD", "A$", "Australian Dollar"),
        CurrencyInfo("NZD", "NZ$", "New Zealand Dollar"),
        CurrencyInfo("CAD", "C$", "Canadian Dollar"),
        CurrencyInfo("CHF", "CHF", "Swiss Franc"),
        CurrencyInfo("ZAR", "R", "South African Rand"),
        CurrencyInfo("LKR", "LKR", "Sri Lankan Rupee"),
        CurrencyInfo("NPR", "NPR", "Nepalese Rupee"),
        CurrencyInfo("BDT", "Tk", "Bangladeshi Taka"),
        CurrencyInfo("KES", "KES", "Kenyan Shilling"),
    )

    fun of(code: String?): CurrencyInfo = all.firstOrNull { it.code == code } ?: all.first()

    fun isForeign(code: String?) = code != null && code != INR

    /** "USD 1,234.50" — Indian grouping only for INR. */
    fun format(amount: Double, code: String): String =
        if (code == INR) AmountWords.inr(amount)
        else "$code " + String.format(Locale.ENGLISH, "%,.2f", amount)

    /** INR value of a foreign amount, rounded to paise. */
    fun toInr(foreign: Double, rate: Double): Double = ((foreign * rate) * 100).roundToLong() / 100.0

    private val codeHits: List<Pair<Regex, String>> = listOf(
        Regex("(?i)(?<![a-z])(usd|us\\$|us dollars?)(?![a-z])") to "USD",
        Regex("(?i)(?<![a-z])(eur|euros?)(?![a-z])") to "EUR",
        Regex("(?i)(?<![a-z])(gbp)(?![a-z])") to "GBP",
        Regex("(?i)(?<![a-z])(aed|dirhams?|dhs)(?![a-z])") to "AED",
        Regex("(?i)(?<![a-z])(sar|riyals?)(?![a-z])") to "SAR",
        Regex("(?i)(?<![a-z])qar(?![a-z])") to "QAR",
        Regex("(?i)(?<![a-z])omr(?![a-z])") to "OMR",
        Regex("(?i)(?<![a-z])kwd(?![a-z])") to "KWD",
        Regex("(?i)(?<![a-z])bhd(?![a-z])") to "BHD",
        Regex("(?i)(?<![a-z])(sgd|s\\$)") to "SGD",
        Regex("(?i)(?<![a-z])(myr|ringgit)(?![a-z])|(?<![a-z])rm\\s?[0-9]") to "MYR",
        Regex("(?i)(?<![a-z])(thb|baht)(?![a-z])|฿") to "THB",
        Regex("(?i)(?<![a-z])idr(?![a-z])") to "IDR",
        Regex("(?i)(?<![a-z])(jpy|yen)(?![a-z])|¥|円") to "JPY",
        Regex("(?i)(?<![a-z])(cny|rmb|yuan)(?![a-z])") to "CNY",
        Regex("(?i)(?<![a-z])(hkd|hk\\$)") to "HKD",
        Regex("(?i)(?<![a-z])(aud|a\\$)") to "AUD",
        Regex("(?i)(?<![a-z])(nzd|nz\\$)") to "NZD",
        Regex("(?i)(?<![a-z])(cad|c\\$)") to "CAD",
        Regex("(?i)(?<![a-z])chf(?![a-z])") to "CHF",
        Regex("(?i)(?<![a-z])zar(?![a-z])") to "ZAR",
        Regex("(?i)(?<![a-z])lkr(?![a-z])") to "LKR",
        Regex("(?i)(?<![a-z])npr(?![a-z])") to "NPR",
        Regex("(?i)(?<![a-z])bdt(?![a-z])") to "BDT",
        Regex("(?i)(?<![a-z])kes(?![a-z])") to "KES",
        Regex("€") to "EUR",
        Regex("£") to "GBP",
        Regex("\\$\\s?[0-9]") to "USD",
    )
    private val inrHits = Regex("(?i)₹|(?<![a-z])(inr|rs\\.?|rupees?)(?![a-z])|gstin|cgst|sgst|igst")

    /**
     * Detects the bill currency from receipt text. Returns null when the bill looks Indian
     * (₹ / Rs / GST markers) or nothing foreign is found — INR is then assumed.
     */
    fun detect(text: String): String? {
        val inrScore = inrHits.findAll(text).count()
        val scores = codeHits.groupBy({ it.second }) { it.first }.mapValues { (_, rs) -> rs.sumOf { r -> r.findAll(text).count() } }.filterValues { it > 0 }
        val best = scores.maxByOrNull { it.value } ?: return null
        return if (best.value > inrScore) best.key else null
    }
}
