package ru.tinyops.android.vboard.diagnostics

import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One recorded error: when it happened, where, what the code said and the stack trace, if there was one. */
internal data class ErrorLogEntry(
    val time: Long,
    val tag: String,
    val message: String,
    val stack: String?,
)

/**
 * The error log on disk, one JSON object per line. Entries live for [RETENTION_DAYS] days and the
 * file never holds more than [MAX_ENTRIES], so a failure that repeats cannot fill the phone.
 */
internal class ErrorLogStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun append(
        tag: String,
        message: String,
        stack: String?,
    ) {
        val added = ErrorLogEntry(clock(), tag, message, stack?.take(MAX_STACK_CHARS))
        val kept = (load() + added).takeLast(MAX_ENTRIES)
        file.parentFile?.mkdirs()
        file.writeText(kept.joinToString(separator = "") { encode(it) + "\n" })
    }

    /** The entries still within the retention period, newest first. */
    @Synchronized
    fun entries(): List<ErrorLogEntry> = load().asReversed()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun load(): List<ErrorLogEntry> {
        if (!file.exists()) return emptyList()
        val oldest = clock() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        return file.readLines().mapNotNull { decode(it) }.filter { it.time >= oldest }
    }

    private fun encode(entry: ErrorLogEntry): String =
        JSONObject()
            .put(TIME, entry.time)
            .put(TAG, entry.tag)
            .put(MESSAGE, entry.message)
            .put(STACK, entry.stack ?: JSONObject.NULL)
            .toString()

    private fun decode(line: String): ErrorLogEntry? =
        runCatching {
            val json = JSONObject(line)
            ErrorLogEntry(
                time = json.getLong(TIME),
                tag = json.getString(TAG),
                message = json.getString(MESSAGE),
                stack = if (json.isNull(STACK)) null else json.getString(STACK),
            )
        }.getOrNull()

    companion object {
        const val RETENTION_DAYS = 7L
        const val MAX_ENTRIES = 500
        const val MAX_STACK_CHARS = 8_000
        private const val TIME = "t"
        private const val TAG = "tag"
        private const val MESSAGE = "msg"
        private const val STACK = "stack"
    }
}
