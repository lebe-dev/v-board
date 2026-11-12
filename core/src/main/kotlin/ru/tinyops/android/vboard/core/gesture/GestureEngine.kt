package ru.tinyops.android.vboard.core.gesture

import ru.tinyops.android.vboard.stt.decode.BeamSearchDecoder
import ru.tinyops.android.vboard.stt.decode.DecodeConfig
import ru.tinyops.android.vboard.stt.lm.LanguageModel
import ru.tinyops.android.vboard.stt.model.InputContext
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint

/**
 * Thin, stateful wrapper around the slide-to-type decoder.
 *
 * Not thread-safe: confine every call to a single (background) thread.
 */
class GestureEngine(
    config: DecodeConfig = DecodeConfig(),
) {
    private val decoder = BeamSearchDecoder(config)
    private var layout: KeyboardLayout? = null
    private var model: LanguageModel? = null

    val isReady: Boolean get() = layout != null && model != null

    fun setLayout(layout: KeyboardLayout) {
        this.layout = layout
        decoder.setKeyboardLayout(layout)
    }

    fun setLanguageModel(model: LanguageModel) {
        if (this.model === model) return
        this.model = model
        decoder.loadLanguageModel(model)
    }

    /**
     * Decodes [points] (view coordinates) into candidate words, best first.
     *
     * @param precedingText text before the cursor, used as language-model context
     * @param complete true once the finger is lifted; false for live suggestions
     */
    fun decode(
        points: List<TouchPoint>,
        precedingText: String,
        complete: Boolean,
    ): List<String> {
        if (!isReady || points.size < 2) return emptyList()
        decoder.startSession(InputContext(precedingText = precedingText))
        val suggestions = if (complete) decoder.onGestureEnd(points) else decoder.onGestureUpdate(points)
        return suggestions.map { it.word }
    }
}
