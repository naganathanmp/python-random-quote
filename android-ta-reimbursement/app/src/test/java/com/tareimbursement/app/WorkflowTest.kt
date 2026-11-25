package com.tareimbursement.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.FileSniffer
import com.tareimbursement.app.core.InsightPeriod
import com.tareimbursement.app.core.InsightsEngine
import com.tareimbursement.app.core.SniffedType
import com.tareimbursement.app.core.pdf.DocPage
import com.tareimbursement.app.core.pdf.JpegImage
import com.tareimbursement.app.core.pdf.PdfWriter
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.AttachmentKind
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.service.BackupManager
import com.tareimbursement.app.service.ExportManager
import com.tareimbursement.app.service.FileImporter
import com.tareimbursement.app.service.PageRenderer
import com.tareimbursement.app.service.TextExtractor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Simulated OCR: returns the text a real receipt with this file name would contain. */
class FakeExtractor : TextExtractor {
    val texts = mutableMapOf<String, String>()
    override suspend fun extract(file: File, kind: AttachmentKind, type: SniffedType): String {
        if (kind == AttachmentKind.TEXT) return file.readText()
        if (type.isOffice) return com.tareimbursement.app.core.OfficeText.extract(file)
        return texts.entries.firstOrNull { file.length() == it.key.substringAfter('#').toLongOrNull() }?.value ?: texts["*"] ?: ""
    }
}

class FakeRenderer(private val jpeg: ByteArray) : PageRenderer {
    override fun pages(file: File, kind: AttachmentKind, originalName: String): List<DocPage> = when (kind) {
        AttachmentKind.IMAGE, AttachmentKind.PDF -> listOf(DocPage.Image { JpegImage(jpeg) })
        AttachmentKind.TEXT -> listOf(DocPage.Text(file.readLines()))
        else -> listOf(DocPage.Placeholder("no preview"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
class WorkflowTest {
    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private lateinit var importer: FileImporter
    private lateinit var extractor: FakeExtractor
    private lateinit var tmp: File
    private val jpeg get() = javaClass.classLoader!!.getResourceAsStream("receipt_portrait.jpg")!!.readBytes()
    private val jpeg2 get() = javaClass.classLoader!!.getResourceAsStream("receipt_landscape.jpg")!!.readBytes()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(ctx)
        repo = Repository(db, ctx.filesDir)
        extractor = FakeExtractor()
        importer = FileImporter(ctx.contentResolver, repo, extractor, ctx.cacheDir)
        tmp = File(ctx.cacheDir, "testsrc").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() { db.close(); File(ctx.filesDir, "trips").deleteRecursively() }

    private fun file(name: String, bytes: ByteArray) = File(tmp, name).apply { writeBytes(bytes) }

    private fun tinyPdf(): ByteArray = ByteArrayOutputStream().also { o ->
        val w = PdfWriter(o); val p = w.beginPage(); p.text(40f, 60f, "Fly91 Total Amount 4500"); w.endPage(p); w.close()
    }.toByteArray()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun newTrip(): Long = runBlocking {
        repo.createTrip(TripEntity(title = "Statutory Audit", clientName = "Acme Ltd", projectCode = "ACM-01", location = "Delhi", purpose = "Fieldwork",
            startEpochDay = LocalDate.of(2026, 3, 24).toEpochDay(), endEpochDay = LocalDate.of(2026, 3, 28).toEpochDay(), advance = 5000.0))
    }

    @Test fun fileSnifferDetectsTypes() {
        assertEquals(SniffedType.JPEG, FileSniffer.sniff(file("x.bin", jpeg)))
        assertEquals(SniffedType.PDF, FileSniffer.sniff(file("a.dat", tinyPdf())))
        assertEquals(SniffedType.ZIP, FileSniffer.sniff(file("b.zip", zipOf("a.txt" to "hi".toByteArray()))))
        assertEquals(SniffedType.DOCX, FileSniffer.sniff(file("c.docx", zipOf("word/document.xml" to "<w:t>Hotel Bill Total 900</w:t>".toByteArray()))))
        assertEquals(SniffedType.TEXT, FileSniffer.sniff(file("d.txt", "Uber receipt".toByteArray())))
        assertEquals(SniffedType.AUDIO, FileSniffer.sniff(file("e.mp3", byteArrayOf(1, 2, 3, 0, 0))))
    }

    @Test fun fullClaimLifecycle() = runBlocking {
        val tripId = newTrip()
        val trip = repo.trips.get(tripId)!!
        assertTrue(trip.claimNumber.matches(Regex("TA-202603-\\d{3}")))

        // ---- import: an image, a PDF, and a ZIP with nested ZIP, text, office doc, junk and audio
        val hotel = file("IMG_2026.jpg", jpeg)
        extractor.texts["#${hotel.length()}"] = "HOTEL SAROVAR\nTAX INVOICE\nInvoice No: INV/77\nInvoice Date: 27/03/2026\nRoom Charges 2 Night\nGrand Total 6,275.00"
        val rapido = jpeg2
        extractor.texts["#${rapido.size}"] = "Rapido\nTime of Ride Mar 24th 2026\nPickup: Airport Terminal 1\nSelected Price\n₹ 500"
        val pdf = tinyPdf()
        extractor.texts["#${pdf.size}"] = "Fly91\nBoarding pass e-Ticket\nDate of Issue 24 Mar 2026\nTotal Amount INR 4,500.00"
        val inner = zipOf("ride2.jpg" to rapido)
        val zip = zipOf(
            "bills/hotel_dup.jpg" to hotel.readBytes(),
            "bills/ticket.pdf" to pdf,
            "bills/uber.txt" to "Uber\nMar 25, 2026\nTotal ₹820.16\nTrip fare 700".toByteArray(),
            "bills/lunch.docx" to zipOf("word/document.xml" to "<w:p><w:t>Sri Krishna Restaurant</w:t></w:p><w:p><w:t>Date 26-03-2026</w:t></w:p><w:p><w:t>Net Amount 504.00</w:t></w:p>".toByteArray()),
            "__MACOSX/._hotel.jpg" to byteArrayOf(1, 2),
            ".DS_Store" to byteArrayOf(0),
            "music.mp3" to byteArrayOf(9, 9, 9),
            "nested.zip" to inner,
        )
        val r1 = importer.importFiles(tripId, listOf(FileImporter.Source(hotel, hotel.name), FileImporter.Source(file("all.zip", zip), "all.zip")), null, File(tmp, "stage"))
        assertEquals("one skipped (audio)", 1, r1.skipped.size)
        assertTrue(r1.skipped.first().contains("music.mp3"))
        val list = repo.expenses.forTrip(tripId)
        assertEquals(6, list.size) // hotel, hotel_dup, ticket, uber, lunch, nested ride
        assertEquals(6, repo.attachments.forTrip(tripId).size)
        assertTrue(repo.attachments.forTrip(tripId).all { repo.fileOf(it).exists() })

        val hotels = list.filter { it.amount == 6275.0 }
        assertEquals(2, hotels.size)
        assertTrue("second hotel copy flagged as duplicate", hotels.any { (it.duplicateOfId ?: 0) > 0 })
        assertEquals(1, r1.duplicates)
        val uber = list.first { it.vendor == "Uber" }
        assertEquals(820.16, uber.amount!!, 0.001)
        assertEquals(ExpenseCategory.LOCAL_CONVEYANCE, uber.suggestedEnum)
        val ride = list.first { it.vendor == "Rapido" }
        assertEquals(ExpenseCategory.PICKUP_DROP, ride.suggestedEnum)
        val lunch = list.first { it.amount == 504.0 }
        assertEquals("Food / restaurant bill", lunch.nature)
        assertNull("category never auto-assigned: user must choose", lunch.category)
        val air = list.first { it.vendor == "Fly91" }
        assertEquals(ExpenseCategory.AIR_FARE, air.suggestedEnum)

        // ---- merge duplicate hotel photo into the original as supporting document
        val dup = hotels.first { (it.duplicateOfId ?: 0) > 0 }
        repo.mergeExpense(dup.id, dup.duplicateOfId!!)
        val original = repo.expenses.get(dup.duplicateOfId!!)!!
        val hotelAtts = repo.attachments.forExpense(original.id)
        assertEquals(2, hotelAtts.size)
        assertEquals(1, hotelAtts.count { it.isPrimary })
        assertEquals(5, repo.expenses.forTrip(tripId).size)

        // ---- add a supporting document to an existing expense (payment screenshot)
        val shot = file("payment.txt", "Paid via UPI to Uber ₹820.16".toByteArray())
        val r2 = importer.importFiles(tripId, listOf(FileImporter.Source(shot, shot.name)), uber.id, File(tmp, "stage2"))
        assertEquals(1, r2.attachedCount)
        assertEquals(2, repo.attachments.forExpense(uber.id).size)
        assertFalse(repo.attachments.forExpense(uber.id).last().isPrimary)

        // ---- move a document, set primary, delete
        val moved = repo.attachments.forExpense(uber.id).first { !it.isPrimary }
        repo.moveAttachment(moved.id, ride.id)
        assertEquals(2, repo.attachments.forExpense(ride.id).size)
        repo.setPrimary(moved.id)
        assertEquals(moved.id, repo.attachments.forExpense(ride.id).first { it.isPrimary }.id)
        repo.deleteAttachment(moved.id)
        assertEquals(1, repo.attachments.forExpense(ride.id).count { it.isPrimary })

        // ---- manual expense without bill
        val manualId = repo.addManualExpense(tripId, LocalDate.of(2026, 3, 26))

        // ---- validation blocks until reviewed
        var issues = repo.validate(tripId, mapOf(ExpenseCategory.LOCAL_CONVEYANCE to 500.0))
        assertTrue(issues.any { it.blocking })
        repo.expenses.forTrip(tripId).forEach { e ->
            val cat = when {
                e.id == manualId -> ExpenseCategory.EXPENSES_DURING_TRAVEL
                e.id == lunch.id -> ExpenseCategory.EXPENSES_DURING_TRAVEL // user's choice for food
                else -> e.suggestedEnum ?: ExpenseCategory.OTHER
            }
            repo.saveExpense(e.copy(category = cat.name, confirmed = true, amount = e.amount ?: 150.0, dateEpochDay = e.dateEpochDay ?: LocalDate.of(2026, 3, 26).toEpochDay(), description = if (e.id == manualId) "Porter charges" else e.description))
        }
        issues = repo.validate(tripId, mapOf(ExpenseCategory.LOCAL_CONVEYANCE to 500.0))
        assertTrue(issues.joinToString { it.message }, issues.none { it.blocking })
        assertTrue("policy limit warning", issues.any { it.message.contains("policy limit") })
        assertTrue("no-bill warning", issues.any { it.message.contains("no bill attached") })
        assertTrue("fractional warning", issues.any { it.message.contains("fractional") })

        // ---- generate outputs
        val exporter = ExportManager(repo, FakeRenderer(jpeg))
        val employee = com.tareimbursement.app.data.Profile("Ravi Kumar", "E1023", "Associate", "Audit", "Chennai", "PKF Test", "r@x.com").toEmployee()
        val out = exporter.generate(tripId, employee)
        assertTrue(out.exist())
        val pdfHead = out.pdf.readBytes().copyOfRange(0, 8).toString(Charsets.ISO_8859_1)
        assertTrue(pdfHead.startsWith("%PDF"))
        val zipNames = mutableListOf<String>()
        ZipInputStream(out.zip.inputStream()).use { z -> while (true) { zipNames += (z.nextEntry ?: break).name } }
        assertTrue(zipNames.toString(), zipNames.contains("Boarding Expenses (Stay & Food)/27th Mar 2026_6275 (1).jpg"))
        assertTrue(zipNames.toString(), zipNames.contains("Boarding Expenses (Stay & Food)/27th Mar 2026_6275 (2).jpg"))
        assertTrue(zipNames.toString(), zipNames.contains("Local Conveyance/25th Mar 2026_820.txt"))
        assertTrue(zipNames.toString(), zipNames.contains("Air Fare/24th Mar 2026_4500.pdf"))
        assertTrue(zipNames.toString(), zipNames.contains("Pick up & Drop/24th Mar 2026_500.jpg"))
        assertTrue(zipNames.toString(), zipNames.contains("Expenses during travel/26th Mar 2026_504.docx"))
        assertTrue(zipNames.any { it.endsWith(".xlsx") } && zipNames.any { it.endsWith(".pdf") && !it.contains('/') })
        // keep a copy of the real generated outputs for inspection
        val keep = File(System.getProperty("java.io.tmpdir"), "ta_test_out/workflow").apply { mkdirs() }
        out.all.forEach { it.copyTo(File(keep, it.name), overwrite = true) }
        val prepared = exporter.prepare(tripId, employee)
        assertEquals(6, prepared.data.lines.size)
        assertEquals(6275.0 + 500 + 4500 + 820.16 + 504 + 150, prepared.data.total, 0.001)
        // v2.0: one remarks field everywhere - a missing bill shows as "Sup Bills = N", not as an extra remark.
        prepared.data.lines.first { it.description == "Porter charges" }.let { assertEquals("", it.remarks); assertFalse(it.billAvailable) }
        assertEquals(LocalDate.of(2026, 3, 24), prepared.data.lines.first().date)

        // ---- submit (archive) and track approval & payment
        repo.submit(tripId)
        assertEquals(ClaimStatus.SUBMITTED, repo.trips.get(tripId)!!.claimStatus)
        assertTrue(repo.trips.get(tripId)!!.isArchived)
        repo.markApproved(tripId, LocalDate.of(2026, 4, 2))
        repo.markPaid(tripId, 7749.16, LocalDate.of(2026, 4, 10))
        val paid = repo.trips.get(tripId)!!
        assertEquals(ClaimStatus.PAID, paid.claimStatus)
        assertEquals(7749.16, paid.paidAmount!!, 0.001)
        val types = repo.events.forTrip(tripId).map { it.type }
        assertTrue(types.containsAll(listOf("CREATED", "FILES", "GENERATED", "SUBMITTED", "APPROVED", "PAID")))

        // ---- insights over real data
        val (ts, es) = repo.insightData()
        val ins = InsightsEngine.compute(ts, es, InsightPeriod.ALL, LocalDate.of(2026, 11, 22))
        assertEquals(1, ins.claimCount)
        assertEquals(7749.16, ins.totalReimbursed, 0.001)
        assertTrue(ins.categories.isNotEmpty())

        // ---- reopen for corrections, then delete
        repo.reopen(tripId)
        assertEquals(ClaimStatus.DRAFT, repo.trips.get(tripId)!!.claimStatus)
        assertNull(repo.trips.get(tripId)!!.paidAmount)
        repo.deleteTrip(tripId)
        assertNull(repo.trips.get(tripId))
        assertTrue(repo.expenses.forTrip(tripId).isEmpty())
        assertFalse(repo.tripDir(tripId).exists())
    }

    @Test fun importFromContentUrisAndBadFiles() = runBlocking {
        val tripId = newTrip()
        extractor.texts["*"] = ""
        val good = file("scan.jpg", jpeg)
        val empty = file("empty.pdf", ByteArray(0))
        val badZip = file("broken.zip", byteArrayOf(0x50, 0x4B, 3, 4, 1, 2, 3))
        val r = importer.importUris(tripId, listOf(Uri.fromFile(good), Uri.fromFile(empty), Uri.fromFile(badZip), Uri.fromFile(File(tmp, "missing.jpg"))))
        assertEquals(1, r.createdExpenseIds.size)
        assertEquals(r.skipped.toString(), 3, r.skipped.size)
        val e = repo.expenses.get(r.createdExpenseIds.first())!!
        assertNull("unreadable photo: fields left empty for the user", e.amount)
        assertEquals(ExpenseCategory.OTHER, e.suggestedEnum ?: ExpenseCategory.OTHER)
        assertTrue(File(ctx.cacheDir, "import").listFiles().isNullOrEmpty())
    }

    @Test fun zipSlipIsBlocked() = runBlocking {
        val tripId = newTrip()
        val evil = zipOf("../../evil.txt" to "Total 100".toByteArray(), "ok.txt" to "Total 200".toByteArray())
        importer.importFiles(tripId, listOf(FileImporter.Source(file("evil.zip", evil), "evil.zip")), null, File(tmp, "stage"))
        assertFalse(File(tmp, "evil.txt").exists())
        assertFalse(File(ctx.cacheDir, "evil.txt").exists())
        assertEquals(2, repo.expenses.forTrip(tripId).size) // both stored safely inside the staging dir
    }

    @Test fun backupAndRestoreRoundTrip() = runBlocking {
        // Use the real on-disk database for backup.
        val diskDb = AppDatabase.build(ctx)
        val diskRepo = Repository(diskDb, ctx.filesDir)
        val id = diskRepo.createTrip(TripEntity(title = "Backup trip", clientName = "C", projectCode = "P", location = "", purpose = "", startEpochDay = 20000, endEpochDay = 20001))
        diskRepo.addManualExpense(id, LocalDate.ofEpochDay(20000))
        File(diskRepo.attachmentDir(id), "f.jpg").writeBytes(jpeg)
        com.tareimbursement.app.data.Prefs(ctx).saveProfile(com.tareimbursement.app.data.Profile(name = "Ravi", employeeCode = "E1"))
        val bm = BackupManager(ctx) { diskDb }
        val bytes = ByteArrayOutputStream().also { bm.backup(it) }.toByteArray()

        // Wipe and restore
        diskRepo.deleteTrip(id)
        assertNull(diskRepo.trips.get(id))
        bm.restore(bytes.inputStream()) { diskDb.close() }
        val reopened = AppDatabase.build(ctx)
        val t = reopened.trips().get(id)
        assertNotNull(t)
        assertEquals("Backup trip", t!!.title)
        assertEquals(1, reopened.expenses().forTrip(id).size)
        assertTrue(File(ctx.filesDir, "trips/$id/att/f.jpg").exists())
        reopened.close()

        // Not a backup file
        val junk = zipOf("x.txt" to "nope".toByteArray())
        val err = runCatching { bm.restore(junk.inputStream()) {} }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        ctx.deleteDatabase(AppDatabase.NAME)
        Unit
    }
}
