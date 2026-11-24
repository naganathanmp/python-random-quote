package com.tareimbursement.app.core

import java.time.LocalDate

/** Plain data used by the statement / PDF generators (no Android types, fully unit-testable). */
data class EmployeeInfo(
    val name: String,
    val employeeCode: String,
    val designation: String,
    val department: String,
    val baseLocation: String,
    val organisation: String,
    val email: String,
)

data class ClaimInfo(
    val claimNumber: String,
    val projectTitle: String,
    val clientName: String,
    val projectCode: String,
    val placeOfVisit: String,
    val purpose: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val advance: Double,
    val preparedOn: LocalDate,
    val onwardBookedBy: String = "Self Booking",
    val returnBookedBy: String = "Self Booking",
    val comments: String = "",
    /** Date the claim was submitted (null until submitted; the template shows it as Submission Date). */
    val submittedOn: LocalDate? = null,
)

data class StatementLine(
    val serial: Int,
    val date: LocalDate?,
    val billNo: String,
    val vendor: String,
    val description: String,
    val category: ExpenseCategory,
    val amount: Double,
    val fileReference: String,
    val remarks: String,
    /** Bill currency; [amount] is always the INR value. */
    val currency: String = Currencies.INR,
    val foreignAmount: Double? = null,
    val exchangeRate: Double? = null,
    /** Client entity / project code this bill is billed to ("" when the claim is not split). */
    val entity: String = "",
    /** Each traveller's share of [amount] (traveller label to INR). */
    val shares: List<Pair<String, Double>> = emptyList(),
    /** Template "Mode of travel-Mandatory" (Car - Taxi, Auto, Food, Laundry…). */
    val modeOfTravel: String = "",
    /** Template "Sup Bills Y/N". */
    val billAvailable: Boolean = true,
    /** Own-vehicle claims: km × rate + toll (written as an Excel formula like the template). */
    val mileageKm: Double? = null,
    val mileageRate: Double? = null,
    val toll: Double? = null,
    /** Short names of the team members this bill is for (template "Team members name"). */
    val teamMembers: String = "",
) {
    val isMileage: Boolean get() = mileageKm != null && mileageRate != null
    val isForeign: Boolean get() = Currencies.isForeign(currency) && foreignAmount != null
}

data class TravellerInfo(val name: String, val employeeCode: String) {
    val label: String get() = if (employeeCode.isBlank()) name else "$name ($employeeCode)"
}

data class EntityInfo(val name: String, val code: String, val sharePercent: Double = 0.0) {
    val label: String get() = if (code.isBlank()) name else "$name [$code]"
}

data class StatementData(
    val employee: EmployeeInfo,
    val claim: ClaimInfo,
    val lines: List<StatementLine>,
    /** Everyone travelling on this claim (the claimant first). */
    val travellers: List<TravellerInfo> = emptyList(),
    /** Client entities the claim is split across (empty = not split). */
    val entities: List<EntityInfo> = emptyList(),
    /** Set for a per-entity statement: only that entity's bills are in [lines]. */
    val billedTo: EntityInfo? = null,
) {
    val total: Double get() = lines.sumOf { it.amount }
    val hasForeign: Boolean get() = lines.any { it.isForeign }
    val multiTraveller: Boolean get() = travellers.size > 1
    val isSplit: Boolean get() = entities.size > 1 && billedTo == null
    fun entityTotal(label: String) = lines.filter { it.entity == label }.sumOf { it.amount }
    fun travellerTotal(label: String) = lines.sumOf { l -> l.shares.filter { it.first == label }.sumOf { it.second } }
    fun travellerHeadTotal(label: String, c: ExpenseCategory) = lines.filter { it.category == c }.sumOf { l -> l.shares.filter { it.first == label }.sumOf { it.second } }
    val netPayable: Double get() = total - claim.advance
    fun categoryTotal(c: ExpenseCategory) = lines.filter { it.category == c }.sumOf { it.amount }
    val days: Long get() = claim.endDate.toEpochDay() - claim.startDate.toEpochDay() + 1
}
