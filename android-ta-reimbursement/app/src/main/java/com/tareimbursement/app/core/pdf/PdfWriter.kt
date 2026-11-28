package com.tareimbursement.app.core.pdf

import java.io.OutputStream

/** JPEG image ready for embedding (DCTDecode pass-through, no re-encoding). */
class JpegImage(val bytes: ByteArray) {
    val width: Int
    val height: Int
    val components: Int

    init {
        var w = 0; var h = 0; var comp = 3
        var i = 2
        require(bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) { "Not a JPEG" }
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 || marker == 0xFF) { i += if (marker == 0xFF) 1 else 2; continue }
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                comp = bytes[i + 9].toInt() and 0xFF
                break
            }
            i += 2 + len
        }
        require(w > 0 && h > 0) { "JPEG size not found" }
        width = w; height = h; components = comp
    }
}

/**
 * Minimal streaming PDF 1.4 writer: A4 pages, Helvetica / Helvetica-Bold text, lines,
 * rectangles and JPEG images. Coordinates passed to [Page] are top-left based, in points.
 */
class PdfWriter(private val out: OutputStream) {
    private var pos = 0L
    private val offsets = mutableMapOf<Int, Long>()
    private var nextObj = 5 // 1 catalog, 2 pages, 3 font, 4 bold font
    private val pageObjs = mutableListOf<Int>()
    var pageCount = 0; private set

    init {
        raw("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    }

    private fun raw(b: ByteArray) { out.write(b); pos += b.size }
    private fun raw(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
    private fun obj(n: Int, body: String) { offsets[n] = pos; raw("$n 0 obj\n$body\nendobj\n") }
    private fun alloc() = nextObj++

    inner class Page(val width: Float, val height: Float) {
        internal val content = StringBuilder()
        internal val images = mutableMapOf<String, Int>()
        private fun f(v: Float) = String.format(java.util.Locale.ROOT, "%.2f", v)

        private fun color(rgb: Int, stroke: Boolean): String {
            val r = ((rgb shr 16) and 0xFF) / 255f; val g = ((rgb shr 8) and 0xFF) / 255f; val b = (rgb and 0xFF) / 255f
            return "${f(r)} ${f(g)} ${f(b)} ${if (stroke) "RG" else "rg"}\n"
        }

        /** [y] is the text baseline measured from the top of the page. */
        fun text(x: Float, y: Float, s: String, size: Float = 10f, bold: Boolean = false, rgb: Int = 0x000000) {
            content.append(color(rgb, false))
            content.append("BT /${if (bold) "F2" else "F1"} ${f(size)} Tf ${f(x)} ${f(height - y)} Td (")
            content.append(encode(s)).append(") Tj ET\n")
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 0.5f, rgb: Int = 0x7F7F7F) {
            content.append(color(rgb, true)).append("${f(w)} w ${f(x1)} ${f(height - y1)} m ${f(x2)} ${f(height - y2)} l S\n")
        }

        fun rect(x: Float, y: Float, w: Float, h: Float, fill: Int? = null, stroke: Int? = null, lineWidth: Float = 0.5f) {
            if (fill != null) content.append(color(fill, false))
            if (stroke != null) content.append(color(stroke, true)).append("${f(lineWidth)} w ")
            content.append("${f(x)} ${f(height - y - h)} ${f(w)} ${f(h)} re ")
            content.append(when { fill != null && stroke != null -> "B"; fill != null -> "f"; else -> "S" }).append("\n")
        }

        fun image(img: JpegImage, x: Float, y: Float, w: Float, h: Float) {
            val n = alloc()
            val cs = when (img.components) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]"; else -> "/DeviceRGB" }
            offsets[n] = pos
            raw("$n 0 obj\n<< /Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace $cs /BitsPerComponent 8 /Filter /DCTDecode /Length ${img.bytes.size} >>\nstream\n")
            raw(img.bytes)
            raw("\nendstream\nendobj\n")
            val name = "Im${images.size + 1}"
            images[name] = n
            content.append("q ${f(w)} 0 0 ${f(h)} ${f(x)} ${f(height - y - h)} cm /$name Do Q\n")
        }
    }

    fun beginPage(width: Float = A4_W, height: Float = A4_H): Page = Page(width, height)

    fun endPage(p: Page) {
        val contentBytes = p.content.toString().toByteArray(Charsets.ISO_8859_1)
        val cn = alloc()
        offsets[cn] = pos
        raw("$cn 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n"); raw(contentBytes); raw("\nendstream\nendobj\n")
        val pn = alloc()
        val xobj = if (p.images.isEmpty()) "" else " /XObject << ${p.images.entries.joinToString(" ") { "/${it.key} ${it.value} 0 R" }} >>"
        obj(pn, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${p.width} ${p.height}] /Contents $cn 0 R /Resources << /Font << /F1 3 0 R /F2 4 0 R >>$xobj >> >>")
        pageObjs += pn
        pageCount++
    }

    fun close(title: String = "Document") {
        obj(2, "<< /Type /Pages /Kids [${pageObjs.joinToString(" ") { "$it 0 R" }}] /Count ${pageObjs.size} >>")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val info = alloc()
        obj(info, "<< /Title (${encode(title)}) /Producer (TA Reimbursement) >>")
        val xref = pos
        val total = nextObj
        val sb = StringBuilder("xref\n0 $total\n0000000000 65535 f \n")
        for (i in 1 until total) {
            val off = offsets[i]
            if (off == null) sb.append("0000000000 65535 f \n") else sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", off))
        }
        sb.append("trailer\n<< /Size $total /Root 1 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        raw(sb.toString())
        out.flush()
    }

    companion object {
        const val A4_W = 595f
        const val A4_H = 842f

        /** Encodes to a WinAnsi PDF string literal body. */
        fun encode(s: String): String {
            val sb = StringBuilder()
            for (ch in s) {
                when (ch) {
                    '(', ')', '\\' -> sb.append('\\').append(ch)
                    '₹' -> sb.append("Rs. ")
                    '–' -> sb.append("\\226")
                    '—' -> sb.append("\\227")
                    '•' -> sb.append("\\225")
                    '‘', '’' -> sb.append('\'')
                    '“', '”' -> sb.append('"')
                    '−' -> sb.append('-')
                    '\n', '\r', '\t' -> sb.append(' ')
                    else -> when (ch.code) {
                        in 32..126 -> sb.append(ch)
                        in 160..255 -> sb.append("\\").append(Integer.toOctalString(ch.code))
                        else -> sb.append('?')
                    }
                }
            }
            return sb.toString()
        }

        private val HELV = intArrayOf(278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584)
        private val HELV_B = intArrayOf(278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584)

        fun textWidth(s: String, size: Float, bold: Boolean = false): Float {
            val table = if (bold) HELV_B else HELV
            var w = 0
            for (ch in s) {
                w += when {
                    ch == '₹' -> table['R'.code - 32] + table['s'.code - 32] + table['.'.code - 32] + table[0]
                    ch.code in 32..126 -> table[ch.code - 32]
                    else -> 556
                }
            }
            return w * size / 1000f
        }

        /** Greedy word wrap to [maxWidth] points. */
        fun wrap(s: String, size: Float, maxWidth: Float, bold: Boolean = false): List<String> {
            val result = mutableListOf<String>()
            s.split('\n').forEach { para ->
                var line = StringBuilder()
                para.split(' ').forEach { word ->
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (textWidth(candidate, size, bold) <= maxWidth) { line = StringBuilder(candidate) }
                    else {
                        if (line.isNotEmpty()) result += line.toString()
                        var w = word
                        while (textWidth(w, size, bold) > maxWidth && w.length > 1) {
                            var cut = w.length
                            while (cut > 1 && textWidth(w.substring(0, cut), size, bold) > maxWidth) cut--
                            result += w.substring(0, cut); w = w.substring(cut)
                        }
                        line = StringBuilder(w)
                    }
                }
                result += line.toString()
            }
            return result
        }

        /** Truncates with an ellipsis to fit [maxWidth]. */
        fun fit(s: String, size: Float, maxWidth: Float, bold: Boolean = false): String {
            if (textWidth(s, size, bold) <= maxWidth) return s
            var t = s
            while (t.isNotEmpty() && textWidth("$t...", size, bold) > maxWidth) t = t.dropLast(1)
            return "$t..."
        }
    }
}
