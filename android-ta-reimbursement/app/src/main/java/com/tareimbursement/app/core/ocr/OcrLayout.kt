package com.tareimbursement.app.core.ocr

import com.tareimbursement.app.core.ParsedReceipt
import com.tareimbursement.app.core.ReceiptParser
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

/** One recognised text line with its bounding box (pixels) and recogniser confidence (0..1, or -1 if unknown). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float = -1f) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = bottom - top
}

/** Result of reading one image variant. */
data class OcrRead(val text: String, val meanConfidence: Double, val lineCount: Int)

object OcrLayout {
    /**
     * Rebuilds printed rows. Recognisers often return a receipt's label column and amount column
     * as separate blocks ("Grand Total" … "6,275.00"); grouping lines whose vertical centres line up
     * and ordering them left→right puts each label next to its value again.
     */
    fun rows(lines: List<OcrLine>): String {
        val sorted = lines.filter { it.text.isNotBlank() && it.height > 0 }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.centerY }.average()
                val rowHeight = row.minOf { it.height }
                if (abs(l.centerY - rowCenter) <= 0.55 * min(rowHeight, l.height)) { row += l; continue }
            }
            rows += mutableListOf(l)
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString("   ") { it.text.trim() } }
    }

    fun read(lines: List<OcrLine>): OcrRead {
        val conf = lines.filter { it.confidence >= 0 }.map { it.confidence.toDouble() }
        return OcrRead(rows(lines), if (conf.isEmpty()) -1.0 else conf.average(), lines.size)
    }

    /** Quality score used to pick the best reading for display and vendor detection. */
    fun quality(r: OcrRead): Double {
        val alnum = r.text.count { it.isLetterOrDigit() }
        val words = Regex("[A-Za-z]{3,}").findAll(r.text).count()
        val conf = if (r.meanConfidence >= 0) r.meanConfidence else 0.7
        return (alnum * 0.5 + words * 4) * (0.4 + conf)
    }
}

/**
 * Combines readings of the same bill (original photo + enhanced variants). A value seen in
 * several readings wins and is marked confident; a single uncertain reading stays "please verify".
 */
object OcrFusion {
    fun parse(reads: List<OcrRead>, tripStart: LocalDate? = null, tripEnd: LocalDate? = null, today: LocalDate = LocalDate.now()): Pair<ParsedReceipt, String>? {
        val usable = reads.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return null
        val ranked = usable.sortedByDescending { OcrLayout.quality(it) }
        val best = ranked.first()
        val parsed = ranked.map { ReceiptParser.parse(it.text, tripStart, tripEnd, today) }
        val bestParsed = parsed.first()
        if (parsed.size == 1) return bestParsed to best.text

        fun <T> vote(values: List<Pair<T?, Boolean>>): Pair<T?, Boolean> {
            val present = values.mapIndexedNotNull { i, (v, c) -> v?.let { Triple(it, c, i) } }
            if (present.isEmpty()) return null to false
            val groups = present.groupBy { it.first }
            val winner = groups.maxWith(compareBy<Map.Entry<T, List<Triple<T, Boolean, Int>>>>(
                { it.value.size }, { it.value.count { t -> t.second } }, { -it.value.minOf { t -> t.third } },
            ))
            // Confident only if found next to a label (e.g. "Grand Total") and no reading disagrees.
            val unanimous = groups.size == 1
            return winner.key to (winner.value.any { it.second } && (unanimous || winner.value.size >= 2))
        }

        val (amount, amountConf) = vote(parsed.map { (it.amount?.let { a -> Math.round(a * 100) / 100.0 }) to it.amountConfident })
        val (date, dateConf) = vote(parsed.map { it.date to it.dateConfident })
        val billNo = vote(parsed.map { it.billNo to false }).first
        val currency = vote(parsed.map { it.currency to false }).first
        val vendor = parsed.firstNotNullOfOrNull { it.vendor }
        // Category: keywords from every reading together catch more clues than any single one.
        val suggestion = ReceiptParser.suggestCategory(usable.joinToString("\n") { it.text }, vendor)
        val fused = bestParsed.copy(
            date = date, dateConfident = dateConf, amount = amount, amountConfident = amountConf,
            vendor = vendor, billNo = billNo, currency = currency, suggestion = suggestion,
        )
        return fused to best.text
    }
}
