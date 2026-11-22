package com.tareimbursement.app.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

enum class ClaimStatus(val label: String) {
    DRAFT("Draft"), SUBMITTED("Submitted"), APPROVED("Approved"), PAID("Reimbursed"), REJECTED("Rejected");
    val archived: Boolean get() = this != DRAFT
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: DRAFT }
}

data class InsightTrip(
    val id: Long, val title: String, val client: String, val projectCode: String,
    val start: LocalDate, val end: LocalDate, val status: ClaimStatus, val advance: Double,
    val submittedOn: LocalDate?, val paidOn: LocalDate?, val paidAmount: Double?,
)

data class InsightExpense(val tripId: Long, val date: LocalDate?, val amount: Double, val category: ExpenseCategory, val vendor: String)

data class CategoryStat(val category: ExpenseCategory, val total: Double, val count: Int, val share: Double, val average: Double)
data class VendorStat(val vendor: String, val count: Int, val total: Double, val category: ExpenseCategory)
data class MonthStat(val month: YearMonth, val total: Double)
data class HighExpense(val expense: InsightExpense, val tripTitle: String, val reason: String)
data class ProjectStat(val key: String, val client: String, val total: Double, val claims: Int)
data class TimelineEntry(val trip: InsightTrip, val total: Double, val settlementDays: Long?)

data class Insights(
    val totalClaimed: Double,
    val totalReimbursed: Double,
    val pendingAmount: Double,
    val claimCount: Int,
    val draftCount: Int,
    val expenseCount: Int,
    val averagePerClaim: Double,
    val averagePerDay: Double,
    val travelDays: Long,
    val averageSettlementDays: Double?,
    val categories: List<CategoryStat>,
    val months: List<MonthStat>,
    val topVendors: List<VendorStat>,
    val repeated: List<VendorStat>,
    val high: List<HighExpense>,
    val projects: List<ProjectStat>,
    val timeline: List<TimelineEntry>,
    val highlights: List<String>,
)

enum class InsightPeriod(val label: String) { ALL("All time"), THIS_YEAR("This year"), LAST_12_MONTHS("Last 12 months"), FY("This FY (Apr–Mar)") }

/** Computes the dashboard: totals, trends, category mix, repeated & unusually high spends. */
object InsightsEngine {
    private val monFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    fun periodStart(period: InsightPeriod, today: LocalDate): LocalDate? = when (period) {
        InsightPeriod.ALL -> null
        InsightPeriod.THIS_YEAR -> today.withDayOfYear(1)
        InsightPeriod.LAST_12_MONTHS -> today.minusMonths(12).plusDays(1)
        InsightPeriod.FY -> if (today.monthValue >= 4) LocalDate.of(today.year, 4, 1) else LocalDate.of(today.year - 1, 4, 1)
    }

    fun compute(trips: List<InsightTrip>, allExpenses: List<InsightExpense>, period: InsightPeriod = InsightPeriod.ALL, today: LocalDate = LocalDate.now()): Insights {
        val from = periodStart(period, today)
        val inTrips = trips.filter { from == null || !it.end.isBefore(from) }
        val ids = inTrips.map { it.id }.toSet()
        val tripById = inTrips.associateBy { it.id }
        val exps = allExpenses.filter { it.tripId in ids && it.amount > 0 }
        val claimed = exps.filter { tripById[it.tripId]?.status?.let { s -> s != ClaimStatus.DRAFT && s != ClaimStatus.REJECTED } == true }
        val total = exps.sumOf { it.amount }
        val totalByTrip = exps.groupBy { it.tripId }.mapValues { e -> e.value.sumOf { it.amount } }

        val paidTrips = inTrips.filter { it.status == ClaimStatus.PAID }
        val reimbursed = paidTrips.sumOf { t -> t.paidAmount ?: ((totalByTrip[t.id] ?: 0.0) - t.advance).coerceAtLeast(0.0) }
        val pending = inTrips.filter { it.status == ClaimStatus.SUBMITTED || it.status == ClaimStatus.APPROVED }
            .sumOf { t -> ((totalByTrip[t.id] ?: 0.0) - t.advance) }
        val days = inTrips.sumOf { it.end.toEpochDay() - it.start.toEpochDay() + 1 }
        val settlements = paidTrips.mapNotNull { t -> if (t.submittedOn != null && t.paidOn != null) t.paidOn.toEpochDay() - t.submittedOn.toEpochDay() else null }

        val categories = exps.groupBy { it.category }.map { (c, list) ->
            CategoryStat(c, list.sumOf { it.amount }, list.size, if (total > 0) list.sumOf { it.amount } / total else 0.0, list.sumOf { it.amount } / list.size)
        }.sortedByDescending { it.total }

        // Monthly trend: last 12 months ending this month (or spanning data for ALL).
        val dated = exps.filter { it.date != null }
        val endMonth = YearMonth.from(today)
        val startMonth = when {
            from != null -> YearMonth.from(from)
            dated.isEmpty() -> endMonth.minusMonths(5)
            else -> maxOf(YearMonth.from(dated.minOf { it.date!! }), endMonth.minusMonths(23)).let { if (it.isAfter(endMonth.minusMonths(5))) endMonth.minusMonths(5) else it }
        }
        val byMonth = dated.groupBy { YearMonth.from(it.date!!) }.mapValues { m -> m.value.sumOf { it.amount } }
        val months = generateSequence(startMonth) { it.plusMonths(1) }.takeWhile { !it.isAfter(endMonth) }.map { MonthStat(it, byMonth[it] ?: 0.0) }.toList()

        val vendors = exps.filter { it.vendor.isNotBlank() }.groupBy { it.vendor.trim().lowercase(Locale.ROOT) }.map { (_, list) ->
            VendorStat(list.first().vendor.trim(), list.size, list.sumOf { it.amount }, list.groupingBy { it.category }.eachCount().maxBy { it.value }.key)
        }
        val topVendors = vendors.sortedByDescending { it.total }.take(8)
        val repeated = vendors.filter { it.count >= 3 }.sortedByDescending { it.count }.take(8)

        // High expenses: outliers within category (mean + 2σ with ≥4 samples) and the top 5 overall.
        val high = mutableListOf<HighExpense>()
        exps.groupBy { it.category }.forEach { (c, list) ->
            if (list.size >= 4) {
                val mean = list.sumOf { it.amount } / list.size
                val sd = sqrt(list.sumOf { (it.amount - mean) * (it.amount - mean) } / list.size)
                list.filter { sd > 0 && it.amount > mean + 2 * sd }.forEach {
                    high += HighExpense(it, tripById[it.tripId]?.title ?: "", "%.1f× the usual %s spend (avg %s)".format(Locale.ENGLISH, it.amount / mean, c.shortLabel.lowercase(Locale.ROOT), AmountWords.inr(mean)))
                }
            }
        }
        exps.sortedByDescending { it.amount }.take(5).forEach { e ->
            if (high.none { it.expense === e }) high += HighExpense(e, tripById[e.tripId]?.title ?: "", "Among your largest single expenses")
        }

        val projects = inTrips.groupBy { it.projectCode.ifBlank { it.title } }.map { (k, ts) ->
            ProjectStat(k, ts.first().client, ts.sumOf { totalByTrip[it.id] ?: 0.0 }, ts.size)
        }.sortedByDescending { it.total }

        val timeline = inTrips.sortedByDescending { it.submittedOn ?: it.start }.map { t ->
            TimelineEntry(t, totalByTrip[t.id] ?: 0.0, if (t.submittedOn != null && t.paidOn != null) t.paidOn.toEpochDay() - t.submittedOn.toEpochDay() else null)
        }

        val highlights = buildHighlights(categories, exps, months, repeated, settlements, inTrips, totalByTrip, today, days)

        val claimCount = inTrips.count { it.status != ClaimStatus.DRAFT }
        return Insights(
            totalClaimed = claimed.sumOf { it.amount },
            totalReimbursed = reimbursed,
            pendingAmount = pending,
            claimCount = claimCount,
            draftCount = inTrips.count { it.status == ClaimStatus.DRAFT },
            expenseCount = exps.size,
            averagePerClaim = if (inTrips.isNotEmpty()) total / inTrips.size else 0.0,
            averagePerDay = if (days > 0) total / days else 0.0,
            travelDays = days,
            averageSettlementDays = if (settlements.isNotEmpty()) settlements.average() else null,
            categories = categories, months = months, topVendors = topVendors, repeated = repeated,
            high = high.sortedByDescending { it.expense.amount }.take(10), projects = projects, timeline = timeline,
            highlights = highlights,
        )
    }

    private fun buildHighlights(
        categories: List<CategoryStat>, exps: List<InsightExpense>, months: List<MonthStat>, repeated: List<VendorStat>,
        settlements: List<Long>, trips: List<InsightTrip>, totalByTrip: Map<Long, Double>, today: LocalDate, days: Long,
    ): List<String> {
        val out = mutableListOf<String>()
        if (exps.isEmpty()) return listOf("Add bills to a TA claim to see insights here.")
        categories.firstOrNull()?.let { out += "${it.category.label} is your biggest head: ${AmountWords.inr(it.total)} (${(it.share * 100).toInt()}% of spend, ${it.count} bills)." }
        // Last 3 months vs the 3 before, per category.
        val recentFrom = today.minusMonths(3); val prevFrom = today.minusMonths(6)
        val recent = exps.filter { it.date != null && !it.date.isBefore(recentFrom) }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        val prev = exps.filter { it.date != null && !it.date.isBefore(prevFrom) && it.date.isBefore(recentFrom) }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        recent.mapNotNull { (c, v) -> prev[c]?.takeIf { it > 0 }?.let { p -> Triple(c, v, (v - p) / p) } }
            .filter { kotlin.math.abs(it.third) >= 0.25 }.sortedByDescending { kotlin.math.abs(it.third) }.take(2).forEach { (c, _, ch) ->
                out += "${c.label} ${if (ch > 0) "rose" else "fell"} ${(kotlin.math.abs(ch) * 100).toInt()}% in the last 3 months versus the 3 months before."
            }
        val nonZero = months.filter { it.total > 0 }
        nonZero.maxByOrNull { it.total }?.let { if (nonZero.size > 1) out += "Highest spend month: ${it.month.format(monFmt)} (${AmountWords.inr(it.total)})." }
        repeated.firstOrNull()?.let { out += "Most frequent vendor: ${it.vendor} — ${it.count} bills totalling ${AmountWords.inr(it.total)}." }
        val local = exps.filter { it.category == ExpenseCategory.LOCAL_CONVEYANCE || it.category == ExpenseCategory.PICKUP_DROP }
        if (local.size >= 5 && days > 0) out += "Local travel averages ${AmountWords.inr(local.sumOf { it.amount } / days)} per travel day across ${local.size} rides."
        if (days > 0) out += "Average spend per travel day: ${AmountWords.inr(exps.sumOf { it.amount } / days)} over $days days."
        val weekend = exps.filter { it.date != null && it.date.dayOfWeek.value >= 6 }
        if (weekend.size >= 3) out += "${weekend.size} bills (${AmountWords.inr(weekend.sumOf { it.amount })}) fall on weekends — keep approvals handy for these."
        if (settlements.isNotEmpty()) out += "Claims are reimbursed in ${"%.0f".format(Locale.ENGLISH, settlements.average())} days on average after submission."
        val waiting = trips.filter { it.status == ClaimStatus.SUBMITTED && it.submittedOn != null && today.toEpochDay() - it.submittedOn.toEpochDay() > 30 }
        if (waiting.isNotEmpty()) out += "${waiting.size} claim(s) submitted over 30 days ago are still awaiting approval — consider following up."
        val noBillCount = exps.count { it.vendor.isBlank() }
        if (noBillCount > 0) out += "$noBillCount expense(s) have no vendor recorded — add vendor names for better tracking."
        val biggestTrip = trips.maxByOrNull { totalByTrip[it.id] ?: 0.0 }
        if (biggestTrip != null && trips.size > 1) out += "Costliest assignment: ${biggestTrip.title} (${AmountWords.inr(totalByTrip[biggestTrip.id] ?: 0.0)})."
        return out
    }
}
