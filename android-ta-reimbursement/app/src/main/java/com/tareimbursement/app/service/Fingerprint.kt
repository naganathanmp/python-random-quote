package com.tareimbursement.app.service

import android.graphics.Bitmap
import android.graphics.Color
import com.tareimbursement.app.core.trust.Duplicates
import com.tareimbursement.app.data.AttachmentKind
import java.io.File
import java.security.MessageDigest

/** File fingerprints used to spot the same bill uploaded twice. */
object Fingerprint {
    fun sha256(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins -> val buf = ByteArray(64 * 1024); while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    /** Perceptual hash of the photo or the first PDF page; null if it can't be rendered. */
    fun dHash(file: File, kind: AttachmentKind): Long? = try {
        val bmp = when (kind) {
            AttachmentKind.IMAGE -> Media.decodeSampled(file, 256)
            AttachmentKind.PDF -> Media.renderPdfPage(file, 0, 256)
            else -> null
        }
        bmp?.let { b -> val h = dHashOf(b); b.recycle(); h }
    } catch (e: Throwable) { null }

    fun dHashOf(b: Bitmap): Long? {
        if (b.width < 9 || b.height < 8) return null
        val small = Bitmap.createScaledBitmap(b, 9, 8, true)
        val gray = IntArray(72) { i ->
            val c = small.getPixel(i % 9, i / 9)
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        if (small != b) small.recycle()
        // A flat image (blank page / failed render) carries no information.
        if (gray.max() - gray.min() < 8) return null
        return Duplicates.dHash(gray)
    }
}
