package com.tareimbursement.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {
    private val today = LocalDate.of(2026, 11, 22)

    @Test fun rapidoRide() {
        val text = """
            Rapido
            Ride ID RD17234567
            Time of Ride Mar 15th 2026, 09:12 AM
            Pickup: Kempegowda International Airport Terminal 2
            Drop: MG Road
            Selected Price
            ₹ 500
            Paid via UPI
        """.trimIndent()
        val r = ReceiptParser.parse(text, today = today)
        assertEquals(LocalDate.of(2026, 3, 15), r.date)
        assertEquals(500.0, r.amount!!, 0.001)
        assertEquals("Rapido", r.vendor)
        assertEquals(ExpenseCategory.PICKUP_DROP, r.suggestion.category)
    }

    @Test fun uberWithDecimals() {
        val text = """
            Mar 28, 2026
            Thanks for riding, Ravi
            Total ₹820.16
            Trip fare ₹700.00
            Booking fee ₹120.16
            Uber Technologies
            UberXL 12.4 km | 32 min
        """.trimIndent()
        val r = ReceiptParser.parse(text, today = today)
        assertEquals(LocalDate.of(2026, 3, 28), r.date)
        assertEquals(820.16, r.amount!!, 0.001)
        assertEquals("Uber", r.vendor)
        assertEquals(ExpenseCategory.LOCAL_CONVEYANCE, r.suggestion.category)
        assertEquals(820L, FileNamer.roundAmount(r.amount!!))
    }

    @Test fun hotelTaxInvoice() {
        val text = """
            HOTEL SAROVAR PORTICO
            TAX INVOICE
            GSTIN: 29ABCDE1234F1Z5
            Invoice No: INV/2026/0456       Invoice Date: 27/03/2026
            Arrival 25/03/2026  Departure 27/03/2026
            Room Charges  2 Night  5,000.00
            CGST 6%   300.00
            SGST 6%   300.00
            Sub Total 5,000.00
            Grand Total   6,275.00
            Thank you for staying with us
        """.trimIndent()
        val r = ReceiptParser.parse(text, LocalDate.of(2026, 3, 24), LocalDate.of(2026, 3, 28), today)
        assertEquals(LocalDate.of(2026, 3, 27), r.date)
        assertEquals(6275.0, r.amount!!, 0.001)
        assertEquals("INV/2026/0456", r.billNo)
        assertEquals("HOTEL SAROVAR PORTICO", r.vendor)
        assertEquals(ExpenseCategory.BOARDING, r.suggestion.category)
        assertTrue(r.amountConfident)
    }

    @Test fun restaurantAsksUserWithAlternatives() {
        val text = """
            Sri Krishna Restaurant
            Table No 12   Covers 2
            Date: 26-Mar-2026  Time 21:10
            Veg Thali  2  360.00
            Masala Dosa 1 120.00
            Sub Total 480.00
            CGST 2.5% 12.00
            SGST 2.5% 12.00
            Net Amount Rs. 504.00
        """.trimIndent()
        val r = ReceiptParser.parse(text, today = today)
        assertEquals(LocalDate.of(2026, 3, 26), r.date)
        assertEquals(504.0, r.amount!!, 0.001)
        assertEquals("Sri Krishna Restaurant", r.vendor)
        assertTrue(ExpenseCategory.EXPENSES_DURING_TRAVEL in r.suggestion.alternatives)
        assertEquals("Food / restaurant bill", r.suggestion.nature)
    }

    @Test fun airTicket() {
        val text = """
            IndiGo
            Boarding Pass / e-Ticket
            PNR: X7YK2Q
            Date of Issue: 10 Mar 2026
            Flight 6E 2134 BLR - DEL Departure 24 Mar 2026 06:10
            Base Fare 5,400
            Taxes & Fees 1,152
            Total Amount INR 6,552.00
        """.trimIndent()
        val r = ReceiptParser.parse(text, LocalDate.of(2026, 3, 24), LocalDate.of(2026, 3, 28), today)
        assertEquals(6552.0, r.amount!!, 0.001)
        assertEquals(ExpenseCategory.AIR_FARE, r.suggestion.category)
        assertEquals("IndiGo", r.vendor)
        assertNotNull(r.date)
    }

    @Test fun mobileRecharge() {
        val r = ReceiptParser.parse("Airtel Prepaid Recharge successful\n12 Mar 2026\nAmount Paid ₹ 299", today = today)
        assertEquals(ExpenseCategory.TELEPHONE, r.suggestion.category)
        assertEquals(299.0, r.amount!!, 0.001)
    }

    @Test fun ignoresFutureAndPhoneNumbers() {
        val r = ReceiptParser.parse("Cafe Day\nPh 9876543210\nDate 01/02/2026\nValid till 01/02/2030\nTotal 150", today = today)
        assertEquals(LocalDate.of(2026, 2, 1), r.date)
        assertEquals(150.0, r.amount!!, 0.001)
    }

    @Test fun unknownTextFallsBackToOther() {
        val r = ReceiptParser.parse("xyz", today = today)
        assertEquals(ExpenseCategory.OTHER, r.suggestion.category)
        assertEquals(null, r.amount)
        assertEquals(null, r.date)
    }

    @Test fun vendorSkipsContactLines() {
        val r = ReceiptParser.parse("Tel: 044 2345 6789\nGSTIN 33AAAAA0000A1Z5\nHotel Grand Residency\nTotal 2,400", today = today)
        assertEquals("Hotel Grand Residency", r.vendor)
    }

    @Test fun indianGroupedAmount() {
        val r = ReceiptParser.parse("Grand Total ₹ 1,23,456.50", today = today)
        assertEquals(123456.5, r.amount!!, 0.001)
    }
}
