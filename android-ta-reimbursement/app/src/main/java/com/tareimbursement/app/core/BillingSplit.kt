package com.tareimbursement.app.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Splits a claim's bills across client entities / project codes without breaking any invoice.
 *
 * - Targets come from the entity share percentages (equal when none are given).
 * - Bills the user locked to an entity stay there.
 * - Two entities: exact subset-sum (rupee precision) – the subset closest to the target, as in
 *   the TA organizer's "split across two project codes" step.
 * - More entities: largest-first greedy towards each target, then move/swap improvement.
 */
object BillingSplit {
    data class Item(val id: Long, val amount: Double, val lockedTo: Int? = null)

    data class Result(val assignment: Map<Long, Int>, val totals: List<Double>, val targets: List<Double>) {
        /** Largest absolute difference between an entity's total and its target. */
        val maxDeviation: Double get() = totals.indices.maxOfOrNull { abs(totals[it] - targets[it]) } ?: 0.0
    }

    fun targets(total: Double, sharePercents: List<Double>): List<Double> {
        val n = sharePercents.size
        val valid = sharePercents.all { it > 0 }
        val w = if (valid) sharePercents else List(n) { 1.0 }
        val sum = w.sum()
        return w.map { total * it / sum }
    }

    fun allocate(items: List<Item>, sharePercents: List<Double>): Result {
        val n = sharePercents.size
        require(n >= 1) { "At least one entity is required" }
        val total = items.sumOf { it.amount }
        val targets = targets(total, sharePercents)
        val assignment = HashMap<Long, Int>()
        val totals = DoubleArray(n)
        items.filter { it.lockedTo != null && it.lockedTo in 0 until n }.forEach { assignment[it.id] = it.lockedTo!!; totals[it.lockedTo] += it.amount }
        val free = items.filter { it.id !in assignment }.sortedByDescending { it.amount }
        if (n == 1) {
            free.forEach { assignment[it.id] = 0; totals[0] += it.amount }
            return Result(assignment, totals.toList(), targets)
        }
        if (n == 2 && free.isNotEmpty() && exactTwoWay(free, targets[0] - totals[0], assignment, totals)) {
            return Result(assignment, totals.toList(), targets)
        }
        // Greedy: give each bill (largest first) to the entity furthest below its target.
        free.forEach { item ->
            val e = (0 until n).maxBy { targets[it] - totals[it] }
            assignment[item.id] = e; totals[e] += item.amount
        }
        improve(free, assignment, totals, targets)
        return Result(assignment, totals.toList(), targets)
    }

    private const val MAX_DP_RUPEES = 5_000_000

    /** Exact: pick the subset of [free] whose sum is closest to [target0]; it goes to entity 0. */
    private fun exactTwoWay(free: List<Item>, target0: Double, assignment: HashMap<Long, Int>, totals: DoubleArray): Boolean {
        val amounts = free.map { it.amount.roundToLong().coerceAtLeast(0).toInt() }
        val sum = amounts.sum()
        if (sum > MAX_DP_RUPEES) return false
        // reach[s] = index+1 of the item that first made sum s reachable (0 = unreachable, s=0 reachable by default).
        val reach = IntArray(sum + 1)
        val reachable = BooleanArray(sum + 1).also { it[0] = true }
        for (i in amounts.indices) {
            val a = amounts[i]
            if (a == 0) continue
            for (s in sum downTo a) if (!reachable[s] && reachable[s - a]) { reachable[s] = true; reach[s] = i + 1 }
        }
        val goal = target0.coerceIn(0.0, sum.toDouble())
        var best = 0; var bestDiff = Double.MAX_VALUE
        for (s in 0..sum) if (reachable[s]) { val d = abs(s - goal); if (d < bestDiff) { bestDiff = d; best = s } }
        val chosen = HashSet<Int>()
        var s = best
        while (s > 0) { val i = reach[s] - 1; chosen += i; s -= amounts[i] }
        free.forEachIndexed { i, item ->
            val e = if (i in chosen) 0 else 1
            assignment[item.id] = e; totals[e] += item.amount
        }
        return true
    }

    private fun cost(totals: DoubleArray, targets: List<Double>) = totals.indices.sumOf { (totals[it] - targets[it]).let { d -> d * d } }

    private fun improve(free: List<Item>, assignment: HashMap<Long, Int>, totals: DoubleArray, targets: List<Double>) {
        val n = totals.size
        var improved = true
        var rounds = 0
        while (improved && rounds++ < 200) {
            improved = false
            val base = cost(totals, targets)
            // Single moves
            loop@ for (item in free) {
                val from = assignment.getValue(item.id)
                for (to in 0 until n) {
                    if (to == from) continue
                    totals[from] -= item.amount; totals[to] += item.amount
                    if (cost(totals, targets) < base - 1e-6) { assignment[item.id] = to; improved = true; break@loop }
                    totals[from] += item.amount; totals[to] -= item.amount
                }
            }
            if (improved) continue
            // Pair swaps
            swap@ for (i in free.indices) for (j in i + 1 until free.size) {
                val a = free[i]; val b = free[j]
                val ea = assignment.getValue(a.id); val eb = assignment.getValue(b.id)
                if (ea == eb) continue
                val delta = a.amount - b.amount
                totals[ea] -= delta; totals[eb] += delta
                if (cost(totals, targets) < base - 1e-6) { assignment[a.id] = eb; assignment[b.id] = ea; improved = true; break@swap }
                totals[ea] += delta; totals[eb] -= delta
            }
        }
    }
}

/** Per-traveller shares of a bill, stored as "travellerId=amount;…" (amounts in INR). */
object Shares {
    fun parse(s: String?): Map<Long, Double> =
        s.orEmpty().split(';').mapNotNull { part ->
            val k = part.substringBefore('=', "").trim().toLongOrNull() ?: return@mapNotNull null
            val v = part.substringAfter('=', "").trim().toDoubleOrNull() ?: return@mapNotNull null
            k to v
        }.toMap()

    fun format(m: Map<Long, Double>): String = m.entries.joinToString(";") { "${it.key}=${"%.2f".format(java.util.Locale.ROOT, it.value)}" }

    /** Splits [amount] equally to the paisa; any remaining paise go to the first travellers. */
    fun equal(amount: Double, ids: List<Long>): Map<Long, Double> {
        if (ids.isEmpty()) return emptyMap()
        val paise = (amount * 100).roundToLong()
        val each = paise / ids.size
        var rem = paise - each * ids.size
        return ids.associateWith { id -> ((each + if (rem-- > 0) 1 else 0) / 100.0) }
    }

    fun isBalanced(shares: Map<Long, Double>, amount: Double) = abs(shares.values.sum() - amount) < 0.995
}
