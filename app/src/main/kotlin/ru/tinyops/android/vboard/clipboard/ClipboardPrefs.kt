package ru.tinyops.android.vboard.clipboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonPlacement

/** Clipboard settings, shared by the keyboard and the settings screen (same process). */
class ClipboardPrefs(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** False removes the clipboard from the keyboard; the history and the other choices stay for when it returns. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Whether what is copied goes to the history now. */
    val recording: Boolean get() = enabled && keepHistory

    /** Whether the strip offers what was copied now. */
    val chipsShown: Boolean get() = enabled && showOnStrip

    /** The color of the clipboard button's icon; null keeps it like the other icons of the strip. */
    var iconColor: ButtonColor?
        get() = ButtonColor.fromId(prefs.getString(KEY_COLOR, null))
        set(value) = prefs.edit { putString(KEY_COLOR, value?.id) }

    /** Recently copied text or image as chips on the suggestion strip. */
    var showOnStrip: Boolean
        get() = prefs.getBoolean(KEY_STRIP, true)
        set(value) = prefs.edit { putBoolean(KEY_STRIP, value) }

    /** Links, e-mail addresses and phone numbers found in the copied text as extra chips. */
    var showExtracted: Boolean
        get() = prefs.getBoolean(KEY_EXTRACTED, true)
        set(value) = prefs.edit { putBoolean(KEY_EXTRACTED, value) }

    /** Remember what was copied, to paste it again later. */
    var keepHistory: Boolean
        get() = prefs.getBoolean(KEY_HISTORY, true)
        set(value) = prefs.edit { putBoolean(KEY_HISTORY, value) }

    /** Which part of the strip holds the clipboard button; the right end, next to the microphone, by default. */
    var iconPlacement: ButtonPlacement
        get() =
            prefs.getString(KEY_PLACEMENT, null).let { id -> ButtonPlacement.entries.firstOrNull { it.id == id } }
                ?: ButtonPlacement.RIGHT
        set(value) = prefs.edit { putString(KEY_PLACEMENT, value.id) }

    private companion object {
        const val FILE = "clipboard_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_COLOR = "icon_color"
        const val KEY_STRIP = "show_on_strip"
        const val KEY_EXTRACTED = "show_extracted"
        const val KEY_HISTORY = "keep_history"
        const val KEY_PLACEMENT = "icon_placement"
    }
}
