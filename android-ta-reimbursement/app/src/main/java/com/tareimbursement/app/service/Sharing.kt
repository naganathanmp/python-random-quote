package com.tareimbursement.app.service

import android.content.ClipData
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
        val uri = uriFor(context, file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        i.clipData = ClipData.newRawUri(file.name, uri)
        return try { context.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: RuntimeException) { false }
    }

    /** SEND / SEND_MULTIPLE with the files both as EXTRA_STREAM and as ClipData (needed for read grants). */
    internal fun sendIntent(context: Context, files: List<File>, subject: String, body: String): Intent {
        val uris = ArrayList(files.map { uriFor(context, it) })
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.size == 1) mimeOf(files[0]) else "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (uris.isNotEmpty()) clipData = ClipData.newRawUri(files[0].name, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Returns false if no app can receive the files. */
    fun share(context: Context, files: List<File>, subject: String, body: String): Boolean = try {
        context.startActivity(Intent.createChooser(sendIntent(context, files, subject, body), "Share TA claim").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: RuntimeException) { false }

    /** Packages of installed e-mail apps (apps that handle mailto:) that can also receive attachments. */
    fun emailApps(context: Context): List<String> {
        val pm = context.packageManager
        val mailers = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != context.packageName } // never ourselves (this app accepts shared files)
        val probe = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
        val receivers = pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.toSet()
        return mailers.filter { it in receivers || pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("*/*").setPackage(it), 0).isNotEmpty() }
    }

    fun hasEmailApp(context: Context): Boolean = try { emailApps(context).isNotEmpty() } catch (e: RuntimeException) { false }

    /** The e-mail intent for one mail app: recipients, subject, body and all files attached. */
    internal fun emailIntent(context: Context, pkg: String, files: List<File>, to: List<String>, cc: List<String>, subject: String, body: String): Intent =
        sendIntent(context, files, subject, body).apply {
            if (files.size > 1) action = Intent.ACTION_SEND_MULTIPLE
            putExtra(Intent.EXTRA_EMAIL, to.map { it.trim() }.filter { it.isNotBlank() }.toTypedArray())
            cc.map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { putExtra(Intent.EXTRA_CC, it.toTypedArray()) }
            setPackage(pkg)
        }

    /**
     * Opens the phone's e-mail app with the approver pre-filled and the outputs attached.
     * Mail apps are found explicitly (instead of a mailto "selector", which several Android
     * versions and Gmail builds ignore or open without attachments). With one mail app it opens
     * directly; with several, the user picks one. Returns false if there is no mail app.
     */
    fun email(context: Context, files: List<File>, to: List<String>, cc: List<String>, subject: String, body: String): Boolean {
        val apps = try { emailApps(context) } catch (e: RuntimeException) { emptyList() }
        if (apps.isEmpty()) return false
        val intents = apps.map { emailIntent(context, it, files, to, cc, subject, body) }
        return try {
            if (intents.size == 1) context.startActivity(intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else context.startActivity(
                Intent.createChooser(intents[0], "Send TA claim with").putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: RuntimeException) {
            share(context, files, subject, body)
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
