package com.tareimbursement.app.core

import com.tareimbursement.app.core.BudgetTracker.Level
import com.tareimbursement.app.core.BudgetTracker.Spend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetTrackerTest {
    private val start = LocalDate.of(2026, 7, 20)
    private val end = LocalDate.of(2026, 7, 29) // 10 days
    private fun d(day: Int) = LocalDate.of(2026, 7, day)

    @Test fun levels() {
        assertEquals(Level.NONE, BudgetTracker.level(100.0, null))
        assertEquals(Level.OK, BudgetTracker.level(7999.0, 10000.0))
        assertEquals(Level.WARNING, BudgetTracker.level(8000.0, 10000.0))
        assertEquals(Level.WARNING, BudgetTracker.level(10000.0, 10000.0))
        assertEquals(Level.OVER, BudgetTracker.level(10000.01, 10000.0))
    }

    @Test fun budgetRemainingAndForecast() {
        val spends = listOf(Spend(d(20), 2000.0), Spend(d(21), 1500.0), Spend(d(22), 1500.0))
        // On 24 Jul (5 days elapsed of 10) ₹5,000 spent → forecast ₹10,000 for the whole trip.
        val s = BudgetTracker.status(spends, 8000.0, null, false, start, end, today = d(24))
        assertEquals(5000.0, s.spent, 0.0)
        assertEquals(3000.0, s.remaining!!, 0.0)
        assertEquals(0.625, s.usedFraction!!, 1e-9)
        assertEquals(Level.OK, s.level)
        assertEquals(10000.0, s.forecast!!, 0.01)
        assertTrue(s.forecastOver)
        assertTrue(s.daysOver.isEmpty())
        val over = BudgetTracker.status(spends + Spend(d(23), 4000.0), 8000.0, null, false, start, end, today = d(24))
        assertEquals(Level.OVER, over.level)
        assertEquals(-1000.0, over.remaining!!, 0.0)
    }

    @Test fun dailyLimitWholeTeam() {
        val spends = listOf(Spend(d(20), 900.0), Spend(d(20), 400.0), Spend(d(21), 1200.0), Spend(null, 5000.0))
        val s = BudgetTracker.status(spends, null, 1200.0, false, start, end, today = d(29))
        assertNull(s.budget)
        assertEquals(Level.NONE, s.level)
        assertEquals(1, s.daysOver.size) // 20 Jul = 1300 > 1200; 21 Jul = 1200 is exactly at the limit
        assertEquals(d(20), s.daysOver[0].date)
        assertEquals(100.0, s.daysOver[0].excess, 0.001)
    }

    @Test fun dailyLimitPerTraveller() {
        // Two travellers (ids 1, 2): dinner shared equally, one taxi only for traveller 1.
        val spends = listOf(
            Spend(d(20), 1600.0, mapOf(1L to 800.0, 2L to 800.0)),
            Spend(d(20), 500.0, mapOf(1L to 500.0)),
        )
        val s = BudgetTracker.status(spends, 50000.0, 1000.0, true, start, end, today = d(21))
        assertEquals(1, s.daysOver.size)
        assertEquals(1L, s.daysOver[0].person)
        assertEquals(1300.0, s.daysOver[0].spent, 0.001)
        val team = BudgetTracker.status(spends, 50000.0, 1000.0, false, start, end, today = d(21))
        assertEquals(1, team.daysOver.size)
        assertEquals(null, team.daysOver[0].person)
        assertFalse(team.forecastOver)
    }
}
