package com.tareimbursement.app.service

import android.content.Context
import android.net.Uri
import com.tareimbursement.app.data.AppDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup (database + all bills + outputs + settings) to a single ZIP the user keeps
 * wherever they like, and restore from it. Everything stays under the user's control.
 */
class BackupManager(private val context: Context, private val db: () -> AppDatabase) {
    private val dataDir get() = context.filesDir.parentFile!!

    fun backup(out: OutputStream) {
        // Flush WAL so the main database file is complete.
        db().query("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("ta-backup.txt")); z.write("TA Reimbursement backup v1\n".toByteArray()); z.closeEntry()
            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            addFile(z, dbFile, "db/${AppDatabase.NAME}")
            File(context.filesDir, "trips").walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                addFile(z, f, "files/" + f.relativeTo(context.filesDir).path)
            }
            addFile(z, File(dataDir, "shared_prefs/ta_prefs.xml"), "prefs/ta_prefs.xml")
        }
    }

    private fun addFile(z: ZipOutputStream, f: File, name: String) {
        if (!f.exists()) return
        z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
    }

    fun backup(uri: Uri) { context.contentResolver.openOutputStream(uri)?.use { backup(it) } ?: throw IllegalStateException("Cannot write backup") }

    /** Validates and restores. The caller must restart the app afterwards. */
    fun restore(input: InputStream, closeDb: () -> Unit) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val canonical = staging.canonicalPath + File.separator
        var marker = false
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == "ta-backup.txt") marker = true
                val t = File(staging, e.name)
                if (!t.canonicalPath.startsWith(canonical)) continue
                t.parentFile?.mkdirs()
                t.outputStream().use { z.copyTo(it) }
            }
        }
        val dbIn = File(staging, "db/${AppDatabase.NAME}")
        if (!marker || !dbIn.exists()) { staging.deleteRecursively(); throw IllegalArgumentException("This file is not a TA Reimbursement backup.") }
        closeDb()
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { it.delete() }
        dbIn.copyTo(dbFile, overwrite = true)
        File(context.filesDir, "trips").deleteRecursively()
        File(staging, "files").takeIf { it.exists() }?.copyRecursively(context.filesDir, overwrite = true)
        File(staging, "prefs/ta_prefs.xml").takeIf { it.exists() }?.copyTo(File(dataDir, "shared_prefs/ta_prefs.xml"), overwrite = true)
        staging.deleteRecursively()
    }
}
