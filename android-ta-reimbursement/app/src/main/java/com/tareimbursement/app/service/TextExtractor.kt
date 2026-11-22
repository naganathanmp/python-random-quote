package com.tareimbursement.app.service

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tareimbursement.app.core.OfficeText
import com.tareimbursement.app.core.SniffedType
import com.tareimbursement.app.data.AttachmentKind
import kotlinx.coroutines.tasks.await
import java.io.File

/** Reads the text of an invoice / supporting document. */
interface TextExtractor {
    suspend fun extract(file: File, kind: AttachmentKind, type: SniffedType): String
}

/**
 * On-device extraction only: Google ML Kit (bundled Latin model, works offline) for images,
 * rendered PDF pages and video frames; direct reading for text, HTML, e-mail and Office files.
 * Nothing leaves the phone.
 */
class OnDeviceTextExtractor : TextExtractor {
    private val recognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun extract(file: File, kind: AttachmentKind, type: SniffedType): String = try {
        when (kind) {
            AttachmentKind.IMAGE -> Media.decodeSampled(file, 2400)?.let { ocrBestRotation(it) }.orEmpty()
            AttachmentKind.PDF -> {
                val pages = Media.pdfPageCount(file).coerceAtMost(3)
                val sb = StringBuilder()
                for (i in 0 until pages) {
                    val b = Media.renderPdfPage(file, i, 1700) ?: continue
                    sb.append(ocr(b)).append('\n')
                    b.recycle()
                }
                sb.toString()
            }
            AttachmentKind.VIDEO -> Media.videoFrame(file, 2000)?.let { ocrBestRotation(it) }.orEmpty()
            AttachmentKind.TEXT -> {
                val raw = com.tareimbursement.app.core.readTextPrefix(file, 300_000)
                if (type == SniffedType.HTML || raw.contains("<html", true)) OfficeText.htmlToText(raw) else raw
            }
            AttachmentKind.OTHER -> if (type.isOffice) OfficeText.extract(file) else ""
        }
    } catch (e: Throwable) { "" }

    private suspend fun ocr(bmp: Bitmap): String = try {
        recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text
    } catch (e: Throwable) { "" }

    /** Receipts photographed sideways are common: retry rotated when little text is found. */
    private suspend fun ocrBestRotation(bmp: Bitmap): String {
        var best = ocr(bmp)
        if (letters(best) >= 40) { bmp.recycle(); return best }
        for (deg in listOf(90f, 270f, 180f)) {
            val r = Media.rotate(bmp, deg)
            val t = ocr(r)
            r.recycle()
            if (letters(t) > letters(best) * 1.3) best = t
            if (letters(best) >= 60) break
        }
        bmp.recycle()
        return best
    }

    private fun letters(s: String) = s.count { it.isLetterOrDigit() }
}
