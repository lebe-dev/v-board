package ru.tinyops.android.vboard.core.prefs

/**
 * Remembers what [parse] made of the last stored text and returns it again while the text stays the
 * same. Preferences hand out the same string until the value is written anew, so a settled value is
 * parsed once instead of at every read: opening the keyboard in a field reads the servers, the
 * prompts and the custom buttons dozens of times.
 *
 * The result is shared between callers and must not be changed. Thread-safe: two threads may parse
 * the same text at once, but a result always belongs to the text it was asked for.
 */
class ParseCache<T>(
    private val parse: (String?) -> T,
) {
    private class Entry<T>(
        val text: String?,
        val value: T,
    )

    @Volatile
    private var last: Entry<T>? = null

    operator fun invoke(text: String?): T {
        val cached = last
        if (cached != null && cached.text == text) return cached.value
        return parse(text).also { last = Entry(text, it) }
    }
}
