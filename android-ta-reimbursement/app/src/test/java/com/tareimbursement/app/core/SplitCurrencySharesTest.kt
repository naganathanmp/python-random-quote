package com.tareimbursement.app.core

import com.tareimbursement.app.core.pdf.CombinedPdfBuilder
import com.tareimbursement.app.core.pdf.DocAttachment
import com.tareimbursement.app.core.pdf.DocPage
import com.tareimbursement.app.core.pdf.ExpenseDocs
import com.tareimbursement.app.core.xlsx.TaStatementWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

class SplitCurrencySharesTest {

    @Test fun twoWaySplitIsExactAndNeverBreaksInvoices() {
        val amounts = listOf(6552.0, 500.0, 504.0, 6275.0, 820.16, 299.0, 1200.0, 75.0, 4500.0)
        val items = amounts.mapIndexed { i, a -> BillingSplit.Item(i.toLong(), a) }
        val r = BillingSplit.allocate(items, listOf(0.0, 0.0))
        assertEquals(items.size, r.assignment.size)
        assertEquals(amounts.sum(), r.totals.sum(), 0.001)
        // Best achievable with whole bills: brute force check
        var best = Double.MAX_VALUE
        for (mask in 0 until (1 shl amounts.size)) {
            val s = amounts.indices.filter { mask and (1 shl it) != 0 }.sumOf { amounts[it] }
            best = minOf(best, abs(s - amounts.sum() / 2))
        }
        assertTrue("deviation ${r.maxDeviation} vs best $best", r.maxDeviation <= best + 1.0)
    }

    @Test fun splitHonoursPercentagesAndLocks() {
        val items = (1..12).map { BillingSplit.Item(it.toLong(), it * 1000.0) } + BillingSplit.Item(99, 5000.0, lockedTo = 1)
        val r = BillingSplit.allocate(items, listOf(70.0, 30.0))
        assertEquals(1, r.assignment[99L])
        val total = items.sumOf { it.amount }
        assertEquals(total * 0.7, r.targets[0], 0.01)
        // Bills are multiples of 1000, so the best achievable is within 500 of the 70% target (58,100 -> 58,000).
        assertEquals(58000.0, r.totals[0], 0.001)
    }

    @Test fun threeWaySplitIsBalanced() {
        val items = listOf(9000.0, 7000.0, 6000.0, 5000.0, 4000.0, 3000.0, 2000.0, 1000.0, 1000.0).mapIndexed { i, a -> BillingSplit.Item(i.toLong(), a) }
        val r = BillingSplit.allocate(items, listOf(0.0, 0.0, 0.0))
        assertEquals(3, r.totals.size)
        assertTrue("totals ${r.totals}", r.maxDeviation <= 1000.0)
        assertEquals(38000.0, r.totals.sum(), 0.01)
    }

    @Test fun singleAndEmpty() {
        val r = BillingSplit.allocate(listOf(BillingSplit.Item(1, 100.0)), listOf(0.0))
        assertEquals(0, r.assignment[1L])
        val e = BillingSplit.allocate(emptyList(), listOf(0.0, 0.0))
        assertTrue(e.assignment.isEmpty())
    }

    @Test fun sharesSplitToThePaisa() {
        val s = Shares.equal(100.0, listOf(1, 2, 3))
        assertEquals(100.0, s.values.sum(), 0.0001)
        assertEquals(33.34, s[1L]!!, 0.0001)
        assertEquals(33.33, s[3L]!!, 0.0001)
        assertEquals(s, Shares.parse(Shares.format(s)))
        assertTrue(Shares.isBalanced(s, 100.0))
        assertTrue(!Shares.isBalanced(mapOf(1L to 40.0), 100.0))
        assertTrue(Shares.parse("garbage;1=;=5").isEmpty())
    }

    @Test fun currencyDetection() {
        assertEquals("USD", Currencies.detect("Starbucks Seattle\nTotal $12.50\nVisa ****1234"))
        assertEquals("AED", Currencies.detect("Dubai Mall\nTotal AED 85.00\nVAT 5%"))
        assertEquals("EUR", Currencies.detect("Hotel Berlin Total 240,00 €"))
        assertEquals("SGD", Currencies.detect("Grab ride SGD 18.40"))
        assertNull(Currencies.detect("Hotel Sarovar GSTIN 29AB CGST SGST Grand Total Rs. 6,275.00"))
        assertNull(Currencies.detect("Uber Total ₹820.16"))
        val p = ReceiptParser.parse("Marriott Dubai\nInvoice Date 12/03/2026\nTotal Amount AED 1,250.00", today = LocalDate.of(2026, 11, 24))
        assertEquals("AED", p.currency)
        assertEquals(1250.0, p.amount!!, 0.001)
        assertEquals(10400.0, Currencies.toInr(1250.0, 8.32), 0.001)
        assertEquals("USD 1,234.50", Currencies.format(1234.5, "USD"))
    }

    private fun data(): StatementData {
        val t = listOf(TravellerInfo("Ravi Kumar", "E1023"), TravellerInfo("Anita Rao", "E2001"), TravellerInfo("Sam Joseph", ""))
        val ents = listOf(EntityInfo("Acme India Pvt Ltd", "ACM-IN"), EntityInfo("Acme Gulf FZE", "ACM-AE"))
        val lines = listOf(
            StatementLine(1, LocalDate.of(2026, 3, 10), "PNR1", "Emirates", "BOM-DXB x3", ExpenseCategory.AIR_FARE, 54000.0, "10th Mar 2026_54000.pdf", "",
                entity = ents[0].label, shares = t.map { it.label to 18000.0 }),
            StatementLine(2, LocalDate.of(2026, 3, 12), "INV9", "Marriott Dubai", "3 rooms 2 nights", ExpenseCategory.BOARDING, 10400.0, "12th Mar 2026_10400.jpg", "",
                currency = "AED", foreignAmount = 1250.0, exchangeRate = 8.32, entity = ents[1].label,
                shares = listOf(t[0].label to 3466.68, t[1].label to 3466.66, t[2].label to 3466.66)),
            StatementLine(3, LocalDate.of(2026, 3, 13), "", "Careem", "Hotel to client", ExpenseCategory.LOCAL_CONVEYANCE, 707.2, "13th Mar 2026_707.png", "",
                currency = "AED", foreignAmount = 85.0, exchangeRate = 8.32, entity = ents[1].label, shares = listOf(t[0].label to 707.2)),
        )
        return StatementData(
            EmployeeInfo("Ravi Kumar", "E1023", "Manager", "Audit", "Mumbai", "PKF Sample & Co", ""),
            ClaimInfo("TA-202603-007", "Group Audit FY26", "Acme Group", "ACM-GRP", "Dubai", "Component audit", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 14), 20000.0, LocalDate.of(2026, 3, 16)),
            lines, travellers = t, entities = ents,
        )
    }

    @Test fun workbookAndPdfWithForeignSplitAndTravellers() {
        val d = data()
        assertTrue(d.isSplit && d.hasForeign && d.multiTraveller)
        assertEquals(54000.0, d.entityTotal("Acme India Pvt Ltd [ACM-IN]"), 0.001)
        assertEquals(18000.0 + 3466.68 + 707.2, d.travellerTotal("Ravi Kumar (E1023)"), 0.001)
        val out = File(System.getProperty("java.io.tmpdir"), "ta_test_out/v11").apply { mkdirs() }
        File(out, "consolidated.xlsx").writeBytes(ByteArrayOutputStream().also { TaStatementWorkbook.write(d, it) }.toByteArray())
        val entityData = d.copy(lines = d.lines.filter { it.entity == d.entities[1].label }, billedTo = d.entities[1], claim = d.claim.copy(advance = 2000.0))
        File(out, "entity.xlsx").writeBytes(ByteArrayOutputStream().also { TaStatementWorkbook.write(entityData, it) }.toByteArray())
        val img = javaClass.classLoader!!.getResourceAsStream("receipt_portrait.jpg")!!.readBytes()
        val docs = d.lines.map { ExpenseDocs(it, listOf(DocAttachment(it.fileReference, "Invoice", listOf(DocPage.Image { com.tareimbursement.app.core.pdf.JpegImage(img) })))) }
        File(out, "consolidated.pdf").writeBytes(ByteArrayOutputStream().also { CombinedPdfBuilder.build(d, docs, it) }.toByteArray())
    }
}
