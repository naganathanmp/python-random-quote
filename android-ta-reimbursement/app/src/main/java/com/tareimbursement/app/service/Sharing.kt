package com.tareimbursement.app.service

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Open / share / e-mail / save-to-Downloads for generated outputs and attachments. */
object Sharing {
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    /** Returns false when no app can open the file. */
    fun open(context: Context, file: File, mime: String = mimeOf(file)): Boolean {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: ActivityNotFoundException) { false }
    }

    fun share(context: Context, files: List<File>, subject: String, body: String) {
        val uris = ArrayList(files.map { uriFor(context, it) })
        val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.size == 1) mimeOf(files[0]) else "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "Share TA claim").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens an e-mail app with the outputs attached and the approver pre-filled. */
    fun email(context: Context, files: List<File>, to: List<String>, cc: List<String>, subject: String, body: String): Boolean {
        val uris = ArrayList(files.map { uriFor(context, it) })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, to.filter { it.isNotBlank() }.toTypedArray())
            if (cc.any { it.isNotBlank() }) putExtra(Intent.EXTRA_CC, cc.filter { it.isNotBlank() }.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        return try {
            context.startActivity(Intent.createChooser(i, "Send TA claim by e-mail").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: ActivityNotFoundException) {
            // No mail app matched the mailto selector: fall back to a generic share sheet.
            try { i.selector = null; context.startActivity(Intent.createChooser(i, "Send TA claim").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e2: ActivityNotFoundException) { false }
        }
    }

    /** Saves copies into Downloads/TA Reimbursement/<folder>. Returns the visible folder path. */
    fun saveToDownloads(context: Context, files: List<File>, folder: String): String {
        val safeFolder = folder.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        val relative = Environment.DIRECTORY_DOWNLOADS + "/TA Reimbursement/" + safeFolder
        val resolver = context.contentResolver
        files.forEach { f ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Could not create ${f.name} in Downloads")
            try {
                resolver.openOutputStream(uri)?.use { os -> f.inputStream().use { it.copyTo(os) } } ?: throw IllegalStateException("Could not write ${f.name}")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null); throw e
            }
        }
        return relative
    }

    /** Copies a file to a user-picked location (Storage Access Framework). */
    fun copyTo(context: Context, file: File, dest: Uri) {
        context.contentResolver.openOutputStream(dest)?.use { os -> file.inputStream().use { it.copyTo(os) } } ?: throw IllegalStateException("Could not write file")
    }
}
