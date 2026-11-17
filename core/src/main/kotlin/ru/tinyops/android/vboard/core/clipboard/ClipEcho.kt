package ru.tinyops.android.vboard.core.clipboard

import java.security.MessageDigest

/**
 * Tells which copies must not be sent to the other devices: what came from them (pasted from a chip, or put into
 * the clipboard by the sync app, recognized by its preview) and a copy that was just sent. Keeps digests and
 * previews, never the text itself. Thread safe.
 */
class ClipEcho {
    private val pasted = HashMap<String, Long>()
    private val arrived = HashMap<String, Long>()
    private val sent = HashMap<String, Long>()

    /** A text from another device was pasted at [at]. */
    fun pasted(
        text: String,
        at: Long,
    ) = synchronized(this) { pasted[digest(text)] = at }

    /** An item with [preview] came from another device at [at]. */
    fun arrived(
        preview: String,
        at: Long,
    ) {
        val shown = whitespace.replace(preview.trim(), " ")
        if (shown.isEmpty()) return
        synchronized(this) { arrived[digest(shown)] = at }
    }

    /** [text] was sent at [at]. */
    fun sent(
        text: String,
        at: Long,
    ) = synchronized(this) { sent[digest(text)] = at }

    /** True when [text] came from another device. */
    fun isEcho(
        text: String,
        now: Long,
    ): Boolean =
        synchronized(this) {
            pasted.expire(now, PASTED_MILLIS)
            arrived.expire(now, ARRIVED_MILLIS)
            digest(text) in pasted || digest(preview(text)) in arrived
        }

    /** True when the same [text] was sent a moment ago. */
    fun isRepeat(
        text: String,
        now: Long,
    ): Boolean =
        synchronized(this) {
            sent.expire(now, REPEAT_MILLIS)
            digest(text) in sent
        }

    private fun HashMap<String, Long>.expire(
        now: Long,
        window: Long,
    ) {
        entries.removeAll { now - it.value > window }
    }

    companion object {
        const val PASTED_MILLIS = 10_000L
        const val ARRIVED_MILLIS = 10 * 60_000L
        const val REPEAT_MILLIS = 5_000L
        private const val PREVIEW_LIMIT = 160
        private val whitespace = Regex("\\s+")

        /** The preview the sync app makes of a text: trimmed, whitespace collapsed, shortened with "...". */
        fun preview(text: String): String {
            val source = whitespace.replace(text.trim(), " ")
            if (source.length <= PREVIEW_LIMIT) return source
            return source.take(PREVIEW_LIMIT).trimEnd() + "..."
        }

        private fun digest(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
