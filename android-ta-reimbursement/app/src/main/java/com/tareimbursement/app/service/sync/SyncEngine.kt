package com.tareimbursement.app.service.sync

import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.data.AttachmentEntity
import com.tareimbursement.app.data.BillingEntity
import com.tareimbursement.app.data.EventType
import com.tareimbursement.app.data.MessageEntity
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.TravellerEntity
import com.tareimbursement.app.data.TripEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Where each project's sync stopped, per table (server timestamps). */
interface CursorStore {
    fun get(key: String): String
    fun put(key: String, value: String)
    fun clear(prefix: String)
}

/** What arrived from team members in one sync (used for notifications). */
data class Incoming(val messages: List<MessageEntity> = emptyList(), val bills: Int = 0, val tripTitles: Set<String> = emptySet()) {
    operator fun plus(o: Incoming) = Incoming(messages + o.messages, bills + o.bills, tripTitles + o.tripTitles)
}

/**
 * Keeps shared projects the same on every team member's phone. Changes are pushed row by row
 * (so one refused row never blocks the rest); the server's clock orders everything; a bill
 * edited on this phone and not yet uploaded is never overwritten by an older server copy.
 */
class SyncEngine(
    private val repo: Repository,
    private val client: SupabaseClient,
    private val cursors: CursorStore,
    private val myName: () -> String,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _status
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private fun me() = client.session ?: throw ApiException(401, "Please sign in to Team sharing first (Settings).")
    private fun q(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun cursorKey(t: TripEntity, table: String) = "${t.uid}:$table"

    // ------------------------------------------------------------ sharing & invites

    /** Turns a local claim into a team project owned by the signed-in person. */
    suspend fun shareTrip(tripId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = me()
            val t0 = repo.trips.get(tripId) ?: return@withLock
            if (t0.shared) return@withLock
            val people = repo.travellers.forTrip(tripId)
            val self = people.firstOrNull { it.isSelf } ?: TravellerEntity(tripId = tripId, name = myName().ifBlank { s.contact }, isSelf = true).let { it.copy(id = repo.travellers.insert(it)) }
            repo.travellers.update(self.copy(userUid = s.userId, contact = s.contact, inviteStatus = "JOINED"))
            val t = t0.copy(shared = true, ownerUid = s.userId, myRole = "OWNER", dirty = true, updatedAt = System.currentTimeMillis())
            repo.trips.update(t)
            client.upsert("projects", JSONObject().put("id", t.uid).put("owner", s.userId).put("data", tripData(t)).put("deleted", false))
            client.upsert("project_members", JSONObject().put("project_id", t.uid).put("contact", s.contact).put("user_id", s.userId)
                .put("role", "OWNER").put("status", "JOINED").put("display_name", self.name).put("traveller_uid", self.uid), onConflict = "project_id,contact")
            repo.trips.update(t.copy(dirty = false))
            repo.expenses.forTrip(tripId).forEach { repo.expenses.update(it.copy(dirty = true)) }
            repo.attachments.forTrip(tripId).forEach { repo.attachments.update(it.copy(dirty = true)) }
            repo.logEvent(tripId, EventType.SHARED, "Shared with the team")
        }
        syncTrip(tripId)
    }

    /**
     * Invites a traveller by e-mail or mobile. Returns the invitation text to send them
     * (WhatsApp / SMS / e-mail) — they join by signing in with that same e-mail or mobile.
     */
    suspend fun invite(tripId: Long, travellerId: Long, contact: String): String = withContext(Dispatchers.IO) {
        val c = SupabaseClient.normalizeContact(contact)
        require(SupabaseClient.isValidContact(c)) { "Enter a valid e-mail or mobile number" }
        mutex.withLock {
            me()
            val t = repo.trips.get(tripId) ?: error("Claim not found")
            require(t.shared && t.myRole == "OWNER") { "Only the project owner can invite people" }
            val p = repo.travellers.forTrip(tripId).firstOrNull { it.id == travellerId } ?: error("Person not found")
            repo.travellers.update(p.copy(contact = c, inviteStatus = if (p.inviteStatus == "JOINED" && p.contact == c) "JOINED" else "INVITED"))
            client.upsert("project_members", JSONObject().put("project_id", t.uid).put("contact", c).put("display_name", p.name)
                .put("traveller_uid", p.uid).put("role", "MEMBER").put("status", "INVITED"), onConflict = "project_id,contact")
            pushTrip(repo.trips.get(tripId)!!.copy(dirty = true))
            "You're invited to the TA project \"${t.title}\" (${t.clientName}) in the TA Reimburse app.\n" +
                "1. Install TA Reimburse.\n2. Settings > Team sharing > sign in with $c.\n3. The project appears on your Claims screen - add your bills there."
        }
    }

    suspend fun removeMember(tripId: Long, travellerId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val t = repo.trips.get(tripId) ?: return@withLock
            val p = repo.travellers.forTrip(tripId).firstOrNull { it.id == travellerId } ?: return@withLock
            if (t.shared && p.contact.isNotBlank()) client.upsert("project_members", JSONObject().put("project_id", t.uid).put("contact", p.contact).put("status", "REMOVED").put("user_id", JSONObject.NULL), onConflict = "project_id,contact")
            repo.travellers.update(p.copy(inviteStatus = "", userUid = ""))
        }
    }

    /**
     * Removes a team project from this phone. The owner also closes it on the server (members keep
     * their copy, marked as removed); a member just stops receiving it here.
     */
    suspend fun forget(tripId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val t = repo.trips.get(tripId) ?: return@withLock
            cursors.put("hidden:${t.uid}", "1")
            cursors.clear("${t.uid}:")
            if (t.shared && t.myRole == "OWNER" && client.signedIn) {
                try { client.upsert("projects", JSONObject().put("id", t.uid).put("owner", t.ownerUid).put("deleted", true)) } catch (_: Exception) { }
            }
        }
    }

    suspend fun sendMessage(tripId: Long, body: String, expenseUid: String = ""): MessageEntity {
        val s = me()
        val m = MessageEntity(tripId = tripId, body = body.trim().take(4000), expenseUid = expenseUid, authorUid = s.userId, authorName = myName().ifBlank { s.contact })
        val id = repo.messages.insert(m)
        try { syncTrip(tripId) } catch (_: Exception) { /* stays unsent; retried by the next sync */ }
        return m.copy(id = id)
    }

    // ------------------------------------------------------------ sync

    /** Joins invitations, discovers new projects and syncs every shared claim. */
    suspend fun syncAll(): Incoming = withContext(Dispatchers.IO) {
        if (!client.signedIn) return@withContext Incoming()
        _syncing.value = true
        try {
            mutex.withLock {
                client.rpc("accept_invites", JSONObject().put("my_name", myName()))
                val visible = client.select("projects", "select=id,owner,data,deleted")
                for (i in 0 until visible.length()) {
                    val r = visible.getJSONObject(i)
                    if (r.optBoolean("deleted") || cursors.get("hidden:" + r.getString("id")).isNotBlank()) continue
                    if (repo.trips.byUid(r.getString("id")) == null) createMemberCopy(r)
                }
            }
            var inc = Incoming()
            repo.trips.sharedTrips().forEach { t -> inc += syncTrip(t.id) }
            _status.value = null
            inc
        } catch (e: Exception) {
            _status.value = e.message ?: "Sync failed"
            throw e
        } finally { _syncing.value = false }
    }

    suspend fun syncTrip(tripId: Long): Incoming = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = me()
            val t = repo.trips.get(tripId) ?: return@withLock Incoming()
            if (!t.shared) return@withLock Incoming()
            val owner = t.ownerUid == s.userId
            if (owner && t.dirty) pushTrip(t)
            pushExpenses(t)
            pushAttachments(t)
            pushMessages(t)
            if (!owner) pullTrip(t)
            pullMembers(t, owner)
            val bills = pullExpenses(t, s.userId)
            pullAttachments(t)
            val msgs = pullMessages(t, s.userId)
            repo.trips.get(tripId)?.let { cur ->
                if (owner && cur.dirty) pushTrip(cur)
                repo.trips.update(cur.copy(syncedAt = System.currentTimeMillis(), dirty = if (owner) false else cur.dirty))
            }
            Incoming(msgs, bills, if (msgs.isNotEmpty() || bills > 0) setOf(t.title) else emptySet())
        }
    }

    // ------------------------------------------------------------ push

    private suspend fun tripData(t: TripEntity): JSONObject = SyncMapper.tripJson(t, repo.travellers.forTrip(t.id), repo.entities.forTrip(t.id))

    private suspend fun pushTrip(t: TripEntity) {
        client.upsert("projects", JSONObject().put("id", t.uid).put("owner", t.ownerUid).put("data", tripData(t)).put("deleted", false))
        repo.trips.get(t.id)?.let { repo.trips.update(it.copy(dirty = false)) }
    }

    private suspend fun pushExpenses(t: TripEntity) {
        val people = repo.travellers.forTrip(t.id).associate { it.id to it.uid }
        val ents = repo.entities.forTrip(t.id).associate { it.id to "${it.name}|${it.code}" }
        for (e in repo.expenses.dirtyForTrip(t.id)) {
            try {
                client.upsert("expenses", JSONObject().put("id", e.uid).put("project_id", t.uid)
                    .put("data", SyncMapper.expenseJson(e, { people[it] }, { ents[it] })).put("deleted", e.deleted))
                repo.expenses.clearDirty(e.id, e.updatedAt)
            } catch (x: ApiException) {
                if (x.code == 401 || x.code == 0 || x.code >= 500) throw x
                // Refused (e.g. someone else's bill): drop the local change and take the server copy again.
                repo.expenses.clearDirty(e.id, e.updatedAt)
                cursors.put(cursorKey(t, "expenses"), "")
            }
        }
    }

    private suspend fun pushAttachments(t: TripEntity) {
        for (a in repo.attachments.dirtyForTrip(t.id)) {
            val e = repo.expenses.get(a.expenseId) ?: continue
            try {
                var path = a.remotePath
                if (!a.deleted && path.isBlank()) {
                    val f = repo.fileOf(a)
                    if (!f.exists()) { repo.attachments.update(a.copy(dirty = false)); continue }
                    path = "${t.uid}/${a.uid}.${a.extension}"
                    client.upload(path, f, a.mimeType)
                }
                client.upsert("attachments", JSONObject().put("id", a.uid).put("project_id", t.uid).put("expense_id", e.uid)
                    .put("storage_path", path.ifBlank { "${t.uid}/${a.uid}.${a.extension}" }).put("sha256", a.sha256).put("deleted", a.deleted)
                    .put("data", JSONObject().put("name", a.originalName).put("mime", a.mimeType).put("kind", a.kind).put("primary", a.isPrimary)
                        .put("size", a.sizeBytes).put("dHash", a.dHash ?: JSONObject.NULL).put("addedAt", a.addedAt)))
                repo.attachments.get(a.id)?.let { repo.attachments.update(it.copy(dirty = false, remotePath = path)) }
            } catch (x: ApiException) {
                if (x.code == 401 || x.code == 0 || x.code >= 500) throw x
                repo.attachments.get(a.id)?.let { repo.attachments.update(it.copy(dirty = false)) }
            }
        }
    }

    private suspend fun pushMessages(t: TripEntity) {
        for (m in repo.messages.unsent(t.id)) {
            client.insert("messages", JSONObject().put("id", m.uid).put("project_id", t.uid).put("expense_id", m.expenseUid.ifBlank { JSONObject.NULL })
                .put("author_name", m.authorName).put("body", m.body))
            repo.messages.update(m.copy(sent = true))
        }
    }

    // ------------------------------------------------------------ pull

    private suspend fun createMemberCopy(r: JSONObject) {
        val d = r.optJSONObject("data") ?: JSONObject()
        val s = me()
        val base = TripEntity(title = "", clientName = "", projectCode = "", location = "", purpose = "", startEpochDay = 0, endEpochDay = 0,
            uid = r.getString("id"), ownerUid = r.optString("owner"), shared = true, myRole = if (r.optString("owner") == s.userId) "OWNER" else "MEMBER")
        val t = SyncMapper.applyTrip(base, d)
        val id = repo.trips.insert(t)
        applyTravellers(repo.trips.get(id)!!, d)
        applyEntities(id, d)
        repo.logEvent(id, EventType.SHARED, "Joined the team project")
    }

    private suspend fun pullTrip(t: TripEntity) {
        val rows = client.select("projects", "id=eq.${t.uid}&select=id,owner,data,deleted")
        if (rows.length() == 0) {
            // No longer visible: removed from the team. Keep the local copy, read-only.
            repo.trips.update(t.copy(shared = false, myRole = "REMOVED"))
            return
        }
        if (rows.getJSONObject(0).optBoolean("deleted")) { repo.trips.update(t.copy(shared = false, myRole = "REMOVED")); return }
        val d = rows.getJSONObject(0).optJSONObject("data") ?: return
        val updated = SyncMapper.applyTrip(t, d)
        if (updated != t) repo.trips.update(updated)
        applyTravellers(updated, d)
        applyEntities(t.id, d)
    }

    private suspend fun applyTravellers(t: TripEntity, d: JSONObject) {
        val remote = SyncMapper.travellers(d)
        if (remote.isEmpty()) return
        val me = client.session
        val local = repo.travellers.forTrip(t.id)
        remote.forEach { r ->
            val isMe = me != null && (r.userUid == me.userId || (r.userUid.isBlank() && r.contact.isNotBlank() && r.contact == me.contact))
            val cur = local.firstOrNull { it.uid == r.uid } ?: repo.travellers.byUid(r.uid)
            val row = (cur ?: TravellerEntity(tripId = t.id, name = r.name, uid = r.uid)).copy(
                tripId = t.id, name = r.name, employeeCode = r.code, userUid = r.userUid, contact = r.contact, inviteStatus = r.status,
                sortOrder = r.order, isSelf = isMe,
            )
            if (cur == null) repo.travellers.insert(row) else if (row != cur) repo.travellers.update(row)
        }
        val keep = remote.map { it.uid }.toSet()
        local.filter { it.uid !in keep }.forEach { repo.travellers.delete(it) }
    }

    private suspend fun applyEntities(tripId: Long, d: JSONObject) {
        val remote = SyncMapper.entities(d)
        val local = repo.entities.forTrip(tripId)
        if (remote.map { Triple(it.first, it.second, it.third) } == local.map { Triple(it.name, it.code, it.sharePercent) }) return
        remote.forEachIndexed { i, (name, code, share) ->
            val cur = local.firstOrNull { it.name == name && it.code == code }
            if (cur == null) repo.entities.insert(BillingEntity(tripId = tripId, name = name, code = code, sharePercent = share, sortOrder = i))
            else repo.entities.update(cur.copy(sharePercent = share, sortOrder = i))
        }
        local.filter { l -> remote.none { it.first == l.name && it.second == l.code } }.forEach { repo.entities.unassign(it.id); repo.entities.delete(it) }
    }

    /** Owner: learns who joined. Member: nothing to do (travellers come with the project). */
    private suspend fun pullMembers(t: TripEntity, owner: Boolean) {
        if (!owner) return
        val rows = client.select("project_members", "project_id=eq.${t.uid}&select=contact,user_id,status,traveller_uid,display_name")
        var changed = false
        val people = repo.travellers.forTrip(t.id)
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val p = people.firstOrNull { it.uid == r.optString("traveller_uid") } ?: people.firstOrNull { it.contact == r.optString("contact") } ?: continue
            val status = r.optString("status").let { if (it == "REMOVED") "" else it }
            val user = if (r.isNull("user_id")) "" else r.optString("user_id")
            if (p.inviteStatus != status || p.userUid != user) { repo.travellers.update(p.copy(inviteStatus = status, userUid = user)); changed = true }
        }
        if (changed) repo.trips.get(t.id)?.let { repo.trips.update(it.copy(dirty = true)) }
    }

    private suspend fun pullExpenses(t: TripEntity, myUid: String): Int {
        val key = cursorKey(t, "expenses")
        var cursor = cursors.get(key)
        var fromOthers = 0
        while (true) {
            val rows = client.select("expenses", "project_id=eq.${t.uid}&select=id,data,deleted,created_by,updated_at&order=updated_at.asc&limit=500" + (if (cursor.isNotBlank()) "&updated_at=gt.${q(cursor)}" else ""))
            if (rows.length() == 0) break
            val idOf = repo.travellers.forTrip(t.id).associate { it.uid to it.id }
            val entOf = repo.entities.forTrip(t.id).associate { "${it.name}|${it.code}" to it.id }
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val uid = r.getString("id")
                cursor = r.getString("updated_at")
                val local = repo.expenses.byUid(uid)
                if (local != null && local.dirty) continue
                if (r.optBoolean("deleted")) {
                    if (local != null && !local.deleted) {
                        repo.attachments.forExpense(local.id).forEach { repo.fileOf(it).delete() }
                        repo.expenses.update(local.copy(deleted = true, dirty = false))
                    }
                    continue
                }
                val e = SyncMapper.expenseFrom(r.getJSONObject("data"), uid, t.id, local?.takeIf { !it.deleted } ?: local?.copy(deleted = false), { idOf[it] }, { entOf[it] })
                if (local == null) { repo.expenses.insert(e.copy(id = 0)); if (r.optString("created_by") != myUid) fromOthers++ }
                else if (e != local) repo.expenses.update(e.copy(id = local.id))
            }
            cursors.put(key, cursor)
            if (rows.length() < 500) break
        }
        return fromOthers
    }

    private suspend fun pullAttachments(t: TripEntity) {
        val key = cursorKey(t, "attachments")
        var cursor = cursors.get(key)
        while (true) {
            val rows = client.select("attachments", "project_id=eq.${t.uid}&select=id,expense_id,storage_path,sha256,data,deleted,updated_at&order=updated_at.asc&limit=200" + (if (cursor.isNotBlank()) "&updated_at=gt.${q(cursor)}" else ""))
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val uid = r.getString("id")
                val local = repo.attachments.byUid(uid)
                if (local != null && local.dirty) { cursor = r.getString("updated_at"); continue }
                val exp = repo.expenses.byUid(r.getString("expense_id"))
                if (exp == null) break // its bill hasn't arrived yet: retry from here next time
                cursor = r.getString("updated_at")
                val d = r.optJSONObject("data") ?: JSONObject()
                if (r.optBoolean("deleted")) {
                    if (local != null) { repo.fileOf(local).delete(); repo.attachments.update(local.copy(deleted = true)) }
                    continue
                }
                if (local == null) {
                    val ext = r.getString("storage_path").substringAfterLast('.', "bin").take(5)
                    val f = File(repo.attachmentDir(t.id), "$uid.$ext")
                    if (!f.exists()) client.download(r.getString("storage_path"), f)
                    repo.attachments.insert(AttachmentEntity(
                        expenseId = exp.id, tripId = t.id, storedPath = f.relativeTo(repo.filesDir).path, originalName = d.optString("name", f.name),
                        mimeType = d.optString("mime", "application/octet-stream"), kind = d.optString("kind", "OTHER"), isPrimary = d.optBoolean("primary", true),
                        sizeBytes = f.length(), addedAt = d.optLong("addedAt", System.currentTimeMillis()), uid = uid, sha256 = r.optString("sha256"),
                        dHash = if (d.isNull("dHash")) null else d.optLong("dHash"), remotePath = r.getString("storage_path"),
                    ))
                } else {
                    val upd = local.copy(expenseId = exp.id, isPrimary = d.optBoolean("primary", local.isPrimary), deleted = false)
                    if (upd != local) repo.attachments.update(upd)
                }
            }
            cursors.put(key, cursor)
            if (rows.length() < 200) break
        }
    }

    private suspend fun pullMessages(t: TripEntity, myUid: String): List<MessageEntity> {
        val key = cursorKey(t, "messages")
        var cursor = cursors.get(key)
        val incoming = mutableListOf<MessageEntity>()
        while (true) {
            val rows = client.select("messages", "project_id=eq.${t.uid}&select=id,expense_id,author,author_name,body,created_at,updated_at&order=updated_at.asc&limit=500" + (if (cursor.isNotBlank()) "&updated_at=gt.${q(cursor)}" else ""))
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                cursor = r.getString("updated_at")
                val uid = r.getString("id")
                val existing = repo.messages.byUid(uid)
                if (existing != null) { if (!existing.sent) repo.messages.update(existing.copy(sent = true)); continue }
                val m = MessageEntity(tripId = t.id, uid = uid, expenseUid = if (r.isNull("expense_id")) "" else r.optString("expense_id"),
                    authorUid = r.optString("author"), authorName = r.optString("author_name"), body = r.optString("body"),
                    createdAt = parseTime(r.optString("created_at")) ?: System.currentTimeMillis(), sent = true)
                repo.messages.insert(m)
                if (m.authorUid != myUid) incoming += m
            }
            cursors.put(key, cursor)
            if (rows.length() < 500) break
        }
        return incoming
    }

    companion object {
        fun parseTime(s: String): Long? = try { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) { null }
        /** Members can add and edit their own bills; only the owner edits claim details and generates / submits. */
        fun canManage(t: TripEntity) = !t.shared || t.myRole == "OWNER"
        fun statusLabel(t: TripEntity) = if (t.myRole == "REMOVED") "Removed from team" else ClaimStatus.of(t.status).label
    }
}

