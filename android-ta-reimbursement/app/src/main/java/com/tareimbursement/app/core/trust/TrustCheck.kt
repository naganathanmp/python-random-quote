package com.tareimbursement.app.core.trust

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class TrustLevel(val label: String) {
    OK("Looks genuine"),
    INFO("Note"),
    WARN("Check this bill"),
    BLOCK("Not allowed"),
}

data class TrustFinding(val level: TrustLevel, val code: String, val message: String)

data class TrustReport(val findings: List<TrustFinding>) {
    val level: TrustLevel get() = findings.maxOfOrNull { it.level } ?: TrustLevel.OK
    val blocked: Boolean get() = level == TrustLevel.BLOCK
    /** A warning must be acknowledged with a reason before the claim can be generated. */
    val needsAck: Boolean get() = level == TrustLevel.WARN
    val summary: String get() = findings.filter { it.level >= TrustLevel.WARN }.joinToString("; ") { it.message }
        .ifBlank { findings.firstOrNull()?.message ?: TrustLevel.OK.label }

    /** Compact storage form: LEVEL|code|message per line. */
    fun encode(): String = findings.joinToString("\n") { "${it.level.name}|${it.code}|${it.message.replace('\n', ' ')}" }

    companion object {
        fun decode(s: String?): TrustReport = TrustReport(
            (s ?: "").lines().mapNotNull { line ->
                val p = line.split('|', limit = 3)
                if (p.size < 3) null else runCatching { TrustFinding(TrustLevel.valueOf(p[0]), p[1], p[2]) }.getOrNull()
            },
        )
    }
}

/**
 * Trust check on an uploaded bill: reads its hidden metadata and the printed text and flags
 * AI-generated, edited or suspicious files. Signals, not proof: a WARN asks the person to confirm with a reason.
 */
object TrustCheck {

    fun assess(meta: FileMeta, billDate: LocalDate?, ocrText: String?, isCameraCapture: Boolean): TrustReport {
        val f = mutableListOf<TrustFinding>()

        // --- AI generation: block.
        val aiDst = meta.digitalSourceType?.lowercase()
        if (aiDst != null && (aiDst.contains("trainedalgorithmicmedia") || aiDst.contains("compositesynthetic") || aiDst.contains("algorithmicmedia"))) {
            f += TrustFinding(TrustLevel.BLOCK, "ai-source", "File is labelled as AI-generated (DigitalSourceType: ${meta.digitalSourceType}).")
        }
        if (meta.diffusionParameters) f += TrustFinding(TrustLevel.BLOCK, "ai-params", "Image contains AI image-generator settings (prompt / sampler / steps).")
        val aiTools = (meta.generatorHints + FileMetadata.aiNamesIn(listOfNotNull(meta.software, meta.pdfCreator, meta.pdfProducer).joinToString(" ")))
            .filter { it != "trainedAlgorithmicMedia" && !it.startsWith("Stable Diffusion parameters") }.distinct()
        if (aiTools.isNotEmpty()) f += TrustFinding(TrustLevel.BLOCK, "ai-tool", "Made with an AI tool: ${aiTools.joinToString(", ")}.")
        if (meta.hasC2pa && f.none { it.level == TrustLevel.BLOCK }) {
            f += TrustFinding(TrustLevel.WARN, "c2pa", "File carries Content Credentials (C2PA) from an editing or generating app - confirm it is the original bill.")
        }

        // --- Editors / invoice makers: warn.
        val editors = (FileMetadata.editorNamesIn(meta.software) + FileMetadata.editorNamesIn(meta.pdfCreator) + FileMetadata.editorNamesIn(meta.pdfProducer)).distinct()
        val makers = editors.filter { e -> FileMetadata.INVOICE_MAKERS.contains(e) }
        if (makers.isNotEmpty()) f += TrustFinding(TrustLevel.WARN, "invoice-maker", "Created with an online invoice/receipt maker (${makers.joinToString()}).")
        val realEditors = editors - makers.toSet()
        if (realEditors.isNotEmpty()) f += TrustFinding(TrustLevel.WARN, "edited", "Saved by an editing app (${realEditors.joinToString()}) - the bill may have been changed.")

        // --- Edited after creation.
        val c = meta.created; val m = meta.modified
        if (c != null && m != null && meta.kind == "pdf" && ChronoUnit.HOURS.between(c, m) > 24) {
            f += TrustFinding(TrustLevel.WARN, "modified-later", "PDF was modified ${ChronoUnit.DAYS.between(c, m)} day(s) after it was created.")
        }
        if (meta.kind == "pdf" && meta.pdfRevisions >= 3) {
            f += TrustFinding(TrustLevel.WARN, "revisions", "PDF has been re-saved ${meta.pdfRevisions - 1} times after it was first made.")
        }

        // --- Dates that don't fit.
        val created = meta.createdDate
        if (created != null && billDate != null) {
            if (meta.kind in setOf("jpeg", "png", "webp", "heic") && created.isBefore(billDate.minusDays(1))) {
                f += TrustFinding(TrustLevel.WARN, "photo-before-bill", "Photo was taken on $created, before the bill date $billDate.")
            } else if (meta.kind == "pdf" && created.isBefore(billDate.minusDays(2))) {
                f += TrustFinding(TrustLevel.WARN, "pdf-before-bill", "PDF was created on $created, before the bill date $billDate.")
            }
        }

        // --- GSTIN check digit.
        if (ocrText != null) {
            val bad = GstIn.findAll(ocrText).filter { !GstIn.valid(it) }
            val good = GstIn.findAll(ocrText).filter { GstIn.valid(it) }
            if (bad.isNotEmpty() && good.isEmpty()) {
                f += TrustFinding(TrustLevel.WARN, "gstin", "GSTIN ${bad.first()} fails its check digit (could be a misread - check the bill).")
            }
        }

        // --- Informational.
        if (meta.isScreenshotName) f += TrustFinding(TrustLevel.INFO, "screenshot", "Screenshot - fine for online bookings; keep the original e-mail/PDF if asked.")
        val noMeta = meta.software == null && meta.created == null && !meta.hasCameraInfo && meta.pdfProducer == null && meta.pdfCreator == null
        if (noMeta && !isCameraCapture && meta.kind != "other") f += TrustFinding(TrustLevel.INFO, "no-metadata", "File has no metadata (often stripped by WhatsApp/e-mail).")
        if (meta.hasCameraInfo) f += TrustFinding(TrustLevel.INFO, "camera", "Photo from ${listOfNotNull(meta.cameraMake, meta.cameraModel).joinToString(" ")}.")

        return TrustReport(f.distinctBy { it.code })
    }
}

/** Indian GSTIN format + mod-36 check digit. */
object GstIn {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val RX = Regex("(?<![0-9A-Z])([0-3][0-9][A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z])(?![0-9A-Z])")

    fun findAll(text: String): List<String> = RX.findAll(text.uppercase()).map { it.groupValues[1] }.distinct().toList()

    fun valid(g: String): Boolean {
        if (g.length != 15) return false
        var sum = 0
        for (i in 0 until 14) {
            val v = CHARS.indexOf(g[i]); if (v < 0) return false
            val p = v * (if (i % 2 == 0) 1 else 2)
            sum += p / 36 + p % 36
        }
        val check = (36 - sum % 36) % 36
        return CHARS[check] == g[14]
    }
}
