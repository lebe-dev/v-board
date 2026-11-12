package ru.tinyops.android.vboard.stt.decode

import ru.tinyops.android.vboard.stt.gesture.GestureDownsampler
import ru.tinyops.android.vboard.stt.lm.LanguageModel
import ru.tinyops.android.vboard.stt.lm.WordTrie
import ru.tinyops.android.vboard.stt.model.InputContext
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.Suggestion
import ru.tinyops.android.vboard.stt.model.TouchPoint
import ru.tinyops.android.vboard.stt.spatial.GaussianSpatialModel

/**
 * Beam-search gesture decoder.
 *
 * The gesture is downsampled, then every vocabulary prefix is aligned against
 * the resulting point sequence using an incremental dynamic-time-warping column.
 * A hypothesis carries the trie node it reached plus the DTW column for that
 * prefix, so extending it by one character costs O(points).
 *
 * The working memory lives in a [DecodeWorkspace] that survives between decodes,
 * so a warm decode allocates little more than its results. That makes the decoder
 * stateful: confine an instance to one thread.
 *
 * Alignment model (lower cost is better). A word is the polyline through the
 * centers of its characters' keys:
 *  - the first character absorbs the leading points (the finger starts on it);
 *  - each subsequent character is a vertex at some later point, and the points
 *    in between are scored against the straight line joining the two vertices;
 *  - the last character is pinned to the final point (the finger lifts on it).
 *
 * The beam is pruned by a prefix score (see [Hypothesis.prefixCost]): the best
 * alignment of the prefix with the *beginning* of the gesture plus a fixed cost
 * per gesture point the prefix has not reached yet. Pinning a prefix's last
 * character to the final point would discard correct prefixes of long words
 * once the vocabulary is larger than the beam. The final ranking uses the full
 * spatial cost plus the language-model score.
 */
class BeamSearchDecoder(
    var config: DecodeConfig = DecodeConfig(),
) : SlideToTypeDecoder {
    private var layout: KeyboardLayout? = null
    private var languageModel: LanguageModel? = null
    private var keys: DecoderKeys = DecoderKeys(emptyMap())
    private var context: InputContext = InputContext()
    private var tools: DecodeTools? = null

    override fun setKeyboardLayout(layout: KeyboardLayout) {
        this.layout = layout
        this.tools = null
        this.keys =
            DecoderKeys(
                buildMap {
                    for (key in layout.characterKeys) {
                        val c = key.character ?: continue
                        putIfAbsent(c, key)
                    }
                },
            )
    }

    override fun loadLanguageModel(model: LanguageModel) {
        this.languageModel = model
    }

    override fun startSession(input: InputContext) {
        this.context = input
    }

    override fun onGestureUpdate(points: List<TouchPoint>): List<Suggestion> =
        decode(points, config.preemptiveMaxResults)

    override fun onGestureEnd(points: List<TouchPoint>): List<Suggestion> = decode(points, config.maxResults)

    private fun decode(
        rawPoints: List<TouchPoint>,
        maxResults: Int,
    ): List<Suggestion> {
        val layout = this.layout ?: return emptyList()
        val languageModel = this.languageModel ?: return emptyList()
        if (keys.size == 0) return emptyList()

        val tools = toolsFor(layout)
        val workspace = tools.workspace
        tools.downsampler.downsampleInto(rawPoints, workspace.gesture)
        if (workspace.gesture.size < 2) return emptyList()

        workspace.begin()
        search(languageModel.trie, workspace)
        if (workspace.terminals.size == 0) return emptyList()
        return rank(workspace.terminals, languageModel, maxResults)
    }

    /** Layout-derived helpers and the workspace, rebuilt only when the layout or [config] changes. */
    private fun toolsFor(layout: KeyboardLayout): DecodeTools {
        val existing = tools
        if (existing != null && existing.config === config) return existing
        return DecodeTools(
            config = config,
            downsampler =
                GestureDownsampler.forLayout(layout, config.minDistanceFraction, config.maxTimeGapMillis),
            workspace =
                DecodeWorkspace(
                    keys = keys.all,
                    spatial = GaussianSpatialModel.forLayout(layout, config.sigmaFraction),
                    beamWidth = config.beamWidth,
                ),
        ).also { tools = it }
    }

    private fun rank(
        terminals: Terminals,
        languageModel: LanguageModel,
        maxResults: Int,
    ): List<Suggestion> {
        scoreTerminals(terminals, languageModel)
        val best = selectBest(terminals, maxResults)
        val trie = languageModel.trie
        return List(best.size) { place ->
            val terminal = best[place]
            Suggestion(
                word = requireNotNull(trie.wordOf(terminals.nodeAt(terminal))),
                score = terminals.scores[terminal],
                spatialScore = -config.spatialWeight * terminals.spatialCostAt(terminal),
                languageScore = config.languageWeight * terminals.logProbs[terminal],
            )
        }
    }

    private fun scoreTerminals(
        terminals: Terminals,
        languageModel: LanguageModel,
    ) {
        val trie = languageModel.trie
        val fromTrie = languageModel.scoresFromTrie
        val contextWords = if (fromTrie) emptyList() else context.contextWords()
        for (i in 0 until terminals.size) {
            val node = terminals.nodeAt(i)
            val logProb =
                if (fromTrie) {
                    trie.logProbOf(node)
                } else {
                    languageModel.contextLogProb(contextWords, requireNotNull(trie.wordOf(node)))
                }
            terminals.logProbs[i] = logProb
            terminals.scores[i] = config.languageWeight * logProb - config.spatialWeight * terminals.spatialCostAt(i)
        }
    }

    /** Indexes of the [limit] best terminals, best first; equal scores keep the order they were found in. */
    private fun selectBest(
        terminals: Terminals,
        limit: Int,
    ): IntArray {
        val scores = terminals.scores
        val best = IntArray(minOf(limit, terminals.size))
        var count = 0
        for (i in 0 until terminals.size) {
            val score = scores[i]
            if (count == best.size && score.compareTo(scores[best[count - 1]]) <= 0) continue
            var slot = if (count < best.size) count++ else count - 1
            while (slot > 0 && scores[best[slot - 1]].compareTo(score) < 0) {
                best[slot] = best[slot - 1]
                slot--
            }
            best[slot] = i
        }
        return best
    }

    /** Every trie path is walked at most once, so each word reaches the result only once. */
    private fun search(
        trie: WordTrie,
        workspace: DecodeWorkspace,
    ) {
        expandRoot(trie, workspace)
        var depth = 2
        while (depth <= config.maxWordLength && workspace.current.size > 0) {
            val frontier = workspace.current
            for (i in 0 until frontier.size) expand(frontier[i], trie, workspace)
            for (i in 0 until frontier.size) workspace.recycle(frontier[i].column)
            workspace.next.finish()
            workspace.advance()
            depth++
        }
        val frontier = workspace.current
        for (i in 0 until frontier.size) workspace.recycle(frontier[i].column)
    }

    private fun expandRoot(
        trie: WordTrie,
        workspace: DecodeWorkspace,
    ) {
        var child = trie.firstChildOf(WordTrie.ROOT)
        while (child != WordTrie.NONE) {
            val key = keys.indexOf(trie.labelOf(child))
            if (key != NO_KEY) {
                val column = workspace.obtainColumn()
                firstKeyColumn(key, workspace, column)
                accept(child, key, column, trie, workspace)
            }
            child = trie.nextSiblingOf(child)
        }
        workspace.next.finish()
        workspace.advance()
    }

    private fun expand(
        beam: Hypothesis,
        trie: WordTrie,
        workspace: DecodeWorkspace,
    ) {
        var child = trie.firstChildOf(beam.node)
        while (child != WordTrie.NONE) {
            val key = keys.indexOf(trie.labelOf(child))
            if (key != NO_KEY) {
                val column = workspace.obtainColumn()
                extendColumn(beam, key, workspace, column)
                accept(child, key, column, trie, workspace)
            }
            child = trie.nextSiblingOf(child)
        }
    }

    /** Records a finished prefix as a word, if it is one, and offers it to the next beam. */
    private fun accept(
        node: Int,
        key: Int,
        column: DoubleArray,
        trie: WordTrie,
        workspace: DecodeWorkspace,
    ) {
        val spatialCost = column[workspace.n - 1]
        if (spatialCost.isInfinite()) {
            workspace.recycle(column)
            return
        }
        if (trie.isWord(node)) workspace.terminals.add(node, spatialCost)
        val rejected = workspace.next.offer(node, key, column, prefixCost(column, workspace.n))
        if (rejected != null) workspace.recycle(rejected)
    }

    /** `min_b(column[b] + unexplainedPointCost * (n - 1 - b))`: optimistic cost of completing the prefix. */
    private fun prefixCost(
        column: DoubleArray,
        n: Int,
    ): Double {
        val last = n - 1
        var best = Double.POSITIVE_INFINITY
        for (b in 0 until n) {
            val cost = column[b] + config.unexplainedPointCost * (last - b)
            if (cost < best) best = cost
        }
        return best
    }

    /** First character absorbs the leading points: cumulative cost to the key center. */
    private fun firstKeyColumn(
        key: Int,
        workspace: DecodeWorkspace,
        column: DoubleArray,
    ) {
        val vertex = workspace.vertexCosts(key)
        var running = 0.0
        for (i in 0 until workspace.n) {
            running += vertex[i]
            column[i] = running
        }
    }

    /**
     * Adds one more character as a vertex `b` that must fall after the previous
     * vertex `a`. Interior points are scored against the line joining the keys.
     *
     * `column[b] = vertex(b) + prefixG[b] + min_{a<b}(prev[a] - prefixG[a + 1])`
     */
    private fun extendColumn(
        beam: Hypothesis,
        nextKey: Int,
        workspace: DecodeWorkspace,
        column: DoubleArray,
    ) {
        val previous = beam.column
        val prefixG = workspace.segmentPrefixCosts(beam.key, nextKey)
        val vertex = workspace.vertexCosts(nextKey)
        var runningMin = Double.POSITIVE_INFINITY
        for (b in 0 until workspace.n) {
            if (b > 0 && previous[b - 1].isFinite()) {
                runningMin = minOf(runningMin, previous[b - 1] - prefixG[b])
            }
            column[b] =
                if (runningMin.isFinite()) vertex[b] + prefixG[b] + runningMin else Double.POSITIVE_INFINITY
        }
    }

    /** The layout's character keys, numbered, with an allocation-free lookup by character. */
    private class DecoderKeys(
        byCharacter: Map<Char, Key>,
    ) {
        private val characters: CharArray = byCharacter.keys.sorted().toCharArray()
        val all: List<Key> = characters.map(byCharacter::getValue)

        val size: Int get() = all.size

        fun indexOf(character: Char): Int = characters.binarySearch(character).coerceAtLeast(NO_KEY)
    }

    private class DecodeTools(
        val config: DecodeConfig,
        val downsampler: GestureDownsampler,
        val workspace: DecodeWorkspace,
    )

    private companion object {
        const val NO_KEY = -1
    }
}
