package com.tareimbursement.app.core

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToLong

/** Implements the TA naming convention: "DDth Mon YYYY_Amount", e.g. "15th Mar 2026_500.pdf". */
object FileNamer {

    fun ordinal(day: Int): String {
        val suffix = if (day % 100 in 11..13) "th" else when (day % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$day$suffix"
    }

    fun roundAmount(amount: Double): Long = amount.roundToLong()

    fun formatDate(date: LocalDate): String =
        "${ordinal(date.dayOfMonth)} ${date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(3)} ${date.year}"

    fun baseName(date: LocalDate?, amount: Double?): String {
        val d = date?.let { formatDate(it) } ?: "Undated"
        val a = amount?.let { roundAmount(it).toString() } ?: "0"
        return "${d}_$a"
    }

    data class NamedFile<T>(val item: T, val fileName: String)

    /**
     * Assigns unique names. When several files share the same (date, rounded amount) they all get
     * " (1)", " (2)"… before the extension, exactly as the TA organizer convention requires.
     */
    fun <T> assignNames(items: List<T>, date: (T) -> LocalDate?, amount: (T) -> Double?, extension: (T) -> String): List<NamedFile<T>> {
        val bases = items.map { baseName(date(it), amount(it)) }
        val totals = bases.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return items.mapIndexed { i, item ->
            val base = bases[i]
            val ext = extension(item).trimStart('.').lowercase(Locale.ROOT).let { if (it.isEmpty()) "" else ".$it" }
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            val name = if (totals.getValue(base) > 1) "$base ($n)$ext" else "$base$ext"
            NamedFile(item, name)
        }
    }

    fun safeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-").replace('–', '-').trim().take(80).ifEmpty { "file" }
}
