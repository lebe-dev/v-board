package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.stt.lm.WordTrie
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint

/**
 * Decodes finished gestures with a neural model, limited to the words of a dictionary.
 *
 * Not thread-safe: confine every call to a single (background) thread.
 */
class NeuralGestureEngine(
    spec: SwipeModelSpec,
    private val model: SwipeSequenceModel,
) {
    private val extractor = SwipeFeatureExtractor(spec)
    private val search = NeuralWordSearch(spec)
    private var layout: KeyboardLayout? = null
    private var geometry: GestureGeometry? = null

    fun setLayout(layout: KeyboardLayout) {
        if (this.layout === layout) return
        this.layout = layout
        this.geometry = GestureGeometry.forLayout(layout)
    }

    /** The candidate words for [points] (view coordinates), best first, weighing the previous word by [context]. */
    fun decode(
        points: List<TouchPoint>,
        trie: WordTrie,
        context: BigramTable.Context? = null,
    ): List<String> {
        val layout = layout ?: return emptyList()
        val features = extractor.extract(points, layout) ?: return emptyList()
        model.encode(features)
        return search.search(model, trie.root, CANDIDATES, context, geometry?.scorer(points))
    }

    /** Like [decode], returning each candidate with its score breakdown. */
    fun decodeDetailed(
        points: List<TouchPoint>,
        trie: WordTrie,
        context: BigramTable.Context? = null,
    ): List<ScoredCandidate> {
        val layout = layout ?: return emptyList()
        val features = extractor.extract(points, layout) ?: return emptyList()
        model.encode(features)
        return search.searchDetailed(model, trie.root, CANDIDATES, context, geometry?.scorer(points))
    }

    private companion object {
        const val CANDIDATES = 4
    }
}
