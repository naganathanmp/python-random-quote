package com.tareimbursement.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.EmployeeInfo
import com.tareimbursement.app.core.ai.BillAiResult
import com.tareimbursement.app.core.sign.DocSigner
import com.tareimbursement.app.core.sign.PdfSigner
import com.tareimbursement.app.core.sign.Signing
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.service.ExportManager
import com.tareimbursement.app.service.FileImporter
import com.tareimbursement.app.service.ai.AiRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
class V2FeaturesTest {
    private lateinit var ctx: Context
    private lateinit var repo: Repository
    private lateinit var extractor: FakeExtractor
    private lateinit var importer: FileImporter
    private lateinit var tmp: File

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        repo = Repository(AppDatabase.inMemory(ctx), File(ctx.filesDir, "v2").apply { deleteRecursively(); mkdirs() })
        extractor = FakeExtractor()
        importer = FileImporter(ctx.contentResolver, repo, extractor, ctx.cacheDir, aiEnabled = { true }, whoAmI = { "Asha" to "u-1" }, dHash = { _, _ -> null })
        tmp = File(ctx.cacheDir, "v2src").apply { deleteRecursively(); mkdirs() }
    }

    private fun res(n: String) = File(tmp, n).apply { writeBytes(File("src/test/resources/trust/$n").readBytes()) }
    private fun trip(title: String, start: LocalDate = LocalDate.of(2026, 3, 10)) = runBlocking {
        repo.createTrip(TripEntity(title = title, clientName = "Client", projectCode = "P1", location = "Hosur", purpose = "Audit",
            startEpochDay = start.toEpochDay(), endEpochDay = start.plusDays(5).toEpochDay()), "Asha", "E1")
    }

    @Test fun aiGeneratedBillIsBlocked() = runBlocking {
        val t = trip("Pune")
        extractor.texts["*"] = "Grand Hotel\nDate 12/03/2026\nTotal Rs 2,400.00"
        val r = importer.importFiles(t, listOf(FileImporter.Source(res("sd.png"), "bill.png")), null, File(tmp, "st").apply { mkdirs() })
        val e = repo.expenses.get(r.createdExpenseIds.single())!!
        assertEquals("BLOCK", e.trustLevel)
        assertEquals("QUEUED", e.aiState)
        assertEquals("Asha", e.addedBy)
        val issues = repo.validate(t, emptyMap())
        assertTrue(issues.any { it.blocking && it.message.contains("AI-generated") })
    }

    @Test fun sameBillInTwoClaimsMustBeExplained() = runBlocking {
        extractor.texts["*"] = "Hotel Sunrise\nInvoice No: INV-77\nDate 12/03/2026\nTotal Rs 1,200.00"
        val a = trip("Claim A"); val b = trip("Claim B")
        importer.importFiles(a, listOf(FileImporter.Source(res("camera.jpg"), "hotel.jpg")), null, File(tmp, "s1").apply { mkdirs() })
        val r = importer.importFiles(b, listOf(FileImporter.Source(res("camera.jpg"), "hotel-again.jpg")), null, File(tmp, "s2").apply { mkdirs() })
        val e = repo.expenses.get(r.createdExpenseIds.single())!!
        assertTrue(e.dupStrong)
        assertTrue(e.dupNote, e.dupNote.contains("Claim A"))
        assertEquals(1, r.duplicates)
        repo.saveExpense(e.copy(category = ExpenseCategory.BOARDING.name, confirmed = true, description = "Hotel stay", modeOfTravel = "NA"))
        assertTrue(repo.validate(b, emptyMap()).any { it.blocking && it.message.contains("confirm it with a reason") })
        repo.acknowledgeTrust(e.id, "Second night billed on same invoice copy - original attached")
        assertTrue(repo.validate(b, emptyMap()).none { it.blocking })
    }

    @Test fun dateComesFromPhotoWhenNotPrinted() = runBlocking {
        val t = trip("Hosur")
        extractor.texts["*"] = "Auto rickshaw\nFare Rs 180"
        val r = importer.importFiles(t, listOf(FileImporter.Source(res("camera.jpg"), "auto.jpg")), null, File(tmp, "s3").apply { mkdirs() })
        val e = repo.expenses.get(r.createdExpenseIds.single())!!
        assertEquals(LocalDate.of(2026, 3, 12), e.date)
        assertTrue(e.dateFromMetadata)
        assertFalse(e.dateConfident)
    }

    @Test fun aiReadingNeverOverwritesTheUser() = runBlocking {
        val t = trip("Merge")
        val id = repo.addManualExpense(t, null)
        val base = repo.expenses.get(id)!!.copy(amount = 100.0, ocrText = "Dinner\nSub total 1,550.00\nGST 280.00\nGrand Total 1,830.00\nDate 21-07-2026", description = "")
        val ai = BillAiResult(LocalDate.of(2026, 7, 21), 1830.0, "INR", "Spice Hut", null, ExpenseCategory.BOARDING, null, "Dinner at Spice Hut, Hosur", "Restaurant", emptyList())
        val merged = AiRunner.apply(base, ai)
        assertEquals(1830.0, merged.amount!!, 0.0)
        assertEquals("Dinner at Spice Hut, Hosur", merged.description)
        assertEquals("DONE", merged.aiState)
        assertTrue(merged.aiNote.contains("verify"))
        val edited = AiRunner.apply(base.copy(userEdited = true, description = "My words"), ai)
        assertEquals(100.0, edited.amount!!, 0.0)
        assertEquals("My words", edited.description)
        assertTrue(edited.aiNote.contains("your values were kept"))
        assertEquals("FAILED", AiRunner.apply(base, null).aiState)
    }

    @Test fun outputsAreSignedWithDeclaration() = runBlocking {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val signer = object : DocSigner {
            override val publicKey = kp.public
            override fun sign(data: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }
        }
        val cert = Signing.selfSignedCert(signer, "Asha Rao (E1)", "Firm")
        val exporter = ExportManager(repo, FakeRenderer(javaClass.classLoader!!.getResourceAsStream("receipt_portrait.jpg")!!.readBytes()), identity = { signer to cert }, appVersion = "2.0.0")
        val t = trip("Signed")
        val id = repo.addManualExpense(t, LocalDate.of(2026, 3, 11))
        repo.saveExpense(repo.expenses.get(id)!!.copy(amount = 500.0, description = "Taxi to client", category = ExpenseCategory.LOCAL_CONVEYANCE.name, confirmed = true, modeOfTravel = "Car - Taxi"))
        val out = exporter.generate(t, EmployeeInfo("Asha Rao", "E1", "", "", "", "Firm", ""))
        val keep = File(System.getProperty("java.io.tmpdir"), "ta_test_out/signed").apply { deleteRecursively(); mkdirs() }
        out.all.forEach { it.copyTo(File(keep, it.name), overwrite = true) }
        val v = PdfSigner.verify(out.pdf.readBytes())
        assertTrue(v.message, v.valid && v.coversWholeFile)
        assertEquals("Asha Rao (E1)", v.signer)
        val p7s = exporter.signatureOf(out.excel)
        assertTrue(p7s.exists())
        assertTrue(Signing.verifyDetached(out.excel.readBytes(), p7s.readBytes()).valid)
        val prov = exporter.prepare(t, EmployeeInfo("Asha Rao", "E1", "", "", "", "Firm", "")).data.provenance
        assertNotNull(prov)
        assertTrue(prov!!.documentId.startsWith(repo.trips.get(t)!!.claimNumber + "-"))
        assertFalse(prov.aiAssisted)
    }
}
