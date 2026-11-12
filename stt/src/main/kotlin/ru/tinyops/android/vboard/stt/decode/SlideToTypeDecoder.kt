package ru.tinyops.android.vboard.stt.decode

import ru.tinyops.android.vboard.stt.lm.LanguageModel
import ru.tinyops.android.vboard.stt.model.InputContext
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.Suggestion
import ru.tinyops.android.vboard.stt.model.TouchPoint

/**
 * Public entry point of the slide-to-type library.
 *
 * The decoder is a pure function of `(points, layout, language model)`: it holds
 * no Android or IME state. A caller pushes the current keyboard geometry once,
 * loads a language model, starts a session with the editing context and then
 * feeds gesture points.
 */
interface SlideToTypeDecoder {
    /** Sets the keyboard geometry used for the spatial model and downsampling. */
    fun setKeyboardLayout(layout: KeyboardLayout)

    /** Loads the language model whose vocabulary constrains decoding. */
    fun loadLanguageModel(model: LanguageModel)

    /** Starts a new decode session describing the text before the cursor. */
    fun startSession(input: InputContext)

    /** Decodes a partially drawn gesture to produce live ("preemptive") suggestions. */
    fun onGestureUpdate(points: List<TouchPoint>): List<Suggestion>

    /** Decodes a completed gesture to produce the final suggestions. */
    fun onGestureEnd(points: List<TouchPoint>): List<Suggestion>
}
