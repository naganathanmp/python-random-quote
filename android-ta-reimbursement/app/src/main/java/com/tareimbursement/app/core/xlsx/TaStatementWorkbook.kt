package com.tareimbursement.app.core.xlsx

import com.tareimbursement.app.core.Currencies
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.StatementData
import com.tareimbursement.app.core.StatementLine
import com.tareimbursement.app.core.xlsx.XlsxWriter.XStyle
import java.io.OutputStream
import java.util.Locale

/**
 * Writes the TA statement in the firm's "TRAVEL CLAIM" template format:
 *
 *  - "TA CON-SHEET " – client / location / nature of audit, team members, audit start & end dates,
 *    total mandays, tickets booked by, the 14 accounting heads with their account numbers and
 *    SUMIF('Annexure-Breakup daywise'!B:B, head, …!C:C) totals, advance and balances, submission date.
 *  - "Annexure-Breakup daywise" – one row per bill: Date, Nature of Expenditure (head), Amount,
 *    Sup Bills Y/N, Remarks, Mode of travel, Team members name, File Name; total, comments,
 *    mandatory-field notes and the accounting-head guidance table.
 *  - Extras only when used: "Billed to" column + entity split table (split claims) and a
 *    "Team member-wise" sheet (several travellers).
 */
object TaStatementWorkbook {
    const val CON_SHEET = "TA CON-SHEET "
    const val ANNEXURE = "Annexure-Breakup daywise"
    const val TRAVELLERS = "Team member-wise"
    const val ANNEX_FIRST_ROW = 6

    private const val BLUE = "95B3D7"
    private const val TOTAL_BLUE = "8DB4E2"
    private const val GREY = "D9D9D9"
    private const val LIGHT = "F2F2F2"
    private const val GREEN = "D7E4BD"
    private const val ORANGE = "F79646"
    private const val ACC0 = "_ * #,##0_ ;_ * \\-#,##0_ ;_ * \"-\"??_ ;_ @_ "
    private const val ACC2 = "_ * #,##0.00_ ;_ * \\-#,##0.00_ ;_ * \"-\"??_ ;_ @_ "
    private const val CAMBRIA = "Cambria"

    private fun con(bold: Boolean = true, fill: String? = "FFFFFF", border: String? = "thin", h: String? = null, num: String? = null, wrap: Boolean = false) =
        XStyle(font = CAMBRIA, size = 12.0, bold = bold, fill = fill, border = border, hAlign = h, numFmt = num, wrap = wrap)

    private fun ann(bold: Boolean = false, size: Double = 11.0, fill: String? = null, border: String? = "thin", h: String? = null, num: String? = null, wrap: Boolean = false, font: String = CAMBRIA) =
        XStyle(font = font, size = size, bold = bold, fill = fill, border = border, hAlign = h, numFmt = num, wrap = wrap)

    private fun q(sheet: String) = "'" + sheet.replace("'", "''") + "'"
    private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')

    fun teamNames(data: StatementData) = data.travellers.joinToString(", ") { it.name }

    fun write(data: StatementData, out: OutputStream) {
        val wb = XlsxWriter()
        val conS = wb.sheet(CON_SHEET)
        val annS = wb.sheet(ANNEXURE)
        val annLast = writeAnnexure(annS, data)
        writeConSheet(conS, data, annLast)
        if (data.multiTraveller) writeTravellerSheet(wb.sheet(TRAVELLERS), data)
        wb.write(out)
    }

    // ------------------------------------------------------------------ CON-SHEET

    private fun writeConSheet(s: XlsxWriter.Sheet, d: StatementData, annLastRow: Int) {
        val c = d.claim
        listOf(28.5, 19.0, 39.5, 41.8, 61.3).forEachIndexed { i, w -> s.width(i + 1, w) }
        val label = con(); val value = con(h = "center", wrap = true); val valueLeft = con(h = "left", wrap = true)

        s.str(1, 1, "TRAVELLING EXPENSES STATEMENT", con(fill = BLUE, border = "medium", h = "center"))
        s.mergeStyled(1, 1, 1, 5, con(fill = BLUE, border = "medium", h = "center")); s.height(1, 44.25)

        val clientText = buildString {
            append(c.clientName)
            if (c.projectTitle.isNotBlank()) append("_").append(c.projectTitle)
            val code = d.billedTo?.code?.ifBlank { null } ?: c.projectCode
            if (code.isNotBlank()) append(" (").append(code).append(")")
            d.billedTo?.let { append(" – Billed to: ").append(it.name) }
        }
        s.str(2, 1, "CLIENT NAME :", label); s.str(2, 2, clientText, value); s.mergeStyled(2, 2, 2, 3, value)
        s.str(2, 4, "Team Members Name ", label); s.str(2, 5, teamNames(d), valueLeft); s.height(2, 42.75)
        (1..3).forEach { s.blank(3, it, label) }
        s.str(3, 4, "Audit Start Date       ", label); s.date(3, 5, c.startDate, con(h = "left", num = "d-mmm-yy")); s.height(3, 24.0)
        s.str(4, 1, "CLIENT LOCATION ", con(h = "left")); s.str(4, 2, c.placeOfVisit, value); s.mergeStyled(4, 2, 4, 3, value)
        s.str(4, 4, "Audit End Date ", label); s.date(4, 5, c.endDate, con(h = "left", num = "d-mmm-yy")); s.height(4, 29.25)
        s.blank(5, 1, label); s.mergeStyled(5, 1, 5, 3, label)
        s.str(5, 4, "Total Mandays ", label)
        s.formula(5, 5, "E4-E3", (c.endDate.toEpochDay() - c.startDate.toEpochDay()).toDouble(), con(h = "left")); s.height(5, 27.75)
        s.str(6, 1, "NATURE OF AUDIT :", label); s.str(6, 2, c.purpose, value); s.mergeStyled(6, 2, 6, 3, value); s.height(6, 30.75)
        s.str(7, 4, "To & Fro -Tickets Booked ", con(h = "center")); s.blank(7, 5, label)
        s.str(8, 4, "Onward booked by ", con(h = "center")); s.str(8, 5, c.onwardBookedBy, valueLeft)
        s.str(9, 4, "Return booked by ", con(h = "center")); s.str(9, 5, c.returnBookedBy, valueLeft)
        (7..9).forEach { s.height(it, 25.0) }
        s.height(10, 3.75)

        val hdr = con(fill = BLUE, h = "center", wrap = true)
        listOf("S.L.NO", "Accounting No.", "Accounting Heads ", "Consolidated Total Expenses ").forEachIndexed { i, t -> s.str(11, i + 1, t, hdr) }
        s.blank(11, 5, hdr); s.height(11, 30.0)
        var r = 12
        val annSheet = q(ANNEXURE)
        ExpenseCategory.entries.forEachIndexed { i, cat ->
            val fill = if (cat.restricted) GREY else "FFFFFF"
            s.num(r, 1, (i + 1).toDouble(), con(fill = fill, h = "left"))
            s.num(r, 2, cat.accountCode.toDouble(), con(fill = fill, h = "left"))
            s.str(r, 3, cat.templateLabel, con(fill = fill))
            s.formula(r, 4, "SUMIF($annSheet!B:B,${q(CON_SHEET)}!C$r,$annSheet!C:C)", d.categoryTotal(cat), con(fill = fill, num = ACC0))
            s.blank(r, 5, con(fill = fill))
            s.height(r, 29.25)
            r++
        }
        val total = con(fill = TOTAL_BLUE, num = ACC0)
        (1..3).forEach { s.blank(26, it, total) }
        s.str(26, 4, "Total Expenses ", con(fill = TOTAL_BLUE, h = "right"))
        s.formula(26, 5, "SUM(D12:D25)", d.total, total)
        val money = con(fill = LIGHT, num = ACC2)
        s.str(28, 4, "Advance Amount:", con(h = "left")); s.num(28, 5, c.advance, money)
        s.str(29, 4, "Total Expenses ", con(h = "left")); s.formula(29, 5, "E26", d.total, con(fill = LIGHT, num = "0.00"))
        s.str(30, 4, "Balance to repay office ", label); s.formula(30, 5, "MAX(E28-E29,0)", maxOf(c.advance - d.total, 0.0), money)
        s.str(31, 4, "Balance to receive from office ", label); s.formula(31, 5, "MAX(E29-E28,0)", maxOf(d.total - c.advance, 0.0), money)
        s.str(32, 4, "Submission Date :", label); s.date(32, 5, c.submittedOn ?: c.preparedOn, con(h = "left", num = "d-mmm-yy"))
        s.str(33, 4, "Signature :", label); s.blank(33, 5, label)
        s.height(33, 36.0)

        if (d.isSplit) {
            var row = 36
            val h2 = con(fill = BLUE, h = "center", wrap = true)
            s.str(row, 1, "Billing split by client entity", h2); s.mergeStyled(row, 1, row, 5, h2); row++
            listOf("S.L.NO", "Client Entity / Code", "", "Amount", "Share of claim").forEachIndexed { i, t -> s.str(row, i + 1, t, h2) }
            s.mergeStyled(row, 2, row, 3, h2); row++
            val annI = "$annSheet!\$I\$$ANNEX_FIRST_ROW:\$I\$$annLastRow"
            val annC = "$annSheet!\$C\$$ANNEX_FIRST_ROW:\$C\$$annLastRow"
            val first = row
            d.entities.forEachIndexed { i, en ->
                s.num(row, 1, (i + 1).toDouble(), con(h = "left"))
                s.str(row, 2, en.label, con()); s.mergeStyled(row, 2, row, 3, con())
                s.formula(row, 4, "SUMIF($annI,B$row,$annC)", d.entityTotal(en.label), con(num = ACC0))
                val share = if (d.total > 0) d.entityTotal(en.label) / d.total * 100 else 0.0
                s.str(row, 5, "%.1f%%".format(Locale.ENGLISH, share) + if (en.sharePercent > 0) " (target ${"%.1f".format(Locale.ENGLISH, en.sharePercent)}%)" else "", con(bold = false))
                row++
            }
            s.blank(row, 1, total); s.str(row, 2, "Total", con(fill = TOTAL_BLUE, h = "right")); s.mergeStyled(row, 2, row, 3, con(fill = TOTAL_BLUE))
            s.formula(row, 4, "SUM(D$first:D${row - 1})", d.total, total); s.blank(row, 5, total)
        }
        s.landscape = false // portrait, fit to one page width — as in the template
    }

    // ------------------------------------------------------------------ Annexure

    private fun remark(l: StatementLine): String = buildString {
        append(l.description.ifBlank { l.vendor })
        if (l.remarks.isNotBlank()) append(" – ").append(l.remarks)
        if (l.isForeign && l.exchangeRate != null) append(" [${Currencies.format(l.foreignAmount!!, l.currency)} @ ₹${num(l.exchangeRate)}]")
        if (l.billNo.isNotBlank()) append(" (Bill ${l.billNo})")
    }

    /** Returns the last data row (used by the CON-SHEET entity split). */
    private fun writeAnnexure(s: XlsxWriter.Sheet, d: StatementData): Int {
        val c = d.claim
        val split = d.isSplit
        val widths = mutableListOf(28.2, 34.3, 18.2, 15.0, 98.4, 46.2, 33.2, 63.9)
        if (split) widths += 34.0
        widths.forEachIndexed { i, w -> s.width(i + 1, w) }
        val last = widths.size

        s.str(1, 1, "ANNEXURE", ann(bold = true, fill = BLUE, h = "center")); s.mergeStyled(1, 1, 1, 7, ann(bold = true, fill = BLUE, h = "center")); s.height(1, 36.0)
        val lb = ann(bold = true, fill = "FFFFFF", h = "left"); val lv = ann(bold = true, fill = "FFFFFF", h = "left", wrap = true)
        s.str(2, 1, "Name. of the team members", lb); s.str(2, 2, teamNames(d), lv); s.height(2, 19.5)
        s.str(3, 1, "Client Location Visited", lb); s.str(3, 2, c.placeOfVisit, lv); s.height(3, 18.75)
        s.str(4, 1, "Nature of Audit  ", lb); s.str(4, 2, c.purpose, lv); s.height(4, 21.0)
        val hdr = ann(bold = true, fill = "FFFFFF", wrap = true)
        val headers = mutableListOf("Date", "Nature of Expenditure", "Amount ", "Sup Bills  Y/N", "Remarks -Mandatory ", " Mode of travel-Mandatory ", "Team members name ", "File Name")
        if (split) headers += "Billed to (Client Entity)"
        headers.forEachIndexed { i, h -> s.str(5, i + 1, h, if (i == 7) hdr.copy(hAlign = "center") else hdr) }
        s.height(5, 27.75)
        s.freezeRows = 5

        var r = ANNEX_FIRST_ROW
        val cell = ann(); val wrapCell = ann(wrap = true)
        val amountStyle = ann(font = "Book Antiqua", num = ACC0, wrap = true)
        d.lines.forEach { l ->
            s.date(r, 1, l.date, ann(h = "left", num = "mm-dd-yy"))
            s.str(r, 2, l.category.templateLabel, ann(bold = true, size = 12.0, fill = "FFFFFF"))
            when {
                l.isMileage -> s.formula(r, 3, "(${num(l.mileageKm!!)}*${num(l.mileageRate!!)})" + if ((l.toll ?: 0.0) > 0) "+${num(l.toll!!)}" else "", l.amount, amountStyle)
                l.isForeign && l.exchangeRate != null -> s.formula(r, 3, "ROUND(${num(l.foreignAmount!!)}*${num(l.exchangeRate)},2)", l.amount, amountStyle)
                else -> s.num(r, 3, l.amount, amountStyle)
            }
            s.str(r, 4, if (l.billAvailable) "Y" else "N", ann(h = "left", wrap = true))
            s.str(r, 5, remark(l), wrapCell.copy(font = "Book Antiqua"))
            s.str(r, 6, l.modeOfTravel, cell)
            s.str(r, 7, l.teamMembers, wrapCell)
            s.str(r, 8, l.fileReference, wrapCell)
            if (split) s.str(r, 9, l.entity, wrapCell)
            s.height(r, 20.15)
            r++
        }
        val lastData = maxOf(r - 1, ANNEX_FIRST_ROW)
        // one empty bordered row, then the total (as in the template: data, blank, Total)
        (1..last).forEach { s.blank(r, it, cell) }
        val blankRow = r
        r++
        s.blank(r, 1, cell)
        s.str(r, 2, "Total", ann(bold = true, h = "right"))
        s.formula(r, 3, "SUM(C$ANNEX_FIRST_ROW:C$blankRow)", d.total, ann(bold = true, h = "right", num = ACC0))
        (4..last).forEach { s.blank(r, it, cell) }
        r += 2

        val green = ann(bold = true, fill = GREEN, h = "center")
        s.str(r, 1, "Comments If any ", green); s.mergeStyled(r, 1, r, 5, green); r++
        val commentStyle = ann(fill = "FFFFFF", wrap = true, h = "left").copy(vAlign = "top")
        s.str(r, 1, c.comments, commentStyle); s.mergeStyled(r, 1, r + 2, 5, commentStyle); r += 4

        s.str(r, 1, "Mandatory Fields ", green); s.mergeStyled(r, 1, r, 7, green); r++
        val note = ann(bold = true, fill = LIGHT)
        listOf(
            "Bill missed / lost for huge amount mention clear details in the remarks ",
            "Please update \"YES\"  for bills available and \"NO\" for not available bills ",
            "Accounting heads highlighted in Grey colour are not applicable for all ",
            "Incase any of your team member joined from other clients directly or travelled directly to some other client location mention details below comments ",
            "Tickets Booked - Mandatory to mention whether booked by Office/ Client or Self booking ",
            "Client Location Visited - Mandatory to mention the name of the place ",
        ).forEach { t -> s.str(r, 1, t, note); r++ }
        ExpenseCategory.entries.filter { it.restricted }.forEach { cat ->
            s.num(r, 1, cat.accountCode.toDouble(), ann(fill = GREEN, h = "left"))
            s.str(r, 2, cat.templateLabel, ann(fill = GREEN))
            s.str(r, 3, cat.guidance, ann(fill = ORANGE))
            r++
        }
        d.provenance?.let { p ->
            r++
            s.str(r, 1, "Declaration & authenticity", green); s.mergeStyled(r, 1, r, 7, green); r++
            val para = ann(fill = "FFFFFF", wrap = true, h = "left").copy(vAlign = "top")
            p.paragraphs().forEach { t ->
                s.str(r, 1, t, para); s.mergeStyled(r, 1, r, 7, para)
                s.height(r, 18.0 * (1 + t.length / 190)); r++
            }
        }
        s.landscape = true
        return lastData
    }

    // ------------------------------------------------------------------ Team member-wise (extra)

    private fun writeTravellerSheet(s: XlsxWriter.Sheet, data: StatementData) {
        val heads = ExpenseCategory.entries.filter { cat -> data.lines.any { it.category == cat } }
        val lastCol = 2 + heads.size + 1
        s.landscape = true
        s.width(1, 8.0); s.width(2, 30.0)
        heads.indices.forEach { s.width(3 + it, 18.0) }
        s.width(lastCol, 18.0)
        val title = ann(bold = true, fill = BLUE, h = "center")
        s.str(1, 1, "TEAM MEMBER-WISE SHARE OF EXPENSES", title); s.mergeStyled(1, 1, 1, lastCol, title); s.height(1, 30.0)
        s.str(2, 1, "Each bill is shared between the team members as recorded in the Annexure (Team members name).", ann(border = null)); s.merge(2, 1, 2, lastCol)
        val hdr = ann(bold = true, fill = BLUE, h = "center", wrap = true)
        s.str(4, 1, "S.L.NO", hdr); s.str(4, 2, "Team member", hdr)
        heads.forEachIndexed { i, h -> s.str(4, 3 + i, h.templateLabel.trim(), hdr) }
        s.str(4, lastCol, "Total", hdr); s.height(4, 36.0)
        var r = 5
        val money = ann(num = ACC2, font = "Book Antiqua")
        data.travellers.forEachIndexed { i, t ->
            s.num(r, 1, (i + 1).toDouble(), ann(h = "left")); s.str(r, 2, t.label, ann(bold = true))
            heads.forEachIndexed { hi, h -> s.num(r, 3 + hi, data.travellerHeadTotal(t.label, h), money) }
            val a = XlsxWriter.colName(3); val b = XlsxWriter.colName(2 + heads.size)
            s.formula(r, lastCol, if (heads.isEmpty()) "0" else "SUM($a$r:$b$r)", data.travellerTotal(t.label), money.copy(bold = true))
            r++
        }
        val tot = ann(bold = true, fill = TOTAL_BLUE, num = ACC2)
        s.blank(r, 1, tot); s.str(r, 2, "Total", tot.copy(hAlign = "right"))
        (3..lastCol).forEach { ci ->
            val cl = XlsxWriter.colName(ci)
            val cached = if (ci == lastCol) data.travellers.sumOf { data.travellerTotal(it.label) } else data.travellers.sumOf { data.travellerHeadTotal(it.label, heads[ci - 3]) }
            s.formula(r, ci, "SUM(${cl}5:$cl${r - 1})", cached, tot)
        }
    }
}
