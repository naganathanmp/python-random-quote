package com.tareimbursement.app.core.trust

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.ZipInputStream

/** Hidden metadata found inside an uploaded file. All fields optional. */
data class FileMeta(
    val kind: String,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val software: String? = null,
    /** When the photo was taken / the document was created. */
    val created: LocalDateTime? = null,
    val modified: LocalDateTime? = null,
    /** IPTC/XMP DigitalSourceType value (e.g. "trainedAlgorithmicMedia" = AI generated). */
    val digitalSourceType: String? = null,
    /** C2PA "Content Credentials" manifest present. */
    val hasC2pa: Boolean = false,
    /** Generator text found in C2PA / PNG parameters / XMP (AI tool names etc.). */
    val generatorHints: List<String> = emptyList(),
    /** PDF: number of saved revisions (incremental updates). */
    val pdfRevisions: Int = 0,
    val pdfProducer: String? = null,
    val pdfCreator: String? = null,
    val hasGps: Boolean = false,
    /** PNG "parameters" text written by Stable Diffusion style tools. */
    val diffusionParameters: Boolean = false,
    val isScreenshotName: Boolean = false,
    val author: String? = null,
) {
    val createdDate: LocalDate? get() = created?.toLocalDate()
    val hasCameraInfo: Boolean get() = cameraMake != null || cameraModel != null
}

/**
 * Reads metadata from JPEG (EXIF + XMP + C2PA/JUMBF), PNG (tEXt/iTXt/eXIf/caBX), WebP (EXIF/XMP),
 * PDF (Info dictionary, XMP, revisions) and Office files (docProps). Pure Kotlin, offline, read-only.
 */
object FileMetadata {
    private const val MAX_SCAN = 4 * 1024 * 1024

    fun read(file: File, name: String = file.name): FileMeta {
        val bytes = try { file.inputStream().use { it.readNBytesCompat(MAX_SCAN) } } catch (e: Exception) { return FileMeta("unknown") }
        val screenshot = Regex("(?i)screenshot|screen_shot|scrnshot").containsMatchIn(name)
        val meta = try {
            when {
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> jpeg(bytes)
                bytes.size > 8 && bytes[0] == 0x89.toByte() && String(bytes, 1, 3, Charsets.ISO_8859_1) == "PNG" -> png(bytes)
                bytes.size > 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> webp(bytes)
                bytes.size > 5 && String(bytes, 0, 5, Charsets.ISO_8859_1).startsWith("%PDF") -> pdf(file, bytes)
                bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> office(file)
                else -> FileMeta("other").withXmp(String(bytes, Charsets.ISO_8859_1))
            }
        } catch (e: Exception) { FileMeta("unreadable") }
        return meta.copy(isScreenshotName = screenshot)
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (out.size() < max) { val r = read(buf, 0, minOf(buf.size, max - out.size())); if (r < 0) break; out.write(buf, 0, r) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ JPEG

    internal fun jpeg(b: ByteArray): FileMeta {
        var m = FileMeta("jpeg")
        var i = 2
        while (i + 4 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break // end / start of scan
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val start = i + 4; val end = minOf(b.size, i + 2 + len)
            if (len < 2 || start > end) break
            when (marker) {
                0xE1 -> {
                    if (startsWith(b, start, "Exif\u0000\u0000")) m = exif(b, start + 6, end, m)
                    else m = m.withXmp(String(b, start, end - start, Charsets.UTF_8))
                }
                0xEB -> { // APP11: JUMBF boxes carry C2PA manifests
                    val s = String(b, start, end - start, Charsets.ISO_8859_1)
                    if (s.contains("c2pa") || s.contains("jumb")) m = m.copy(hasC2pa = true, generatorHints = m.generatorHints + aiNamesIn(s))
                }
                0xED -> m = m.withXmp(String(b, start, end - start, Charsets.ISO_8859_1)) // IPTC/Photoshop
            }
            i += 2 + len
        }
        return m
    }

    private fun startsWith(b: ByteArray, at: Int, s: String) = at + s.length <= b.size && s.indices.all { b[at + it] == s[it].code.toByte() }

    /** Minimal TIFF/EXIF reader: Make, Model, Software, DateTime, DateTimeOriginal, GPS presence. */
    internal fun exif(b: ByteArray, tiff: Int, end: Int, base: FileMeta): FileMeta {
        if (tiff + 8 > end) return base
        val le = b[tiff] == 'I'.code.toByte()
        fun u16(p: Int) = if (p + 2 > end) 0 else if (le) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun u32(p: Int): Long = if (p + 4 > end) 0 else if (le) (u16(p).toLong() or (u16(p + 2).toLong() shl 16)) else ((u16(p).toLong() shl 16) or u16(p + 2).toLong())
        fun ascii(entry: Int): String? {
            val count = u32(entry + 4).toInt()
            if (count <= 0 || count > 4096) return null
            val p = if (count <= 4) entry + 8 else tiff + u32(entry + 8).toInt()
            if (p < 0 || p + count > end) return null
            return String(b, p, count, Charsets.ISO_8859_1).trim('\u0000', ' ').takeIf { it.isNotBlank() }
        }
        var m = base
        fun ifd(off: Int, depth: Int) {
            val p = tiff + off
            if (depth > 2 || p + 2 > end || off <= 0) return
            val n = u16(p)
            for (k in 0 until minOf(n, 200)) {
                val e = p + 2 + k * 12
                if (e + 12 > end) break
                when (u16(e)) {
                    0x010F -> m = m.copy(cameraMake = ascii(e))
                    0x0110 -> m = m.copy(cameraModel = ascii(e))
                    0x0131 -> m = m.copy(software = ascii(e))
                    0x013B -> m = m.copy(author = ascii(e))
                    0x0132 -> m = m.copy(modified = exifDate(ascii(e)) ?: m.modified)
                    0x9003 -> m = m.copy(created = exifDate(ascii(e)) ?: m.created)
                    0x8769 -> ifd(u32(e + 8).toInt(), depth + 1)
                    0x8825 -> m = m.copy(hasGps = true)
                }
            }
        }
        ifd(u32(tiff + 4).toInt(), 0)
        if (m.created == null && m.modified != null && m.software == null) m = m.copy(created = m.modified)
        return m
    }

    internal fun exifDate(s: String?): LocalDateTime? {
        val mm = Regex("(\\d{4})[:\\-](\\d{2})[:\\-](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})").find(s ?: return null) ?: return null
        val (y, mo, d, h, mi, se) = mm.destructured
        return runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt()) }.getOrNull()
    }

    // ------------------------------------------------------------------ PNG / WebP

    internal fun png(b: ByteArray): FileMeta {
        var m = FileMeta("png")
        var i = 8
        while (i + 12 <= b.size) {
            val len = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            val ds = i + 8; val de = ds + len
            if (len < 0 || de > b.size) break
            when (type) {
                "tEXt", "iTXt", "zTXt" -> {
                    val text = String(b, ds, len, Charsets.ISO_8859_1)
                    val key = text.substringBefore('\u0000')
                    val value = text.substringAfterLast('\u0000')
                    when {
                        key.equals("parameters", true) && (value.contains("Steps:") || value.contains("Sampler") || value.contains("CFG scale")) ->
                            m = m.copy(diffusionParameters = true, generatorHints = m.generatorHints + "Stable Diffusion parameters")
                        key.equals("Software", true) -> m = m.copy(software = value.take(120))
                        key.equals("Creation Time", true) -> m = m.copy(created = exifDate(value) ?: m.created)
                        key.contains("prompt", true) || key.contains("workflow", true) -> m = m.copy(generatorHints = m.generatorHints + "AI generation prompt embedded ($key)")
                        key.contains("XML:com.adobe.xmp") -> m = m.withXmp(text)
                    }
                    m = m.copy(generatorHints = (m.generatorHints + aiNamesIn(value)).distinct())
                }
                "eXIf" -> m = exif(b, ds, de, m)
                "caBX" -> m = m.copy(hasC2pa = true, generatorHints = (m.generatorHints + aiNamesIn(String(b, ds, len, Charsets.ISO_8859_1))).distinct())
                "IEND" -> break
            }
            i = de + 4
        }
        return m
    }

    internal fun webp(b: ByteArray): FileMeta {
        var m = FileMeta("webp")
        var i = 12
        while (i + 8 <= b.size) {
            val type = String(b, i, 4, Charsets.ISO_8859_1)
            val len = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            val ds = i + 8
            if (len < 0 || ds + len > b.size) break
            when (type) {
                "EXIF" -> m = exif(b, if (startsWith(b, ds, "Exif\u0000\u0000")) ds + 6 else ds, ds + len, m)
                "XMP " -> m = m.withXmp(String(b, ds, len, Charsets.UTF_8))
            }
            i = ds + len + (len and 1)
        }
        return m
    }

    // ------------------------------------------------------------------ XMP (shared)

    private fun FileMeta.withXmp(x: String): FileMeta {
        if (!x.contains("xmp", true) && !x.contains("DigitalSourceType", true) && !x.contains("c2pa", true)) return this
        fun tag(name: String): String? =
            Regex("(?is)$name\\s*=\\s*\"([^\"]{1,200})\"").find(x)?.groupValues?.get(1)
                ?: Regex("(?is)<$name>\\s*([^<]{1,200})</$name>").find(x)?.groupValues?.get(1)
        val dst = tag("Iptc4xmpExt:DigitalSourceType") ?: tag("DigitalSourceType")
            ?: Regex("(?i)digitalsourcetype/(\\w+)").find(x)?.groupValues?.get(1)
        val tool = tag("xmp:CreatorTool")
        val created = exifDate(tag("xmp:CreateDate") ?: tag("photoshop:DateCreated"))
        val modified = exifDate(tag("xmp:ModifyDate"))
        return copy(
            digitalSourceType = digitalSourceType ?: dst?.substringAfterLast('/'),
            software = software ?: tool,
            created = created ?: this.created,
            modified = modified ?: this.modified,
            hasC2pa = hasC2pa || x.contains("c2pa", true),
            generatorHints = (generatorHints + aiNamesIn(x)).distinct(),
        )
    }

    // ------------------------------------------------------------------ PDF

    internal fun pdf(file: File, head: ByteArray): FileMeta {
        val all = if (file.length() <= MAX_SCAN) head else head + tail(file, 512 * 1024)
        val s = String(all, Charsets.ISO_8859_1)
        fun info(key: String): String? = Regex("/$key\\s*\\((.{0,200}?)(?<!\\\\)\\)").find(s)?.groupValues?.get(1)?.let { decodePdfString(it) }
            ?: Regex("/$key\\s*<([0-9A-Fa-f]{2,400})>").find(s)?.groupValues?.get(1)?.let { hexString(it) }
        val revisions = Regex("%%EOF").findAll(s).count()
        val m = FileMeta(
            "pdf", software = info("Producer"), created = pdfDate(info("CreationDate")), modified = pdfDate(info("ModDate")),
            pdfRevisions = revisions, pdfProducer = info("Producer"), pdfCreator = info("Creator"), author = info("Author"),
            generatorHints = aiNamesIn(s.take(200_000) + (info("Producer") ?: "") + (info("Creator") ?: "")).distinct(),
        )
        return m.withXmp(s)
    }

    private fun tail(file: File, n: Int): ByteArray = java.io.RandomAccessFile(file, "r").use { r ->
        val len = r.length(); val start = maxOf(0L, len - n); val buf = ByteArray((len - start).toInt()); r.seek(start); r.readFully(buf); buf
    }

    private fun decodePdfString(s: String): String =
        if (s.startsWith("\u00FE\u00FF")) s.drop(2).chunked(2).joinToString("") { if (it.length == 2) ((it[0].code shl 8) or it[1].code).toChar().toString() else "" }
        else s.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")

    private fun hexString(h: String): String {
        val bytes = h.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
    }

    internal fun pdfDate(s: String?): LocalDateTime? {
        val m = Regex("D:(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?").find(s ?: return null) ?: return null
        val g = m.groupValues
        return runCatching { LocalDateTime.of(g[1].toInt(), g[2].ifEmpty { "1" }.toInt(), g[3].ifEmpty { "1" }.toInt(), g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt()) }.getOrNull()
    }

    // ------------------------------------------------------------------ Office

    internal fun office(file: File): FileMeta {
        var m = FileMeta("office")
        ZipInputStream(file.inputStream().buffered()).use { z ->
            var n = 0
            while (n++ < 400) {
                val e = z.nextEntry ?: break
                if (e.name == "docProps/app.xml" || e.name == "docProps/core.xml") {
                    val x = String(z.readBytes().take(200_000).toByteArray(), Charsets.UTF_8)
                    fun t(tag: String) = Regex("(?is)<$tag[^>]*>([^<]{1,200})</$tag>").find(x)?.groupValues?.get(1)?.trim()
                    m = m.copy(
                        software = m.software ?: t("Application"),
                        author = m.author ?: t("dc:creator"),
                        created = m.created ?: exifDate(t("dcterms:created")),
                        modified = m.modified ?: exifDate(t("dcterms:modified")),
                        generatorHints = (m.generatorHints + aiNamesIn(x)).distinct(),
                    )
                }
            }
        }
        return m
    }

    // ------------------------------------------------------------------ tool names

    /** AI generators / assistants whose name in a file's metadata marks AI involvement. */
    val AI_TOOLS = listOf(
        "DALL-E", "DALL·E", "Midjourney", "Stable Diffusion", "StableDiffusion", "SDXL", "ComfyUI", "Automatic1111", "InvokeAI",
        "Adobe Firefly", "Firefly", "Imagen", "Gemini", "Google AI", "ChatGPT", "OpenAI", "GPT-4", "Leonardo.Ai", "Ideogram",
        "Flux", "NovelAI", "Bing Image Creator", "Microsoft Designer", "Copilot", "Canva AI", "Magic Media", "Craiyon", "NightCafe",
        "Playground AI", "Runway", "Grok", "Aurora", "Meta AI", "Recraft", "Kandinsky",
    )

    /** Photo/PDF editors and online invoice makers: legitimate tools, but a bill passed through them may be altered. */
    val EDITOR_TOOLS = listOf(
        "Photoshop", "Lightroom", "GIMP", "Canva", "PicsArt", "Snapseed", "Pixlr", "Fotor", "Paint.NET", "Paint 3D", "Affinity",
        "PhotoDirector", "Photopea", "iLovePDF", "Sejda", "PDFescape", "Smallpdf", "PDF-XChange", "Foxit PhantomPDF", "Nitro",
        "PDFelement", "Acrobat Pro", "LibreOffice Draw", "Inkscape", "CorelDRAW",
    )
    val INVOICE_MAKERS = listOf("invoice generator", "invoicehome", "invoice-generator", "invoicesimple", "fakereceipt", "receiptmaker", "makereceipt", "receipt generator", "billgenerator", "bill generator")

    fun aiNamesIn(s: String): List<String> {
        val l = s.lowercase(Locale.ROOT)
        val found = AI_TOOLS.filter { n -> Regex("(?<![a-z])${Regex.escape(n.lowercase(Locale.ROOT))}(?![a-z])").containsMatchIn(l) }.toMutableList()
        if (l.contains("trainedalgorithmicmedia")) found += "trainedAlgorithmicMedia"
        // Bare "flux"/"aurora"/"runway"/"copilot"/"gemini" are too common in ordinary words: require an AI context word nearby.
        val ambiguous = setOf("Flux", "Aurora", "Runway", "Copilot", "Gemini", "Grok", "Imagen", "Firefly")
        return found.filter { it !in ambiguous || Regex("(?i)${Regex.escape(it)}[^\\n]{0,40}(ai|image|generat|model)|(ai|generat)[^\\n]{0,40}${Regex.escape(it)}").containsMatchIn(s) }.distinct()
    }

    fun editorNamesIn(s: String?): List<String> {
        val l = (s ?: return emptyList()).lowercase(Locale.ROOT)
        return EDITOR_TOOLS.filter { l.contains(it.lowercase(Locale.ROOT)) } + INVOICE_MAKERS.filter { l.contains(it) }
    }
}
