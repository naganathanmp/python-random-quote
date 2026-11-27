package com.tareimbursement.app.core

import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.service.Reminders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CustomisationTest {
    @After fun reset() { AmountWords.indianGrouping = true; AmountWords.showPaise = true }

    @Test fun numberFormats() {
        assertEquals("₹1,23,456.50", AmountWords.inr(123456.5))
        assertEquals("₹123,456.50", AmountWords.inr(123456.5, indian = false))
        assertEquals("₹1,23,457", AmountWords.inr(123456.5, paiseShown = false))
        assertEquals("₹1,234,567", AmountWords.inr(1234567.0, indian = false, paiseShown = false))
        assertEquals("₹12,34,567.00", AmountWords.inr(1234567.0))
        assertEquals("-₹950.00", AmountWords.inr(-950.0))
        AmountWords.indianGrouping = false; AmountWords.showPaise = false
        assertEquals("₹10,000,000", AmountWords.inr(1e7))
    }

    @Test fun cityCodes() {
        assertEquals("BLR", CityCodes.code("Bengaluru"))
        assertEquals("BLR", CityCodes.code("Bangalore, Karnataka"))
        assertEquals("DEL", CityCodes.code("New Delhi"))
        assertEquals("HOS", CityCodes.code("Hosur"))
        assertEquals("DXB", CityCodes.code("Dubai"))
        assertEquals("BLR→HOS", CityCodes.route("Bengaluru", "Hosur"))
        assertEquals("HOS", CityCodes.route("", "Hosur"))
        assertEquals("BLR", CityCodes.route("Bangalore", "Bengaluru"))
    }

    @Test fun reminders() {
        val today = LocalDate.of(2026, 11, 27)
        fun t(id: Long, end: LocalDate, status: String = "DRAFT") = TripEntity(id = id, title = "T$id", clientName = "", projectCode = "", location = "", purpose = "",
            startEpochDay = end.minusDays(3).toEpochDay(), endEpochDay = end.toEpochDay(), status = status)
        val r = Reminders.due(listOf(t(1, today.minusDays(9)), t(2, today.minusDays(2)), t(3, today.minusDays(20), "SUBMITTED"), t(4, today.plusDays(3))),
            mapOf(1L to 2, 2L to 3, 4L to 5), today)
        assertEquals(listOf(1L, 2L), r.map { it.tripId })
        assertTrue(r[0].text.contains("9 days ago") && r[0].text.contains("2 bills"))
        assertEquals("3 bills still to review", r[1].text)
    }
}
