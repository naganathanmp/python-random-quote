package com.tareimbursement.app.sync

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * In-memory stand-in for the Supabase endpoints the app uses, enforcing the same access rules as
 * server/supabase-setup.sql (membership, owner-only project/member changes, creator-or-owner bill edits).
 */
class FakeSupabase : Dispatcher() {
    data class User(val id: String, val contact: String)
    val users = mutableMapOf<String, User>()          // contact -> user
    val projects = linkedMapOf<String, JSONObject>()
    val members = mutableListOf<JSONObject>()
    val tables = mapOf("expenses" to linkedMapOf<String, JSONObject>(), "attachments" to linkedMapOf(), "messages" to linkedMapOf())
    val files = mutableMapOf<String, ByteArray>()
    /** One-time e-mail link tokens (token_hash -> contact). */
    val links = mutableMapOf<String, String>()
    var lastOtpPath = ""
    private var clock = 0L
    var requests = 0

    private fun now(): String { clock++; return "2026-11-25T10:00:00.%06d+00:00".format(clock) }
    private fun json(code: Int, body: Any = "[]") = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun err(code: Int, msg: String) = json(code, JSONObject().put("message", msg))

    private fun userOf(r: RecordedRequest): User? {
        val tok = r.getHeader("Authorization")?.removePrefix("Bearer ") ?: return null
        return users.values.firstOrNull { "tok-${it.id}" == tok }
    }

    private fun isOwner(u: User, pid: String) = projects[pid]?.optString("owner") == u.id
    private fun isMember(u: User, pid: String) = isOwner(u, pid) || members.any { it.optString("project_id") == pid && it.optString("user_id") == u.id && it.optString("status") == "JOINED" }

    private fun session(u: User) = JSONObject().put("access_token", "tok-${u.id}").put("refresh_token", "ref-${u.id}").put("expires_in", 3600)
        .put("user", JSONObject().put("id", u.id).put("email", if (u.contact.contains('@')) u.contact else "").put("phone", if (u.contact.contains('@')) "" else u.contact.removePrefix("+")))

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests++
        val full = request.path ?: return err(404, "no path")
        val path = full.substringBefore('?')
        val query = full.substringAfter('?', "").split('&').filter { it.isNotBlank() }.associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        val bytes = request.body.readByteArray()
        val body = String(bytes, Charsets.UTF_8)
        if (request.getHeader("apikey") != "anon-key-for-tests-0123456789") return err(401, "bad apikey")

        // ---------------- auth
        when (path) {
            "/auth/v1/otp" -> { lastOtpPath = full; return json(200, "{}") }
            "/auth/v1/verify" -> {
                val b = JSONObject(body)
                if (b.has("token_hash")) {
                    val contact = links.remove(b.getString("token_hash")) ?: return err(403, "Email link is invalid or has expired")
                    return json(200, session(users.getOrPut(contact) { User(UUID.randomUUID().toString(), contact) }))
                }
                if (b.optString("token") != "123456") return err(403, "Token has expired or is invalid")
                val contact = if (b.has("email")) b.getString("email") else b.getString("phone")
                val u = users.getOrPut(contact) { User(UUID.randomUUID().toString(), contact) }
                return json(200, session(u))
            }
            "/auth/v1/token" -> {
                val rt = JSONObject(body).optString("refresh_token")
                val u = users.values.firstOrNull { "ref-${it.id}" == rt } ?: return err(400, "invalid refresh")
                return json(200, session(u))
            }
            "/auth/v1/logout" -> return MockResponse().setResponseCode(204)
            "/auth/v1/user" -> {
                val u = userOf(request) ?: return err(401, "invalid JWT")
                return json(200, session(u).getJSONObject("user"))
            }
        }
        val u = userOf(request) ?: return err(401, "JWT expired")

        // ---------------- storage
        if (path.startsWith("/storage/v1/object/")) {
            val key = URLDecoder.decode(path.substringAfter("/bills/"), "UTF-8")
            val pid = key.substringBefore('/')
            if (!isMember(u, pid)) return err(403, "new row violates row-level security policy")
            return if (request.method == "POST") { files[key] = bytes; json(200, JSONObject().put("Key", key)) }
            else files[key]?.let { MockResponse().setResponseCode(200).setBody(Buffer().write(it)) } ?: err(404, "not found")
        }

        // ---------------- rpc
        if (path == "/rest/v1/rpc/accept_invites") {
            val out = JSONArray()
            members.filter { it.optString("contact") == u.contact && it.optString("status") != "REMOVED" && (it.isNull("user_id") || it.optString("user_id") == u.id) }.forEach {
                it.put("user_id", u.id).put("status", "JOINED").put("updated_at", now()); out.put(it.getString("project_id"))
            }
            return json(200, out)
        }

        val table = path.removePrefix("/rest/v1/")
        return when (request.method) {
            "GET" -> json(200, select(u, table, query))
            "POST" -> { val rows = JSONArray(body); for (i in 0 until rows.length()) { val e = write(u, table, rows.getJSONObject(i), request.getHeader("Prefer").orEmpty()); if (e != null) return e }; json(201, "") }
            else -> err(405, "method")
        }
    }

    private fun select(u: User, table: String, q: Map<String, String>): JSONArray {
        val rows: List<JSONObject> = when (table) {
            "projects" -> projects.values.filter { isMember(u, it.getString("id")) }
            "project_members" -> members.filter { isMember(u, it.getString("project_id")) || it.optString("user_id") == u.id || it.optString("contact") == u.contact }
            else -> tables.getValue(table).values.filter { isMember(u, it.getString("project_id")) }
        }
        var out = rows.filter { r ->
            q.all { (k, v) ->
                when {
                    k in setOf("select", "order", "limit", "on_conflict") -> true
                    v.startsWith("eq.") -> r.optString(k) == v.removePrefix("eq.")
                    v.startsWith("gt.") -> r.optString(k) > v.removePrefix("gt.")
                    else -> true
                }
            }
        }
        if (q["order"] == "updated_at.asc") out = out.sortedBy { it.optString("updated_at") }
        q["limit"]?.toIntOrNull()?.let { out = out.take(it) }
        return JSONArray(out.map { JSONObject(it.toString()) })
    }

    private fun write(u: User, table: String, row: JSONObject, prefer: String): MockResponse? {
        val ts = now()
        when (table) {
            "projects" -> {
                val id = row.getString("id"); val cur = projects[id]
                if (cur == null) { if (row.optString("owner") != u.id) return err(403, "rls"); projects[id] = JSONObject(row.toString()).put("updated_at", ts) }
                else { if (cur.optString("owner") != u.id) return err(403, "rls"); row.keys().forEach { k -> cur.put(k, row.get(k)) }; cur.put("updated_at", ts) }
            }
            "project_members" -> {
                val pid = row.getString("project_id")
                if (!isOwner(u, pid)) return err(403, "rls")
                val cur = members.firstOrNull { it.getString("project_id") == pid && it.getString("contact") == row.getString("contact") }
                if (cur == null) members += JSONObject(row.toString()).apply { if (!has("user_id")) put("user_id", JSONObject.NULL) }.put("updated_at", ts)
                else { row.keys().forEach { k -> cur.put(k, row.get(k)) }; cur.put("updated_at", ts) }
            }
            "messages" -> {
                val pid = row.getString("project_id"); if (!isMember(u, pid)) return err(403, "rls")
                tables.getValue(table)[row.getString("id")] = JSONObject(row.toString()).put("author", u.id).put("created_at", ts).put("updated_at", ts)
            }
            else -> {
                val pid = row.getString("project_id"); if (!isMember(u, pid)) return err(403, "rls")
                val t = tables.getValue(table); val cur = t[row.getString("id")]
                if (cur == null) t[row.getString("id")] = JSONObject(row.toString()).put("created_by", u.id).put("updated_at", ts)
                else {
                    if (cur.optString("created_by") != u.id && !isOwner(u, pid)) return err(403, "new row violates row-level security policy (USING expression)")
                    row.keys().forEach { k -> cur.put(k, row.get(k)) }; cur.put("updated_at", ts)
                }
            }
        }
        return null
    }
}
