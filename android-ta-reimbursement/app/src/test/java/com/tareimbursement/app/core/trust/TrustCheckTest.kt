package com.tareimbursement.app.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class TrustCheckTest {
    private fun res(name: String): File {
        val u = javaClass.classLoader!!.getResource("trust/$name")!!
        return File(u.toURI())
    }

    @Test fun cameraPhotoExifIsRead() {
        val m = FileMetadata.read(res("camera.jpg"))
        assertEquals("samsung", m.cameraMake)
        assertEquals("SM-S918B", m.cameraModel)
        assertEquals(LocalDateTime.of(2026, 3, 12, 19, 4, 10), m.created)
        val r = TrustCheck.assess(m, LocalDate.of(2026, 3, 12), null, isCameraCapture = false)
        assertEquals(TrustLevel.INFO, r.level)
        assertFalse(r.blocked)
    }

    @Test fun photoTakenBeforeBillDateWarns() {
        val m = FileMetadata.read(res("camera.jpg"))
        val r = TrustCheck.assess(m, LocalDate.of(2026, 3, 20), null, false)
        assertEquals(TrustLevel.WARN, r.level)
        assertTrue(r.findings.any { it.code == "photo-before-bill" })
    }

    @Test fun photoshopEditWarns() {
        val m = FileMetadata.read(res("edited.jpg"))
        assertTrue(m.software!!.contains("Photoshop"))
        val r = TrustCheck.assess(m, null, null, false)
        assertTrue(r.needsAck)
        assertTrue(r.findings.any { it.code == "edited" })
    }

    @Test fun stableDiffusionPngIsBlocked() {
        val r = TrustCheck.assess(FileMetadata.read(res("sd.png")), null, null, false)
        assertTrue(r.blocked)
    }

    @Test fun xmpDigitalSourceTypeAiIsBlocked() {
        val m = FileMetadata.read(res("xmp_ai.png"))
        assertEquals("trainedAlgorithmicMedia", m.digitalSourceType)
        assertTrue(TrustCheck.assess(m, null, null, false).blocked)
    }

    @Test fun invoiceMakerPdfWarnsAndDatesAreRead() {
        val m = FileMetadata.read(res("maker.pdf"))
        assertEquals("iLovePDF", m.pdfProducer)
        assertEquals(LocalDate.of(2026, 3, 1), m.createdDate)
        val r = TrustCheck.assess(m, LocalDate.of(2026, 3, 1), null, false)
        assertTrue(r.findings.any { it.code == "invoice-maker" })
        assertTrue(r.findings.any { it.code == "modified-later" })
        assertFalse(r.blocked)
        // round trip
        assertEquals(r.findings, TrustReport.decode(r.encode()).findings)
    }

    @Test fun ordinaryWordsAreNotAiTools() {
        assertTrue(FileMetadata.aiNamesIn("Aurora Hotel, Flux Cafe, Runway Road, Gemini Travels").isEmpty())
        assertEquals(listOf("Midjourney"), FileMetadata.aiNamesIn("Created with Midjourney v6"))
        assertTrue(FileMetadata.aiNamesIn("Google Gemini image generation").contains("Gemini"))
    }

    @Test fun gstinCheckDigit() {
        assertTrue(GstIn.valid("27AAPFU0939F1ZV"))
        assertFalse(GstIn.valid("27AAPFU0939F1ZX"))
        val r = TrustCheck.assess(FileMeta("jpeg"), null, "GSTIN: 27AAPFU0939F1ZX Total 500", true)
        assertTrue(r.findings.any { it.code == "gstin" })
        val ok = TrustCheck.assess(FileMeta("jpeg"), null, "GSTIN 27AAPFU0939F1ZV", true)
        assertFalse(ok.findings.any { it.code == "gstin" })
    }

    @Test fun duplicatesAcrossClaims() {
        val a = BillPrint(1, 10, "Pune audit", "abc", 0x0F0F0F0F0F0F0F0FL, LocalDate.of(2026, 3, 1), 1200.0, "Hotel Sunrise", "INV-77")
        val sameFile = a.copy(expenseId = 2, tripId = 11, tripTitle = "Delhi")
        assertTrue(Duplicates.find(sameFile, listOf(a)).first().strong)
        val rephoto = a.copy(expenseId = 3, sha256 = "zzz", dHash = 0x0F0F0F0F0F0F0F0EL)
        assertTrue(Duplicates.find(rephoto, listOf(a)).first().reason.contains("this claim"))
        val billNo = a.copy(expenseId = 4, sha256 = null, dHash = null, amount = 99.0, vendor = "HOTEL SUNRISE PVT", billNo = "inv 77")
        assertTrue(Duplicates.find(billNo, listOf(a)).isNotEmpty())
        val different = a.copy(expenseId = 5, sha256 = "q", dHash = 0L.inv(), date = LocalDate.of(2026, 3, 2), billNo = "X1", vendor = "Cafe")
        assertTrue(Duplicates.find(different, listOf(a)).isEmpty())
        val grid = IntArray(72) { if (it % 9 < 4) 200 else 50 }
        assertNotNull(Duplicates.dHash(grid))
    }
}
