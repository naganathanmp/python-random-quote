package com.tareimbursement.app.service.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ServerConfig(val url: String, val anonKey: String) {
    /** HTTPS only (plain http is accepted for localhost test servers, which Android blocks anyway). */
    val valid: Boolean get() = anonKey.length >= 20 && (url.startsWith("https://") || Regex("^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?$").matches(url))
}

data class Session(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String, val email: String, val phone: String) {
    /** The sign-in identity in the form used for invites (lower-case e-mail or +mobile). */
    val contact: String get() = email.ifBlank { if (phone.isBlank()) "" else "+" + phone.trimStart('+') }
    fun toJson(): String = JSONObject().put("a", accessToken).put("r", refreshToken).put("e", expiresAt).put("u", userId).put("m", email).put("p", phone).toString()

    companion object {
        fun fromJson(s: String?): Session? {
            if (s == null) return null
            return try {
                JSONObject(s).let { Session(it.getString("a"), it.getString("r"), it.getLong("e"), it.getString("u"), it.optString("m"), it.optString("p")) }
            } catch (e: Exception) { null }
        }
    }
}

interface SessionStore {
    fun load(): Session?
    fun save(s: Session?)
}

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Minimal Supabase client: GoTrue one-time-code sign-in, PostgREST tables/RPC and Storage.
 * Only the public "anon" key is ever on the phone; row-level security on the server decides
 * what each signed-in person can read or write.
 */
class SupabaseClient(private val config: () -> ServerConfig, private val store: SessionStore, private val now: () -> Long = System::currentTimeMillis) {

    @Volatile private var cached: Session? = store.load()
    val session: Session? get() = cached
    val signedIn: Boolean get() = cached != null && config().valid

    private fun base() = config().also { if (!it.valid) throw ApiException(0, "Team server is not set up (Settings > Team sharing).") }

    // ------------------------------------------------------------ auth

    /** Sends a 6-digit sign-in code by e-mail (or SMS when [contact] is a mobile number). */
    fun sendCode(contact: String) {
        val body = JSONObject().put("create_user", true)
        if (contact.contains('@')) body.put("email", contact.trim().lowercase()) else body.put("phone", normalizePhone(contact))
        // The e-mail's sign-in link opens the app (tareimburse://auth); the 6-digit code works too when the server's e-mail shows it.
        call("POST", "/auth/v1/otp?redirect_to=" + URLEncoder.encode(APP_REDIRECT, "UTF-8"), body.toString(), auth = false)
    }

    /**
     * Signs in from the link in the sign-in e-mail: either the app link the server redirects to
     * (tareimburse://auth#access_token=…&refresh_token=…) or the e-mail's own verify link
     * (…/auth/v1/verify?token=…&type=magiclink) copied and pasted into the app.
     */
    fun signInWithLink(link: String): Session {
        val l = link.trim()
        fun params(part: String) = part.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val p = params(l.substringAfter('#', "")) + params(l.substringAfter('?', "").substringBefore('#'))
        p["error_description"]?.let { throw ApiException(400, if (it.contains("expired", true) || it.contains("invalid", true)) "This sign-in link has expired or was already used. Request a new one." else it) }
        val access = p["access_token"]; val refresh = p["refresh_token"]
        val s = if (access != null && refresh != null) {
            // Ask the server who this token belongs to (also proves it was issued by our server).
            val c = open("GET", "/auth/v1/user", auth = false, headers = mapOf("Authorization" to "Bearer $access"))
            val user = JSONObject(finish(c))
            Session(access, refresh, now() + (p["expires_in"]?.toLongOrNull() ?: 3600) * 1000, user.getString("id"),
                user.optString("email", "").lowercase(), user.optString("phone", ""))
        } else {
            val token = p["token"] ?: p["token_hash"] ?: throw ApiException(400, "That is not a TA Reimburse sign-in link. Copy the whole link from the e-mail.")
            val type = p["type"]?.takeIf { it in setOf("magiclink", "signup", "email", "invite") } ?: "magiclink"
            sessionFrom(JSONObject(call("POST", "/auth/v1/verify", JSONObject().put("type", type).put("token_hash", token).toString(), auth = false)))
        }
        cached = s; store.save(s)
        return s
    }

    fun verifyCode(contact: String, code: String): Session {
        val email = contact.contains('@')
        val body = JSONObject().put("token", code.trim()).put("type", if (email) "email" else "sms")
        if (email) body.put("email", contact.trim().lowercase()) else body.put("phone", normalizePhone(contact))
        val s = sessionFrom(JSONObject(call("POST", "/auth/v1/verify", body.toString(), auth = false)))
        cached = s; store.save(s)
        return s
    }

    fun signOut() {
        try { if (cached != null) call("POST", "/auth/v1/logout", "{}") } catch (_: Exception) { }
        cached = null; store.save(null)
    }

    private fun sessionFrom(j: JSONObject): Session {
        val user = j.getJSONObject("user")
        return Session(
            j.getString("access_token"), j.getString("refresh_token"), now() + j.optLong("expires_in", 3600) * 1000,
            user.getString("id"), user.optString("email", "").lowercase(), user.optString("phone", ""),
        )
    }

    @Synchronized
    private fun fresh(): Session {
        val s = cached ?: throw ApiException(401, "Not signed in")
        if (s.expiresAt - now() > 60_000) return s
        return try {
            val r = sessionFrom(JSONObject(call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refreshToken).toString(), auth = false)))
            cached = r; store.save(r); r
        } catch (e: ApiException) {
            if (e.code in 400..499) { cached = null; store.save(null) }
            throw e
        }
    }

    // ------------------------------------------------------------ data

    /** GET rows: [query] is a PostgREST query string, e.g. "project_id=eq.<id>&order=updated_at.asc". */
    fun select(table: String, query: String): JSONArray = JSONArray(call("GET", "/rest/v1/$table?$query", null))

    /** Insert or update one row (by primary key). */
    fun upsert(table: String, row: JSONObject, onConflict: String? = null) {
        call("POST", "/rest/v1/$table" + (onConflict?.let { "?on_conflict=$it" } ?: ""), JSONArray().put(row).toString(),
            headers = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
    }

    fun insert(table: String, row: JSONObject) {
        call("POST", "/rest/v1/$table", JSONArray().put(row).toString(), headers = mapOf("Prefer" to "return=minimal"))
    }

    fun delete(table: String, query: String) { call("DELETE", "/rest/v1/$table?$query", null) }

    fun rpc(name: String, args: JSONObject): String = call("POST", "/rest/v1/rpc/$name", args.toString())

    // ------------------------------------------------------------ files

    fun upload(path: String, file: File, mime: String) {
        val c = open("POST", "/storage/v1/object/bills/${encPath(path)}", auth = true, headers = mapOf("x-upsert" to "true", "Content-Type" to mime.ifBlank { "application/octet-stream" }))
        c.doOutput = true
        c.setFixedLengthStreamingMode(file.length())
        file.inputStream().use { it.copyTo(c.outputStream) }
        finish(c)
    }

    fun download(path: String, dest: File) {
        val c = open("GET", "/storage/v1/object/authenticated/bills/${encPath(path)}", auth = true)
        val code = c.responseCode
        if (code !in 200..299) throw ApiException(code, "Download failed ($code)")
        val tmp = File(dest.parentFile, dest.name + ".part")
        c.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
    }

    // ------------------------------------------------------------ http

    private fun encPath(p: String) = p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    internal fun open(method: String, path: String, auth: Boolean, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val cfg = base()
        val c = URL(cfg.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("apikey", cfg.anonKey)
        c.setRequestProperty("Authorization", "Bearer " + if (auth) fresh().accessToken else cfg.anonKey)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun call(method: String, path: String, body: String?, auth: Boolean = true, headers: Map<String, String> = emptyMap()): String {
        val c = open(method, path, auth, headers)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return finish(c)
    }

    internal fun finish(c: HttpURLConnection): String {
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) {
                val msg = try { JSONObject(text).let { it.optString("msg").ifBlank { it.optString("message").ifBlank { it.optString("error_description").ifBlank { it.optString("error") } } } } } catch (e: Exception) { "" }
                throw ApiException(code, friendly(code, msg))
            }
            return text
        } finally { c.disconnect() }
    }

    private fun friendly(code: Int, msg: String) = when {
        msg.contains("Token has expired or is invalid", true) || msg.contains("otp", true) && code in 400..499 -> "The code is wrong or has expired. Request a new one."
        code == 429 -> "Too many requests - please wait a minute and try again."
        code == 401 -> "Signed out - please sign in again."
        code == 403 -> "Not allowed: ${msg.ifBlank { "you don't have access to this" }}"
        else -> msg.ifBlank { "Server error ($code)" }
    }

    companion object {
        const val APP_REDIRECT = "tareimburse://auth"

        /** "+91 98123 45678", "098123 45678", "9812345678" → "+919812345678" (India default). */
        fun normalizePhone(s: String): String {
            val digits = s.filter { it.isDigit() }
            return when {
                s.trim().startsWith("+") -> "+$digits"
                digits.length == 10 -> "+91$digits"
                digits.length == 11 && digits.startsWith("0") -> "+91" + digits.drop(1)
                digits.length == 12 && digits.startsWith("91") -> "+$digits"
                else -> "+$digits"
            }
        }

        fun normalizeContact(s: String): String = if (s.contains('@')) s.trim().lowercase() else normalizePhone(s)

        fun isValidContact(s: String): Boolean = if (s.contains('@')) Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(s.trim())
            else normalizePhone(s).length in 11..16
    }
}
