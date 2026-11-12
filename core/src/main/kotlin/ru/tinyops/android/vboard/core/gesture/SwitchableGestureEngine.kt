package ru.tinyops.android.vboard.core.gesture

import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.core.gesture.neural.NeuralGestureEngine
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.lm.LanguageModel
import ru.tinyops.android.vboard.stt.lm.WordTrie
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint

/**
 * Sends gestures of one language to the chosen [SlideEngine].
 *
 * The neural model decodes only finished gestures: it was trained on whole words and is too heavy to
 * run on every finger movement, so live suggestions stay with the default decoder. So does anything
 * the model cannot serve: a language it does not know, a build without it, a runtime failure.
 *
 * Failures of the native runtime often arrive as an [Error] (a missing symbol, no memory), so both
 * opening and decoding catch any [Throwable]: an uncaught one would kill the decoder thread and with
 * it the whole keyboard.
 *
 * Not thread-safe: confine every call to a single (background) thread.
 */
class SwitchableGestureEngine(
    private val language: Language,
    private val openNeural: () -> NeuralGestureEngine?,
    private val onNeuralFailure: (Throwable) -> Unit = {},
    private val classic: GestureEngine = GestureEngine(),
) {
    private var layout: KeyboardLayout? = null
    private var trie: WordTrie? = null
    private var bigrams: BigramTable? = null
    private var neural: NeuralGestureEngine? = null
    private var neuralOpened = false
    private var neuralFailed = false

    var slideEngine: SlideEngine = SlideEngine.DEFAULT

    fun setLayout(layout: KeyboardLayout) {
        this.layout = layout
        classic.setLayout(layout)
        neural?.setLayout(layout)
    }

    fun setLanguageModel(model: LanguageModel) {
        trie = model.trie
        classic.setLanguageModel(model)
    }

    /** Word pairs the neural model weighs the previous word with; without them it decodes the gesture alone. */
    fun setBigrams(table: BigramTable?) {
        bigrams = table
    }

    /** Opens the model ahead of the first gesture if the chosen engine needs it. */
    fun prepare() {
        if (!slideEngine.usesNeuralModel(language) || neuralOpened) return
        neuralOpened = true
        neural =
            try {
                openNeural()?.also { engine -> layout?.let(engine::setLayout) }
            } catch (expected: Throwable) {
                fail(expected)
                null
            }
    }

    /** Whether [decode] with [complete] goes to the neural model. */
    fun routesToNeural(complete: Boolean): Boolean {
        if (!complete || neuralFailed || !slideEngine.usesNeuralModel(language)) return false
        prepare()
        return neural != null && !neuralFailed
    }

    /** Decodes [points] (view coordinates) into candidate words, best first. */
    fun decode(
        points: List<TouchPoint>,
        precedingText: String,
        complete: Boolean,
    ): List<String> {
        val words = if (routesToNeural(complete)) neuralWords(points, precedingText) else emptyList()
        return words.ifEmpty { classic.decode(points, precedingText, complete) }
    }

    private fun neuralWords(
        points: List<TouchPoint>,
        precedingText: String,
    ): List<String> {
        val engine = neural ?: return emptyList()
        val trie = trie ?: return emptyList()
        return try {
            val context = bigrams?.let { table -> BigramTable.previousWord(precedingText)?.let(table::context) }
            engine.decode(points, trie, context)
        } catch (expected: Throwable) {
            fail(expected)
            emptyList()
        }
    }

    private fun fail(cause: Throwable) {
        neuralFailed = true
        onNeuralFailure(cause)
    }
}
