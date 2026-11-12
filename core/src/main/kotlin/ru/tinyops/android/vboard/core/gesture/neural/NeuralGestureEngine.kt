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

    fun setLayout(layout: KeyboardLayout) {
        this.layout = layout
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
        return search.search(model, trie.root, CANDIDATES, context)
    }

    private companion object {
        const val CANDIDATES = 4
    }
}
