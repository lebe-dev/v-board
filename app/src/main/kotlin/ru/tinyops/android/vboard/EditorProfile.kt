package ru.tinyops.android.vboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import ru.tinyops.android.vboard.core.layout.KeyboardMode

/** Icon shown on the enter key, derived from the editor's IME action. */
enum class EnterAction(
    @param:DrawableRes val icon: Int,
) {
    NEWLINE(R.drawable.ic_action_newline),
    SEARCH(R.drawable.ic_action_search),
    GO(R.drawable.ic_action_go),
    SEND(R.drawable.ic_action_send),
    NEXT(R.drawable.ic_action_go),
    PREVIOUS(R.drawable.ic_action_previous),
    DONE(R.drawable.ic_action_done),
}

/** What the focused editor allows, derived from its [EditorInfo]. */
data class EditorProfile(
    val initialMode: KeyboardMode,
    val suggestionsAllowed: Boolean,
    val gesturesAllowed: Boolean,
    val enterAction: EnterAction,
    val multiLine: Boolean,
    val fullscreenAllowed: Boolean,
    /**
     * The field asks the keyboard not to learn from what is typed (`IME_FLAG_NO_PERSONALIZED_LEARNING`), as a
     * private tab of a browser does: nothing typed or dictated there is kept on the phone.
     */
    val incognito: Boolean = false,
) {
    /** Dictation goes where words can be swiped: text fields that are not passwords. */
    val voiceAllowed: Boolean get() = gesturesAllowed

    companion object {
        private val PASSWORD_VARIATIONS =
            setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )

        /**
         * @param suggestAlways suggest words also in a field that asks for none; passwords and fields that are not
         *   text stay without them
         */
        fun from(
            info: EditorInfo?,
            suggestAlways: Boolean = false,
        ): EditorProfile {
            val inputType = info?.inputType ?: InputType.TYPE_NULL
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val isText = inputClass == InputType.TYPE_CLASS_TEXT
            val isPassword = isText && variation in PASSWORD_VARIATIONS
            val noSuggestions = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
            val numeric =
                inputClass == InputType.TYPE_CLASS_NUMBER ||
                    inputClass == InputType.TYPE_CLASS_PHONE ||
                    inputClass == InputType.TYPE_CLASS_DATETIME
            return EditorProfile(
                initialMode = if (numeric) KeyboardMode.SYMBOLS else KeyboardMode.LETTERS,
                suggestionsAllowed = isText && !isPassword && (suggestAlways || !noSuggestions),
                gesturesAllowed = isText && !isPassword,
                enterAction = enterAction(info),
                multiLine = isText && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
                // The flags against fullscreen are left out on purpose: the mode only starts at the user's tap.
                fullscreenAllowed = isText && !isPassword,
                incognito = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
            )
        }

        private fun enterAction(info: EditorInfo?): EnterAction {
            val options = info?.imeOptions ?: return EnterAction.NEWLINE
            if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.NEWLINE
            return when (options and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                EditorInfo.IME_ACTION_GO -> EnterAction.GO
                EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
                EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
                EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
                else -> EnterAction.NEWLINE
            }
        }
    }
}
