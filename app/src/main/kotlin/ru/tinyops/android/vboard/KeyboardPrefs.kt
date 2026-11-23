package ru.tinyops.android.vboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import ru.tinyops.android.vboard.core.clipboard.LinkCleaner
import ru.tinyops.android.vboard.core.dict.TapSuggestOptions
import ru.tinyops.android.vboard.core.gesture.SlideEngine
import ru.tinyops.android.vboard.core.input.DeleteHoldMode
import ru.tinyops.android.vboard.core.input.SpaceDragMode
import ru.tinyops.android.vboard.core.input.SuggestionTimeout
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonPlacement

/** Typing settings, shared by the keyboard and the settings screen (same process). */
class KeyboardPrefs(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Ids of the rows hidden on the main settings screen; the ids are stored, so they must stay stable. */
    var hiddenSettings: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_SETTINGS, null).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_HIDDEN_SETTINGS, value) }

    var spaceDragMode: SpaceDragMode
        get() = SpaceDragMode.fromCode(prefs.getString(KEY_SPACE_DRAG, null))
        set(value) = prefs.edit { putString(KEY_SPACE_DRAG, value.code) }

    /** What holding the delete key does; deletes characters one by one by default. */
    var deleteHold: DeleteHoldMode
        get() = DeleteHoldMode.fromCode(prefs.getString(KEY_DELETE_HOLD, null))
        set(value) = prefs.edit { putString(KEY_DELETE_HOLD, value.code) }

    /** Pause between words while the delete key is held in the word mode, in milliseconds. */
    var wordDeleteInterval: Int
        get() =
            prefs
                .getInt(KEY_WORD_DELETE_INTERVAL, DEFAULT_WORD_DELETE_INTERVAL)
                .coerceIn(MIN_WORD_DELETE_INTERVAL, MAX_WORD_DELETE_INTERVAL)
        set(value) =
            prefs.edit {
                putInt(KEY_WORD_DELETE_INTERVAL, value.coerceIn(MIN_WORD_DELETE_INTERVAL, MAX_WORD_DELETE_INTERVAL))
            }

    /** Whether a finger sliding across letters types a word; on by default. */
    var slideEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLIDE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SLIDE_ENABLED, value) }

    /** Whether the keyboard turns Shift on by itself where the field asks for capital letters; on by default. */
    var autoCapitalize: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPITALIZE, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CAPITALIZE, value) }

    /** Whether links pasted from the keyboard lose their ad tracking parameters; off by default. */
    var cleanLinks: Boolean
        get() = prefs.getBoolean(KEY_CLEAN_LINKS, false)
        set(value) = prefs.edit { putBoolean(KEY_CLEAN_LINKS, value) }

    /** The text with ad tracking removed from its links when [cleanLinks] is on. */
    fun cleanedLinks(text: String): String = if (cleanLinks) LinkCleaner.clean(text) else text

    var slideEngine: SlideEngine
        get() = SlideEngine.fromCode(prefs.getString(KEY_SLIDE_ENGINE, null))
        set(value) = prefs.edit { putString(KEY_SLIDE_ENGINE, value.code) }

    /**
     * Set while the neural slide to type model loads; written synchronously so that it survives a
     * native crash in the middle of the load.
     */
    var neuralModelLoading: Boolean
        get() = prefs.getBoolean(KEY_NEURAL_LOADING, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_NEURAL_LOADING, value) }

    /** Whether the strip offers the text area that takes the screen above the keys; off by default. */
    var fullscreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, false)
        set(value) = prefs.edit { putBoolean(KEY_FULLSCREEN, value) }

    /** Which part of the strip holds the fullscreen button; the left, next to the cog, by default. */
    var fullscreenPlacement: ButtonPlacement
        get() = ButtonPlacement.fromId(prefs.getString(KEY_FULLSCREEN_PLACEMENT, null))
        set(value) = prefs.edit { putString(KEY_FULLSCREEN_PLACEMENT, value.id) }

    /** The color of the fullscreen button's icon; null keeps it like the other icons of the strip. */
    var fullscreenColor: ButtonColor?
        get() = ButtonColor.fromId(prefs.getString(KEY_FULLSCREEN_COLOR, null))
        set(value) = prefs.edit { putString(KEY_FULLSCREEN_COLOR, value?.id) }

    /** Whether the custom buttons that do not fit on the strip go to a second row under it; off by default. */
    var stripSecondRow: Boolean
        get() = prefs.getBoolean(KEY_STRIP_SECOND_ROW, false)
        set(value) = prefs.edit { putBoolean(KEY_STRIP_SECOND_ROW, value) }

    /**
     * The width of a custom, clipboard or fullscreen button of the strip, in percent of the strip's height:
     * narrower buttons stand closer and more of them fit.
     */
    var stripButtonWidth: Int
        get() = prefs.getInt(KEY_STRIP_BUTTON_WIDTH, MAX_BUTTON_WIDTH).coerceIn(MIN_BUTTON_WIDTH, MAX_BUTTON_WIDTH)
        set(value) = prefs.edit { putInt(KEY_STRIP_BUTTON_WIDTH, value.coerceIn(MIN_BUTTON_WIDTH, MAX_BUTTON_WIDTH)) }

    /** How long word suggestions stay on the strip after the last key. */
    var suggestionTimeout: SuggestionTimeout
        get() = SuggestionTimeout.fromSeconds(prefs.getInt(KEY_SUGGESTION_TIMEOUT, SuggestionTimeout.DEFAULT.seconds))
        set(value) = prefs.edit { putInt(KEY_SUGGESTION_TIMEOUT, value.seconds) }

    /** Whether words are suggested also in fields that ask for no suggestions; off by default. */
    var suggestAlways: Boolean
        get() = prefs.getBoolean(KEY_SUGGEST_ALWAYS, false)
        set(value) = prefs.edit { putBoolean(KEY_SUGGEST_ALWAYS, value) }

    /** Whether the words that usually follow are suggested after a space; on by default. */
    var suggestNextWord: Boolean
        get() = prefs.getBoolean(KEY_SUGGEST_NEXT_WORD, true)
        set(value) = prefs.edit { putBoolean(KEY_SUGGEST_NEXT_WORD, value) }

    /** Whether typo corrections are suggested while typing manually; on by default. */
    var tapCorrections: Boolean
        get() = prefs.getBoolean(KEY_TAP_CORRECTIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_TAP_CORRECTIONS, value) }

    /** Whether confident typo corrections apply automatically on space or punctuation; on by default. */
    var autocorrect: Boolean
        get() = prefs.getBoolean(KEY_AUTOCORRECT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTOCORRECT, value) }

    /** Whether completions are suggested when an unfinished word contains a typo; on by default. */
    var fuzzyPrefix: Boolean
        get() = prefs.getBoolean(KEY_FUZZY_PREFIX, true)
        set(value) = prefs.edit { putBoolean(KEY_FUZZY_PREFIX, value) }

    /** Whether up to two typos are corrected in longer words; on by default. */
    var twoEditCorrections: Boolean
        get() = prefs.getBoolean(KEY_TWO_EDIT_CORRECTIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_TWO_EDIT_CORRECTIONS, value) }

    /** Whether exact touch coordinates on keys weigh typing corrections; on by default. */
    var spatialTapModel: Boolean
        get() = prefs.getBoolean(KEY_SPATIAL_TAP_MODEL, true)
        set(value) = prefs.edit { putBoolean(KEY_SPATIAL_TAP_MODEL, value) }

    /** Current options for manual-typing suggestions and corrections. */
    fun tapSuggestOptions(): TapSuggestOptions =
        TapSuggestOptions(
            corrections = tapCorrections,
            fuzzyPrefix = tapCorrections && fuzzyPrefix,
            twoEdits = tapCorrections && twoEditCorrections,
            spatialModel = tapCorrections && spatialTapModel,
        )

    /** Whether crash reports go to the developer; off until the user agrees. */
    var sendErrors: Boolean
        get() = prefs.getBoolean(KEY_SEND_ERRORS, false)
        set(value) = prefs.edit { putBoolean(KEY_SEND_ERRORS, value) }

    /** Whether errors are written to the log in the settings; off until the user turns it on. */
    var logErrors: Boolean
        get() = prefs.getBoolean(KEY_LOG_ERRORS, false)
        set(value) = prefs.edit { putBoolean(KEY_LOG_ERRORS, value) }

    companion object {
        const val MIN_BUTTON_WIDTH = 60
        const val MAX_BUTTON_WIDTH = 100
        const val BUTTON_WIDTH_STEP = 5
        const val MIN_WORD_DELETE_INTERVAL = 50
        const val MAX_WORD_DELETE_INTERVAL = 400
        const val WORD_DELETE_INTERVAL_STEP = 25
        const val DEFAULT_WORD_DELETE_INTERVAL = 150

        private const val FILE = "keyboard_prefs"
        private const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
        private const val KEY_AUTOCORRECT = "autocorrect"
        private const val KEY_CLEAN_LINKS = "clean_links"
        private const val KEY_DELETE_HOLD = "delete_hold"
        private const val KEY_FULLSCREEN = "fullscreen_enabled"
        private const val KEY_FULLSCREEN_COLOR = "fullscreen_color"
        private const val KEY_FULLSCREEN_PLACEMENT = "fullscreen_placement"
        private const val KEY_FUZZY_PREFIX = "fuzzy_prefix"
        private const val KEY_HIDDEN_SETTINGS = "hidden_settings"
        private const val KEY_LOG_ERRORS = "log_errors"
        private const val KEY_NEURAL_LOADING = "neural_model_loading"
        private const val KEY_SEND_ERRORS = "send_errors"
        private const val KEY_SLIDE_ENABLED = "slide_enabled"
        private const val KEY_SLIDE_ENGINE = "slide_engine"
        private const val KEY_SPACE_DRAG = "space_drag_mode"
        private const val KEY_SPATIAL_TAP_MODEL = "spatial_tap_model"
        private const val KEY_STRIP_BUTTON_WIDTH = "strip_button_width"
        private const val KEY_WORD_DELETE_INTERVAL = "word_delete_interval_ms"
        private const val KEY_STRIP_SECOND_ROW = "strip_second_row"
        private const val KEY_SUGGEST_ALWAYS = "suggest_always"
        private const val KEY_SUGGEST_NEXT_WORD = "suggest_next_word"
        private const val KEY_SUGGESTION_TIMEOUT = "suggestion_timeout_seconds"
        private const val KEY_TAP_CORRECTIONS = "tap_corrections"
        private const val KEY_TWO_EDIT_CORRECTIONS = "two_edit_corrections"
    }
}
