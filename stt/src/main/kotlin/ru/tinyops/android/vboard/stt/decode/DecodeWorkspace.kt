package ru.tinyops.android.vboard.stt.decode

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.TouchPoint
import ru.tinyops.android.vboard.stt.spatial.SpatialModel

/** A vocabulary prefix being aligned. Instances are pooled: see [Beam]. */
internal class Hypothesis {
    /** The prefix's trie node. */
    var node = 0

    /** The prefix's last key. */
    var key = 0

    /** The alignment column; its first [DecodeWorkspace.n] values are meaningful. */
    var column = NO_COLUMN

    /** Optimistic cost of completing the prefix, which the beam is pruned by. */
    var prefixCost = 0.0

    /** Expansion order: ties in [prefixCost] keep the earlier hypothesis. */
    var order = 0

    /** Whether this hypothesis should stay in the beam rather than [other]. */
    fun isBetterThan(other: Hypothesis): Boolean {
        val byCost = prefixCost.compareTo(other.prefixCost)
        return byCost < 0 || (byCost == 0 && order < other.order)
    }

    private companion object {
        val NO_COLUMN = DoubleArray(0)
    }
}

/**
 * The best [capacity] hypotheses of one word-length level. Hypotheses that do not make it are
 * rejected as they arrive, so a level holds at most [capacity] alignment columns, however many
 * prefixes it expands.
 */
internal class Beam(
    private val capacity: Int,
) {
    private val hypotheses = Array(capacity) { Hypothesis() }

    /** Whether something has been rejected: [hypotheses] is then a heap with the worst on top. */
    private var overflowed = false
    private var offered = 0

    var size = 0
        private set

    operator fun get(index: Int): Hypothesis = hypotheses[index]

    fun clear() {
        size = 0
        offered = 0
        overflowed = false
    }

    /** Stores a hypothesis, or returns [column] when the beam has no place for it. */
    fun offer(
        node: Int,
        key: Int,
        column: DoubleArray,
        prefixCost: Double,
    ): DoubleArray? {
        val order = offered++
        if (size == capacity && !overflowed) {
            for (i in capacity / 2 - 1 downTo 0) siftDown(i, capacity)
            overflowed = true
        }
        if (overflowed) {
            val worst = hypotheses[0]
            if (prefixCost.compareTo(worst.prefixCost) >= 0) return column
            val released = worst.column
            worst.set(node, key, column, prefixCost, order)
            siftDown(0, capacity)
            return released
        }
        hypotheses[size++].set(node, key, column, prefixCost, order)
        return null
    }

    /** Puts a beam that rejected something in best-first order; one that did not keeps its order. */
    fun finish() {
        if (!overflowed) return
        for (end in capacity - 1 downTo 1) {
            swap(0, end)
            siftDown(0, end)
        }
    }

    private fun Hypothesis.set(
        node: Int,
        key: Int,
        column: DoubleArray,
        prefixCost: Double,
        order: Int,
    ) {
        this.node = node
        this.key = key
        this.column = column
        this.prefixCost = prefixCost
        this.order = order
    }

    private fun siftDown(
        start: Int,
        end: Int,
    ) {
        var parent = start
        while (true) {
            val left = 2 * parent + 1
            if (left >= end) return
            val right = left + 1
            val worse = if (right < end && hypotheses[left].isBetterThan(hypotheses[right])) right else left
            if (!hypotheses[parent].isBetterThan(hypotheses[worse])) return
            swap(parent, worse)
            parent = worse
        }
    }

    private fun swap(
        a: Int,
        b: Int,
    ) {
        val held = hypotheses[a]
        hypotheses[a] = hypotheses[b]
        hypotheses[b] = held
    }
}

/** The complete words found by a search, in the order the search reached them. */
internal class Terminals {
    var size = 0
        private set

    private var nodes = IntArray(INITIAL_CAPACITY)
    private var spatialCosts = DoubleArray(INITIAL_CAPACITY)

    /** The language score of each terminal, filled in by ranking. */
    var logProbs = DoubleArray(INITIAL_CAPACITY)
        private set

    /** The total score of each terminal, filled in by ranking. */
    var scores = DoubleArray(INITIAL_CAPACITY)
        private set

    fun nodeAt(index: Int): Int = nodes[index]

    fun spatialCostAt(index: Int): Double = spatialCosts[index]

    fun clear() {
        size = 0
    }

    fun add(
        node: Int,
        spatialCost: Double,
    ) {
        if (size == nodes.size) grow()
        nodes[size] = node
        spatialCosts[size] = spatialCost
        size++
    }

    private fun grow() {
        val capacity = size * 2
        nodes = nodes.copyOf(capacity)
        spatialCosts = spatialCosts.copyOf(capacity)
        logProbs = logProbs.copyOf(capacity)
        scores = scores.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}

/**
 * Everything one decode works on, kept between decodes so that the collector stays idle while the
 * user types: the downsampled gesture, costs memoized by key and key pair, the two beams and the
 * spare DTW columns. A decode expands thousands of hypotheses, but they all draw on few distinct
 * keys. Arrays only grow, up to the longest gesture seen. Not thread-safe.
 */
internal class DecodeWorkspace(
    private val keys: List<Key>,
    private val spatial: SpatialModel,
    beamWidth: Int,
) {
    /** Number of gesture points of the current decode. */
    var n = 0
        private set

    var current = Beam(beamWidth)
        private set
    var next = Beam(beamWidth)
        private set
    val terminals = Terminals()

    /** Points for the downsampler to fill, reused by every decode. */
    val gesture = ArrayList<TouchPoint>()

    private var capacity = 0
    private var xs = DoubleArray(0)
    private var ys = DoubleArray(0)

    private val vertexCosts = arrayOfNulls<DoubleArray>(keys.size)
    private val segmentCosts = arrayOfNulls<DoubleArray>(keys.size * keys.size)

    // A memo entry belongs to the decode whose number it is stamped with.
    private val vertexStamps = IntArray(keys.size)
    private val segmentStamps = IntArray(keys.size * keys.size)
    private var stamp = 0

    private var spareColumns = arrayOfNulls<DoubleArray>(INITIAL_SPARE_COLUMNS)
    private var spareCount = 0

    /** Starts a decode of [gesture]. */
    fun begin() {
        n = gesture.size
        stamp++
        if (n > capacity) {
            capacity = maxOf(n, capacity + capacity / 2)
            xs = DoubleArray(capacity)
            ys = DoubleArray(capacity)
            spareCount = 0
            spareColumns.fill(null)
        }
        for (i in 0 until n) {
            xs[i] = gesture[i].x.toDouble()
            ys[i] = gesture[i].y.toDouble()
        }
        gesture.clear()
        current.clear()
        next.clear()
        terminals.clear()
    }

    /** Makes the level just built the current one; the former current beam must have been released. */
    fun advance() {
        val finished = next
        next = current
        current = finished
        next.clear()
    }

    fun obtainColumn(): DoubleArray {
        if (spareCount == 0) return DoubleArray(capacity)
        return requireNotNull(spareColumns[--spareCount])
    }

    fun recycle(column: DoubleArray) {
        if (spareCount == spareColumns.size) spareColumns = spareColumns.copyOf(spareCount * 2)
        spareColumns[spareCount++] = column
    }

    /** Cost of every gesture point against the center of the key. */
    fun vertexCosts(key: Int): DoubleArray {
        val memo = vertexCosts[key]
        if (memo != null && vertexStamps[key] == stamp && memo.size >= n) return memo
        val costs = if (memo != null && memo.size >= n) memo else DoubleArray(capacity)
        val target = keys[key]
        for (i in 0 until n) costs[i] = spatial.matchCost(xs[i].toFloat(), ys[i].toFloat(), target)
        vertexCosts[key] = costs
        vertexStamps[key] = stamp
        return costs
    }

    /**
     * Cumulative point-to-segment costs for the straight line joining the two key
     * centers: `prefixG[i + 1] = prefixG[i] + cost(points[i], segment)`.
     */
    fun segmentPrefixCosts(
        from: Int,
        to: Int,
    ): DoubleArray {
        val slot = from * keys.size + to
        val memo = segmentCosts[slot]
        if (memo != null && segmentStamps[slot] == stamp && memo.size > n) return memo
        val costs = if (memo != null && memo.size > n) memo else DoubleArray(capacity + 1)
        fillSegmentCosts(keys[from], keys[to], costs)
        segmentCosts[slot] = costs
        segmentStamps[slot] = stamp
        return costs
    }

    private fun fillSegmentCosts(
        from: Key,
        to: Key,
        prefixG: DoubleArray,
    ) {
        val ax = from.centerX.toDouble()
        val ay = from.centerY.toDouble()
        val dx = to.centerX.toDouble() - from.centerX.toDouble()
        val dy = to.centerY.toDouble() - from.centerY.toDouble()
        val lenSq = dx * dx + dy * dy

        prefixG[0] = 0.0
        for (i in 0 until n) {
            val px = xs[i]
            val py = ys[i]
            val t = projectionParam(px - ax, py - ay, dx, dy, lenSq)
            prefixG[i + 1] = prefixG[i] +
                spatial.matchCostToPoint(
                    px.toFloat(),
                    py.toFloat(),
                    (ax + t * dx).toFloat(),
                    (ay + t * dy).toFloat(),
                )
        }
    }

    private fun projectionParam(
        relX: Double,
        relY: Double,
        dx: Double,
        dy: Double,
        lenSq: Double,
    ): Double {
        if (lenSq <= MIN_SEGMENT_LENGTH_SQ) return 0.0
        return ((relX * dx + relY * dy) / lenSq).coerceIn(0.0, 1.0)
    }

    private companion object {
        /** Squared length below which a key-to-key segment is treated as a point. */
        const val MIN_SEGMENT_LENGTH_SQ = 1e-9
        const val INITIAL_SPARE_COLUMNS = 256
    }
}
