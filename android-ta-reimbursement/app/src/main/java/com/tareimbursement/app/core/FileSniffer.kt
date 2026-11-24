package com.tareimbursement.app.core

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

enum class SniffedType(val mime: String) {
    JPEG("image/jpeg"), PNG("image/png"), GIF("image/gif"), WEBP("image/webp"), HEIC("image/heic"), BMP("image/bmp"),
    TIFF("image/tiff"), PDF("application/pdf"), ZIP("application/zip"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    ODF("application/vnd.oasis.opendocument.text"),
    TEXT("text/plain"), HTML("text/html"), CSV("text/csv"), EML("message/rfc822"), XML("text/xml"), JSON("application/json"),
    VIDEO("video/mp4"), AUDIO("audio/*"), OTHER("application/octet-stream");

    val isImage get() = this in setOf(JPEG, PNG, GIF, WEBP, HEIC, BMP)
    val isText get() = this in setOf(TEXT, HTML, CSV, EML, XML, JSON)
    val isOffice get() = this in setOf(DOCX, XLSX, PPTX, ODF)
}

/** Reads at most [max] bytes of a file as text (works on every Android version, unlike readNBytes). */
fun readTextPrefix(file: File, max: Int): String {
    val buf = java.io.ByteArrayOutputStream()
    file.inputStream().use { input ->
        val chunk = ByteArray(16 * 1024)
        while (buf.size() < max) {
            val r = input.read(chunk, 0, minOf(chunk.size, max - buf.size()))
            if (r < 0) break
            buf.write(chunk, 0, r)
        }
    }
    return buf.toString(Charsets.UTF_8.name())
}

/** Detects file types by magic bytes first, then by extension — receipts often arrive misnamed. */
object FileSniffer {
    fun sniff(file: File, name: String = file.name): SniffedType {
        val head = ByteArray(16)
        val n = try { file.inputStream().use { it.read(head) } } catch (e: Exception) { -1 }
        return sniff(head, n, name) { file.inputStream() }
    }

    fun sniff(head: ByteArray, n: Int, name: String, reopen: () -> InputStream): SniffedType {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        fun b(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
        fun ascii(from: Int, s: String) = s.indices.all { b(from + it) == s[it].code }
        when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> return SniffedType.JPEG
            b(0) == 0x89 && ascii(1, "PNG") -> return SniffedType.PNG
            ascii(0, "GIF8") -> return SniffedType.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> return SniffedType.WEBP
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1") || ascii(8, "hevc") || ascii(8, "avif")) -> return SniffedType.HEIC
            ascii(4, "ftyp") -> return SniffedType.VIDEO
            ascii(0, "BM") && ext == "bmp" -> return SniffedType.BMP
            (ascii(0, "II*") || ascii(0, "MM")) && (ext == "tif" || ext == "tiff") -> return SniffedType.TIFF
            ascii(0, "%PDF") -> return SniffedType.PDF
            ascii(0, "PK") && b(2) == 3 && b(3) == 4 -> return zipFlavour(ext, reopen)
        }
        // Some PDFs have junk before the header.
        if (ext == "pdf") return SniffedType.PDF
        return when (ext) {
            "jpg", "jpeg", "jfif" -> SniffedType.JPEG
            "png" -> SniffedType.PNG
            "heic", "heif" -> SniffedType.HEIC
            "webp" -> SniffedType.WEBP
            "txt", "log", "md" -> SniffedType.TEXT
            "htm", "html" -> SniffedType.HTML
            "csv", "tsv" -> SniffedType.CSV
            "eml", "msg" -> if (ext == "eml") SniffedType.EML else SniffedType.OTHER
            "xml" -> SniffedType.XML
            "json" -> SniffedType.JSON
            "mp4", "mov", "3gp", "mkv", "webm" -> SniffedType.VIDEO
            "mp3", "m4a", "aac", "wav", "ogg", "opus" -> SniffedType.AUDIO
            else -> if (looksLikeText(head, n)) SniffedType.TEXT else SniffedType.OTHER
        }
    }

    private fun zipFlavour(ext: String, reopen: () -> InputStream): SniffedType {
        when (ext) { "docx" -> return SniffedType.DOCX; "xlsx" -> return SniffedType.XLSX; "pptx" -> return SniffedType.PPTX; "odt", "ods", "odp" -> return SniffedType.ODF }
        return try {
            ZipInputStream(reopen()).use { z ->
                var i = 0
                while (i++ < 30) {
                    val e = z.nextEntry ?: break
                    when {
                        e.name.startsWith("word/") -> return SniffedType.DOCX
                        e.name.startsWith("xl/") -> return SniffedType.XLSX
                        e.name.startsWith("ppt/") -> return SniffedType.PPTX
                        e.name == "mimetype" -> return SniffedType.ODF
                    }
                }
                SniffedType.ZIP
            }
        } catch (e: Exception) { SniffedType.ZIP }
    }

    private fun looksLikeText(head: ByteArray, n: Int): Boolean {
        if (n <= 0) return false
        return (0 until n).all { val c = head[it].toInt() and 0xFF; c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 0xC0 || c in 0x80..0xBF }
    }
}

/** Safely expands (nested) ZIP archives: zip-slip safe, skips OS junk, bounded size/entries. */
object ZipExpander {
    data class Entry(val file: File, val name: String)

    const val MAX_ENTRIES = 500
    const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
    const val MAX_DEPTH = 3

    fun expand(zip: File, outDir: File, depth: Int = 0, budget: LongArray = longArrayOf(MAX_TOTAL_BYTES), count: IntArray = intArrayOf(0)): List<Entry> {
        val out = mutableListOf<Entry>()
        outDir.mkdirs()
        val canonicalOut = outDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                val base = name.substringAfterLast('/')
                if (base.isEmpty() || base.startsWith(".") || name.contains("__MACOSX/") || base.equals("Thumbs.db", true) || base.equals("desktop.ini", true)) continue
                if (++count[0] > MAX_ENTRIES) break
                val target = File(outDir, "${count[0]}_${base.replace(Regex("[^A-Za-z0-9._ ()\\-]"), "_")}")
                if (!target.canonicalPath.startsWith(canonicalOut)) continue
                var written = 0L
                target.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = z.read(buf); if (r < 0) break
                        written += r; budget[0] -= r
                        if (budget[0] < 0) throw IllegalStateException("ZIP is too large to import")
                        os.write(buf, 0, r)
                    }
                }
                if (written == 0L) { target.delete(); continue }
                val type = FileSniffer.sniff(target, base)
                if (type == SniffedType.ZIP && depth < MAX_DEPTH) {
                    out += expand(target, File(outDir, "z${count[0]}"), depth + 1, budget, count)
                    target.delete()
                } else out += Entry(target, base)
            }
        }
        return out
    }
}

/** Pulls readable text out of DOCX / XLSX / PPTX / ODF files without any Office library. */
object OfficeText {
    fun extract(file: File, maxChars: Int = 20000): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(file.inputStream().buffered()).use { z ->
                while (sb.length < maxChars) {
                    val e = z.nextEntry ?: break
                    val n = e.name
                    val wanted = n == "word/document.xml" || n == "xl/sharedStrings.xml" || (n.startsWith("ppt/slides/slide") && n.endsWith(".xml")) || n == "content.xml" ||
                        (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"))
                    if (!wanted) continue
                    val xml = z.readBytes().toString(Charsets.UTF_8)
                    val text = xml.replace(Regex("</w:p>|</a:p>|</text:p>|</row>|</si>"), "\n")
                        .replace(Regex("<w:tab/>|</c>"), "\t")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                    sb.append(text).append('\n')
                }
            }
        } catch (e: Exception) { /* unreadable document: return what we have */ }
        return sb.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(maxChars)
    }

    fun htmlToText(html: String): String =
        html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>|</h[1-6]>"), "\n")
            .replace(Regex("(?i)</td>|</th>"), "  ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#8377;", "₹").replace("&quot;", "\"")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
