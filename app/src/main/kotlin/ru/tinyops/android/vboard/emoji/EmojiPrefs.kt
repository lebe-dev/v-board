package ru.tinyops.android.vboard.emoji

import android.content.Context
import androidx.core.content.edit
import ru.tinyops.android.vboard.core.emoji.EmojiHidden
import ru.tinyops.android.vboard.core.emoji.EmojiPins
import ru.tinyops.android.vboard.core.emoji.EmojiRecents

/** The emoji used last and the pinned ones, kept between sessions. */
class EmojiPrefs(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var recents: EmojiRecents
        get() = EmojiRecents.decode(prefs.getString(KEY_RECENTS, null))
        set(value) = prefs.edit { putString(KEY_RECENTS, value.encode()) }

    /** Whether the emoji picker is available on the keyboard; on by default. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** The emoji pinned to the top row of the picker. */
    var pins: EmojiPins
        get() = EmojiPins.decode(prefs.getString(KEY_PINS, null))
        set(value) = prefs.edit { putString(KEY_PINS, value.encode()) }

    /** The categories and single emoji the user removed from the picker. */
    var hidden: EmojiHidden
        get() = EmojiHidden.decode(prefs.getString(KEY_HIDDEN, null))
        set(value) = prefs.edit { putString(KEY_HIDDEN, value.encode()) }

    private companion object {
        const val FILE = "emoji_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_RECENTS = "recents"
        const val KEY_PINS = "pins"
        const val KEY_HIDDEN = "hidden"
    }
}
