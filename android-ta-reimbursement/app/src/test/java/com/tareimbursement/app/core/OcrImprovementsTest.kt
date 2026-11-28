package com.tareimbursement.app.core

import com.tareimbursement.app.core.ocr.ImageOps
import com.tareimbursement.app.core.ocr.OcrFusion
import com.tareimbursement.app.core.ocr.OcrLayout
import com.tareimbursement.app.core.ocr.OcrLine
import com.tareimbursement.app.core.ocr.OcrRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OcrImprovementsTest {
    private val today = LocalDate.of(2026, 11, 24)

    /** Synthetic photo: faded grey "ink" strokes on paper with a strong shadow gradient. */
    private fun shadowedReceipt(w: Int, h: Int): Pair<IntArray, BooleanArray> {
        val ink = BooleanArray(w * h)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val isInk = (y % 20 in 8..11) && (x % 12 in 2..8) && x in 10 until w - 10
            ink[y * w + x] = isInk
            val paper = 235 - (x * 150 / w)          // shadow: 235 on the left, 85 on the right
            val v = if (isInk) (paper * 0.55).toInt() else paper
            px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return px to ink
    }

    @Test fun shadowRemovalAndAdaptiveThresholdRecoverText() {
        val w = 240; val h = 120
        val (px, ink) = shadowedReceipt(w, h)
        val gray = ImageOps.toGray(px)
        // A plain global threshold fails: the shadowed paper on the right is darker than the ink on the left.
        val global = gray.map { if (it < 128) 0 else 255 }
        val globalErrors = global.indices.count { (global[it] == 0) != ink[it] }
        val bin = ImageOps.adaptiveThreshold(ImageOps.normalizeIllumination(gray, w, h), w, h)
        val errors = bin.indices.count { (bin[it] == 0) != ink[it] }
        assertTrue("adaptive errors $errors vs global $globalErrors", errors < globalErrors / 4)
        assertTrue(errors.toDouble() / bin.size < 0.05)
        val even = ImageOps.autoContrast(ImageOps.normalizeIllumination(gray, w, h))
        // After illumination normalisation, paper is uniformly bright across the shadow.
        val leftPaper = even[5 * w + 5]; val rightPaper = even[5 * w + w - 5]
        assertTrue("paper L=$leftPaper R=$rightPaper", kotlin.math.abs(leftPaper - rightPaper) < 30)
        assertTrue(ImageOps.inkRatio(bin) in 0.05..0.4)
        assertEquals(1.0, ImageOps.upscaleFactor(2400, 3200), 0.0)
        assertTrue(ImageOps.upscaleFactor(600, 800) > 2.0)
        assertEquals(gray.size, ImageOps.sharpen(gray, w, h).size)
    }

    @Test fun rowsRejoinLabelsWithAmounts() {
        // Recogniser returned the left column and the right column as separate blocks.
        val lines = listOf(
            OcrLine("HOTEL MANGALAM", 100, 10, 400, 40, 0.95f),
            OcrLine("Sub Total", 20, 300, 160, 330, 0.9f),
            OcrLine("CGST 2.5%", 20, 340, 160, 370, 0.9f),
            OcrLine("Grand Total", 20, 380, 180, 412, 0.92f),
            OcrLine("790.00", 400, 302, 480, 332, 0.9f),
            OcrLine("20.00", 410, 342, 480, 372, 0.9f),
            OcrLine("830.00", 400, 383, 480, 413, 0.88f),
            OcrLine("Date: 20/07/2026", 20, 60, 220, 90, 0.9f),
        )
        val text = OcrLayout.rows(lines)
        assertTrue(text, text.lines().contains("Grand Total   830.00"))
        assertTrue(text, text.lines().first() == "HOTEL MANGALAM")
        val r = ReceiptParser.parse(text, today = today)
        assertEquals(830.0, r.amount!!, 0.001)
        assertTrue(r.amountConfident)
        // Without row rebuilding the same text in block order loses the pairing.
        val blockOrder = lines.joinToString("\n") { it.text }
        assertEquals("block order picks the sub total — the bug row rebuilding fixes", 790.0, ReceiptParser.parse(blockOrder, today = today).amount ?: 0.0, 0.001)
        assertTrue(OcrLayout.read(lines).meanConfidence in 0.8..1.0)
    }

    @Test fun commonMisreadsAreCorrected() {
        assertEquals("6,205.00", ReceiptParser.fixDigitConfusions("6,2O5.0O"))
        assertEquals("115", ReceiptParser.fixDigitConfusions("1l5"))
        assertEquals("250", ReceiptParser.fixDigitConfusions("2S0"))
        assertEquals("INV/2026", ReceiptParser.fixDigitConfusions("INV/2O26"))
        val p1 = ReceiptParser.parse("Cafe Amruth Sagar\nDate 21/07/2026\nGrand Total %681.00", today = today)
        assertEquals(681.0, p1.amount!!, 0.001)
        val p2 = ReceiptParser.parse("Swiggy\n26 Jul 2026\nTotal Z976", today = today)
        assertEquals(976.0, p2.amount!!, 0.001)
        val p3 = ReceiptParser.parse("Tumbledry Laundry\n28-07-2026\nNet Amount 2 745 . 00", today = today)
        assertEquals(2745.0, p3.amount!!, 0.001)
        val p4 = ReceiptParser.parse("Hotel\nDate 2O/O7/2026\nGrand Total 1,32O.00", today = today)
        assertEquals(LocalDate.of(2026, 7, 20), p4.date)
        assertEquals(1320.0, p4.amount!!, 0.001)
        // Words are not damaged
        assertTrue(ReceiptParser.fixDigitConfusions("ZOMATO Order SOLD BOOK").contains("ZOMATO Order SOLD BOOK"))
    }

    @Test fun fusionVotesAcrossReadings() {
        val original = OcrRead("HOTEL MANGALAM\nDate 20/07/2026\nGrand Total 8.30.00", 0.55, 3)        // blurry: amount garbled
        val enhanced = OcrRead("HOTEL MANGALAM\nDate 20/07/2026\nGrand Total 830.00", 0.86, 3)
        val binary = OcrRead("HOTEL MANGALAM\nDate 20/07/2O26\nGrand Total 830.00", 0.8, 3)
        val (p, best) = OcrFusion.parse(listOf(original, enhanced, binary), today = today)!!
        assertEquals(830.0, p.amount!!, 0.001)
        assertTrue("two readings agree on the labelled total", p.amountConfident)
        assertEquals(LocalDate.of(2026, 7, 20), p.date)
        assertEquals("HOTEL MANGALAM", p.vendor)
        assertTrue(best.contains("830.00"))

        // Disagreement between equally good readings stays "please verify".
        val a = OcrRead("Shop\nGrand Total 1,250.00", 0.9, 2)
        val b = OcrRead("Shop\nGrand Total 1,750.00", 0.9, 2)
        val (q, _) = OcrFusion.parse(listOf(a, b), today = today)!!
        assertFalse(q.amountConfident)

        // Single reading behaves exactly like the plain parser; empty readings give null.
        val (s, _) = OcrFusion.parse(listOf(enhanced), today = today)!!
        assertEquals(830.0, s.amount!!, 0.001)
        assertEquals(null, OcrFusion.parse(listOf(OcrRead("", -1.0, 0)), today = today))
    }
}
