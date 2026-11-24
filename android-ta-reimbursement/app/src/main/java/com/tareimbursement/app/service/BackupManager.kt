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
        var total = 0L; var count = 0
        try {
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    // Only the entries a backup contains; anything else is ignored.
                    val expected = name == "ta-backup.txt" || name == "db/${AppDatabase.NAME}" || name == "prefs/ta_prefs.xml" || name.startsWith("files/trips/")
                    if (!expected || name.contains("..")) continue
                    if (++count > MAX_ENTRIES) throw IllegalArgumentException("Backup has too many files.")
                    if (name == "ta-backup.txt") marker = true
                    val t = File(staging, name)
                    if (!t.canonicalPath.startsWith(canonical)) continue
                    t.parentFile?.mkdirs()
                    t.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = z.read(buf); if (r < 0) break
                            total += r
                            if (total > MAX_BYTES) throw IllegalArgumentException("Backup is too large to restore.")
                            out.write(buf, 0, r)
                        }
                    }
                }
            }
            val dbIn = File(staging, "db/${AppDatabase.NAME}")
            if (!marker || !dbIn.exists()) throw IllegalArgumentException("This file is not a TA Reimbursement backup.")
            validateDatabase(dbIn)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw if (e is IllegalArgumentException) e else IllegalArgumentException("This backup file is damaged or not a TA Reimbursement backup.")
        }

        // Keep the current data until the restore has fully succeeded.
        val dbFile = context.getDatabasePath(AppDatabase.NAME)
        val rollback = File(context.cacheDir, "restore-rollback").apply { deleteRecursively(); mkdirs() }
        closeDb()
        val dbParts = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
        val trips = File(context.filesDir, "trips")
        val prefs = File(dataDir, "shared_prefs/ta_prefs.xml")
        try {
            dbParts.filter { it.exists() }.forEach { it.copyTo(File(rollback, "db/${it.name}"), overwrite = true) }
            if (trips.exists()) trips.copyRecursively(File(rollback, "trips"), overwrite = true)
            if (prefs.exists()) prefs.copyTo(File(rollback, "ta_prefs.xml"), overwrite = true)

            dbParts.forEach { it.delete() }
            File(staging, "db/${AppDatabase.NAME}").copyTo(dbFile, overwrite = true)
            trips.deleteRecursively()
            File(staging, "files/trips").takeIf { it.exists() }?.copyRecursively(trips, overwrite = true)
            File(staging, "prefs/ta_prefs.xml").takeIf { it.exists() }?.copyTo(prefs, overwrite = true)
        } catch (e: Exception) {
            // Put everything back exactly as it was.
            dbParts.forEach { it.delete() }
            File(rollback, "db").listFiles()?.forEach { it.copyTo(File(dbFile.parentFile, it.name), overwrite = true) }
            trips.deleteRecursively()
            File(rollback, "trips").takeIf { it.exists() }?.copyRecursively(trips, overwrite = true)
            File(rollback, "ta_prefs.xml").takeIf { it.exists() }?.copyTo(prefs, overwrite = true)
            throw IllegalStateException("Restore failed; your existing data was kept. (${e.message})")
        } finally {
            staging.deleteRecursively()
            rollback.deleteRecursively()
        }
    }

    /** The restored database must be a healthy SQLite file from this app (same or older version). */
    private fun validateDatabase(f: File) {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(f.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            val ok = db.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0).equals("ok", true) }
            if (!ok) throw IllegalArgumentException("The database inside this backup is damaged.")
            if (db.version !in 1..DB_VERSION) throw IllegalArgumentException("This backup was made by a newer version of the app. Please update the app first.")
            val hasTrips = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='trips'", null).use { it.moveToFirst() }
            if (!hasTrips) throw IllegalArgumentException("This file is not a TA Reimbursement backup.")
        } finally { db.close() }
    }

    companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_BYTES = 4L * 1024 * 1024 * 1024
        /** Keep in sync with @Database(version). */
        const val DB_VERSION = 3
    }
}
