package com.tareimbursement.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.service.BackupManager
import com.tareimbursement.app.service.FileImporter
import com.tareimbursement.app.service.Sharing
import com.tareimbursement.app.service.UriPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
class SecurityAndEmailTest {
    private lateinit var ctx: Context
    private val jpeg get() = javaClass.classLoader!!.getResourceAsStream("receipt_portrait.jpg")!!.readBytes()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // FileProvider caches its roots per process; Robolectric uses a new data dir per test.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    // ------------------------------------------------------------------ incoming URIs

    @Test fun uriPolicyBlocksConfusedDeputyAttacks() {
        val ownDb = Uri.fromFile(ctx.getDatabasePath(AppDatabase.NAME))
        val ownProvider = Uri.parse("content://${ctx.packageName}.fileprovider/trips/1/att/x.jpg")
        val gallery = Uri.parse("content://media/external/images/media/42")
        val camera = Uri.fromFile(File(ctx.cacheDir, "camera/bill_1.jpg"))
        val traversal = Uri.fromFile(File(ctx.cacheDir, "camera/../../databases/ta.db"))
        assertFalse(UriPolicy.isAllowedForImport(ctx, ownDb))
        assertFalse(UriPolicy.isAllowedForImport(ctx, ownProvider))
        assertFalse(UriPolicy.isAllowedForImport(ctx, traversal))
        assertFalse(UriPolicy.isAllowedForImport(ctx, Uri.parse("https://example.com/a.pdf")))
        assertTrue(UriPolicy.isAllowedForImport(ctx, gallery))
        assertTrue(UriPolicy.isAllowedForImport(ctx, camera))
        // Share/Send from other apps: content:// only.
        assertFalse(UriPolicy.isAllowedFromShare(ctx, camera))
        assertTrue(UriPolicy.isAllowedFromShare(ctx, gallery))
        // Extensions from untrusted names.
        assertEquals("jpg", UriPolicy.safeExtension("IMG_1.JPG"))
        assertNull(UriPolicy.safeExtension("evil./../x"))
        assertNull(UriPolicy.safeExtension("noext"))
        assertNull(UriPolicy.safeExtension("a.toolongext"))
    }

    @Test fun shareIntentWithPrivateFileIsIgnored() {
        val evil = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(ctx.getDatabasePath(AppDatabase.NAME)))
        val activity = Robolectric.buildActivity(MainActivity::class.java, evil).setup().get()
        assertTrue(ctx.container.pendingShared.value.isEmpty())
        val ok = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,
            arrayListOf(Uri.parse("content://media/external/images/media/1"), Uri.parse("content://${ctx.packageName}.fileprovider/trips/1/x.jpg")))
        activity.handleShare(ok)
        assertEquals(listOf(Uri.parse("content://media/external/images/media/1")), ctx.container.pendingShared.value)
        // Malformed extras don't crash.
        activity.handleShare(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a uri"))
    }

    @Test fun importerWithStrictPolicyRejectsPrivateFiles() = runBlocking {
        val db = AppDatabase.inMemory(ctx)
        val repo = Repository(db, ctx.filesDir)
        val importer = FileImporter(ctx.contentResolver, repo, FakeExtractor(), ctx.cacheDir, uriAllowed = { UriPolicy.isAllowedForImport(ctx, it) })
        val trip = repo.createTrip(TripEntity(title = "T", clientName = "C", projectCode = "P", location = "L", purpose = "Internal Audit", startEpochDay = 20000, endEpochDay = 20001))
        val secret = File(ctx.filesDir, "secret.txt").apply { writeText("Grand Total 999") }
        val cam = File(ctx.cacheDir, "camera/bill.jpg").apply { parentFile?.mkdirs(); writeBytes(jpeg) }
        val r = importer.importUris(trip, listOf(Uri.fromFile(secret), Uri.fromFile(cam)))
        assertEquals(1, r.createdExpenseIds.size)
        assertTrue(r.skipped.single().contains("not allowed"))
        db.close()
    }

    @Test fun fileProviderExposesOnlyClaimFolders() {
        val auth = "${ctx.packageName}.fileprovider"
        val claimFile = File(ctx.filesDir, "trips/1/outputs/a.pdf").apply { parentFile?.mkdirs(); writeText("x") }
        assertNotNull(FileProvider.getUriForFile(ctx, auth, claimFile))
        for (f in listOf(ctx.getDatabasePath("ta.db"), File(ctx.filesDir, "other.txt"), File(ctx.filesDir.parentFile, "shared_prefs/ta_prefs.xml"))) {
            try { FileProvider.getUriForFile(ctx, auth, f); fail("$f must not be shareable") } catch (e: IllegalArgumentException) { }
        }
    }

    // ------------------------------------------------------------------ e-mail

    /** Installs a fake mail app: handles mailto: and receives attachments (SEND / SEND_MULTIPLE). */
    private fun installMailApp(pkg: String) {
        val spm = shadowOf(ctx.packageManager)
        val component = ComponentName(pkg, "$pkg.ComposeActivity")
        spm.addActivityIfNotPresent(component)
        spm.addIntentFilterForActivity(component, android.content.IntentFilter(Intent.ACTION_SENDTO).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("mailto") })
        spm.addIntentFilterForActivity(component, android.content.IntentFilter(Intent.ACTION_SEND).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType("*/*") })
        spm.addIntentFilterForActivity(component, android.content.IntentFilter(Intent.ACTION_SEND_MULTIPLE).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType("*/*") })
    }

    private fun outputs(): List<File> = listOf("TA.xlsx", "Invoices.pdf", "Receipts.zip").map { File(ctx.filesDir, "trips/7/outputs/$it").apply { parentFile?.mkdirs(); writeText(it) } }

    @Test fun emailOpensGmailWithRecipientsAndAllAttachments() {
        assertFalse("no mail app → E-mail button hidden", Sharing.hasEmailApp(ctx))
        assertFalse(Sharing.email(ctx, outputs(), listOf("a@b.com"), emptyList(), "s", "b"))
        installMailApp("com.google.android.gm")
        assertTrue(Sharing.hasEmailApp(ctx))
        val files = outputs()
        assertTrue(Sharing.email(ctx, files, listOf("accounts@example.com", " "), listOf("tl@example.com"), "TA Claim TA-1", "Please find attached"))
        val sent = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals("started: $sent extras=${sent.extras?.keySet()}", "com.google.android.gm", sent.`package`)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, sent.action)
        assertEquals(listOf("accounts@example.com"), sent.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList())
        assertEquals(listOf("tl@example.com"), sent.getStringArrayExtra(Intent.EXTRA_CC)!!.toList())
        assertEquals("TA Claim TA-1", sent.getStringExtra(Intent.EXTRA_SUBJECT))
        @Suppress("DEPRECATION") val streams = sent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(3, streams.size)
        assertTrue(streams.all { it.authority == "${ctx.packageName}.fileprovider" })
        assertEquals(3, sent.clipData!!.itemCount)
        assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull("no mailto selector any more", sent.selector)
    }

    @Test fun severalMailAppsGiveAChooserWithEachPrefilled() {
        installMailApp("com.google.android.gm")
        installMailApp("com.microsoft.office.outlook")
        assertTrue(Sharing.email(ctx, outputs(), listOf("a@b.com"), emptyList(), "s", "b"))
        val chooser = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION") val extra = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        assertEquals(setOf("com.google.android.gm", "com.microsoft.office.outlook"), (listOf(target) + extra).map { it.`package` }.toSet())
    }

    @Test fun shareAttachesAllFilesWithReadGrant() {
        val files = outputs()
        val i = Sharing.sendIntent(ctx, files, "s", "b")
        assertEquals(3, i.clipData!!.itemCount)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val single = Sharing.sendIntent(ctx, files.take(1), "s", "b")
        assertEquals(Intent.ACTION_SEND, single.action)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", single.type)
    }

    // ------------------------------------------------------------------ backup restore

    private fun zipOf(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test fun restoreRejectsDamagedOrForeignBackupsAndKeepsData() = runBlocking {
        val disk = AppDatabase.build(ctx)
        val id = Repository(disk, ctx.filesDir).createTrip(TripEntity(title = "Keep me", clientName = "C", projectCode = "P", location = "L", purpose = "x", startEpochDay = 20000, endEpochDay = 20001))
        val bm = BackupManager(ctx) { disk }
        val good = ByteArrayOutputStream().also { bm.backup(it) }.toByteArray()

        // 1. A database that isn't SQLite.
        val junkDb = zipOf("ta-backup.txt" to "x".toByteArray(), "db/ta.db" to "not a database".toByteArray())
        val e1 = runCatching { bm.restore(junkDb.inputStream()) { } }.exceptionOrNull()
        assertTrue("$e1", e1 is IllegalArgumentException)
        // 2. A database from a newer app version.
        val newer = File(ctx.cacheDir, "newer.db").apply { delete() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(newer, null).use { it.execSQL("CREATE TABLE trips(id INTEGER)"); it.version = 99 }
        val e2 = runCatching { bm.restore(zipOf("ta-backup.txt" to "x".toByteArray(), "db/ta.db" to newer.readBytes()).inputStream()) { } }.exceptionOrNull()
        assertTrue("$e2", e2?.message?.contains("newer version") == true)
        // Data is untouched after the failed attempts.
        assertEquals("Keep me", disk.trips().get(id)!!.title)

        // 3. A valid backup with injected path-traversal / unexpected entries: only claim files are restored.
        val tampered = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z ->
                java.util.zip.ZipInputStream(good.inputStream()).use { zin ->
                    while (true) { val e = zin.nextEntry ?: break; z.putNextEntry(ZipEntry(e.name)); z.write(zin.readBytes()); z.closeEntry() }
                }
                for ((n, b) in listOf("files/../../shared_prefs/evil.xml" to "x", "files/other/evil.txt" to "x", "evil.sh" to "x", "files/trips/$id/att/ok.jpg" to "img")) {
                    z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry()
                }
            }
        }.toByteArray()
        bm.restore(tampered.inputStream()) { disk.close() }
        assertFalse(File(ctx.filesDir.parentFile, "shared_prefs/evil.xml").exists())
        assertFalse(File(ctx.filesDir, "other/evil.txt").exists())
        assertTrue(File(ctx.filesDir, "trips/$id/att/ok.jpg").exists())
        val reopened = AppDatabase.build(ctx)
        assertEquals("Keep me", reopened.trips().get(id)!!.title)
        reopened.close()
        ctx.deleteDatabase(AppDatabase.NAME)
        Unit
    }

    @Test fun backupDbVersionMatchesSchema() {
        val dir = File("schemas/com.tareimbursement.app.data.AppDatabase")
        val latest = dir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals("BackupManager.DB_VERSION must follow @Database(version)", latest, BackupManager.DB_VERSION)
        assertTrue(LocalDate.now().year > 2000)
    }
}
