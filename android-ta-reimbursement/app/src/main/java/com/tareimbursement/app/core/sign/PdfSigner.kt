package com.tareimbursement.app.core.sign

import java.security.cert.X509Certificate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Adds a standard PDF digital signature (ISO 32000 / PAdES-basic, adbe.pkcs7.detached) as an
 * incremental update: the original bytes are kept, a signature field and a signature dictionary
 * are appended, and a CMS signature over the whole file (except the signature itself) is inserted.
 * Adobe Reader, Foxit, poppler's pdfsig etc. can verify it.
 */
object PdfSigner {
    /** Hex characters reserved for the CMS blob (8 KB is ample for one EC certificate). */
    private const val CONTENTS_HEX = 16384
    private const val BR_PLACEHOLDER = "/ByteRange [0 0000000000 0000000000 0000000000]"

    fun sign(pdf: ByteArray, signer: DocSigner, cert: X509Certificate, name: String, reason: String, location: String = "", now: ZonedDateTime = ZonedDateTime.now()): ByteArray {
        val s = String(pdf, Charsets.ISO_8859_1)
        val startxref = Regex("startxref\\s+(\\d+)\\s+%%EOF\\s*$").find(s)?.groupValues?.get(1)?.toLong() ?: error("Not a PDF this signer can update")
        val trailer = s.substring(s.lastIndexOf("trailer"))
        val size = Regex("/Size\\s+(\\d+)").find(trailer)!!.groupValues[1].toInt()
        val rootNum = Regex("/Root\\s+(\\d+)\\s+0\\s+R").find(trailer)!!.groupValues[1].toInt()
        val infoRef = Regex("/Info\\s+(\\d+\\s+0\\s+R)").find(trailer)?.groupValues?.get(1)
        val root = objectBody(s, rootNum)
        val pagesNum = Regex("/Pages\\s+(\\d+)\\s+0\\s+R").find(root)!!.groupValues[1].toInt()
        val firstPage = Regex("/Kids\\s*\\[\\s*(\\d+)\\s+0\\s+R").find(objectBody(s, pagesNum))!!.groupValues[1].toInt()
        val pageBody = objectBody(s, firstPage)

        val sigNum = size; val fieldNum = size + 1
        val date = "D:" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + now.offset.id.replace("Z", "+00:00").let { o -> o.substring(0, 3) + "'" + o.substring(4, 6) + "'" }
        val sigDict = "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached /Name ${pdfString(name)} /Reason ${pdfString(reason)}" +
            (if (location.isNotBlank()) " /Location ${pdfString(location)}" else "") +
            " /M ($date) $BR_PLACEHOLDER /Contents <${"0".repeat(CONTENTS_HEX)}> >>"
        val field = "<< /Type /Annot /Subtype /Widget /FT /Sig /T (Signature1) /V $sigNum 0 R /F 132 /Rect [0 0 0 0] /P $firstPage 0 R >>"
        val newRoot = insertBeforeEnd(root.replace(Regex("/AcroForm\\s*<<.*?>>"), ""), " /AcroForm << /Fields [$fieldNum 0 R] /SigFlags 3 >>")
        val newPage = if (pageBody.contains("/Annots")) pageBody.replace(Regex("/Annots\\s*\\["), "/Annots [$fieldNum 0 R ") else insertBeforeEnd(pageBody, " /Annots [$fieldNum 0 R]")

        val out = StringBuilder()
        val base = pdf.size.toLong() + 1 // leading newline
        val offsets = sortedMapOf<Int, Long>()
        fun add(n: Int, body: String) { offsets[n] = base + out.length; out.append("$n 0 obj\n$body\nendobj\n") }
        add(rootNum, newRoot); add(firstPage, newPage); add(sigNum, sigDict); add(fieldNum, field)
        val xrefPos = base + out.length
        out.append("xref\n")
        offsets.forEach { (n, off) -> out.append("$n 1\n").append("%010d 00000 n \n".format(off)) }
        out.append("trailer\n<< /Size ${size + 2} /Root $rootNum 0 R" + (infoRef?.let { " /Info $it" } ?: "") + " /Prev $startxref >>\nstartxref\n$xrefPos\n%%EOF\n")

        val full = (s + "\n" + out).toByteArray(Charsets.ISO_8859_1)
        val text = String(full, Charsets.ISO_8859_1)
        val contentsStart = text.lastIndexOf("/Contents <") + "/Contents ".length // at '<'
        val contentsEnd = contentsStart + CONTENTS_HEX + 2 // after '>'
        val ranges = longArrayOf(0, contentsStart.toLong(), contentsEnd.toLong(), (full.size - contentsEnd).toLong())
        val br = "/ByteRange [0 %010d %010d %010d]".format(ranges[1], ranges[2], ranges[3])
        check(br.length == BR_PLACEHOLDER.length)
        val brAt = text.lastIndexOf(BR_PLACEHOLDER)
        br.toByteArray(Charsets.ISO_8859_1).copyInto(full, brAt)

        val signed = full.copyOfRange(0, contentsStart) + full.copyOfRange(contentsEnd, full.size)
        val cms = Signing.cmsDetached(signed, signer, cert)
        val hex = cms.joinToString("") { "%02X".format(it) }
        check(hex.length <= CONTENTS_HEX) { "Signature too large" }
        hex.toByteArray(Charsets.ISO_8859_1).copyInto(full, contentsStart + 1)
        return full
    }

    /** Checks the last signature in a PDF. */
    fun verify(pdf: ByteArray): Signing.Verification {
        val s = String(pdf, Charsets.ISO_8859_1)
        val m = Regex("/ByteRange\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*]").findAll(s).lastOrNull()
            ?: return Signing.Verification(false, null, null, message = "This PDF is not digitally signed.")
        val (a, b, c, d) = m.destructured.toList().map { it.toLong() }
        if (a != 0L || b <= 0 || c <= b || c + d > pdf.size) return Signing.Verification(false, null, null, message = "The signature's byte range is invalid.")
        val hex = String(pdf, b.toInt() + 1, (c - b - 2).toInt(), Charsets.ISO_8859_1).filter { it.isLetterOrDigit() }
        // DER is self-delimiting: the zero padding after the CMS blob is ignored by the parser.
        val padded = (if (hex.length % 2 == 1) hex + "0" else hex).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cms = derPrefix(padded)
        val signed = pdf.copyOfRange(0, b.toInt()) + pdf.copyOfRange(c.toInt(), (c + d).toInt())
        val v = Signing.verifyDetached(signed, cms)
        val whole = c + d == pdf.size.toLong()
        return if (v.valid && !whole) v.copy(coversWholeFile = false, message = "The signature is valid, but content was added to the PDF after it was signed.") else v
    }

    /** Cuts a DER structure to its declared length. */
    private fun derPrefix(b: ByteArray): ByteArray {
        if (b.size < 2) return b
        val l0 = b[1].toInt() and 0xFF
        if (l0 == 0x80) return b // indefinite length (BER): the parser stops at its end marker
        val (len, hdr) = if (l0 < 0x80) l0 to 2 else {
            val n = l0 and 0x7F; var v = 0
            for (i in 0 until n) v = (v shl 8) or (b[2 + i].toInt() and 0xFF)
            v to 2 + n
        }
        return if (hdr + len <= b.size) b.copyOfRange(0, hdr + len) else b
    }

    private fun objectBody(s: String, n: Int): String {
        val m = Regex("(?<![0-9])$n 0 obj\\s*(.*?)\\s*endobj", RegexOption.DOT_MATCHES_ALL).findAll(s).lastOrNull() ?: error("Object $n not found")
        return m.groupValues[1].trim()
    }

    private fun insertBeforeEnd(dict: String, add: String): String {
        val i = dict.lastIndexOf(">>"); require(i > 0)
        return dict.substring(0, i).trimEnd() + add + " >>"
    }

    private fun pdfString(v: String): String {
        val ascii = v.all { it.code in 32..126 }
        return if (ascii) "(" + v.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
        else "<FEFF" + v.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) } + ">"
    }
}
