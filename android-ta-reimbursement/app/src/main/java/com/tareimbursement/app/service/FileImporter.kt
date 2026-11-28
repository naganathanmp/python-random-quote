package com.tareimbursement.app.service

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.tareimbursement.app.core.FileSniffer
import com.tareimbursement.app.core.ReceiptParser
import com.tareimbursement.app.core.SniffedType
import com.tareimbursement.app.core.ZipExpander
import com.tareimbursement.app.core.trust.FileMetadata
import com.tareimbursement.app.core.trust.TrustCheck
import com.tareimbursement.app.core.trust.TrustReport
import com.tareimbursement.app.data.AttachmentEntity
import com.tareimbursement.app.data.AttachmentKind
import com.tareimbursement.app.data.EventType
import com.tareimbursement.app.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportProgress(val done: Int, val total: Int, val current: String)

data class ImportResult(
    val createdExpenseIds: List<Long>,
    val attachedCount: Int,
    val skipped: List<String>,
    val duplicates: Int,
)

/**
 * Imports any mix of photos, PDFs, screenshots, documents and (nested) ZIP files.
 * Each file is copied into private storage, its text read on-device and parsed into a draft
 * expense (or added as a supporting document of an existing expense).
 */
class FileImporter(
    private val resolver: ContentResolver,
    private val repo: Repository,
    private val extractor: TextExtractor,
    private val cacheDir: File,
    /** Security policy for incoming URIs (see [UriPolicy]); the app passes the strict policy. */
    private val uriAllowed: (Uri) -> Boolean = { true },
    /** Queue bills for the on-device AI reader after OCR. */
    private val aiEnabled: () -> Boolean = { false },
    /** (name, user id) of the person adding bills - shown to the team. */
    private val whoAmI: () -> Pair<String, String> = { "" to "" },
    /** Picture fingerprint for duplicate detection (overridable in tests). */
    private val dHash: (File, AttachmentKind) -> Long? = { f, k -> Fingerprint.dHash(f, k) },
) {

    data class Source(val file: File, val name: String)

    suspend fun importUris(tripId: Long, uris: List<Uri>, attachToExpenseId: Long? = null, onProgress: (ImportProgress) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val staging = File(cacheDir, "import/${UUID.randomUUID()}").apply { mkdirs() }
        val skipped = mutableListOf<String>()
        try {
            val sources = mutableListOf<Source>()
            uris.forEachIndexed { i, uri ->
                val name = displayName(uri) ?: "file_${i + 1}"
                onProgress(ImportProgress(0, uris.size, "Copying $name"))
                val f = File(staging, "src_${i}_" + name.replace(Regex("[^A-Za-z0-9._ ()\\-]"), "_").take(80))
                if (!uriAllowed(uri)) { skipped += "$name (not allowed: only files shared from other apps or the camera can be imported)"; return@forEachIndexed }
                try {
                    val input = resolver.openInputStream(uri) ?: throw IllegalStateException("cannot open")
                    var tooBig = false
                    input.use { inp -> f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val r = inp.read(buf); if (r < 0) break
                            total += r
                            if (total > MAX_FILE_BYTES) { tooBig = true; break }
                            out.write(buf, 0, r)
                        }
                    } }
                    when {
                        tooBig -> { skipped += "$name (larger than ${MAX_FILE_BYTES / 1024 / 1024} MB)"; f.delete() }
                        f.length() == 0L -> { skipped += "$name (empty file)"; f.delete() }
                        else -> sources += Source(f, name)
                    }
                } catch (e: Exception) {
                    f.delete()
                    skipped += "$name (could not be read)"
                }
            }
            importFiles(tripId, sources, attachToExpenseId, staging, skipped, onProgress)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Also used directly by tests and the camera flow. */
    suspend fun importFiles(
        tripId: Long, sources: List<Source>, attachToExpenseId: Long?, staging: File,
        skipped: MutableList<String> = mutableListOf(), onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val expanded = mutableListOf<Source>()
        sources.forEachIndexed { i, s ->
            val type = FileSniffer.sniff(s.file, s.name)
            if (type == SniffedType.ZIP) {
                onProgress(ImportProgress(0, sources.size, "Unzipping ${s.name}"))
                try {
                    val entries = ZipExpander.expand(s.file, File(staging, "zip_$i"))
                    if (entries.isEmpty()) skipped += "${s.name} (empty ZIP)"
                    expanded += entries.map { Source(it.file, it.name) }
                } catch (e: Exception) {
                    skipped += "${s.name} (${e.message ?: "invalid ZIP"})"
                }
            } else expanded += s
        }

        val trip = repo.trips.get(tripId) ?: throw IllegalStateException("Claim not found")
        val created = mutableListOf<Long>()
        var attached = 0
        var duplicates = 0
        val attDir = repo.attachmentDir(tripId)
        expanded.forEachIndexed { i, s ->
            try {
            onProgress(ImportProgress(i, expanded.size, "Reading ${s.name}"))
            val type = FileSniffer.sniff(s.file, s.name)
            if (type == SniffedType.AUDIO) { skipped += "${s.name} (audio files are not supported)"; return@forEachIndexed }
            val kind = kindOf(type)
            val ext = UriPolicy.safeExtension(s.name) ?: defaultExt(type)
            val stored = File(attDir, "${UUID.randomUUID()}.$ext")
            try {
                s.file.copyTo(stored, overwrite = true)
            } catch (e: Exception) {
                skipped += "${s.name} (storage error)"; return@forEachIndexed
            }
            // Several readings (original + enhanced) are cross-checked; the best one is kept for display.
            val fused = com.tareimbursement.app.core.ocr.OcrFusion.parse(extractor.extractReadings(stored, kind, type), trip.startDate, trip.endDate)
            val text = fused?.second.orEmpty()
            // Hidden metadata: date fallback + trust check (AI-generated / edited / invoice makers).
            val meta = FileMetadata.read(stored, s.name)
            val sha = Fingerprint.sha256(stored)
            val hash = dHash(stored, kind)
            val relPath = stored.relativeTo(repo.filesDir).path
            val mime = type.mime.takeIf { type != SniffedType.OTHER } ?: (MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
            if (attachToExpenseId != null) {
                val target = repo.expenses.get(attachToExpenseId)
                if (target != null) {
                    val hasPrimary = repo.attachments.forExpense(target.id).any { it.isPrimary }
                    repo.attachments.insert(repo.touchA(AttachmentEntity(expenseId = target.id, tripId = tripId, storedPath = relPath, originalName = s.name, mimeType = mime, kind = kind.name, isPrimary = !hasPrimary, sizeBytes = stored.length(), sha256 = sha, dHash = hash)))
                    applyTrust(target.id, meta, text, isCamera = s.name.startsWith(CAMERA_PREFIX))
                    repo.refreshDuplicates(target.id)
                    // Fill any still-missing fields from the supporting document.
                    if (text.isNotBlank() && (target.amount == null || target.dateEpochDay == null || target.vendor.isBlank())) {
                        val p = fused!!.first
                        val cur = repo.expenses.get(target.id) ?: target
                        repo.expenses.update(repo.touch(cur.copy(
                            amount = target.amount ?: p.amount.takeIf { !target.isForeign }, dateEpochDay = target.dateEpochDay ?: p.date?.toEpochDay(),
                            vendor = target.vendor.ifBlank { p.vendor.orEmpty() }, billNo = target.billNo.ifBlank { p.billNo.orEmpty() },
                            suggestedCategory = target.suggestedCategory ?: p.suggestion.category.name,
                            suggestionReason = target.suggestionReason.ifBlank { p.suggestion.reason },
                            noBill = false,
                        )))
                    } else if (target.noBill) repo.expenses.get(target.id)?.let { repo.expenses.update(repo.touch(it.copy(noBill = false))) }
                    attached++
                    return@forEachIndexed
                }
            }
            var parsed = fused?.first
            // Date not printed on the bill: use when the photo was taken / the PDF was created.
            val metaDate = meta.createdDate?.takeIf { d -> !d.isAfter(java.time.LocalDate.now()) && !d.isBefore(trip.startDate.minusDays(60)) }
            val fromMeta = parsed != null && parsed.date == null && metaDate != null
            if (fromMeta) parsed = parsed!!.copy(date = metaDate, dateConfident = false, dateFromMetadata = true)
            val expenseId = repo.createExpenseFromReceipt(tripId, parsed, text)
            val (who, whoUid) = whoAmI()
            repo.expenses.get(expenseId)?.let { e ->
                repo.expenses.update(e.copy(addedBy = who, addedByUid = whoUid, dateFromMetadata = fromMeta,
                    aiState = if (aiEnabled() && text.isNotBlank()) AI_QUEUED else ""))
            }
            repo.attachments.insert(repo.touchA(AttachmentEntity(expenseId = expenseId, tripId = tripId, storedPath = relPath, originalName = s.name, mimeType = mime, kind = kind.name, isPrimary = true, sizeBytes = stored.length(), sha256 = sha, dHash = hash)))
            applyTrust(expenseId, meta, text, isCamera = s.name.startsWith(CAMERA_PREFIX))
            if (repo.refreshDuplicates(expenseId).isNotBlank() || (repo.expenses.get(expenseId)?.duplicateOfId ?: 0) > 0) duplicates++
            created += expenseId
            } catch (e: Throwable) {
                // One unreadable / too-large file never stops the other files in the same upload.
                skipped += "${s.name} (could not be read${if (e is OutOfMemoryError) ": image too large" else ""})"
            }
        }
        onProgress(ImportProgress(expanded.size, expanded.size, "Done"))
        val n = created.size + attached
        if (n > 0) repo.logEvent(tripId, EventType.FILES, if (attachToExpenseId != null) "Added $attached supporting document(s)" else "Added ${created.size} bill(s) from ${sources.size} upload(s)")
        ImportResult(created, attached, skipped, duplicates)
    }

    private suspend fun applyTrust(expenseId: Long, meta: com.tareimbursement.app.core.trust.FileMeta, text: String, isCamera: Boolean) {
        val e = repo.expenses.get(expenseId) ?: return
        val now = TrustCheck.assess(meta, e.date, text, isCamera)
        // Merge with findings from the bill's other documents; the worst one decides.
        val merged = TrustReport((e.trust.findings + now.findings).distinctBy { it.code + it.message })
        repo.expenses.update(repo.touch(e.copy(trustLevel = merged.level.name, trustReport = merged.encode(),
            trustAck = if (merged.level.ordinal > e.trust.level.ordinal) "" else e.trustAck)))
    }

    private fun displayName(uri: Uri): String? = try {
        if (uri.scheme == "file") uri.lastPathSegment
        else resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (e: Exception) { uri.lastPathSegment?.substringAfterLast('/') }

    companion object {
        /** Largest single file accepted (protects storage from oversized / malicious uploads). */
        const val MAX_FILE_BYTES = 200L * 1024 * 1024
        const val AI_QUEUED = "QUEUED"
        /** Photos taken with the in-app camera are named with this prefix. */
        const val CAMERA_PREFIX = "Camera_"

        fun kindOf(t: SniffedType): AttachmentKind = when {
            t.isImage -> AttachmentKind.IMAGE
            t == SniffedType.PDF -> AttachmentKind.PDF
            t.isText -> AttachmentKind.TEXT
            t == SniffedType.VIDEO -> AttachmentKind.VIDEO
            else -> AttachmentKind.OTHER
        }
        fun defaultExt(t: SniffedType) = when (t) {
            SniffedType.JPEG -> "jpg"; SniffedType.PNG -> "png"; SniffedType.GIF -> "gif"; SniffedType.WEBP -> "webp"; SniffedType.HEIC -> "heic"
            SniffedType.BMP -> "bmp"; SniffedType.TIFF -> "tif"; SniffedType.PDF -> "pdf"; SniffedType.DOCX -> "docx"; SniffedType.XLSX -> "xlsx"
            SniffedType.PPTX -> "pptx"; SniffedType.ODF -> "odt"; SniffedType.TEXT -> "txt"; SniffedType.HTML -> "html"; SniffedType.CSV -> "csv"
            SniffedType.EML -> "eml"; SniffedType.XML -> "xml"; SniffedType.JSON -> "json"; SniffedType.VIDEO -> "mp4"; else -> "bin"
        }
    }
}
