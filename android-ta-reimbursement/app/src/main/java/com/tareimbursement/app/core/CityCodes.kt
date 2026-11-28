package com.tareimbursement.app.core

import java.util.Locale

/** Three-letter place codes for the claims "departure board" (airport codes where people know them). */
object CityCodes {
    private val known = mapOf(
        "bengaluru" to "BLR", "bangalore" to "BLR", "delhi" to "DEL", "new delhi" to "DEL", "noida" to "NOI", "gurugram" to "GGN", "gurgaon" to "GGN",
        "mumbai" to "BOM", "navi mumbai" to "BOM", "chennai" to "MAA", "kolkata" to "CCU", "hyderabad" to "HYD", "pune" to "PNQ", "ahmedabad" to "AMD",
        "kochi" to "COK", "cochin" to "COK", "goa" to "GOI", "jaipur" to "JAI", "lucknow" to "LKO", "chandigarh" to "IXC", "coimbatore" to "CJB",
        "mysuru" to "MYQ", "mysore" to "MYQ", "trivandrum" to "TRV", "thiruvananthapuram" to "TRV", "indore" to "IDR", "bhubaneswar" to "BBI", "nagpur" to "NAG",
        "visakhapatnam" to "VTZ", "vizag" to "VTZ", "madurai" to "IXM", "mangaluru" to "IXE", "mangalore" to "IXE", "patna" to "PAT", "guwahati" to "GAU",
        "dubai" to "DXB", "abu dhabi" to "AUH", "doha" to "DOH", "singapore" to "SIN", "london" to "LHR", "new york" to "JFK", "kuala lumpur" to "KUL",
        "bangkok" to "BKK", "colombo" to "CMB", "dhaka" to "DAC", "kathmandu" to "KTM", "riyadh" to "RUH", "muscat" to "MCT", "hong kong" to "HKG",
        "tokyo" to "NRT", "sydney" to "SYD", "frankfurt" to "FRA", "paris" to "CDG", "san francisco" to "SFO",
    )

    /** "Hosur, Tamil Nadu" → "HOS"; "Bengaluru" → "BLR"; blank → "". */
    fun code(place: String): String {
        val p = place.lowercase(Locale.ROOT).trim()
        if (p.isBlank()) return ""
        known.entries.firstOrNull { (k, _) -> p == k || p.startsWith("$k ") || p.startsWith("$k,") || p.contains(" $k") }?.let { return it.value }
        val letters = p.substringBefore(',').filter { it.isLetter() }
        return letters.take(3).uppercase(Locale.ROOT).padEnd(3, 'X')
    }

    /** "BLR→HOS", or just "HOS" when the home city is unknown or the same. */
    fun route(home: String, to: String): String {
        val a = code(home); val b = code(to)
        return when { b.isBlank() -> a; a.isBlank() || a == b -> b; else -> "$a→$b" }
    }
}
