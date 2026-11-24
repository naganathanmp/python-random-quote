package com.tareimbursement.app.core.xlsx

import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small, dependency-free XLSX (Office Open XML) writer. Supports strings, numbers, dates,
 * formulas (with cached values so every viewer shows numbers), a fixed style palette,
 * merged cells, column widths, frozen header rows, auto-filter and print setup.
 */
class XlsxWriter {

    enum class Style(val id: Int) {
        DEFAULT(0), TITLE(1), LABEL(2), TEXT(3), MONEY(4), HEADER(5), DATE(6), MONEY_BOLD(7),
        TEXT_WRAP(8), CENTER(9), NOTE(10), BOLD(11), SUBTITLE(12), LABEL_BOLD_BORDER(13), CENTER_BOLD(14), RATE(15)
    }

    sealed class Value {
        data class Str(val v: String) : Value()
        data class Num(val v: Double) : Value()
        data class Date(val v: LocalDate) : Value()
        data class Formula(val f: String, val cached: Double) : Value()
        object Blank : Value()
    }

    data class Cell(val value: Value, val styleId: Int)

    /** Free-form cell style (deduplicated into styles.xml). Colours are RRGGBB. */
    data class XStyle(
        val font: String = "Arial", val size: Double = 10.0, val bold: Boolean = false, val italic: Boolean = false,
        val color: String? = null, val fill: String? = null, val border: String? = null,
        val numFmt: String? = null, val hAlign: String? = null, val vAlign: String? = "center", val wrap: Boolean = false,
    )

    private val custom = LinkedHashMap<XStyle, Int>()
    fun style(x: XStyle): Int = custom.getOrPut(x) { BASE_XF_COUNT + custom.size }

    inner class Sheet(val name: String) {
        internal val rows = sortedMapOf<Int, MutableMap<Int, Cell>>()
        internal val merges = mutableListOf<String>()
        internal val widths = mutableMapOf<Int, Double>()
        internal val heights = mutableMapOf<Int, Double>()
        var freezeRows = 0
        var autoFilter: String? = null
        var landscape = false

        /** row and col are 1-based. */
        fun set(row: Int, col: Int, value: Value, style: Style = Style.DEFAULT) = setId(row, col, value, style.id)
        fun setId(row: Int, col: Int, value: Value, styleId: Int) {
            rows.getOrPut(row) { sortedMapOf() }[col] = Cell(value, styleId)
        }
        fun set(row: Int, col: Int, value: Value, x: XStyle) = setId(row, col, value, style(x))
        fun str(row: Int, col: Int, v: String?, x: XStyle) = set(row, col, if (v == null) Value.Blank else Value.Str(v), x)
        fun num(row: Int, col: Int, v: Double, x: XStyle) = set(row, col, Value.Num(v), x)
        fun date(row: Int, col: Int, v: LocalDate?, x: XStyle) = set(row, col, if (v == null) Value.Blank else Value.Date(v), x)
        fun formula(row: Int, col: Int, f: String, cached: Double, x: XStyle) = set(row, col, Value.Formula(f, cached), x)
        fun blank(row: Int, col: Int, x: XStyle) = set(row, col, Value.Blank, x)
        /** Merges and gives every cell in the range the same style (so borders/fills cover the whole block). */
        fun mergeStyled(row1: Int, col1: Int, row2: Int, col2: Int, x: XStyle) {
            merges += "${ref(row1, col1)}:${ref(row2, col2)}"
            for (r in row1..row2) for (c in col1..col2) if (rows[r]?.get(c) == null) blank(r, c, x)
        }
        fun str(row: Int, col: Int, v: String?, style: Style = Style.TEXT) = set(row, col, if (v == null) Value.Blank else Value.Str(v), style)
        fun num(row: Int, col: Int, v: Double, style: Style = Style.MONEY) = set(row, col, Value.Num(v), style)
        fun date(row: Int, col: Int, v: LocalDate?, style: Style = Style.DATE) = set(row, col, if (v == null) Value.Blank else Value.Date(v), style)
        fun formula(row: Int, col: Int, f: String, cached: Double, style: Style = Style.MONEY) = set(row, col, Value.Formula(f, cached), style)
        fun blank(row: Int, col: Int, style: Style) = set(row, col, Value.Blank, style)
        fun merge(row1: Int, col1: Int, row2: Int, col2: Int, style: Style? = null) {
            merges += "${ref(row1, col1)}:${ref(row2, col2)}"
            if (style != null) for (r in row1..row2) for (c in col1..col2) {
                if (rows[r]?.get(c) == null) blank(r, c, style)
            }
        }
        fun width(col: Int, w: Double) { widths[col] = w }
        fun height(row: Int, h: Double) { heights[row] = h }
    }

    private val sheets = mutableListOf<Sheet>()

    fun sheet(name: String): Sheet = Sheet(name.take(31)).also { sheets += it }

    fun write(out: OutputStream) {
        val zip = ZipOutputStream(out)
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        entry("[Content_Types].xml", contentTypes())
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>""")
        entry("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>TA Statement</dc:title><dc:creator>TA Reimbursement</dc:creator></cp:coreProperties>""")
        entry("docProps/app.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>TA Reimbursement</Application></Properties>""")
        entry("xl/workbook.xml", workbook())
        entry("xl/_rels/workbook.xml.rels", workbookRels())
        entry("xl/styles.xml", stylesXml())
        sheets.forEachIndexed { i, s -> entry("xl/worksheets/sheet${i + 1}.xml", sheetXml(s, i == 0)) }
        zip.finish()
        zip.flush()
    }

    private fun contentTypes(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
        append("</Types>")
    }

    private fun workbook(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView activeTab="0"/></bookViews><sheets>""")
        sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        append("</sheets>")
        val filters = sheets.withIndex().filter { it.value.autoFilter != null }
        if (filters.isNotEmpty()) {
            append("<definedNames>")
            filters.forEach { (i, s) ->
                val abs = s.autoFilter!!.split(":").joinToString(":") { absRef(it) }
                append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">'${esc(s.name.replace("'", "''"))}'!$abs</definedName>""")
            }
            append("</definedNames>")
        }
        append("""<calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")
    }

    private fun workbookRels(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
        append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
    }

    private fun sheetXml(s: Sheet, selected: Boolean): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
        append("<sheetViews><sheetView workbookViewId=\"0\"${if (selected) " tabSelected=\"1\"" else ""}>")
        if (s.freezeRows > 0) append("""<pane ySplit="${s.freezeRows}" topLeftCell="A${s.freezeRows + 1}" activePane="bottomLeft" state="frozen"/>""")
        append("</sheetView></sheetViews>")
        append("""<sheetFormatPr defaultRowHeight="15"/>""")
        if (s.widths.isNotEmpty()) {
            append("<cols>")
            s.widths.toSortedMap().forEach { (c, w) -> append("""<col min="$c" max="$c" width="$w" customWidth="1"/>""") }
            append("</cols>")
        }
        append("<sheetData>")
        s.rows.forEach { (r, cells) ->
            append("<row r=\"$r\"")
            s.heights[r]?.let { append(" ht=\"$it\" customHeight=\"1\"") }
            append(">")
            cells.forEach { (c, cell) ->
                val ref = ref(r, c)
                val st = cell.styleId
                when (val v = cell.value) {
                    is Value.Str -> append("""<c r="$ref" s="$st" t="inlineStr"><is><t xml:space="preserve">${esc(v.v)}</t></is></c>""")
                    is Value.Num -> append("""<c r="$ref" s="$st"><v>${num(v.v)}</v></c>""")
                    is Value.Date -> append("""<c r="$ref" s="$st"><v>${excelSerial(v.v)}</v></c>""")
                    is Value.Formula -> append("""<c r="$ref" s="$st"><f>${esc(v.f)}</f><v>${num(v.cached)}</v></c>""")
                    Value.Blank -> append("""<c r="$ref" s="$st"/>""")
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        s.autoFilter?.let { append("""<autoFilter ref="$it"/>""") }
        if (s.merges.isNotEmpty()) {
            append("<mergeCells count=\"${s.merges.size}\">")
            s.merges.forEach { append("""<mergeCell ref="$it"/>""") }
            append("</mergeCells>")
        }
        append("""<printOptions horizontalCentered="1"/>""")
        append("""<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/>""")
        append("""<pageSetup paperSize="9" orientation="${if (s.landscape) "landscape" else "portrait"}" fitToWidth="1" fitToHeight="0"/>""")
        append("</worksheet>")
    }

    private fun stylesXml(): String {
        if (custom.isEmpty()) return STYLES
        val fonts = mutableListOf<String>(); val fills = mutableListOf<String>(); val borders = mutableListOf<String>(); val fmts = LinkedHashMap<String, Int>()
        val xfs = StringBuilder()
        custom.keys.forEach { x ->
            val font = "<font>${if (x.bold) "<b/>" else ""}${if (x.italic) "<i/>" else ""}<sz val=\"${x.size}\"/>${x.color?.let { "<color rgb=\"FF$it\"/>" } ?: ""}<name val=\"${esc(x.font)}\"/><family val=\"1\"/></font>"
            var fontId = fonts.indexOf(font); if (fontId < 0) { fonts += font; fontId = fonts.size - 1 }
            val fillId = x.fill?.let { rgb ->
                val f = "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF$rgb\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                var i = fills.indexOf(f); if (i < 0) { fills += f; i = fills.size - 1 }; BASE_FILLS + i
            } ?: 0
            val borderId = x.border?.let { b ->
                val side = "style=\"$b\"><color rgb=\"FF000000\"/>"
                val f = "<border><left $side</left><right $side</right><top $side</top><bottom $side</bottom><diagonal/></border>"
                var i = borders.indexOf(f); if (i < 0) { borders += f; i = borders.size - 1 }; BASE_BORDERS + i
            } ?: 0
            val numId = x.numFmt?.let { nf -> fmts.getOrPut(nf) { 167 + fmts.size } } ?: 0
            xfs.append("<xf numFmtId=\"$numId\" fontId=\"${BASE_FONTS + fontId}\" fillId=\"$fillId\" borderId=\"$borderId\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">")
            xfs.append("<alignment${x.hAlign?.let { " horizontal=\"$it\"" } ?: ""}${x.vAlign?.let { " vertical=\"$it\"" } ?: ""}${if (x.wrap) " wrapText=\"1\"" else ""}/></xf>")
        }
        var out = STYLES
        out = out.replace(Regex("<numFmts count=\"3\">(.*?)</numFmts>")) { m ->
            "<numFmts count=\"${3 + fmts.size}\">" + m.groupValues[1] + fmts.entries.joinToString("") { "<numFmt numFmtId=\"${it.value}\" formatCode=\"${esc(it.key)}\"/>" } + "</numFmts>"
        }
        out = out.replace("<fonts count=\"$BASE_FONTS\">", "<fonts count=\"${BASE_FONTS + fonts.size}\">").replace("</fonts>", fonts.joinToString("") + "</fonts>")
        out = out.replace("<fills count=\"$BASE_FILLS\">", "<fills count=\"${BASE_FILLS + fills.size}\">").replace("</fills>", fills.joinToString("") + "</fills>")
        out = out.replace("<borders count=\"$BASE_BORDERS\">", "<borders count=\"${BASE_BORDERS + borders.size}\">").replace("</borders>", borders.joinToString("") + "</borders>")
        out = out.replace("<cellXfs count=\"$BASE_XF_COUNT\">", "<cellXfs count=\"${BASE_XF_COUNT + custom.size}\">").replace("</cellXfs>", xfs.toString() + "</cellXfs>")
        return out
    }

    companion object {
        private const val BASE_FONTS = 6
        private const val BASE_FILLS = 4
        private const val BASE_BORDERS = 2
        const val BASE_XF_COUNT = 16

        fun colName(col: Int): String {
            var c = col; val sb = StringBuilder()
            while (c > 0) { val m = (c - 1) % 26; sb.insert(0, ('A' + m)); c = (c - 1) / 26 }
            return sb.toString()
        }
        fun ref(row: Int, col: Int) = "${colName(col)}$row"
        private fun absRef(r: String): String {
            val m = Regex("([A-Z]+)([0-9]+)").matchEntire(r) ?: return r
            return "$${m.groupValues[1]}$${m.groupValues[2]}"
        }
        fun excelSerial(d: LocalDate): Long = d.toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()
        private fun num(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        fun esc(s: String): String = buildString {
            for (ch in s) when {
                ch == '&' -> append("&amp;"); ch == '<' -> append("&lt;"); ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch.code < 0x20 && ch != '\n' && ch != '\t' && ch != '\r' -> {}
                else -> append(ch)
            }
        }

        private const val BORDER = """<border><left style="thin"><color rgb="FF7F7F7F"/></left><right style="thin"><color rgb="FF7F7F7F"/></right><top style="thin"><color rgb="FF7F7F7F"/></top><bottom style="thin"><color rgb="FF7F7F7F"/></bottom><diagonal/></border>"""

        // Style ids must match the Style enum order.
        private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="3"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="dd-mmm-yyyy"/><numFmt numFmtId="166" formatCode="0.0000"/></numFmts>
<fonts count="6">
<font><sz val="10"/><name val="Arial"/><family val="2"/></font>
<font><b/><sz val="14"/><color rgb="FF1F3864"/><name val="Arial"/><family val="2"/></font>
<font><b/><sz val="10"/><name val="Arial"/><family val="2"/></font>
<font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/><family val="2"/></font>
<font><i/><sz val="9"/><color rgb="FF595959"/><name val="Arial"/><family val="2"/></font>
<font><b/><sz val="11"/><color rgb="FF1F3864"/><name val="Arial"/><family val="2"/></font>
</fonts>
<fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FF1F3864"/><bgColor indexed="64"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFDDEBF7"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>$BORDER</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="16">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
<xf numFmtId="0" fontId="3" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="164" fontId="2" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="0" fontId="4" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="5" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
<xf numFmtId="0" fontId="2" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="166" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
    }
}
