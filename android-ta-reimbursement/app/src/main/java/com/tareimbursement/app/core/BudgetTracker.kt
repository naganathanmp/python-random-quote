package com.tareimbursement.app.core

import java.time.LocalDate
import kotlin.math.max

/** Budget vs actual and daily-limit tracking for one TA claim. */
object BudgetTracker {

    enum class Level { NONE, OK, WARNING, OVER }

    /** One bill for tracking: INR amount and, when shared, each traveller's share. */
    data class Spend(val date: LocalDate?, val amount: Double, val shares: Map<Long, Double> = emptyMap())

    data class DayOver(val date: LocalDate, val spent: Double, val limit: Double, val person: Long? = null) {
        val excess: Double get() = spent - limit
    }

    data class Status(
        val budget: Double?,
        val spent: Double,
        val level: Level,
        /** Spend per elapsed travel day × total travel days (null until there is spend). */
        val forecast: Double?,
        val daysOver: List<DayOver>,
        val dailyLimit: Double?,
        val perPerson: Boolean,
    ) {
        val remaining: Double? get() = budget?.let { it - spent }
        val usedFraction: Double? get() = budget?.takeIf { it > 0 }?.let { spent / it }
        val forecastOver: Boolean get() = budget != null && forecast != null && forecast > budget
    }

    const val WARN_AT = 0.8

    fun level(spent: Double, budget: Double?): Level = when {
        budget == null || budget <= 0 -> Level.NONE
        spent > budget + 0.005 -> Level.OVER
        spent >= budget * WARN_AT -> Level.WARNING
        else -> Level.OK
    }

    fun status(
        spends: List<Spend>, budget: Double?, dailyLimit: Double?, perPerson: Boolean,
        start: LocalDate, end: LocalDate, today: LocalDate = LocalDate.now(),
    ): Status {
        val spent = spends.sumOf { it.amount }
        // Forecast: average spend per elapsed day of the trip, projected over the whole trip.
        val totalDays = max(1L, end.toEpochDay() - start.toEpochDay() + 1)
        val lastDay = minOf(end, maxOf(today, spends.mapNotNull { it.date }.maxOrNull() ?: start))
        val elapsed = (lastDay.toEpochDay() - start.toEpochDay() + 1).coerceIn(1, totalDays)
        val forecast = if (spent > 0 && elapsed < totalDays) spent / elapsed * totalDays else if (spent > 0) spent else null

        val over = mutableListOf<DayOver>()
        if (dailyLimit != null && dailyLimit > 0) {
            val dated = spends.filter { it.date != null }
            if (!perPerson) {
                dated.groupBy { it.date!! }.forEach { (d, list) ->
                    val s = list.sumOf { it.amount }
                    if (s > dailyLimit + 0.005) over += DayOver(d, s, dailyLimit)
                }
            } else {
                // Per traveller: each person's share of the day's bills (unshared bills count for nobody specific → the team).
                dated.groupBy { it.date!! }.forEach { (d, list) ->
                    val perPersonTotals = HashMap<Long, Double>()
                    list.forEach { sp -> sp.shares.forEach { (p, v) -> perPersonTotals[p] = (perPersonTotals[p] ?: 0.0) + v } }
                    if (perPersonTotals.isEmpty()) {
                        val s = list.sumOf { it.amount }
                        if (s > dailyLimit + 0.005) over += DayOver(d, s, dailyLimit)
                    } else perPersonTotals.forEach { (p, s) -> if (s > dailyLimit + 0.005) over += DayOver(d, s, dailyLimit, p) }
                }
            }
        }
        return Status(budget?.takeIf { it > 0 }, spent, level(spent, budget), forecast, over.sortedWith(compareBy({ it.date }, { it.person ?: -1 })), dailyLimit?.takeIf { it > 0 }, perPerson)
    }
}
