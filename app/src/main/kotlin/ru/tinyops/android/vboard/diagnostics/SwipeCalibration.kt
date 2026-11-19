package ru.tinyops.android.vboard.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.tinyops.android.vboard.core.gesture.calibration.SwipeSample
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.util.concurrent.ConcurrentHashMap

/** Records a finished gesture: its points, the candidates and the code of the engine that gave them. */
typealias CalibrationRecorder = (points: List<TouchPoint>, candidates: List<String>, engine: String) -> Unit

/**
 * A slide to type calibration: the settings page asks for words one by one, and the keyboard records the
 * gestures made in the field of that page. Kept in memory only.
 */
object SwipeCalibration {
    data class State(
        val words: List<String> = emptyList(),
        val index: Int = 0,
        val samples: List<SwipeSample> = emptyList(),
        val wrongLanguage: Boolean = false,
    ) {
        val started: Boolean get() = words.isNotEmpty()

        /** The word to swipe now, or null when the calibration has not started or is over. */
        val current: String? get() = words.getOrNull(index)
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Set while the field of the calibration page has the focus in a focused window: the keyboard then types
     * into that field, so gestures in any other field are never recorded.
     */
    @Volatile
    var listening: Boolean = false

    fun start(words: List<String>) {
        mutableState.value = State(words = words)
    }

    fun clear() {
        mutableState.value = State()
    }

    fun skip() {
        mutableState.update { it.copy(index = it.index + 1, wrongLanguage = false) }
    }

    /** Ends the calibration with the words recorded so far. */
    fun finish() {
        mutableState.update { it.copy(index = it.words.size, wrongLanguage = false) }
    }

    /** Drops the last recorded gesture and asks for its word again. */
    fun undo() {
        mutableState.update { state ->
            val last = state.samples.lastOrNull() ?: return@update state
            state.copy(index = state.words.indexOf(last.word), samples = state.samples.dropLast(1))
        }
    }

    /** The layout gestures of each language are decoded against, kept up to date by the keyboard. */
    private val layouts = ConcurrentHashMap<Language, KeyboardLayout>()

    fun setLayout(
        language: Language,
        layout: KeyboardLayout,
    ) {
        layouts[language] = layout
    }

    /** The recorder of a finished gesture of [language], or null when the gesture is not part of the calibration. */
    fun recorder(language: Language): CalibrationRecorder? {
        if (!listening || state.value.current == null) return null
        val layout = layouts[language] ?: return null
        return { points, candidates, engine -> record(language, points, layout, candidates, engine) }
    }

    /** Records a finished gesture for the current word; only the Russian layout is calibrated. */
    private fun record(
        language: Language,
        points: List<TouchPoint>,
        layout: KeyboardLayout,
        candidates: List<String>,
        engine: String,
    ) {
        mutableState.update { state ->
            val word = state.current ?: return@update state
            if (language != Language.RUSSIAN) return@update state.copy(wrongLanguage = true)
            state.copy(
                index = state.index + 1,
                samples = state.samples + SwipeSample(word, points, layout, candidates, engine),
                wrongLanguage = false,
            )
        }
    }
}
