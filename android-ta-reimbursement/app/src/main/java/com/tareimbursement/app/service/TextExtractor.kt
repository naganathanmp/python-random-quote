package com.tareimbursement.app.service

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tareimbursement.app.core.OfficeText
import com.tareimbursement.app.core.SniffedType
import com.tareimbursement.app.core.ocr.ImageOps
import com.tareimbursement.app.core.ocr.OcrLayout
import com.tareimbursement.app.core.ocr.OcrLine
import com.tareimbursement.app.core.ocr.OcrRead
import com.tareimbursement.app.data.AttachmentKind
import kotlinx.coroutines.tasks.await
import java.io.File

/** Reads the text of an invoice / supporting document. */
interface TextExtractor {
    suspend fun extract(file: File, kind: AttachmentKind, type: SniffedType): String

    /**
     * Several readings of the same document (e.g. original photo + enhanced versions) so the
     * parser can cross-check values. Defaults to a single reading.
     */
    suspend fun extractReadings(file: File, kind: AttachmentKind, type: SniffedType): List<OcrRead> =
        extract(file, kind, type).let { if (it.isBlank()) emptyList() else listOf(OcrRead(it, -1.0, it.lines().size)) }
}

/**
 * On-device extraction only: Google ML Kit (bundled Latin model, works offline) for images,
 * rendered PDF pages and video frames; direct reading for text, HTML, e-mail and Office files.
 * Nothing leaves the phone.
 *
 * For photos, hard-to-read bills are handled by:
 *  1. upscaling small images so characters are tall enough,
 *  2. finding the right orientation (sideways / upside-down photos),
 *  3. reading the original plus two enhanced versions (shadow removal + contrast, and a
 *     sharpened adaptive black-and-white version),
 *  4. rebuilding printed rows from the text boxes so labels stay next to their amounts,
 *  5. letting the parser vote across the readings.
 */
class OnDeviceTextExtractor : TextExtractor {
    private val recognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun extract(file: File, kind: AttachmentKind, type: SniffedType): String =
        extractReadings(file, kind, type).maxByOrNull { OcrLayout.quality(it) }?.text.orEmpty()

    override suspend fun extractReadings(file: File, kind: AttachmentKind, type: SniffedType): List<OcrRead> = try {
        when (kind) {
            AttachmentKind.IMAGE -> Media.decodeSampled(file, 2400)?.let { readImage(it) }.orEmpty()
            AttachmentKind.VIDEO -> Media.videoFrame(file, 2000)?.let { readImage(it) }.orEmpty()
            AttachmentKind.PDF -> readPdf(file)
            AttachmentKind.TEXT -> {
                val raw = com.tareimbursement.app.core.readTextPrefix(file, 300_000)
                single(if (type == SniffedType.HTML || raw.contains("<html", true)) OfficeText.htmlToText(raw) else raw)
            }
            AttachmentKind.OTHER -> single(if (type.isOffice) OfficeText.extract(file) else "")
        }
    } catch (e: Throwable) { emptyList() }

    private fun single(t: String) = if (t.isBlank()) emptyList() else listOf(OcrRead(t, -1.0, t.lines().size))

    /** PDFs: render pages at high resolution; a scanned page also gets an enhanced reading. */
    private suspend fun readPdf(file: File): List<OcrRead> {
        val pages = Media.pdfPageCount(file).coerceAtMost(3)
        val plain = StringBuilder(); val enhanced = StringBuilder()
        val conf = mutableListOf<Double>()
        for (i in 0 until pages) {
            val b = Media.renderPdfPage(file, i, 2200) ?: continue
            val r = recognise(b)
            plain.append(r.text).append('\n'); if (r.meanConfidence >= 0) conf += r.meanConfidence
            if (r.text.count { it.isLetterOrDigit() } < 80 || (r.meanConfidence in 0.0..0.75)) {
                enhancedVariants(b).firstOrNull()?.let { v -> enhanced.append(recognise(v).text).append('\n'); v.recycle() }
            }
            b.recycle()
        }
        val out = mutableListOf(OcrRead(plain.toString(), conf.average().takeIf { !it.isNaN() } ?: -1.0, plain.lines().size))
        if (enhanced.isNotBlank()) out += OcrRead(enhanced.toString(), -1.0, enhanced.lines().size)
        return out.filter { it.text.isNotBlank() }
    }

    private suspend fun readImage(src: Bitmap): List<OcrRead> {
        // 1. Upscale small images (screenshots of small bills, cropped photos).
        val f = ImageOps.upscaleFactor(src.width, src.height)
        val base = if (f > 1.05) Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true).also { if (it != src) src.recycle() } else src
        // 2. Orientation.
        val oriented = bestRotation(base)
        val readings = mutableListOf(oriented.second)
        // 3. Enhanced variants when the plain reading is weak or uncertain.
        val weak = oriented.second.text.count { it.isLetterOrDigit() } < 150 || oriented.second.meanConfidence in 0.0..0.85
        val variants = enhancedVariants(oriented.first, includeBinary = weak)
        variants.forEach { v -> readings += recognise(v); v.recycle() }
        oriented.first.recycle()
        return readings.filter { it.text.isNotBlank() }
    }

    /** Shadow-free high-contrast greyscale, and (for weak readings) sharpened adaptive black & white. */
    private fun enhancedVariants(bmp: Bitmap, includeBinary: Boolean = true): List<Bitmap> {
        val w = bmp.width; val h = bmp.height
        // Work on at most ~4 MP to keep memory and time reasonable.
        val scale = minOf(1.0, Math.sqrt(4_000_000.0 / (w.toDouble() * h)))
        val work = if (scale < 0.99) Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true) else bmp
        val ww = work.width; val hh = work.height
        val px = IntArray(ww * hh)
        work.getPixels(px, 0, ww, 0, 0, ww, hh)
        if (work != bmp) work.recycle()
        val gray = ImageOps.toGray(px)
        val even = ImageOps.autoContrast(ImageOps.normalizeIllumination(gray, ww, hh))
        val out = mutableListOf(fromGray(even, ww, hh))
        if (includeBinary) {
            val bin = ImageOps.adaptiveThreshold(ImageOps.sharpen(even, ww, hh), ww, hh)
            val ink = ImageOps.inkRatio(bin)
            if (ink in 0.01..0.45) out += fromGray(bin, ww, hh)
        }
        return out
    }

    private fun fromGray(g: IntArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(ImageOps.grayToArgb(g), w, h, Bitmap.Config.ARGB_8888)

    private suspend fun recognise(bmp: Bitmap): OcrRead = try {
        val result: Text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val lines = result.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
            val box = l.boundingBox ?: return@mapNotNull null
            OcrLine(l.text, box.left, box.top, box.right, box.bottom, l.confidence)
        }
        if (lines.isEmpty()) OcrRead(result.text, -1.0, 0) else OcrLayout.read(lines)
    } catch (e: Throwable) { OcrRead("", -1.0, 0) }

    /** Receipts photographed sideways are common: pick the orientation that reads best. */
    private suspend fun bestRotation(bmp: Bitmap): Pair<Bitmap, OcrRead> {
        var bestBmp = bmp
        var best = recognise(bmp)
        if (OcrLayout.quality(best) >= 120 && best.meanConfidence !in 0.0..0.6) return bestBmp to best
        for (deg in listOf(90f, 270f, 180f)) {
            val r = Media.rotate(bmp, deg)
            val read = recognise(r)
            if (OcrLayout.quality(read) > OcrLayout.quality(best) * 1.3) {
                if (bestBmp != bmp) bestBmp.recycle()
                bestBmp = r; best = read
            } else r.recycle()
            if (OcrLayout.quality(best) >= 200) break
        }
        if (bestBmp != bmp) bmp.recycle()
        return bestBmp to best
    }
}
