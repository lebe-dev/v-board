package ru.tinyops.android.vboard.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.DiskQueue
import ru.tinyops.android.vboard.core.voice.DictationResults
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.PromptResult
import ru.tinyops.android.vboard.core.voice.PromptResultHistory
import ru.tinyops.android.vboard.core.voice.PromptTag
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The buffer of prompt results on disk, next to the clipboard history and apart from it. Nothing is
 * kept while [enabled] says no; what was kept stays until the user deletes it. Disk work runs on
 * [executor] and the index is written atomically; [history] is replaced as a whole and may be read
 * from any thread, [onChanged] runs through [notify].
 */
class PromptResultStore(
    context: Context,
    private val enabled: () -> Boolean,
    executor: Executor = Executors.newSingleThreadExecutor(),
    private val notify: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val directory = File(context.filesDir, DIRECTORY)
    private val index = AtomicFile(File(directory, INDEX))
    private val disk = DiskQueue(executor)

    @Volatile
    var history: PromptResultHistory = PromptResultHistory()
        private set

    @Volatile
    var onChanged: (() -> Unit)? = null

    /** The highest id given out or read from the disk; guarded by the store's lock. */
    private var lastId = 0L

    init {
        // Off the main thread: the keyboard starts without waiting for the disk.
        disk.execute {
            load()
            if (history.entries.isNotEmpty()) notify(Runnable { onChanged?.invoke() })
        }
    }

    /** What a model answered for [prompts]; only a successful answer is kept. */
    fun recordAnswer(
        answer: TextEnhancer.Result,
        prompts: List<PromptTag>,
    ) {
        if (answer.outcome != EnhancementOutcome.SUCCESS || !enabled()) return
        val id = nextIds(1)
        add(listOf(PromptResult(id, answer.text, PromptResult.Kind.ANSWER, prompts)))
    }

    /** The raw and the improved text of a dictation, the improved one on top. */
    fun recordDictation(
        texts: DictationResults.Texts,
        prompts: List<PromptTag>,
    ) {
        if (!enabled()) return
        val id = nextIds(2)
        add(
            listOf(
                PromptResult(id + 1, texts.improved, PromptResult.Kind.ANSWER, prompts),
                PromptResult(id, texts.transcript, PromptResult.Kind.TRANSCRIPT),
            ),
        )
    }

    fun remove(id: Long) = update { it.remove(id) }

    fun clear() = update { it.clear() }

    /** Puts the results [ids] in this order, as the user dragged them. */
    fun reorder(ids: List<Long>) = update { it.reorder(ids) }

    /** The keyboard is gone: what is queued is still written, nothing later is. */
    fun close() = disk.close()

    // Two results kept within one millisecond still get ids of their own.
    @Synchronized
    private fun nextIds(count: Int): Long {
        val id = maxOf(clock(), lastId + 1)
        lastId = id + count - 1
        return id
    }

    private fun add(results: List<PromptResult>) = update { it.add(results) }

    private fun update(change: (PromptResultHistory) -> PromptResultHistory) =
        disk.execute {
            history = change(history)
            save()
            notify(Runnable { onChanged?.invoke() })
        }

    private fun save() {
        val entries = JSONArray()
        history.entries.forEach { entries.put(toJson(it)) }
        directory.mkdirs()
        index.writeText(JSONObject().put(KEY_ENTRIES, entries).toString())
    }

    private fun load() {
        if (!index.baseFile.exists()) return
        val json = runCatching { JSONObject(index.readText()) }.getOrNull() ?: return
        val entries = json.optJSONArray(KEY_ENTRIES) ?: return
        val loaded = (0 until entries.length()).mapNotNull { fromJson(entries.optJSONObject(it)) }
        history = PromptResultHistory(loaded)
        val highest = loaded.maxOfOrNull { it.id } ?: 0
        synchronized(this) { lastId = maxOf(lastId, highest) }
    }

    private fun toJson(result: PromptResult): JSONObject {
        val prompts = JSONArray()
        result.prompts.forEach { prompts.put(JSONObject().put("id", it.id).put("title", it.title)) }
        return JSONObject()
            .put("id", result.id)
            .put("text", result.text)
            .put("kind", result.kind.id)
            .put("prompts", prompts)
    }

    private fun fromJson(json: JSONObject?): PromptResult? {
        if (json == null) return null
        val id = json.optLong("id", -1)
        val kind = PromptResult.Kind.fromId(json.optString("kind"))
        val text = json.optString("text")
        if (id < 0 || kind == null || text.isBlank()) return null
        val array = json.optJSONArray("prompts") ?: JSONArray()
        val prompts =
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { PromptTag(it.optString("id"), it.optString("title")) }
            }
        return PromptResult(id, text, kind, prompts)
    }

    private companion object {
        const val DIRECTORY = "prompt_results"
        const val INDEX = "history.json"
        const val KEY_ENTRIES = "entries"
    }
}
