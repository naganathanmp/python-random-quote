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
)

data class StatementData(
    val employee: EmployeeInfo,
    val claim: ClaimInfo,
    val lines: List<StatementLine>,
) {
    val total: Double get() = lines.sumOf { it.amount }
    val netPayable: Double get() = total - claim.advance
    fun categoryTotal(c: ExpenseCategory) = lines.filter { it.category == c }.sumOf { it.amount }
    val days: Long get() = claim.endDate.toEpochDay() - claim.startDate.toEpochDay() + 1
}
