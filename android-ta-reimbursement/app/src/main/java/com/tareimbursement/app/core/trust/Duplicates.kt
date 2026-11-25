package com.tareimbursement.app.core.trust

import java.time.LocalDate
import java.util.Locale

/** Fingerprint of one bill, used to find the same bill uploaded twice (same or another claim). */
data class BillPrint(
    val expenseId: Long,
    val tripId: Long,
    val tripTitle: String,
    val sha256: String?,
    /** 64-bit difference hash of the first page/photo (null for files we can't render). */
    val dHash: Long?,
    val date: LocalDate?,
    val amount: Double?,
    val vendor: String?,
    val billNo: String?,
)

data class DuplicateHit(val other: BillPrint, val reason: String, val strong: Boolean)

object Duplicates {
    /** Hamming distance at or below this = visually the same picture (re-photographed or re-compressed). */
    const val DHASH_SAME = 6

    /**
     * dHash from a 9x8 grayscale grid (row-major, 72 values 0..255): bit = left pixel brighter than right.
     * The caller scales the image down to 9x8 first.
     */
    fun dHash(gray9x8: IntArray): Long {
        require(gray9x8.size == 72)
        var h = 0L; var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (gray9x8[y * 9 + x] > gray9x8[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    fun distance(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    private fun norm(s: String?) = s?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]"), "")?.takeIf { it.length >= 2 }

    /** Finds earlier bills that match [me]. Strong hits block the bill until the person gives a reason. */
    fun find(me: BillPrint, others: List<BillPrint>): List<DuplicateHit> {
        val hits = mutableListOf<DuplicateHit>()
        for (o in others) {
            if (o.expenseId == me.expenseId) continue
            val where = if (o.tripId == me.tripId) "this claim" else "claim \"${o.tripTitle}\""
            when {
                me.sha256 != null && me.sha256 == o.sha256 ->
                    hits += DuplicateHit(o, "Exact same file is already in $where.", true)
                me.dHash != null && o.dHash != null && distance(me.dHash, o.dHash) <= DHASH_SAME && amountsMatch(me, o) ->
                    hits += DuplicateHit(o, "Same-looking bill with the same amount is already in $where.", true)
                norm(me.billNo) != null && norm(me.billNo) == norm(o.billNo) && sameVendor(me, o) ->
                    hits += DuplicateHit(o, "Bill no. ${me.billNo} from this vendor is already in $where.", true)
                me.date != null && me.date == o.date && amountsMatch(me, o) && sameVendor(me, o) ->
                    hits += DuplicateHit(o, "Same date, amount and vendor as a bill in $where.", true)
                me.date != null && me.date == o.date && amountsMatch(me, o) && (me.amount ?: 0.0) >= 500 ->
                    hits += DuplicateHit(o, "Same date and amount as a bill in $where.", false)
            }
        }
        return hits.sortedByDescending { it.strong }
    }

    private fun amountsMatch(a: BillPrint, b: BillPrint) = a.amount != null && b.amount != null && a.amount > 0 && kotlin.math.abs(a.amount - b.amount) < 0.5
    private fun sameVendor(a: BillPrint, b: BillPrint): Boolean {
        val x = norm(a.vendor) ?: return a.vendor == null && b.vendor == null && false
        val y = norm(b.vendor) ?: return false
        return x == y || (x.length >= 5 && y.length >= 5 && (x.contains(y) || y.contains(x)))
    }
}
