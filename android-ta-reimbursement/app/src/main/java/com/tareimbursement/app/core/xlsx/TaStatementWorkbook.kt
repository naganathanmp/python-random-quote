package com.tareimbursement.app.core.xlsx

import com.tareimbursement.app.core.AmountWords
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.StatementData
import com.tareimbursement.app.core.xlsx.XlsxWriter.Style
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the PKF-style TA workbook:
 *  - "CON-SHEET": claim header, consolidated totals per accounting head (SUMIF over the
 *    Annexure), advance adjustment, net payable, amount in words, declaration and sign-offs.
 *  - "Annexure": itemised list of every bill with date, bill no., vendor, head, amount and
 *    the renamed file reference (DDth Mon YYYY_Amount) so auditors can trace each bill.
 */
object TaStatementWorkbook {
    const val CON_SHEET = "CON-SHEET"
    const val ANNEXURE = "Annexure"
    const val ANNEX_FIRST_ROW = 5
    private val dmy = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

    fun write(data: StatementData, out: OutputStream) {
        val wb = XlsxWriter()
        val con = wb.sheet(CON_SHEET)
        val ann = wb.sheet(ANNEXURE)
        val c = data.claim
        val e = data.employee

        // ---------------- Annexure ----------------
        ann.landscape = true
        listOf(6.0, 13.0, 16.0, 26.0, 30.0, 30.0, 14.0, 28.0, 26.0).forEachIndexed { i, w -> ann.width(i + 1, w) }
        ann.str(1, 1, (e.organisation.ifBlank { "TRAVELLING ALLOWANCE" }) + " – ANNEXURE TO TA STATEMENT", Style.TITLE)
        ann.merge(1, 1, 1, 9)
        ann.str(2, 1, "Employee: ${e.name} (${e.employeeCode})   |   Client: ${c.clientName}   |   Project: ${c.projectTitle} [${c.projectCode}]   |   Claim No: ${c.claimNumber}", Style.SUBTITLE)
        ann.merge(2, 1, 2, 9)
        ann.str(3, 1, "Period: ${c.startDate.format(dmy)} to ${c.endDate.format(dmy)}   |   Place of visit: ${c.placeOfVisit}", Style.NOTE)
        ann.merge(3, 1, 3, 9)
        val headers = listOf("S.No", "Date", "Bill / Invoice No.", "Vendor / Particulars", "Description", "Accounting Head", "Amount (₹)", "File Reference", "Remarks")
        headers.forEachIndexed { i, h -> ann.str(4, i + 1, h, Style.HEADER) }
        ann.height(4, 30.0)
        ann.freezeRows = 4
        var r = ANNEX_FIRST_ROW
        data.lines.forEach { l ->
            ann.num(r, 1, l.serial.toDouble(), Style.CENTER)
            ann.date(r, 2, l.date)
            ann.str(r, 3, l.billNo)
            ann.str(r, 4, l.vendor, Style.TEXT_WRAP)
            ann.str(r, 5, l.description, Style.TEXT_WRAP)
            ann.str(r, 6, l.category.label)
            ann.num(r, 7, l.amount)
            ann.str(r, 8, l.fileReference, Style.TEXT_WRAP)
            ann.str(r, 9, l.remarks, Style.TEXT_WRAP)
            r++
        }
        val annLast = maxOf(r - 1, ANNEX_FIRST_ROW)
        if (data.lines.isEmpty()) { (1..9).forEach { ann.blank(ANNEX_FIRST_ROW, it, Style.TEXT) }; r = ANNEX_FIRST_ROW + 1 }
        ann.autoFilter = "A4:I$annLast"
        val annTotalRow = r
        (1..9).forEach { ann.blank(annTotalRow, it, Style.LABEL) }
        ann.str(annTotalRow, 4, "TOTAL", Style.LABEL_BOLD_BORDER)
        ann.merge(annTotalRow, 4, annTotalRow, 6)
        ann.formula(annTotalRow, 7, "SUM(G$ANNEX_FIRST_ROW:G$annLast)", data.total, Style.MONEY_BOLD)

        // ---------------- CON-SHEET ----------------
        listOf(7.0, 44.0, 18.0, 12.0, 30.0).forEachIndexed { i, w -> con.width(i + 1, w) }
        con.str(1, 1, e.organisation.ifBlank { "TRAVELLING ALLOWANCE STATEMENT" }, Style.TITLE); con.merge(1, 1, 1, 5)
        con.height(1, 24.0)
        con.str(2, 1, "TRAVELLING ALLOWANCE (TA) STATEMENT", Style.SUBTITLE); con.merge(2, 1, 2, 5)
        con.str(3, 1, "Claim No: ${c.claimNumber}        Prepared on: ${c.preparedOn.format(dmy)}", Style.NOTE); con.merge(3, 1, 3, 5)

        val details = listOf(
            "Name of the Employee" to e.name,
            "Employee Code" to e.employeeCode,
            "Designation" to e.designation,
            "Department / Division" to e.department,
            "Base Location" to e.baseLocation,
            "Client Name" to c.clientName,
            "Project / Assignment" to c.projectTitle,
            "Project / Engagement Code" to c.projectCode,
            "Place(s) of Visit" to c.placeOfVisit,
            "Purpose of Visit" to c.purpose,
            "Period of Travel" to "${c.startDate.format(dmy)} to ${c.endDate.format(dmy)}  (${data.days} day${if (data.days == 1L) "" else "s"})",
        )
        var row = 5
        details.forEach { (k, v) ->
            con.str(row, 1, k, Style.LABEL); con.merge(row, 1, row, 2, Style.LABEL)
            con.str(row, 3, v, Style.TEXT_WRAP); con.merge(row, 3, row, 5, Style.TEXT_WRAP)
            row++
        }
        row++
        listOf("S.No", "Particulars (Accounting Head)", "Amount (₹)", "No. of Bills", "Remarks").forEachIndexed { i, h -> con.str(row, i + 1, h, Style.HEADER) }
        con.height(row, 28.0)
        row++
        val firstCat = row
        val annG = "$ANNEXURE!\$G\$$ANNEX_FIRST_ROW:\$G\$$annLast"
        val annF = "$ANNEXURE!\$F\$$ANNEX_FIRST_ROW:\$F\$$annLast"
        ExpenseCategory.entries.forEachIndexed { i, cat ->
            con.num(row, 1, (i + 1).toDouble(), Style.CENTER)
            con.str(row, 2, cat.label)
            con.formula(row, 3, "SUMIF($annF,B$row,$annG)", data.categoryTotal(cat))
            con.formula(row, 4, "COUNTIF($annF,B$row)", data.lines.count { it.category == cat }.toDouble(), Style.CENTER)
            con.blank(row, 5, Style.TEXT)
            row++
        }
        val lastCat = row - 1
        val totalRow = row
        con.blank(totalRow, 1, Style.LABEL)
        con.str(totalRow, 2, "Total Expenses (A)", Style.LABEL_BOLD_BORDER)
        con.formula(totalRow, 3, "SUM(C$firstCat:C$lastCat)", data.total, Style.MONEY_BOLD)
        con.formula(totalRow, 4, "SUM(D$firstCat:D$lastCat)", data.lines.size.toDouble(), Style.CENTER_BOLD)
        con.blank(totalRow, 5, Style.LABEL)
        row++
        con.blank(row, 1, Style.TEXT); con.str(row, 2, "Less: Advance received (B)", Style.LABEL_BOLD_BORDER)
        con.num(row, 3, c.advance); con.blank(row, 4, Style.TEXT); con.blank(row, 5, Style.TEXT)
        val advRow = row
        row++
        val net = data.netPayable
        con.blank(row, 1, Style.LABEL)
        con.str(row, 2, "Net payable / (refundable) (A − B)", Style.LABEL_BOLD_BORDER)
        con.formula(row, 3, "C$totalRow-C$advRow", net, Style.MONEY_BOLD)
        con.blank(row, 4, Style.LABEL)
        con.str(row, 5, if (net >= 0) "Payable to employee" else "Refundable by employee", Style.LABEL)
        row++
        con.str(row, 1, "Amount in words: " + AmountWords.rupees(kotlin.math.abs(net)) + if (net < 0) " (refundable)" else "", Style.TEXT_WRAP)
        con.merge(row, 1, row, 5, Style.TEXT_WRAP)
        con.height(row, 30.0)
        row += 2
        con.str(row, 1, "Declaration: I hereby certify that the above expenses were actually incurred by me wholly and exclusively for official purposes in connection with the above assignment, that the bills / supporting documents enclosed are genuine, and that no part of this claim has been reimbursed earlier or claimed from the client directly.", Style.NOTE)
        con.merge(row, 1, row, 5)
        con.height(row, 48.0)
        row += 2
        listOf("Claimed by (Employee)", "Verified by", "Approved by").forEachIndexed { i, s ->
            val col = when (i) { 0 -> 1; 1 -> 3; else -> 5 }
            con.str(row, col, s, Style.BOLD)
        }
        con.merge(row, 1, row, 2)
        row += 3
        con.str(row, 1, "Signature & Date: ________________", Style.NOTE); con.merge(row, 1, row, 2)
        con.str(row, 3, "________________", Style.NOTE); con.merge(row, 3, row, 4)
        con.str(row, 5, "________________", Style.NOTE)
        row++
        con.str(row, 1, e.name, Style.NOTE); con.merge(row, 1, row, 2)
        row += 2
        con.str(row, 1, "Supporting bills: see Annexure (${data.lines.size} item${if (data.lines.size == 1) "" else "s"}) and the combined invoices PDF. Files are named \"DDth Mon YYYY_Amount\".", Style.NOTE)
        con.merge(row, 1, row, 5)

        wb.write(out)
    }
}
