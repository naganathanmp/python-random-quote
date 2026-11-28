package com.tareimbursement.app.service

import com.tareimbursement.app.core.ClaimInfo
import com.tareimbursement.app.core.EntityInfo
import com.tareimbursement.app.core.Shares
import com.tareimbursement.app.core.TravellerInfo
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
import com.tareimbursement.app.data.SplitMode
import com.tareimbursement.app.data.TripEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tareimbursement.app.core.Provenance
import com.tareimbursement.app.core.sign.DocSigner
import com.tareimbursement.app.core.sign.PdfSigner
import com.tareimbursement.app.core.sign.Signing
import java.io.File
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ClaimOutputs(val excel: File, val pdf: File, val zip: File, val extras: List<File> = emptyList()) {
    /** Consolidated outputs first, then per-entity statements / PDFs. */
    val all get() = listOf(excel, pdf, zip) + extras
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

class ExportManager(
    private val repo: Repository,
    private val renderer: PageRenderer = AndroidPageRenderer(),
    /** Signing key + certificate for the person (null = outputs are not signed). */
    private val identity: (EmployeeInfo) -> Pair<DocSigner, X509Certificate>? = { null },
    private val appVersion: String = "",
) {

    fun outputsFor(trip: TripEntity): ClaimOutputs {
        val dir = repo.outputDir(trip.id)
        val base = FileNamer.safeFileName("${trip.claimNumber}_${trip.projectCode.ifBlank { trip.title }}")
        val main = listOf(File(dir, "TA Statement_$base.xlsx"), File(dir, "Invoices & Supporting_$base.pdf"), File(dir, "Receipts_$base.zip"))
        val extras = dir.listFiles()?.filter { f -> f.isFile && !f.name.endsWith(".tmp") && main.none { it.name == f.name } }?.sortedBy { it.name }.orEmpty()
        return ClaimOutputs(main[0], main[1], main[2], extras)
    }

    data class Prepared(
        val data: StatementData,
        val docs: List<ExpenseDocs>,
        /** (category folder / renamed file name, source file, entity label) */
        val zipEntries: List<Triple<String, File, String>>,
    )

    suspend fun prepare(tripId: Long, employee: EmployeeInfo, today: LocalDate = LocalDate.now(), signer: String? = null): Prepared {
        val trip = repo.trips.get(tripId) ?: throw IllegalStateException("Claim not found")
        val expenses = repo.expenses.forTrip(tripId)
            .sortedWith(compareBy<ExpenseEntity>({ it.dateEpochDay ?: Long.MAX_VALUE }, { (it.categoryEnum ?: ExpenseCategory.OTHER).ordinal }, { it.createdAt }))
        val attsByExpense = expenses.associate { it.id to repo.attachments.forExpense(it.id) }
        val people = repo.travellers.forTrip(tripId)
        val labelOf = people.associate { it.id to it.label }
        val ents = if (trip.split != SplitMode.NONE) repo.entities.forTrip(tripId) else emptyList()
        val entLabel = ents.associate { it.id to it.label }

        // Rename every file "DDth Mon YYYY_Amount" (with (1)/(2) for clashes) across the claim.
        val flat = expenses.flatMap { e -> attsByExpense.getValue(e.id).map { e to it } }
        val names = FileNamer.assignNames(flat, { it.first.date }, { it.first.amount }, { it.second.extension })
        val nameOf = HashMap<Long, String>()
        names.forEach { nameOf[it.item.second.id] = it.fileName }

        val lines = expenses.mapIndexed { i, e ->
            val atts = attsByExpense.getValue(e.id)
            // One remarks field everywhere: the mandatory remarks (description). "Sup Bills Y/N" already shows a missing bill.
            val remarks = ""
            val parsedShares = Shares.parse(e.shares)
            val shares = if (people.size > 1) parsedShares.mapNotNull { (id, v) -> labelOf[id]?.let { it to v } } else emptyList()
            val personName = people.associate { it.id to it.name }
            val team = when {
                people.size <= 1 -> people.firstOrNull()?.name ?: employee.name
                parsedShares.isEmpty() -> people.joinToString(", ") { it.name }
                parsedShares.values.let { v -> v.max() - v.min() <= 0.011 } -> parsedShares.keys.mapNotNull { personName[it] }.joinToString(", ")
                else -> parsedShares.entries.mapNotNull { (id, v) -> personName[id]?.let { "$it ${"%,.2f".format(java.util.Locale.ENGLISH, v)}" } }.joinToString(", ")
            }
            StatementLine(
                serial = i + 1, date = e.date, billNo = e.billNo.trim(), vendor = e.vendor.trim(), description = e.description.trim(),
                category = e.categoryEnum ?: e.suggestedEnum ?: ExpenseCategory.OTHER, amount = e.amount ?: 0.0,
                fileReference = atts.joinToString("; ") { nameOf[it.id] ?: it.originalName }, remarks = remarks,
                currency = e.currency, foreignAmount = e.foreignAmount, exchangeRate = e.exchangeRate,
                entity = e.entityId?.let { entLabel[it] }.orEmpty(), shares = shares,
                modeOfTravel = e.modeOfTravel, billAvailable = atts.isNotEmpty() && !e.noBill,
                mileageKm = e.mileageKm, mileageRate = e.mileageRate, toll = e.tollAmount, teamMembers = team,
            )
        }
        val travellers = people.map { TravellerInfo(it.name, it.employeeCode) }.ifEmpty { listOf(TravellerInfo(employee.name, employee.employeeCode)) }
        val data = StatementData(
            employee,
            ClaimInfo(trip.claimNumber, trip.title, trip.clientName, trip.projectCode, trip.location, trip.purpose, trip.startDate, trip.endDate, trip.advance, today,
                onwardBookedBy = trip.onwardBookedBy, returnBookedBy = trip.returnBookedBy, comments = trip.notes,
                submittedOn = trip.submittedAt?.let { Repository.toDate(it) }),
            lines,
            travellers = travellers,
            entities = ents.map { EntityInfo(it.name, it.code, it.sharePercent) },
            provenance = Provenance(
                documentId = trip.claimNumber + "-" + documentSuffix(),
                preparedBy = if (employee.employeeCode.isBlank()) employee.name else "${employee.name} (${employee.employeeCode})",
                preparedAt = java.time.LocalDateTime.now(), appVersion = appVersion,
                aiAssisted = expenses.any { it.aiState == "DONE" },
                flaggedKept = expenses.mapIndexedNotNull { i, e -> e.trustAck.takeIf { it.isNotBlank() }?.let { "#${i + 1} ${it.take(80)}" } },
                signer = signer,
            ),
        )
        val docs = expenses.mapIndexed { i, e ->
            ExpenseDocs(lines[i], attsByExpense.getValue(e.id).map { a ->
                DocAttachment(nameOf[a.id] ?: a.originalName, if (a.isPrimary) "Invoice" else "Supporting", safePages(a))
            })
        }
        val zipEntries = expenses.flatMapIndexed { i, e ->
            attsByExpense.getValue(e.id).map { a -> Triple("${lines[i].category.folderName}/${nameOf[a.id]}", repo.fileOf(a), lines[i].entity) }
        }
        return Prepared(data, docs, zipEntries)
    }

    /** The statement & documents for one client entity: its bills only, renumbered, advance pro-rated. */
    fun forEntity(p: Prepared, entity: EntityInfo): Pair<StatementData, List<ExpenseDocs>> {
        val docs = p.docs.filter { it.line.entity == entity.label }.mapIndexed { i, d -> d.copy(line = d.line.copy(serial = i + 1)) }
        val lines = docs.map { it.line }
        val total = p.data.total
        val share = if (total > 0) lines.sumOf { it.amount } / total else 0.0
        val advance = Math.round(p.data.claim.advance * share * 100) / 100.0
        val data = p.data.copy(claim = p.data.claim.copy(advance = advance), lines = lines, billedTo = entity)
        return data to docs
    }

    private fun safePages(a: AttachmentEntity): List<DocPage> {
        val f = repo.fileOf(a)
        if (!f.exists()) return listOf(DocPage.Placeholder("File \"${a.originalName}\" is missing from storage."))
        return try { renderer.pages(f, a.kindEnum, a.originalName) } catch (e: Throwable) {
            listOf(DocPage.Placeholder("\"${a.originalName}\" could not be rendered (${e.javaClass.simpleName})."))
        }
    }

    /**
     * Generates the Excel TA statement, the combined PDF and the organised receipts ZIP. For claims
     * split across client entities, each entity also gets its own statement and PDF, and the ZIP has
     * one folder per entity.
     */
    suspend fun generate(tripId: Long, employee: EmployeeInfo, onStep: (String) -> Unit = {}): ClaimOutputs = withContext(Dispatchers.IO) {
        val trip0 = repo.trips.get(tripId) ?: throw IllegalStateException("Claim not found")
        if (trip0.split == SplitMode.AUTO) { onStep("Splitting bills across client entities…"); repo.autoAllocate(tripId) }
        val trip = repo.trips.get(tripId)!!
        onStep("Preparing statement…")
        val id = try { identity(employee) } catch (e: Throwable) { null }
        val signerName = id?.second?.subjectX500Principal?.name?.let { Regex("CN=([^,]+)").find(it)?.groupValues?.get(1) }
        val p = prepare(tripId, employee, signer = signerName)
        val out = outputsFor(trip)
        repo.outputDir(tripId).listFiles()?.forEach { it.delete() }

        onStep("Writing Excel TA statement…")
        writeAtomically(out.excel) { TaStatementWorkbook.write(p.data, it) }
        onStep("Combining invoices into one PDF…")
        writeAtomically(out.pdf) { CombinedPdfBuilder.build(p.data, p.docs, it) }
        if (id != null) { onStep("Signing digitally…"); signOutputs(id, trip.claimNumber, out.excel, out.pdf) }

        val perEntity = mutableListOf<Triple<EntityInfo, File, File>>()
        if (p.data.isSplit) {
            val dir = repo.outputDir(tripId)
            p.data.entities.forEach { en ->
                val (data, docs) = forEntity(p, en)
                if (data.lines.isEmpty()) return@forEach
                val tag = FileNamer.safeFileName("${trip.claimNumber}_${en.code.ifBlank { en.name }}")
                onStep("Statement for ${en.name}…")
                val x = File(dir, "TA Statement_$tag.xlsx"); val pdf = File(dir, "Invoices & Supporting_$tag.pdf")
                writeAtomically(x) { TaStatementWorkbook.write(data, it) }
                writeAtomically(pdf) { CombinedPdfBuilder.build(data, docs, it) }
                if (id != null) signOutputs(id, trip.claimNumber, x, pdf)
                perEntity += Triple(en, x, pdf)
            }
        }

        onStep("Packing renamed receipts…")
        writeAtomically(out.zip) { os ->
            ZipOutputStream(os).use { z ->
                fun put(path: String, f: File) { if (f.exists()) { z.putNextEntry(ZipEntry(path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
                if (p.data.isSplit) {
                    p.zipEntries.forEach { (path, file, ent) ->
                        val folder = FileNamer.safeFileName(ent.ifBlank { "Unassigned" })
                        put("$folder/$path", file)
                    }
                    perEntity.forEach { (en, x, pdf) ->
                        val folder = FileNamer.safeFileName(en.label)
                        put("$folder/${x.name}", x); put("$folder/${pdf.name}", pdf)
                        signatureOf(x).let { sig -> put("$folder/${sig.name}", sig) }
                    }
                } else p.zipEntries.forEach { (path, file, _) -> put(path, file) }
                put(out.excel.name, out.excel)
                put(out.pdf.name, out.pdf)
                put(signatureOf(out.excel).name, signatureOf(out.excel))
            }
        }
        repo.trips.update(repo.touchTrip(trip.copy(outputsGeneratedAt = System.currentTimeMillis())))
        val splitNote = if (perEntity.isNotEmpty()) ", split across ${perEntity.size} entities" else ""
        repo.logEvent(tripId, EventType.GENERATED, "Generated TA statement (₹%.2f, %d bills%s)".format(p.data.total, p.data.lines.size, splitNote))
        outputsFor(trip)
    }

    /** Detached signature file for an Excel statement ("<name>.xlsx.p7s"). */
    fun signatureOf(f: File) = File(f.parentFile, f.name + ".p7s")

    /**
     * PDF: embedded digital signature (any PDF reader can check it). Excel: a detached ".p7s"
     * signature next to it. Large PDFs (over 80 MB) get a detached signature instead.
     */
    private fun signOutputs(id: Pair<DocSigner, X509Certificate>, claimNo: String, excel: File, pdf: File) {
        val (signer, cert) = id
        val name = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1) ?: "TA Reimburse user"
        try {
            signatureOf(excel).writeBytes(Signing.cmsDetached(excel.readBytes(), signer, cert))
            if (pdf.length() <= 80L * 1024 * 1024) {
                val signed = PdfSigner.sign(pdf.readBytes(), signer, cert, name, "TA claim $claimNo prepared and confirmed by $name")
                writeAtomically(pdf) { it.write(signed) }
            } else signatureOf(pdf).writeBytes(Signing.cmsDetached(pdf.readBytes(), signer, cert))
        } catch (e: Throwable) {
            // Signing is best-effort: outputs stay valid and usable without it.
            signatureOf(excel).delete(); signatureOf(pdf).delete()
        }
    }

    private fun documentSuffix(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        return (1..4).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    private inline fun writeAtomically(target: File, block: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().buffered().use(block)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }
}
