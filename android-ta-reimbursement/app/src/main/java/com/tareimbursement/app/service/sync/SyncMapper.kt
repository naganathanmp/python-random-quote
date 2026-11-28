package com.tareimbursement.app.service.sync

import com.tareimbursement.app.core.Shares
import com.tareimbursement.app.data.BillingEntity
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.data.TravellerEntity
import com.tareimbursement.app.data.TripEntity
import org.json.JSONArray
import org.json.JSONObject

/** Converts claims and bills to the JSON stored on the team server and back. Local row ids never leave the phone. */
object SyncMapper {

    private fun JSONObject.optStr(k: String): String = if (isNull(k)) "" else optString(k, "")
    private fun JSONObject.optD(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
    private fun JSONObject.optL(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)

    fun tripJson(t: TripEntity, travellers: List<TravellerEntity>, entities: List<BillingEntity>): JSONObject = JSONObject()
        .put("title", t.title).put("clientName", t.clientName).put("projectCode", t.projectCode).put("location", t.location)
        .put("purpose", t.purpose).put("start", t.startEpochDay).put("end", t.endEpochDay).put("advance", t.advance)
        .put("status", t.status).put("claimNumber", t.claimNumber).put("notes", t.notes).put("currency", t.currency)
        .put("splitMode", t.splitMode).put("onwardBookedBy", t.onwardBookedBy).put("returnBookedBy", t.returnBookedBy)
        .put("budget", t.budget ?: JSONObject.NULL).put("dailyLimit", t.dailyLimit ?: JSONObject.NULL).put("dailyLimitPerPerson", t.dailyLimitPerPerson)
        .put("travellers", JSONArray().apply {
            travellers.forEach { p -> put(JSONObject().put("uid", p.uid).put("name", p.name).put("code", p.employeeCode).put("userUid", p.userUid).put("contact", p.contact).put("status", p.inviteStatus).put("order", p.sortOrder)) }
        })
        .put("entities", JSONArray().apply {
            entities.forEach { e -> put(JSONObject().put("name", e.name).put("code", e.code).put("share", e.sharePercent)) }
        })

    /** Applies the owner's claim details onto a member's local copy. */
    fun applyTrip(local: TripEntity, d: JSONObject): TripEntity = local.copy(
        title = d.optStr("title"), clientName = d.optStr("clientName"), projectCode = d.optStr("projectCode"), location = d.optStr("location"),
        purpose = d.optStr("purpose"), startEpochDay = d.optL("start") ?: local.startEpochDay, endEpochDay = d.optL("end") ?: local.endEpochDay,
        advance = d.optD("advance") ?: 0.0, status = d.optStr("status").ifBlank { local.status }, claimNumber = d.optStr("claimNumber").ifBlank { local.claimNumber },
        notes = d.optStr("notes"), currency = d.optStr("currency").ifBlank { "INR" }, splitMode = d.optStr("splitMode").ifBlank { "NONE" },
        onwardBookedBy = d.optStr("onwardBookedBy").ifBlank { "Self Booking" }, returnBookedBy = d.optStr("returnBookedBy").ifBlank { "Self Booking" },
        budget = d.optD("budget"), dailyLimit = d.optD("dailyLimit"), dailyLimitPerPerson = d.optBoolean("dailyLimitPerPerson", false),
    )

    data class RemoteTraveller(val uid: String, val name: String, val code: String, val userUid: String, val contact: String, val status: String, val order: Int)

    fun travellers(d: JSONObject): List<RemoteTraveller> {
        val a = d.optJSONArray("travellers") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { RemoteTraveller(it.optStr("uid"), it.optStr("name"), it.optStr("code"), it.optStr("userUid"), it.optStr("contact"), it.optStr("status"), it.optInt("order", i)) } }
            .filter { it.uid.isNotBlank() && it.name.isNotBlank() }
    }

    fun entities(d: JSONObject): List<Triple<String, String, Double>> {
        val a = d.optJSONArray("entities") ?: return emptyList()
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { Triple(it.optStr("name"), it.optStr("code"), it.optDouble("share", 0.0)) } }
    }

    /** [uidOf] maps a local traveller id to its global uid; [entityKey] a local entity id to "name|code". */
    fun expenseJson(e: ExpenseEntity, uidOf: (Long) -> String?, entityKey: (Long) -> String?): JSONObject {
        val shares = Shares.parse(e.shares).entries.mapNotNull { (id, v) -> uidOf(id)?.let { "$it=$v" } }.joinToString(";")
        return JSONObject()
            .put("date", e.dateEpochDay ?: JSONObject.NULL).put("amount", e.amount ?: JSONObject.NULL).put("currency", e.currency)
            .put("foreignAmount", e.foreignAmount ?: JSONObject.NULL).put("exchangeRate", e.exchangeRate ?: JSONObject.NULL)
            .put("vendor", e.vendor).put("billNo", e.billNo).put("category", e.category ?: JSONObject.NULL)
            .put("suggestedCategory", e.suggestedCategory ?: JSONObject.NULL).put("suggestionReason", e.suggestionReason)
            .put("alternatives", e.suggestionAlternatives).put("nature", e.nature).put("remarks", e.description)
            .put("confirmed", e.confirmed).put("noBill", e.noBill).put("dateConfident", e.dateConfident).put("amountConfident", e.amountConfident)
            .put("mode", e.modeOfTravel).put("km", e.mileageKm ?: JSONObject.NULL).put("rate", e.mileageRate ?: JSONObject.NULL).put("toll", e.tollAmount ?: JSONObject.NULL)
            .put("shares", shares).put("entity", e.entityId?.let(entityKey) ?: JSONObject.NULL).put("entityLocked", e.entityLocked)
            .put("addedBy", e.addedBy).put("addedByUid", e.addedByUid).put("createdAt", e.createdAt)
            .put("trustLevel", e.trustLevel).put("trustReport", e.trustReport).put("trustAck", e.trustAck)
            .put("dupNote", e.dupNote).put("dupStrong", e.dupStrong).put("dateFromMetadata", e.dateFromMetadata)
            .put("aiNote", e.aiNote).put("ocrText", e.ocrText.take(8000))
    }

    /** Builds / updates the local bill from the server row. [idOf] maps traveller uid → local id; [entityId] "name|code" → local id. */
    fun expenseFrom(d: JSONObject, uid: String, tripId: Long, base: ExpenseEntity?, idOf: (String) -> Long?, entityId: (String) -> Long?): ExpenseEntity {
        val shares = d.optStr("shares").split(';').mapNotNull { p ->
            val k = p.substringBefore('=', ""); val v = p.substringAfter('=', "").toDoubleOrNull()
            idOf(k)?.let { id -> v?.let { id to it } }
        }.toMap()
        val e = base ?: ExpenseEntity(tripId = tripId, uid = uid)
        return e.copy(
            tripId = tripId, uid = uid,
            dateEpochDay = d.optL("date"), amount = d.optD("amount"), currency = d.optStr("currency").ifBlank { "INR" },
            foreignAmount = d.optD("foreignAmount"), exchangeRate = d.optD("exchangeRate"),
            vendor = d.optStr("vendor"), billNo = d.optStr("billNo"), category = d.optStr("category").ifBlank { null },
            suggestedCategory = d.optStr("suggestedCategory").ifBlank { null }, suggestionReason = d.optStr("suggestionReason"),
            suggestionAlternatives = d.optStr("alternatives"), nature = d.optStr("nature"), description = d.optStr("remarks"),
            confirmed = d.optBoolean("confirmed"), noBill = d.optBoolean("noBill"), dateConfident = d.optBoolean("dateConfident"), amountConfident = d.optBoolean("amountConfident"),
            modeOfTravel = d.optStr("mode"), mileageKm = d.optD("km"), mileageRate = d.optD("rate"), tollAmount = d.optD("toll"),
            shares = if (shares.isEmpty()) "" else Shares.format(shares),
            entityId = d.optStr("entity").ifBlank { null }?.let(entityId), entityLocked = d.optBoolean("entityLocked"),
            addedBy = d.optStr("addedBy"), addedByUid = d.optStr("addedByUid"), createdAt = d.optL("createdAt") ?: e.createdAt,
            trustLevel = d.optStr("trustLevel").ifBlank { "OK" }, trustReport = d.optStr("trustReport"), trustAck = d.optStr("trustAck"),
            dupNote = d.optStr("dupNote"), dupStrong = d.optBoolean("dupStrong"), dateFromMetadata = d.optBoolean("dateFromMetadata"),
            aiNote = d.optStr("aiNote"), ocrText = d.optStr("ocrText").ifBlank { e.ocrText },
            dirty = false, deleted = false, aiState = if (e.aiState == "QUEUED") "" else e.aiState,
        )
    }
}
