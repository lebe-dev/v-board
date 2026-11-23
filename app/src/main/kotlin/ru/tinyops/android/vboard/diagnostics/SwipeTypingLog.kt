package ru.tinyops.android.vboard.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.core.gesture.GestureDecodeResult
import ru.tinyops.android.vboard.core.gesture.logging.SwipeLogArchive
import ru.tinyops.android.vboard.core.gesture.logging.SwipeLogBuffer
import ru.tinyops.android.vboard.core.gesture.logging.SwipeLogEntry
import ru.tinyops.android.vboard.core.gesture.logging.SwipeLogTracker
import ru.tinyops.android.vboard.core.gesture.logging.SwipeOutcome
import ru.tinyops.android.vboard.core.input.DeleteHoldMode
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in ring buffer of recent free-typing swipe gestures for diagnosing slide-to-type accuracy.
 * Off by default, turns itself off after [AUTO_OFF_MILLIS], keeps at most [MAX_ENTRIES] gestures
 * in private non-backed-up storage, and never writes user text to error logs or crash reports.
 */
internal object SwipeTypingLog {
    const val MAX_ENTRIES = SwipeLogBuffer.DEFAULT_CAPACITY
    const val AUTO_OFF_MILLIS = 4L * 60 * 60 * 1000
    private const val FILE_NAME = "swipe-typing-log.jsonl"

    data class State(
        val enabled: Boolean = false,
        val untilMillis: Long = 0L,
        val count: Int = 0,
        val pickedCount: Int = 0,
        val retypedCount: Int = 0,
    )

    private val lock = Any()
    private val buffer = SwipeLogBuffer(MAX_ENTRIES)
    private val tracker = SwipeLogTracker(::applyOutcome)
    private val layouts = ConcurrentHashMap<Language, KeyboardLayout>()
    private val nextId = AtomicLong(1L)
    private val io: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "swipe-log-io") }

    private var store: SwipeLogStore? = null
    private var prefs: KeyboardPrefs? = null

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Connects storage and preferences and loads any previously saved entries. */
    fun init(context: Context) {
        val app = context.applicationContext
        val localStore = SwipeLogStore(File(app.noBackupFilesDir, FILE_NAME))
        synchronized(lock) {
            store = localStore
            prefs = KeyboardPrefs(app)
        }
        io.execute {
            val loaded = localStore.read()
            synchronized(lock) {
                if (buffer.size == 0 && loaded.isNotEmpty()) {
                    buffer.replaceAll(loaded)
                    val maxId = loaded.maxOf { it.id }
                    nextId.updateAndGet { current -> maxOf(current, maxId + 1L) }
                }
                publishLocked()
            }
        }
        synchronized(lock) { publishLocked() }
    }

    /** Turns recording on (for [AUTO_OFF_MILLIS]) or off without deleting already captured gestures. */
    fun setEnabled(
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            prefs?.swipeLogUntilMillis = if (enabled) nowMillis + AUTO_OFF_MILLIS else 0L
            if (!enabled) tracker.reset()
            publishLocked(nowMillis)
        }
    }

    /** Whether recording is currently active; turns itself off automatically once expired. */
    fun isRecording(nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val active = prefs?.isSwipeLogActive(nowMillis) == true
            if (!active && mutableState.value.enabled) {
                tracker.reset()
                publishLocked(nowMillis)
            }
            active
        }

    fun setLayout(
        language: Language,
        layout: KeyboardLayout,
    ) {
        layouts[language] = layout
    }

    /** Records a completed swipe gesture and starts tracking what the user does with [inserted]. */
    fun recordSwipe(
        language: Language,
        points: List<TouchPoint>,
        previousWord: String?,
        result: GestureDecodeResult,
        inserted: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val layout = layouts[language] ?: return
        val snapshot: List<SwipeLogEntry>
        synchronized(lock) {
            if (prefs?.isSwipeLogActive(nowMillis) != true) {
                publishLocked(nowMillis)
                return
            }
            val id = nextId.getAndIncrement()
            buffer.add(
                SwipeLogEntry(
                    id = id,
                    timestampMillis = nowMillis,
                    points = points.toList(),
                    layout = layout,
                    previousWord = previousWord,
                    engine = result.engine,
                    candidates = result.words,
                    candidateScores = result.details,
                    inserted = inserted,
                ),
            )
            tracker.onSwipeCommitted(id, inserted)
            snapshot = buffer.snapshot()
            publishLocked(nowMillis)
        }
        persist(snapshot)
    }

    fun onSuggestionPicked(word: String) {
        mutateTracker { tracker.onSuggestionPicked(word) }
    }

    fun onSwipeWordDeleted() {
        mutateTracker { tracker.onSwipeWordDeleted() }
    }

    fun onTypedWordCommitted(word: String) {
        mutateTracker { tracker.onTypedWordCommitted(word) }
    }

    fun onWordSettled() {
        synchronized(lock) { tracker.onWordSettled() }
    }

    fun onInputFinished() {
        synchronized(lock) { tracker.reset() }
    }

    fun entries(): List<SwipeLogEntry> = synchronized(lock) { buffer.snapshot() }

    /** Deletes all recorded gestures from memory and storage. */
    fun clear() {
        val localStore: SwipeLogStore?
        synchronized(lock) {
            buffer.clear()
            tracker.reset()
            localStore = store
            publishLocked()
        }
        io.execute { localStore?.clear() }
    }

    /** Writes all currently buffered gestures as a zip archive compatible with `evaluate.py`. */
    fun writeArchive(
        context: Context,
        output: OutputStream,
    ) {
        val items = entries()
        SwipeLogArchive.write(items, swipeLogMeta(context, items.size), output)
    }

    private fun mutateTracker(block: () -> Unit) {
        var snapshot: List<SwipeLogEntry>? = null
        synchronized(lock) {
            val before = buffer.snapshot()
            block()
            val after = buffer.snapshot()
            if (after != before) {
                snapshot = after
                publishLocked()
            }
        }
        snapshot?.let(::persist)
    }

    private fun applyOutcome(
        id: Long,
        outcome: SwipeOutcome,
        finalWord: String,
    ) {
        buffer.updateOutcome(id, outcome, finalWord)
    }

    private fun persist(snapshot: List<SwipeLogEntry>) {
        val localStore = synchronized(lock) { store } ?: return
        io.execute { localStore.write(snapshot) }
    }

    private fun publishLocked(nowMillis: Long = System.currentTimeMillis()) {
        val active = prefs?.isSwipeLogActive(nowMillis) == true
        val until = if (active) prefs?.swipeLogUntilMillis ?: 0L else 0L
        val items = buffer.snapshot()
        mutableState.value =
            State(
                enabled = active,
                untilMillis = until,
                count = items.size,
                pickedCount = items.count { it.outcome == SwipeOutcome.PICKED_SUGGESTION },
                retypedCount = items.count { it.outcome == SwipeOutcome.RETYPED },
            )
    }
}

internal fun recordAfterTextKey(
    wasGesture: Boolean,
    wasComposing: String,
    stillComposing: Boolean,
) {
    when {
        wasGesture -> SwipeTypingLog.onWordSettled()
        wasComposing.isNotEmpty() && !stillComposing -> SwipeTypingLog.onTypedWordCommitted(wasComposing)
    }
}

internal fun recordAfterDelete(
    step: DeleteHoldMode,
    wasGesture: Boolean,
) {
    when {
        step == DeleteHoldMode.ALL -> SwipeTypingLog.onInputFinished()
        wasGesture -> SwipeTypingLog.onSwipeWordDeleted()
    }
}

internal fun recordAfterSuggestionPicked(
    wasGesture: Boolean,
    wasComposing: Boolean,
    word: String,
) {
    when {
        wasGesture -> SwipeTypingLog.onSuggestionPicked(word)
        wasComposing -> SwipeTypingLog.onTypedWordCommitted(word)
    }
}

private fun swipeLogMeta(
    context: Context,
    count: Int,
): Map<String, String> {
    val metrics = context.resources.displayMetrics
    return linkedMapOf(
        "type" to "free_typing_log",
        "app" to BuildConfig.VERSION_NAME,
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to Build.VERSION.SDK_INT.toString(),
        "density" to metrics.density.toString(),
        "xdpi" to metrics.xdpi.toString(),
        "ydpi" to metrics.ydpi.toString(),
        "entries" to count.toString(),
        "saved" to Instant.now().toString(),
    )
}
