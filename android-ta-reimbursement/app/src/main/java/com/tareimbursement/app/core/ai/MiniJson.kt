package com.tareimbursement.app.core.ai

/**
 * Tiny, lenient JSON reader for model output (no Android/org.json dependency so it runs in JVM
 * tests). Accepts the first JSON object found in the text, tolerates trailing commas, single
 * quotes, code fences and chatter before/after the object.
 */
object MiniJson {
    fun firstObject(text: String): Map<String, Any?>? {
        val start = text.indexOf('{')
        if (start < 0) return null
        return try { Parser(text, start).parseObject() } catch (e: Exception) { null }
    }

    private class Parser(val s: String, var i: Int) {
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun parseValue(): Any? {
            ws()
            if (i >= s.length) throw IllegalStateException("eof")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> parseString(c)
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { if (s.startsWith("null", i)) { i += 4; null } else throw IllegalStateException("bad n") }
                else -> parseNumber()
            }
        }
        fun expect(w: String) { if (!s.startsWith(w, i)) throw IllegalStateException("expected $w"); i += w.length }
        fun parseObject(): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            while (true) {
                ws()
                if (i >= s.length) return m // truncated output: keep what we have
                if (s[i] == '}') { i++; return m }
                if (s[i] == ',') { i++; continue }
                val key = if (s[i] == '"' || s[i] == '\'') parseString(s[i]) else parseBareKey()
                ws(); if (i < s.length && s[i] == ':') i++ else throw IllegalStateException("colon")
                m[key] = try { parseValue() } catch (e: IllegalStateException) { if (i >= s.length) return m else throw e }
            }
        }
        fun parseBareKey(): String { val st = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++; return s.substring(st, i) }
        fun parseArray(): List<Any?> {
            i++
            val l = mutableListOf<Any?>()
            while (true) {
                ws()
                if (i >= s.length) return l
                if (s[i] == ']') { i++; return l }
                if (s[i] == ',') { i++; continue }
                l += parseValue()
            }
        }
        fun parseString(q: Char): String {
            i++
            val sb = StringBuilder()
            while (i < s.length && s[i] != q) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> {}
                        'u' -> { if (i + 4 < s.length) { sb.append(s.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: '?'); i += 4 } }
                        else -> sb.append(e)
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun parseNumber(): Any? {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE,₹")) i++
            val raw = s.substring(st, i).replace(",", "").replace("₹", "")
            if (raw.isEmpty()) { // unquoted word value (e.g. Local Conveyance) — read to delimiter
                while (i < s.length && s[i] !in ",}\n") i++
                return s.substring(st, i).trim()
            }
            return raw.toDoubleOrNull() ?: raw
        }
    }
}
