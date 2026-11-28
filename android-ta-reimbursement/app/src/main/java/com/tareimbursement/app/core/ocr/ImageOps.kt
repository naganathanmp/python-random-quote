package com.tareimbursement.app.core.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin image clean-up used before on-device text recognition of hard-to-read bills
 * (faded thermal paper, shadows, low light). Works on ARGB pixel arrays so it can be unit
 * tested on the JVM; the Android layer converts Bitmaps to/from these arrays.
 */
object ImageOps {

    /** Luma (0..255) per pixel. */
    fun toGray(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000
    }

    fun grayToArgb(gray: IntArray): IntArray = IntArray(gray.size) { i ->
        val v = gray[i].coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** Stretches the 1st–99th percentile to the full range: lifts faded print, darkens grey ink. */
    fun autoContrast(gray: IntArray, lowPct: Double = 0.01, highPct: Double = 0.99): IntArray {
        if (gray.isEmpty()) return gray
        val hist = IntArray(256)
        gray.forEach { hist[it.coerceIn(0, 255)]++ }
        fun percentile(p: Double): Int {
            val target = (gray.size * p).toLong(); var acc = 0L
            for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
            return 255
        }
        val lo = percentile(lowPct); val hi = percentile(highPct)
        if (hi - lo < 8) return gray.copyOf()
        val scale = 255.0 / (hi - lo)
        return IntArray(gray.size) { i -> ((gray[i] - lo) * scale).toInt().coerceIn(0, 255) }
    }

    /**
     * Removes uneven lighting / shadows: divides each pixel by a blurred background estimate
     * (large box blur via integral image), so text stays dark and paper becomes uniformly white.
     */
    fun normalizeIllumination(gray: IntArray, w: Int, h: Int, radius: Int = max(8, min(w, h) / 20)): IntArray {
        val integral = integral(gray, w, h)
        return IntArray(gray.size) { i ->
            val x = i % w; val y = i / w
            val mean = boxMean(integral, w, h, x, y, radius)
            if (mean <= 0.0) 255 else (gray[i] * 255.0 / mean).toInt().coerceIn(0, 255)
        }
    }

    /**
     * Sauvola adaptive threshold: robust binarisation for receipts with shadows and faded print.
     * Returns 0 (ink) / 255 (paper).
     */
    fun adaptiveThreshold(gray: IntArray, w: Int, h: Int, radius: Int = max(7, min(w, h) / 40), k: Double = 0.2): IntArray {
        val sum = integral(gray, w, h)
        val sq = integral(IntArray(gray.size) { gray[it] * gray[it] }, w, h)
        return IntArray(gray.size) { i ->
            val x = i % w; val y = i / w
            val mean = boxMean(sum, w, h, x, y, radius)
            val meanSq = boxMean(sq, w, h, x, y, radius)
            val sd = sqrt(max(0.0, meanSq - mean * mean))
            val t = mean * (1 + k * (sd / 128.0 - 1))
            if (gray[i] <= t) 0 else 255
        }
    }

    /** 3×3 unsharp mask to crisp up slightly blurred photos. */
    fun sharpen(gray: IntArray, w: Int, h: Int, amount: Double = 1.0): IntArray {
        val out = gray.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val blur = (gray[i - w - 1] + gray[i - w] + gray[i - w + 1] + gray[i - 1] + gray[i] + gray[i + 1] + gray[i + w - 1] + gray[i + w] + gray[i + w + 1]) / 9.0
            out[i] = (gray[i] + amount * (gray[i] - blur)).toInt().coerceIn(0, 255)
        }
        return out
    }

    /** Fraction of dark pixels — used to skip binarised variants that came out empty or black. */
    fun inkRatio(bin: IntArray): Double = if (bin.isEmpty()) 0.0 else bin.count { it < 128 }.toDouble() / bin.size

    /** Factor to upscale small images so characters are tall enough for recognition. */
    fun upscaleFactor(w: Int, h: Int, targetLongSide: Int = 2000): Double {
        val longSide = max(w, h)
        return if (longSide >= targetLongSide * 0.8) 1.0 else min(3.0, targetLongSide.toDouble() / longSide)
    }

    // (w+1)*(h+1) integral image in Long
    private fun integral(v: IntArray, w: Int, h: Int): LongArray {
        val s = LongArray((w + 1) * (h + 1))
        for (y in 1..h) {
            var row = 0L
            for (x in 1..w) {
                row += v[(y - 1) * w + (x - 1)]
                s[y * (w + 1) + x] = s[(y - 1) * (w + 1) + x] + row
            }
        }
        return s
    }

    private fun boxMean(s: LongArray, w: Int, h: Int, x: Int, y: Int, r: Int): Double {
        val x0 = max(0, x - r); val y0 = max(0, y - r); val x1 = min(w, x + r + 1); val y1 = min(h, y + r + 1)
        val area = (x1 - x0).toLong() * (y1 - y0)
        val total = s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0]
        return total.toDouble() / area
    }
}
