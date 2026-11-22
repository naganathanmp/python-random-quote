package com.tareimbursement.app.core

import com.tareimbursement.app.core.pdf.CombinedPdfBuilder
import com.tareimbursement.app.core.pdf.DocAttachment
import com.tareimbursement.app.core.pdf.DocPage
import com.tareimbursement.app.core.pdf.ExpenseDocs
import com.tareimbursement.app.core.pdf.JpegImage
import com.tareimbursement.app.core.pdf.PdfWriter
import com.tareimbursement.app.core.xlsx.TaStatementWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

class CoreOutputsTest {

    @Test fun ordinalsAndNames() {
        assertEquals("1st", FileNamer.ordinal(1)); assertEquals("2nd", FileNamer.ordinal(2)); assertEquals("3rd", FileNamer.ordinal(3))
        assertEquals("4th", FileNamer.ordinal(4)); assertEquals("11th", FileNamer.ordinal(11)); assertEquals("12th", FileNamer.ordinal(12))
        assertEquals("13th", FileNamer.ordinal(13)); assertEquals("21st", FileNamer.ordinal(21)); assertEquals("22nd", FileNamer.ordinal(22))
        assertEquals("23rd", FileNamer.ordinal(23)); assertEquals("31st", FileNamer.ordinal(31))
        assertEquals("15th Mar 2026_500", FileNamer.baseName(LocalDate.of(2026, 3, 15), 500.0))
        assertEquals("28th Mar 2026_820", FileNamer.baseName(LocalDate.of(2026, 3, 28), 820.16))
        assertEquals("28th Mar 2026_821", FileNamer.baseName(LocalDate.of(2026, 3, 28), 820.5))
        data class F(val d: LocalDate, val a: Double, val ext: String)
        val files = listOf(F(LocalDate.of(2026, 3, 28), 6275.0, "jpg"), F(LocalDate.of(2026, 3, 28), 6275.0, ".JPG"), F(LocalDate.of(2026, 3, 15), 500.0, "pdf"))
        val names = FileNamer.assignNames(files, { it.d }, { it.a }, { it.ext }).map { it.fileName }
        assertEquals(listOf("28th Mar 2026_6275 (1).jpg", "28th Mar 2026_6275 (2).jpg", "15th Mar 2026_500.pdf"), names)
    }

    @Test fun amountWords() {
        assertEquals("Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Fifty Paise Only", AmountWords.rupees(123456.5))
        assertEquals("Rupees Zero Only", AmountWords.rupees(0.0))
        assertEquals("Rupees Two Crore Five Lakh Only", AmountWords.rupees(20500000.0))
        assertEquals("₹1,23,456.50", AmountWords.inr(123456.5))
        assertEquals("₹999.00", AmountWords.inr(999.0))
        assertEquals("-₹1,000.00", AmountWords.inr(-1000.0))
    }

    private fun sample(): StatementData {
        val lines = listOf(
            StatementLine(1, LocalDate.of(2026, 3, 24), "X7YK2Q", "IndiGo", "BLR-DEL", ExpenseCategory.AIR_FARE, 6552.0, "24th Mar 2026_6552.pdf", ""),
            StatementLine(2, LocalDate.of(2026, 3, 24), "", "Rapido", "Airport to hotel", ExpenseCategory.PICKUP_DROP, 500.0, "24th Mar 2026_500.jpg", ""),
            StatementLine(3, LocalDate.of(2026, 3, 26), "", "Sri Krishna Restaurant", "Dinner", ExpenseCategory.BOARDING, 504.0, "26th Mar 2026_504.jpg", ""),
            StatementLine(4, LocalDate.of(2026, 3, 27), "INV/2026/0456", "Hotel Sarovar <&> \"Portico\"", "2 nights", ExpenseCategory.BOARDING, 6275.0, "27th Mar 2026_6275 (1).jpg; 27th Mar 2026_6275 (2).jpg", "3-photo checkout"),
            StatementLine(5, LocalDate.of(2026, 3, 28), "", "Uber", "", ExpenseCategory.LOCAL_CONVEYANCE, 820.16, "28th Mar 2026_820.pdf", ""),
            StatementLine(6, LocalDate.of(2026, 3, 28), "", "Airtel", "", ExpenseCategory.TELEPHONE, 299.0, "28th Mar 2026_299.png", "No bill – self declared"),
        )
        return StatementData(
            EmployeeInfo("Ravi Kumar", "E1023", "Senior Associate", "Audit", "Chennai", "PKF Sample & Co", "ravi@example.com"),
            ClaimInfo("TA-202603-001", "Statutory Audit FY26", "Acme Ltd", "ACM-2026-01", "Delhi", "Audit fieldwork", LocalDate.of(2026, 3, 24), LocalDate.of(2026, 3, 28), 10000.0, LocalDate.of(2026, 3, 30)),
            lines,
        )
    }

    @Test fun workbookIsValidXlsx() {
        val data = sample()
        val bos = ByteArrayOutputStream()
        TaStatementWorkbook.write(data, bos)
        val names = mutableListOf<String>()
        ZipInputStream(bos.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")))
        val out = File(System.getProperty("java.io.tmpdir"), "ta_test_out").apply { mkdirs() }
        File(out, "TA_Statement.xlsx").writeBytes(bos.toByteArray())
        assertEquals(14950.16, data.total, 0.001)
        assertEquals(4950.16, data.netPayable, 0.001)
    }

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test fun jpegHeaderParsing() {
        val j = JpegImage(res("receipt_landscape.jpg"))
        assertEquals(1200, j.width); assertEquals(700, j.height); assertEquals(3, j.components)
        val g = JpegImage(res("receipt_gray.jpg"))
        assertEquals(600, g.width); assertEquals(1, g.components)
    }

    @Test fun combinedPdfIsWritten() {
        val data = sample()
        val img1 = res("receipt_portrait.jpg"); val img2 = res("receipt_landscape.jpg"); val img3 = res("receipt_gray.jpg")
        val docs = data.lines.map { l ->
            when (l.serial) {
                1 -> ExpenseDocs(l, listOf(DocAttachment("24th Mar 2026_6552.pdf", "Invoice", listOf(DocPage.Image { JpegImage(img1) }, DocPage.Image { JpegImage(img2) }))))
                4 -> ExpenseDocs(l, listOf(DocAttachment("a.jpg", "Invoice", listOf(DocPage.Image { JpegImage(img1) }, DocPage.Image { JpegImage(img3) })), DocAttachment("b.txt", "Supporting", listOf(DocPage.Text((1..130).map { "Line $it of the email confirmation (with brackets) \\ ₹ – done" })))))
                5 -> ExpenseDocs(l, listOf(DocAttachment("broken.jpg", "Invoice", listOf(DocPage.Image { throw IllegalStateException("corrupt") }))))
                6 -> ExpenseDocs(l, emptyList())
                else -> ExpenseDocs(l, listOf(DocAttachment("x.docx", "Invoice", listOf(DocPage.Placeholder("Word document – original in ZIP")))))
            }
        }
        val many = data.copy(lines = data.lines + (7..40).map { data.lines[1].copy(serial = it) })
        val manyDocs = docs + (7..40).map { ExpenseDocs(data.lines[1].copy(serial = it), listOf(DocAttachment("r.jpg", "Invoice", listOf(DocPage.Image { JpegImage(img2) })))) }
        val bos = ByteArrayOutputStream()
        CombinedPdfBuilder.build(many, manyDocs, bos)
        val bytes = bos.toByteArray()
        assertTrue(String(bytes, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        assertTrue(String(bytes, bytes.size - 6, 6, Charsets.ISO_8859_1).contains("%%EOF"))
        val out = File(System.getProperty("java.io.tmpdir"), "ta_test_out").apply { mkdirs() }
        File(out, "Invoices.pdf").writeBytes(bytes)
    }

    @Test fun wrapAndFit() {
        val lines = PdfWriter.wrap("The quick brown fox jumps over the lazy dog ".repeat(10), 10f, 200f)
        assertTrue(lines.size > 3)
        lines.forEach { assertTrue(PdfWriter.textWidth(it, 10f) <= 200f) }
        assertTrue(PdfWriter.fit("x".repeat(500), 10f, 100f).endsWith("..."))
        assertEquals("a\\(b\\)\\\\Rs. \\226", PdfWriter.encode("a(b)\\₹–"))
    }

    @Test fun insights() {
        val t1 = InsightTrip(1, "Audit A", "Acme", "A1", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5), ClaimStatus.PAID, 1000.0, LocalDate.of(2026, 6, 7), LocalDate.of(2026, 6, 21), 4000.0)
        val t2 = InsightTrip(2, "Audit B", "Beta", "B1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), ClaimStatus.SUBMITTED, 0.0, LocalDate.of(2026, 10, 5), null, null)
        val t3 = InsightTrip(3, "Draft C", "Beta", "B1", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), ClaimStatus.DRAFT, 0.0, null, null, null)
        val ex = mutableListOf<InsightExpense>()
        repeat(6) { ex += InsightExpense(1, LocalDate.of(2026, 6, 1 + (it % 5)), 200.0 + it, ExpenseCategory.LOCAL_CONVEYANCE, "Uber") }
        ex += InsightExpense(1, LocalDate.of(2026, 6, 2), 3000.0, ExpenseCategory.LOCAL_CONVEYANCE, "Uber")
        ex += InsightExpense(2, LocalDate.of(2026, 10, 1), 7000.0, ExpenseCategory.AIR_FARE, "IndiGo")
        ex += InsightExpense(2, LocalDate.of(2026, 10, 2), 2500.0, ExpenseCategory.BOARDING, "Taj")
        ex += InsightExpense(3, LocalDate.of(2026, 11, 10), 400.0, ExpenseCategory.BOARDING, "")
        val i = InsightsEngine.compute(listOf(t1, t2, t3), ex, InsightPeriod.ALL, LocalDate.of(2026, 11, 22))
        assertEquals(2, i.claimCount); assertEquals(1, i.draftCount)
        assertEquals(4000.0, i.totalReimbursed, 0.01)
        assertEquals(9500.0, i.pendingAmount, 0.01)
        assertEquals(ExpenseCategory.AIR_FARE, i.categories.first().category)
        assertEquals(4215.0, i.categories.first { it.category == ExpenseCategory.LOCAL_CONVEYANCE }.total, 0.01)
        assertTrue(i.repeated.any { it.vendor == "Uber" && it.count == 7 })
        assertTrue(i.high.any { it.expense.amount == 3000.0 && it.reason.contains("usual") })
        assertEquals(14.0, i.averageSettlementDays!!, 0.01)
        assertTrue(i.highlights.isNotEmpty())
        assertTrue(i.months.isNotEmpty())
        val fy = InsightsEngine.compute(listOf(t1, t2, t3), ex, InsightPeriod.FY, LocalDate.of(2026, 11, 22))
        assertEquals(3, fy.timeline.size)
        val empty = InsightsEngine.compute(emptyList(), emptyList(), InsightPeriod.ALL, LocalDate.of(2026, 11, 22))
        assertEquals(0.0, empty.totalClaimed, 0.0)
    }
}
