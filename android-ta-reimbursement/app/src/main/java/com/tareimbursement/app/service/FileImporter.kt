package com.tareimbursement.app.service

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.tareimbursement.app.core.FileSniffer
import com.tareimbursement.app.core.ReceiptParser
import com.tareimbursement.app.core.SniffedType
import com.tareimbursement.app.core.ZipExpander
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
            val relPath = stored.relativeTo(repo.filesDir).path
            val mime = type.mime.takeIf { type != SniffedType.OTHER } ?: (MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
            if (attachToExpenseId != null) {
                val target = repo.expenses.get(attachToExpenseId)
                if (target != null) {
                    val hasPrimary = repo.attachments.forExpense(target.id).any { it.isPrimary }
                    repo.attachments.insert(AttachmentEntity(expenseId = target.id, tripId = tripId, storedPath = relPath, originalName = s.name, mimeType = mime, kind = kind.name, isPrimary = !hasPrimary, sizeBytes = stored.length()))
                    // Fill any still-missing fields from the supporting document.
                    if (text.isNotBlank() && (target.amount == null || target.dateEpochDay == null || target.vendor.isBlank())) {
                        val p = fused!!.first
                        repo.expenses.update(target.copy(
                            amount = target.amount ?: p.amount.takeIf { !target.isForeign }, dateEpochDay = target.dateEpochDay ?: p.date?.toEpochDay(),
                            vendor = target.vendor.ifBlank { p.vendor.orEmpty() }, billNo = target.billNo.ifBlank { p.billNo.orEmpty() },
                            suggestedCategory = target.suggestedCategory ?: p.suggestion.category.name,
                            suggestionReason = target.suggestionReason.ifBlank { p.suggestion.reason },
                            noBill = false,
                        ))
                    } else if (target.noBill) repo.expenses.update(target.copy(noBill = false))
                    attached++
                    return@forEachIndexed
                }
            }
            val parsed = fused?.first
            val expenseId = repo.createExpenseFromReceipt(tripId, parsed, text)
            if ((repo.expenses.get(expenseId)?.duplicateOfId ?: 0) > 0) duplicates++
            repo.attachments.insert(AttachmentEntity(expenseId = expenseId, tripId = tripId, storedPath = relPath, originalName = s.name, mimeType = mime, kind = kind.name, isPrimary = true, sizeBytes = stored.length()))
            created += expenseId
        }
        onProgress(ImportProgress(expanded.size, expanded.size, "Done"))
        val n = created.size + attached
        if (n > 0) repo.logEvent(tripId, EventType.FILES, if (attachToExpenseId != null) "Added $attached supporting document(s)" else "Added ${created.size} bill(s) from ${sources.size} upload(s)")
        ImportResult(created, attached, skipped, duplicates)
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
