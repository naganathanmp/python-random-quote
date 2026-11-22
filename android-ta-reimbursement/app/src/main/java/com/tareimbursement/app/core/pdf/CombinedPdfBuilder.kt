package com.tareimbursement.app.core.pdf

import com.tareimbursement.app.core.AmountWords
import com.tareimbursement.app.core.FileNamer
import com.tareimbursement.app.core.StatementData
import com.tareimbursement.app.core.StatementLine
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One rendered page of an attachment. Image bytes are loaded lazily to keep memory low. */
sealed class DocPage {
    class Image(val load: () -> JpegImage?) : DocPage()
    class Text(val lines: List<String>) : DocPage()
    class Placeholder(val message: String) : DocPage()
}

data class DocAttachment(val fileName: String, val label: String, val pages: List<DocPage>)

data class ExpenseDocs(val line: StatementLine, val attachments: List<DocAttachment>)

/**
 * Produces the single, clean PDF of all invoices & supporting documents: a cover/index page
 * listing every bill with its page number, then each bill's documents with a header strip
 * (S.No · date · head · amount) so reviewers can tick them off against the Annexure.
 */
object CombinedPdfBuilder {
    private const val M = 36f
    private const val NAVY = 0x1F3864
    private const val GREY = 0x595959
    private const val LIGHT = 0xDDEBF7
    private val dmy = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    private const val ROWS_FIRST = 26
    private const val ROWS_NEXT = 40
    private const val TEXT_LINES_PER_PAGE = 58

    private data class Planned(val expense: ExpenseDocs, val attachment: DocAttachment?, val attIndex: Int, val page: DocPage, val pageInAtt: Int, val pagesInAtt: Int)

    fun build(data: StatementData, docs: List<ExpenseDocs>, out: OutputStream) {
        // Expand text pages into page-sized chunks and give attachment-less bills a placeholder.
        val planned = mutableListOf<Planned>()
        docs.forEach { ed ->
            if (ed.attachments.isEmpty()) {
                planned += Planned(ed, null, 0, DocPage.Placeholder("No bill / supporting document attached for this expense (self-declared)."), 1, 1)
            }
            ed.attachments.forEachIndexed { ai, att ->
                val pages = att.pages.flatMap { p ->
                    if (p is DocPage.Text && p.lines.size > TEXT_LINES_PER_PAGE) p.lines.chunked(TEXT_LINES_PER_PAGE).map { DocPage.Text(it) } else listOf(p)
                }.ifEmpty { listOf(DocPage.Placeholder("This file could not be previewed. The original is included in the receipts ZIP.")) }
                pages.forEachIndexed { pi, p -> planned += Planned(ed, att, ai, p, pi + 1, pages.size) }
            }
        }
        val rows = docs.size
        val coverPages = if (rows <= ROWS_FIRST) 1 else 1 + Math.ceil((rows - ROWS_FIRST) / ROWS_NEXT.toDouble()).toInt()
        val totalPages = coverPages + planned.size
        val firstPageOf = mutableMapOf<Int, Int>()
        planned.forEachIndexed { i, p -> firstPageOf.putIfAbsent(p.expense.line.serial, coverPages + i + 1) }

        val pdf = PdfWriter(out)
        writeCover(pdf, data, docs, firstPageOf, coverPages, totalPages)
        planned.forEachIndexed { i, p -> writeDocPage(pdf, data, p, coverPages + i + 1, totalPages) }
        pdf.close("TA Invoices - ${data.claim.claimNumber}")
    }

    private fun footer(pg: PdfWriter.Page, data: StatementData, n: Int, total: Int) {
        val y = PdfWriter.A4_H - 20f
        pg.line(M, y - 10f, PdfWriter.A4_W - M, y - 10f, 0.4f, 0xBFBFBF)
        pg.text(M, y, PdfWriter.fit("${data.claim.claimNumber}  |  ${data.employee.name}  |  ${data.claim.projectTitle} [${data.claim.projectCode}]", 7.5f, 420f), 7.5f, rgb = GREY)
        val s = "Page $n of $total"
        pg.text(PdfWriter.A4_W - M - PdfWriter.textWidth(s, 7.5f), y, s, 7.5f, rgb = GREY)
    }

    private fun writeCover(pdf: PdfWriter, data: StatementData, docs: List<ExpenseDocs>, firstPageOf: Map<Int, Int>, coverPages: Int, totalPages: Int) {
        val c = data.claim; val e = data.employee
        val colX = floatArrayOf(M, M + 30f, M + 100f, M + 230f, M + 400f, M + 478f)
        val right = PdfWriter.A4_W - M
        var idx = 0
        for (cp in 1..coverPages) {
            val pg = pdf.beginPage()
            var y: Float
            if (cp == 1) {
                pg.rect(0f, 0f, PdfWriter.A4_W, 86f, fill = NAVY)
                pg.text(M, 34f, PdfWriter.fit(e.organisation.ifBlank { "Travel Reimbursement" }, 16f, 520f, true), 16f, true, 0xFFFFFF)
                pg.text(M, 56f, "TA Claim - Invoices & Supporting Documents", 12f, false, 0xFFFFFF)
                pg.text(M, 74f, "Claim No: ${c.claimNumber}    Prepared on: ${c.preparedOn.format(dmy)}", 9f, false, 0xDDEBF7)
                y = 108f
                val info = listOf(
                    "Employee" to "${e.name} (${e.employeeCode})${if (e.designation.isNotBlank()) ", ${e.designation}" else ""}",
                    "Client" to c.clientName,
                    "Project / Assignment" to "${c.projectTitle}  [${c.projectCode}]",
                    "Place of visit" to c.placeOfVisit,
                    "Purpose" to c.purpose,
                    "Period" to "${c.startDate.format(dmy)} to ${c.endDate.format(dmy)} (${data.days} days)",
                )
                info.forEach { (k, v) ->
                    pg.text(M, y, k, 9f, true, GREY)
                    pg.text(M + 120f, y, PdfWriter.fit(v, 9.5f, right - M - 120f), 9.5f)
                    y += 15f
                }
                y += 6f
                // Totals strip
                pg.rect(M, y, right - M, 40f, fill = LIGHT)
                val boxes = listOf("Total claimed" to AmountWords.inr(data.total), "Advance" to AmountWords.inr(c.advance), (if (data.netPayable >= 0) "Net payable" else "Refundable") to AmountWords.inr(Math.abs(data.netPayable)), "Bills" to docs.size.toString())
                val bw = (right - M) / boxes.size
                boxes.forEachIndexed { i, (k, v) ->
                    pg.text(M + 10f + i * bw, y + 15f, k, 8f, false, GREY)
                    pg.text(M + 10f + i * bw, y + 31f, v, 11f, true, NAVY)
                }
                y += 58f
                pg.text(M, y, "Index of bills", 11f, true, NAVY)
                y += 10f
            } else {
                pg.text(M, 40f, "Index of bills (continued)", 11f, true, NAVY)
                y = 50f
            }
            // table header
            pg.rect(M, y, right - M, 18f, fill = NAVY)
            listOf("#", "Date", "Head", "Vendor / Particulars", "Amount", "Page").forEachIndexed { i, h ->
                pg.text(colX[i] + 3f, y + 12.5f, h, 8.5f, true, 0xFFFFFF)
            }
            y += 18f
            val take = if (cp == 1) ROWS_FIRST else ROWS_NEXT
            repeat(take) {
                if (idx >= docs.size) return@repeat
                val l = docs[idx].line
                if (idx % 2 == 1) pg.rect(M, y, right - M, 16f, fill = 0xF3F6FA)
                pg.text(colX[0] + 3f, y + 11.5f, l.serial.toString(), 8.5f)
                pg.text(colX[1] + 3f, y + 11.5f, l.date?.format(dmy) ?: "-", 8.5f)
                pg.text(colX[2] + 3f, y + 11.5f, PdfWriter.fit(l.category.label, 8.5f, 124f), 8.5f)
                pg.text(colX[3] + 3f, y + 11.5f, PdfWriter.fit(l.vendor.ifBlank { l.description }, 8.5f, 164f), 8.5f)
                val amt = AmountWords.inr(l.amount)
                pg.text(colX[5] - 6f - PdfWriter.textWidth(amt, 8.5f), y + 11.5f, amt, 8.5f)
                pg.text(colX[5] + 3f, y + 11.5f, (firstPageOf[l.serial] ?: 0).toString(), 8.5f)
                y += 16f
                idx++
            }
            pg.line(M, y, right, y, 0.6f, NAVY)
            if (idx >= docs.size) {
                y += 14f
                val t = "Total: ${AmountWords.inr(data.total)}"
                pg.text(colX[5] - 6f - PdfWriter.textWidth(t, 9.5f, true), y, t, 9.5f, true, NAVY)
                y += 14f
                pg.text(M, y, PdfWriter.fit(AmountWords.rupees(data.total), 8.5f, right - M), 8.5f, rgb = GREY)
            }
            footer(pg, data, cp, totalPages)
            pdf.endPage(pg)
        }
    }

    private fun writeDocPage(pdf: PdfWriter, data: StatementData, p: Planned, n: Int, total: Int) {
        val pg = pdf.beginPage()
        val l = p.expense.line
        val right = PdfWriter.A4_W - M
        pg.rect(M, 22f, right - M, 40f, fill = LIGHT)
        val head = "#${l.serial}   ${l.date?.let { FileNamer.formatDate(it) } ?: "Undated"}   |   ${l.category.label}"
        pg.text(M + 8f, 37f, PdfWriter.fit(head, 10.5f, 380f, true), 10.5f, true, NAVY)
        val amt = AmountWords.inr(l.amount)
        pg.text(right - 8f - PdfWriter.textWidth(amt, 12f, true), 38f, amt, 12f, true, NAVY)
        val sub = buildString {
            append(l.vendor.ifBlank { l.description.ifBlank { "-" } })
            if (l.billNo.isNotBlank()) append("   |   Bill: ${l.billNo}")
            if (p.attachment != null) {
                append("   |   ${p.attachment.label} ${p.attIndex + 1}/${p.expense.attachments.size}")
                if (p.pagesInAtt > 1) append(" (page ${p.pageInAtt}/${p.pagesInAtt})")
                append(":  ${p.attachment.fileName}")
            }
        }
        pg.text(M + 8f, 54f, PdfWriter.fit(sub, 8.5f, right - M - 16f), 8.5f, rgb = GREY)

        val top = 74f; val bottom = PdfWriter.A4_H - 40f
        val areaW = right - M; val areaH = bottom - top
        when (val page = p.page) {
            is DocPage.Image -> {
                val img = try { page.load() } catch (e: Throwable) { null }
                if (img == null) placeholder(pg, top, "This page could not be rendered. The original file is included in the receipts ZIP.")
                else {
                    val scale = minOf(areaW / img.width, areaH / img.height)
                    val w = img.width * scale; val h = img.height * scale
                    val x = M + (areaW - w) / 2; val y = top + (areaH - h) / 2
                    pg.image(img, x, y, w, h)
                    pg.rect(x, y, w, h, stroke = 0xBFBFBF, lineWidth = 0.4f)
                }
            }
            is DocPage.Text -> {
                var y = top + 12f
                page.lines.forEach { line ->
                    pg.text(M + 4f, y, PdfWriter.fit(line, 8.5f, areaW - 8f), 8.5f)
                    y += 12f
                }
            }
            is DocPage.Placeholder -> placeholder(pg, top, page.message)
        }
        footer(pg, data, n, total)
        pdf.endPage(pg)
    }

    private fun placeholder(pg: PdfWriter.Page, top: Float, msg: String) {
        val right = PdfWriter.A4_W - M
        pg.rect(M, top + 40f, right - M, 80f, fill = 0xFAFAFA, stroke = 0xBFBFBF)
        var y = top + 70f
        PdfWriter.wrap(msg, 10f, right - M - 30f).forEach { pg.text(M + 15f, y, it, 10f, rgb = GREY); y += 14f }
    }
}
