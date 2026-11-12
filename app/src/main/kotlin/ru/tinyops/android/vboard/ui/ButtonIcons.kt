package ru.tinyops.android.vboard.ui

import androidx.annotation.DrawableRes
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.voice.ProofreadState

/**
 * A custom button as the strip or the fullscreen panel shows it; [holds] means a long press has something
 * to do, and [description] is its name: what the popup of a group shows and a screen reader says. A [hidden]
 * button is left off the strip itself, but where it stands in the second row it is shown all the same.
 */
data class StripButton(
    val id: String,
    @param:DrawableRes val icon: Int,
    val state: ProofreadState,
    val color: ButtonColor? = null,
    val placement: ButtonPlacement = ButtonPlacement.LEFT,
    val holds: Boolean = false,
    val description: String = "",
    val members: List<StripButton> = emptyList(),
    val hidden: Boolean = false,
    val secondRow: Boolean = false,
) {
    /** A tap opens the popup with the [members] instead of doing something. */
    val group: Boolean get() = members.isNotEmpty()
}

/** The icons a custom button can have; they are stored by name, which must not change. */
object ButtonIcons {
    val all: List<Pair<String, Int>> =
        listOf(
            "broom" to R.drawable.ic_broom,
            "copy" to R.drawable.ic_content_copy,
            "paste" to R.drawable.ic_content_paste,
            "cut" to R.drawable.ic_content_cut,
            "backspace" to R.drawable.ic_backspace,
            "close" to R.drawable.ic_close,
            "check" to R.drawable.ic_check,
            "add" to R.drawable.ic_add,
            "sparkles" to R.drawable.ic_auto_awesome,
            "fix" to R.drawable.ic_auto_fix_high,
            "robot" to R.drawable.ic_smart_toy,
            "idea" to R.drawable.ic_lightbulb,
            "edit" to R.drawable.ic_edit,
            "shorten" to R.drawable.ic_short_text,
            "title" to R.drawable.ic_title,
            "bold" to R.drawable.ic_format_bold,
            "list" to R.drawable.ic_format_list_bulleted,
            "translate" to R.drawable.ic_translate,
            "language" to R.drawable.ic_language,
            "search" to R.drawable.ic_action_search,
            "chat" to R.drawable.ic_chat,
            "mail" to R.drawable.ic_mail,
            "send" to R.drawable.ic_action_send,
            "phone" to R.drawable.ic_phone,
            "link" to R.drawable.ic_link,
            "code" to R.drawable.ic_code,
            "note" to R.drawable.ic_assignment,
            "book" to R.drawable.ic_menu_book,
            "school" to R.drawable.ic_school,
            "work" to R.drawable.ic_work,
            "person" to R.drawable.ic_person,
            "home" to R.drawable.ic_home,
            "event" to R.drawable.ic_event,
            "timer" to R.drawable.ic_timer,
            "star" to R.drawable.ic_star,
            "heart" to R.drawable.ic_favorite,
            "bookmark" to R.drawable.ic_bookmark,
            "flag" to R.drawable.ic_flag,
            "pin" to R.drawable.ic_push_pin,
            "bolt" to R.drawable.ic_bolt,
            "sun" to R.drawable.ic_light_mode,
            "cloud" to R.drawable.ic_cloud,
            "palette" to R.drawable.ic_palette,
            "image" to R.drawable.ic_image,
            "emoji" to R.drawable.ic_emoji,
            "help" to R.drawable.ic_help,
            "like" to R.drawable.ic_thumb_up,
            "dislike" to R.drawable.ic_thumb_down,
            "lock" to R.drawable.ic_lock,
            "share" to R.drawable.ic_share,
            "bell" to R.drawable.ic_notifications,
            "refresh" to R.drawable.ic_refresh,
            "redo" to R.drawable.ic_redo,
            "play" to R.drawable.ic_play_arrow,
            "pause" to R.drawable.ic_pause,
            "stop" to R.drawable.ic_stop,
            "up" to R.drawable.ic_arrow_upward,
            "down" to R.drawable.ic_arrow_downward,
            "forward" to R.drawable.ic_arrow_forward,
            "save" to R.drawable.ic_save,
            "cart" to R.drawable.ic_shopping_cart,
            "place" to R.drawable.ic_place,
            "attach" to R.drawable.ic_attach_file,
            "folder" to R.drawable.ic_folder,
            "italic" to R.drawable.ic_format_italic,
            "underline" to R.drawable.ic_format_underlined,
            "quote" to R.drawable.ic_format_quote,
            "numbered" to R.drawable.ic_format_list_numbered,
            "text" to R.drawable.ic_text_fields,
            "moon" to R.drawable.ic_dark_mode,
            "music" to R.drawable.ic_music_note,
            "camera" to R.drawable.ic_camera,
            "shield" to R.drawable.ic_shield,
            "tag" to R.drawable.ic_tag,
            "done_all" to R.drawable.ic_done_all,
            "menu" to R.drawable.ic_menu,
            "more" to R.drawable.ic_more_horiz,
        )

    @DrawableRes
    fun of(button: CustomButton): Int {
        val name = button.icon ?: if (button.group) "folder" else ofAction(button.main?.action)
        return all.firstOrNull { it.first == name }?.second ?: R.drawable.ic_auto_awesome
    }

    private fun ofAction(action: ButtonAction?): String =
        when (action) {
            ButtonAction.CLEAR -> "broom"
            ButtonAction.COPY -> "copy"
            ButtonAction.PASTE -> "paste"
            ButtonAction.PROMPT, null -> "sparkles"
        }
}
