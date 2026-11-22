package com.tareimbursement.app.service

import com.tareimbursement.app.core.ClaimInfo
import com.tareimbursement.app.core.EmployeeInfo
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.FileNamer
import com.tareimbursement.app.core.OfficeText
import com.tareimbursement.app.core.StatementData
import com.tareimbursement.app.core.StatementLine
import com.tareimbursement.app.core.pdf.CombinedPdfBuilder
import com.tareimbursement.app.core.pdf.DocAttachment
import com.tareimbursement.app.core.pdf.DocPage
import com.tareimbursement.app.core.pdf.ExpenseDocs
import com.tareimbursement.app.core.pdf.JpegImage
import com.tareimbursement.app.core.pdf.PdfWriter
import com.tareimbursement.app.core.xlsx.TaStatementWorkbook
import com.tareimbursement.app.data.AttachmentEntity
import com.tareimbursement.app.data.AttachmentKind
import com.tareimbursement.app.data.EventType
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.TripEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ClaimOutputs(val excel: File, val pdf: File, val zip: File) {
    val all get() = listOf(excel, pdf, zip)
    fun exist() = all.all { it.exists() && it.length() > 0 }
}

/** Renders a page of an attachment to JPEG for the combined PDF. Overridable in tests. */
interface PageRenderer {
    fun pages(file: File, kind: AttachmentKind, originalName: String): List<DocPage>
}

class AndroidPageRenderer : PageRenderer {
    override fun pages(file: File, kind: AttachmentKind, originalName: String): List<DocPage> = when (kind) {
        AttachmentKind.IMAGE -> listOf(DocPage.Image { Media.decodeSampled(file, 1800)?.let { b -> JpegImage(Media.toJpeg(b)).also { b.recycle() } } })
        AttachmentKind.PDF -> {
            val n = Media.pdfPageCount(file)
            if (n <= 0) listOf(DocPage.Placeholder("The PDF \"$originalName\" is password-protected or damaged and could not be rendered. The original is included in the receipts ZIP."))
            else (0 until n.coerceAtMost(40)).map { i -> DocPage.Image { Media.renderPdfPage(file, i, 1400)?.let { b -> JpegImage(Media.toJpeg(b, 80)).also { b.recycle() } } } }
        }
        AttachmentKind.VIDEO -> listOf(DocPage.Image { Media.videoFrame(file, 1600)?.let { b -> JpegImage(Media.toJpeg(b)).also { b.recycle() } } })
        AttachmentKind.TEXT -> textPages(com.tareimbursement.app.core.readTextPrefix(file, 400_000).let { if (it.contains("<html", true)) OfficeText.htmlToText(it) else it })
        AttachmentKind.OTHER -> {
            val text = OfficeText.extract(file)
            if (text.isNotBlank()) textPages("[Text extracted from $originalName — original file in receipts ZIP]\n\n$text")
            else listOf(DocPage.Placeholder("\"$originalName\" cannot be previewed in a PDF. The original file is included in the receipts ZIP."))
        }
    }

    companion object {
        fun textPages(text: String): List<DocPage> {
            val lines = text.replace("\r", "").lines().flatMap { PdfWriter.wrap(it, 8.5f, 515f) }
            return listOf(DocPage.Text(lines.ifEmpty { listOf("(empty)") }))
        }
    }
}

class ExportManager(private val repo: Repository, private val renderer: PageRenderer = AndroidPageRenderer()) {

    fun outputsFor(trip: TripEntity): ClaimOutputs {
        val dir = repo.outputDir(trip.id)
        val base = FileNamer.safeFileName("${trip.claimNumber}_${trip.projectCode.ifBlank { trip.title }}")
        return ClaimOutputs(File(dir, "TA Statement_$base.xlsx"), File(dir, "Invoices & Supporting_$base.pdf"), File(dir, "Receipts_$base.zip"))
    }

    data class Prepared(val data: StatementData, val docs: List<ExpenseDocs>, val zipEntries: List<Pair<String, File>>)

    suspend fun prepare(tripId: Long, employee: EmployeeInfo, today: LocalDate = LocalDate.now()): Prepared {
        val trip = repo.trips.get(tripId) ?: throw IllegalStateException("Claim not found")
        val expenses = repo.expenses.forTrip(tripId)
            .sortedWith(compareBy<ExpenseEntity>({ it.dateEpochDay ?: Long.MAX_VALUE }, { (it.categoryEnum ?: ExpenseCategory.OTHER).ordinal }, { it.createdAt }))
        val attsByExpense = expenses.associate { it.id to repo.attachments.forExpense(it.id) }

        // Rename every file "DDth Mon YYYY_Amount" (with (1)/(2) for clashes) across the claim.
        val flat = expenses.flatMap { e -> attsByExpense.getValue(e.id).map { e to it } }
        val names = FileNamer.assignNames(flat, { it.first.date }, { it.first.amount }, { it.second.extension })
        val nameOf = HashMap<Long, String>()
        names.forEach { nameOf[it.item.second.id] = it.fileName }

        val lines = expenses.mapIndexed { i, e ->
            val atts = attsByExpense.getValue(e.id)
            val remarks = buildList {
                if (e.remarks.isNotBlank()) add(e.remarks.trim())
                if (atts.isEmpty() || e.noBill) add("No bill – self-declared")
                if (atts.size > 1) add("${atts.size} documents")
                if (e.paymentMode.isNotBlank()) add("Paid by ${e.paymentMode}")
            }.joinToString("; ")
            StatementLine(
                serial = i + 1, date = e.date, billNo = e.billNo.trim(), vendor = e.vendor.trim(), description = e.description.trim(),
                category = e.categoryEnum ?: e.suggestedEnum ?: ExpenseCategory.OTHER, amount = e.amount ?: 0.0,
                fileReference = atts.joinToString("; ") { nameOf[it.id] ?: it.originalName }, remarks = remarks,
            )
        }
        val data = StatementData(
            employee,
            ClaimInfo(trip.claimNumber, trip.title, trip.clientName, trip.projectCode, trip.location, trip.purpose, trip.startDate, trip.endDate, trip.advance, today),
            lines,
        )
        val docs = expenses.mapIndexed { i, e ->
            ExpenseDocs(lines[i], attsByExpense.getValue(e.id).map { a ->
                DocAttachment(nameOf[a.id] ?: a.originalName, if (a.isPrimary) "Invoice" else "Supporting", safePages(a))
            })
        }
        val zipEntries = expenses.flatMapIndexed { i, e ->
            attsByExpense.getValue(e.id).map { a -> "${lines[i].category.folderName}/${nameOf[a.id]}" to repo.fileOf(a) }
        }
        return Prepared(data, docs, zipEntries)
    }

    private fun safePages(a: AttachmentEntity): List<DocPage> {
        val f = repo.fileOf(a)
        if (!f.exists()) return listOf(DocPage.Placeholder("File \"${a.originalName}\" is missing from storage."))
        return try { renderer.pages(f, a.kindEnum, a.originalName) } catch (e: Throwable) {
            listOf(DocPage.Placeholder("\"${a.originalName}\" could not be rendered (${e.javaClass.simpleName})."))
        }
    }

    /** Generates the Excel TA statement, the combined PDF and the organised receipts ZIP. */
    suspend fun generate(tripId: Long, employee: EmployeeInfo, onStep: (String) -> Unit = {}): ClaimOutputs = withContext(Dispatchers.IO) {
        val trip = repo.trips.get(tripId) ?: throw IllegalStateException("Claim not found")
        onStep("Preparing statement…")
        val p = prepare(tripId, employee)
        val out = outputsFor(trip)
        repo.outputDir(tripId).listFiles()?.forEach { it.delete() }

        onStep("Writing Excel TA statement…")
        writeAtomically(out.excel) { TaStatementWorkbook.write(p.data, it) }
        onStep("Combining invoices into one PDF…")
        writeAtomically(out.pdf) { CombinedPdfBuilder.build(p.data, p.docs, it) }
        onStep("Packing renamed receipts…")
        writeAtomically(out.zip) { os ->
            ZipOutputStream(os).use { z ->
                p.zipEntries.forEach { (path, file) ->
                    if (!file.exists()) return@forEach
                    z.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
                z.putNextEntry(ZipEntry(out.excel.name)); out.excel.inputStream().use { it.copyTo(z) }; z.closeEntry()
                z.putNextEntry(ZipEntry(out.pdf.name)); out.pdf.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        repo.trips.update(trip.copy(outputsGeneratedAt = System.currentTimeMillis()))
        repo.logEvent(tripId, EventType.GENERATED, "Generated TA statement (₹%.2f, %d bills)".format(p.data.total, p.data.lines.size))
        out
    }

    private inline fun writeAtomically(target: File, block: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().buffered().use(block)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }
}
