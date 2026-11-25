package ru.tinyops.android.vboard.core.dict

import kotlin.math.abs

/**
 * Banded Damerau-Levenshtein helpers for fast typo matching against [WordTable] without
 * allocating strings for rejected words, plus weighted edit-cost calculation for matched words.
 */
internal object TapEditDistance {
    const val YO_COST = 0.05
    const val TRANSPOSE_COST = 0.65
    const val DOUBLE_OR_NEIGHBOR_DELETE_COST = 0.65
    const val DELETE_COST = 1.0
    const val INSERT_COST = 0.90
    private const val MAX_BAND_DIST = 2
    private const val INF = 1e6
    private const val BASE_SPATIAL_SUB = 0.25
    private const val SPATIAL_SLOPE = 0.55
    private const val MIN_SPATIAL_SUB = 0.20
    private const val MAX_SUB_COST = 1.35
    private const val KEY_SUB_BASE = 0.55
    private const val KEY_SUB_SLOPE = 0.45
    private const val NEAR_DELETE_RADIUS = 1.35f

    class TwoEditScratch(
        maxTypedLength: Int,
        maxDist: Int = MAX_BAND_DIST,
    ) {
        private val capacity = maxTypedLength + maxDist + 1
        val prev2 = IntArray(capacity)
        val prev1 = IntArray(capacity)
        val curr = IntArray(capacity)
    }

    /**
     * Optimal string alignment distance between [typed] and word [index] in [table], or
     * `maxDist + 1` when the distance exceeds [maxDist] (at most 2). Allocates no strings or row
     * arrays when [scratch] is provided.
     */
    fun withinTwo(
        typed: String,
        table: WordTable,
        index: Int,
        maxDist: Int = MAX_BAND_DIST,
        scratch: TwoEditScratch? = null,
    ): Int {
        val m = typed.length
        val n = table.lengthAt(index)
        if (abs(m - n) > maxDist) return maxDist + 1
        val buffer = scratch ?: TwoEditScratch(m, maxDist)
        var prev2 = buffer.prev2
        var prev1 = buffer.prev1
        var curr = buffer.curr
        for (j in 0..n) prev1[j] = j
        for (i in 1..m) {
            curr[0] = i
            val from = maxOf(1, i - maxDist)
            val to = minOf(n, i + maxDist)
            if (from > 1) curr[from - 1] = maxDist + 1
            if (to < n) curr[to + 1] = maxDist + 1
            val a = typed[i - 1]
            var rowMin = curr[0]
            for (j in from..to) {
                val b = table.charAt(index, j - 1)
                val sub = if (a == b) 0 else 1
                var best = minOf(prev1[j] + 1, curr[j - 1] + 1, prev1[j - 1] + sub)
                if (isTableTransposition(typed, i, table, index, j)) {
                    best = minOf(best, prev2[j - 2] + 1)
                }
                curr[j] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prev2
            prev2 = prev1
            prev1 = curr
            curr = tmp
        }
        return prev1[n]
    }

    /** Weighted Damerau-Levenshtein cost from [typed] to [candidate] using key proximity and tap points. */
    fun weightedCost(
        typed: String,
        candidate: String,
        taps: List<TapPoint?>,
        proximity: KeyProximity,
        spatialModel: Boolean,
    ): Double {
        val m = typed.length
        val n = candidate.length
        val dp = Array(m + 1) { DoubleArray(n + 1) { INF } }
        dp[0][0] = 0.0
        for (i in 1..m) dp[i][0] = dp[i - 1][0] + deleteCost(typed, i - 1, proximity)
        for (j in 1..n) dp[0][j] = dp[0][j - 1] + INSERT_COST
        for (i in 1..m) {
            for (j in 1..n) {
                val sub =
                    if (typed[i - 1] == candidate[j - 1]) {
                        0.0
                    } else {
                        substitutionCost(
                            typed[i - 1],
                            candidate[j - 1],
                            taps.getOrNull(i - 1),
                            proximity,
                            spatialModel,
                        )
                    }
                var best =
                    minOf(
                        dp[i - 1][j] + deleteCost(typed, i - 1, proximity),
                        dp[i][j - 1] + INSERT_COST,
                        dp[i - 1][j - 1] + sub,
                    )
                if (isStringTransposition(typed, i, candidate, j)) {
                    best = minOf(best, dp[i - 2][j - 2] + TRANSPOSE_COST)
                }
                dp[i][j] = best
            }
        }
        return dp[m][n]
    }

    fun substitutionCost(
        typedChar: Char,
        candidateChar: Char,
        tap: TapPoint?,
        proximity: KeyProximity,
        spatialModel: Boolean,
    ): Double {
        if (typedChar == candidateChar) return 0.0
        if (isYoPair(typedChar, candidateChar)) return YO_COST
        if (spatialModel && tap != null) {
            val dCand = proximity.tapDistance(tap, candidateChar)
            val dTyped = proximity.tapDistance(tap, typedChar)
            val delta = (dCand - dTyped).coerceAtLeast(0f).toDouble()
            return (BASE_SPATIAL_SUB + SPATIAL_SLOPE * delta).coerceIn(MIN_SPATIAL_SUB, MAX_SUB_COST)
        }
        val dist = proximity.distance(typedChar, candidateChar)
        val extra = (dist - 1f).coerceAtLeast(0f).toDouble()
        return (KEY_SUB_BASE + KEY_SUB_SLOPE * extra).coerceIn(KEY_SUB_BASE, MAX_SUB_COST)
    }

    fun deleteCost(
        typed: String,
        index: Int,
        proximity: KeyProximity,
    ): Double {
        val ch = typed[index]
        val prev = typed.getOrNull(index - 1)
        val next = typed.getOrNull(index + 1)
        val duplicateOrNear =
            (prev != null && (prev == ch || proximity.distance(prev, ch) <= NEAR_DELETE_RADIUS)) ||
                (next != null && (next == ch || proximity.distance(next, ch) <= NEAR_DELETE_RADIUS))
        return if (duplicateOrNear) DOUBLE_OR_NEIGHBOR_DELETE_COST else DELETE_COST
    }

    private fun isTableTransposition(
        typed: String,
        i: Int,
        table: WordTable,
        index: Int,
        j: Int,
    ): Boolean {
        if (i <= 1 || j <= 1) return false
        return typed[i - 1] == table.charAt(index, j - 2) && typed[i - 2] == table.charAt(index, j - 1)
    }

    private fun isStringTransposition(
        typed: String,
        i: Int,
        candidate: String,
        j: Int,
    ): Boolean {
        if (i <= 1 || j <= 1) return false
        return typed[i - 1] == candidate[j - 2] && typed[i - 2] == candidate[j - 1]
    }

    private fun isYoPair(
        a: Char,
        b: Char,
    ): Boolean = (a == 'е' && b == 'ё') || (a == 'ё' && b == 'е')
}
