package com.tareimbursement.app.core.sign

import com.tareimbursement.app.core.ClaimInfo
import com.tareimbursement.app.core.EmployeeInfo
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.Provenance
import com.tareimbursement.app.core.StatementData
import com.tareimbursement.app.core.StatementLine
import com.tareimbursement.app.core.pdf.CombinedPdfBuilder
import com.tareimbursement.app.core.pdf.DocAttachment
import com.tareimbursement.app.core.pdf.DocPage
import com.tareimbursement.app.core.pdf.ExpenseDocs
import com.tareimbursement.app.core.xlsx.TaStatementWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

class SigningTest {
    /** Software EC key standing in for the Android Keystore key. */
    private class SoftSigner : DocSigner {
        private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKey: PublicKey get() = kp.public
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(data); sign() }
    }

    private fun data(signer: String?) = StatementData(
        EmployeeInfo("Asha Rao", "E101", "Manager", "Audit", "Bengaluru", "Firm", "a@x.com"),
        ClaimInfo("TA-202607-001", "Hosur audit", "Titan", "P-77", "Hosur", "Internal audit", LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 22), 0.0, LocalDate.of(2026, 7, 23)),
        listOf(StatementLine(1, LocalDate.of(2026, 7, 21), "", "Uber", "Uber Bangalore to Hosur", ExpenseCategory.LOCAL_CONVEYANCE, 640.0, "21st Jul 2026_640.jpg", "", modeOfTravel = "Car - Taxi")),
        provenance = Provenance("TA-202607-001-7K3Q", "Asha Rao (E101)", LocalDateTime.of(2026, 7, 23, 10, 0), "2.0.0", aiAssisted = true,
            flaggedKept = listOf("#1 photo older than bill: re-photographed original"), signer = signer),
    )

    private fun pdf(signer: String?): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = data(signer)
        CombinedPdfBuilder.build(d, listOf(ExpenseDocs(d.lines[0], listOf(DocAttachment("21st Jul 2026_640.txt", "Invoice", listOf(DocPage.Text(listOf("Uber receipt", "Total 640")))))) ), bos)
        return bos.toByteArray()
    }

    @Test fun signedPdfVerifiesAndDetectsTampering() {
        val s = SoftSigner()
        val cert = Signing.selfSignedCert(s, "Asha Rao (E101)", "Firm")
        val signed = PdfSigner.sign(pdf("Asha Rao (E101)"), s, cert, "Asha Rao (E101)", "TA claim TA-202607-001 prepared and confirmed")
        val v = PdfSigner.verify(signed)
        assertTrue(v.message, v.valid)
        assertTrue(v.coversWholeFile)
        assertEquals("Asha Rao (E101)", v.signer)

        // Change one byte inside the signed range: signature must fail.
        val t = signed.copyOf(); val i = String(t, Charsets.ISO_8859_1).indexOf("Uber"); t[i] = 'X'.code.toByte()
        assertFalse(PdfSigner.verify(t).valid)
        // Append content after signing: flagged.
        val appended = signed + "\n% extra\n".toByteArray()
        assertFalse(PdfSigner.verify(appended).coversWholeFile)
        assertFalse(PdfSigner.verify(pdf(null)).valid)

        // Independent check with poppler's pdfsig when available.
        val pdfsig = File("/usr/bin/pdfsig")
        if (pdfsig.exists()) {
            val f = File.createTempFile("signed", ".pdf"); f.writeBytes(signed)
            val p = ProcessBuilder(pdfsig.path, f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); p.waitFor()
            println(out)
            assertTrue(out, out.contains("Signature Validation: Signature is Valid."))
            assertTrue(out, out.contains("Signed Ranges: [0 -") || out.contains("Total document signed"))
            f.delete()
        }
    }

    @Test fun excelDetachedSignatureAndDeclaration() {
        val s = SoftSigner()
        val cert = Signing.selfSignedCert(s, "Asha Rao (E101)", "")
        val bos = ByteArrayOutputStream(); TaStatementWorkbook.write(data("Asha Rao (E101)"), bos)
        val xlsx = bos.toByteArray()
        val p7s = Signing.cmsDetached(xlsx, s, cert)
        assertTrue(Signing.verifyDetached(xlsx, p7s).valid)
        assertFalse(Signing.verifyDetached(xlsx + byteArrayOf(0), p7s).valid)
        // The declaration is in the Annexure.
        val shared = ZipInputStream(xlsx.inputStream()).use { z ->
            var text = ""; while (true) { val e = z.nextEntry ?: break; if (e.name.endsWith(".xml")) text += z.readBytes().decodeToString() }; text
        }
        assertTrue(shared.contains("Declaration &amp; authenticity"))
        assertTrue(shared.contains("no amount, date or bill in this claim was created by AI"))
        assertTrue(shared.contains("TA-202607-001-7K3Q"))
    }

    @Test fun opensslCanVerifyExcelSignature() {
        val openssl = File("/usr/bin/openssl")
        assumeTrue(openssl.exists())
        val s = SoftSigner()
        val cert = Signing.selfSignedCert(s, "Asha Rao", "")
        val data = "statement bytes".toByteArray()
        val dir = kotlin.io.path.createTempDirectory().toFile()
        File(dir, "a.xlsx").writeBytes(data); File(dir, "a.xlsx.p7s").writeBytes(Signing.cmsDetached(data, s, cert))
        val p = ProcessBuilder(openssl.path, "cms", "-verify", "-binary", "-inform", "DER", "-in", "a.xlsx.p7s", "-content", "a.xlsx", "-noverify", "-out", "/dev/null")
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        assertTrue(out, out.contains("Verification successful"))
        dir.deleteRecursively()
    }
}
