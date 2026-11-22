package com.tareimbursement.app.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Bitmap helpers: memory-safe decoding with EXIF rotation, PDF page rendering, video frames. */
object Media {

    fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) >= maxDim || opts.outHeight / (sample * 2) >= maxDim) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
            val rotated = applyExif(file, decoded)
            scaleDown(rotated, maxDim)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun applyExif(file: File, bmp: Bitmap): Bitmap {
        val orientation = try { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out != bmp) bmp.recycle()
        return out
    }

    fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val s = maxDim.toFloat() / longest
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        if (out != bmp) bmp.recycle()
        return out
    }

    fun rotate(bmp: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Flattens transparency onto white (PNG screenshots) and encodes as JPEG. */
    fun toJpeg(bmp: Bitmap, quality: Int = 82): ByteArray {
        val flat = if (bmp.hasAlpha()) {
            Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also { c ->
                Canvas(c).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            }
        } else bmp
        val bos = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (flat != bmp) flat.recycle()
        return bos.toByteArray()
    }

    fun pdfPageCount(file: File): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    } catch (e: Exception) { -1 }

    /** Renders one PDF page onto a white bitmap of the given width. */
    fun renderPdfPage(file: File, index: Int, targetWidth: Int): Bitmap? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (index !in 0 until r.pageCount) return null
                r.openPage(index).use { p ->
                    val scale = targetWidth.toFloat() / p.width
                    val w = targetWidth; val h = (p.height * scale).toInt().coerceIn(1, targetWidth * 4)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    } catch (e: Throwable) { null }

    fun videoFrame(file: File, maxDim: Int): Bitmap? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            r.getFrameAtTime(1_000_000)?.let { scaleDown(it, maxDim) }
        } finally { r.release() }
    } catch (e: Throwable) { null }
}
