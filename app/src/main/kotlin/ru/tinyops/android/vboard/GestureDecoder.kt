package ru.tinyops.android.vboard

import android.os.Handler
import android.os.Looper
import android.util.Log
import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.core.dict.Dictionary
import ru.tinyops.android.vboard.core.gesture.SlideEngine
import ru.tinyops.android.vboard.core.gesture.SwitchableGestureEngine
import ru.tinyops.android.vboard.core.gesture.neural.NeuralGestureEngine
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the dictionaries and gesture engines. Loading and decoding run on one background
 * thread (the engines are confined to it); results are delivered on the main thread.
 */
class GestureDecoder(
    private val openDictionary: (Language) -> Dictionary,
    private val openNeural: (Language) -> NeuralGestureEngine? = { null },
    private val openBigrams: (Language) -> BigramTable? = { null },
) {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "gesture-decoder") }
    private val main = Handler(Looper.getMainLooper())
    private val dictionaries = ConcurrentHashMap<Language, Dictionary>()
    private val requested = ConcurrentHashMap.newKeySet<Language>()

    /** Engines, touched only from [executor]. */
    private val engines = HashMap<Language, SwitchableGestureEngine>()

    /** The engine the next engines start with, touched only from [executor]. */
    private var slideEngine = SlideEngine.DEFAULT

    fun isReady(language: Language): Boolean = dictionaries.containsKey(language)

    /** Applies the user's choice to the gestures that follow; opens the model in the background if it needs one. */
    fun setSlideEngine(engine: SlideEngine) {
        executor.execute {
            if (slideEngine == engine) return@execute
            slideEngine = engine
            for (languageEngine in engines.values) {
                languageEngine.slideEngine = engine
                languageEngine.prepare()
            }
        }
    }

    /** Loads [language] in the background (once) and calls [onLoaded] on the main thread. */
    fun load(
        language: Language,
        onLoaded: () -> Unit = {},
    ) {
        if (!requested.add(language)) return
        executor.execute {
            val started = System.nanoTime()
            val dictionary =
                PerfTrace.section(PerfTrace.DICTIONARY_LOAD) {
                    openDictionary(language).also { engine(language).setLanguageModel(it.languageModel) }
                }
            dictionaries[language] = dictionary
            val millis = (System.nanoTime() - started) / NANOS_PER_MS
            Log.i(TAG, "loaded ${language.code}: ${dictionary.size} words in $millis ms")
            main.post(onLoaded)
            loadBigrams(language)
        }
    }

    /** After the dictionary, so typing can start first; a missing or broken table only costs the context. */
    private fun loadBigrams(language: Language) {
        val table =
            try {
                openBigrams(language)
            } catch (expected: Exception) {
                AppLog.error(TAG, "The word pair table of ${language.code} could not be read", expected)
                null
            }
        if (table == null) {
            Log.i(TAG, "no word pair table for ${language.code}")
            return
        }
        engine(language).setBigrams(table)
        Log.i(TAG, "loaded word pairs of ${language.code}: ${table.size} words")
    }

    fun setLayout(
        language: Language,
        layout: KeyboardLayout,
    ) {
        executor.execute { engine(language).setLayout(layout) }
    }

    /** Decodes a gesture in the background; [onResult] receives candidates on the main thread. */
    fun decode(
        language: Language,
        points: List<TouchPoint>,
        precedingText: String,
        complete: Boolean,
        onResult: (List<String>) -> Unit,
    ) {
        executor.execute {
            val engine = engine(language)
            val section =
                when {
                    !complete -> PerfTrace.DECODE_LIVE
                    engine.routesToNeural(complete) -> PerfTrace.DECODE_NEURAL
                    else -> PerfTrace.DECODE_FINAL
                }
            val words = PerfTrace.section(section) { engine.decode(points, precedingText, complete) }
            main.post { onResult(words) }
        }
    }

    /** Prefix completions for tap typing; empty until the dictionary is loaded. */
    fun completions(
        language: Language,
        prefix: String,
        limit: Int,
    ): List<String> = dictionaries[language]?.completions(prefix, limit).orEmpty()

    /** Better spellings of a lowercase [word] for the cursor standing on it; empty until the dictionary is loaded. */
    fun corrections(
        language: Language,
        word: String,
        limit: Int,
    ): List<String> = dictionaries[language]?.corrections(word, limit).orEmpty()

    fun shutdown() {
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun engine(language: Language): SwitchableGestureEngine =
        engines.getOrPut(language) {
            SwitchableGestureEngine(
                language = language,
                openNeural = { openNeural(language) },
                onNeuralFailure = {
                    AppLog.error(TAG, "The neural slide to type model failed; using the default engine", it)
                    ErrorReporting.capture(it)
                },
            ).also {
                it.slideEngine = slideEngine
                it.prepare()
            }
        }

    private companion object {
        const val TAG = "GestureDecoder"
        const val NANOS_PER_MS = 1_000_000
    }
}
