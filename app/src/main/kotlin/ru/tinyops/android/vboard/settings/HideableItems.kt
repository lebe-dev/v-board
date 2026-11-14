package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.tinyops.android.vboard.R

/** Sections of the main settings screen that can lose rows. */
internal enum class HideableSection { APPEARANCE, TYPING, VOICE, ENHANCEMENT }

/** A row of the main settings screen the user may hide. The [id] is stored in the settings and must stay stable. */
internal enum class HideableItem(
    val id: String,
    val section: HideableSection,
) {
    THEME("theme", HideableSection.APPEARANCE),
    KEYS("keys", HideableSection.APPEARANCE),
    EMOJI("emoji", HideableSection.APPEARANCE),
    SUGGESTIONS("suggestions", HideableSection.TYPING),
    QUICK_REPLIES("quick_replies", HideableSection.TYPING),
    AUTOCORRECT("autocorrect", HideableSection.TYPING),
    SLIDE_ENGINE("slide_engine", HideableSection.TYPING),
    CLIPBOARD("clipboard", HideableSection.TYPING),
    SPACE_DRAG("space_drag", HideableSection.TYPING),
    FULLSCREEN("fullscreen", HideableSection.TYPING),
    DELETE_KEY("delete_key", HideableSection.TYPING),
    VOICE_SETTINGS("voice_settings", HideableSection.VOICE),
    MIC_BUTTON("mic_button", HideableSection.VOICE),
    VOICE_ANIMATION("voice_animation", HideableSection.VOICE),
    DICTATION("dictation", HideableSection.ENHANCEMENT),
    PROOFREAD("proofread", HideableSection.ENHANCEMENT),
    CUSTOM_BUTTONS("custom_buttons", HideableSection.ENHANCEMENT),
    DICTIONARY("dictionary", HideableSection.ENHANCEMENT),
    PROMPTS("prompts", HideableSection.ENHANCEMENT),
    SERVERS("servers", HideableSection.ENHANCEMENT),
}

/**
 * Which rows of the main settings screen are shown. While [editing], every row is listed and a tap
 * hides or restores it instead of opening the page.
 */
internal class HiddenRows(
    private val hidden: Set<String>,
    val editing: Boolean,
    private val onToggle: (HideableItem) -> Unit,
) {
    fun isHidden(item: HideableItem) = item.id in hidden

    /** Whether the section has anything to list. */
    fun shows(section: HideableSection) = editing || HideableItem.entries.any { it.section == section && !isHidden(it) }

    fun toggle(item: HideableItem) = onToggle(item)
}

/** Adds the tile of [item] unless it is hidden; in the editing mode the tile gets an eye that toggles it. */
internal fun SettingsSectionScope.hideable(
    rows: HiddenRows,
    item: HideableItem,
    content: @Composable () -> Unit,
) {
    val hidden = rows.isHidden(item)
    if (hidden && !rows.editing) return
    item {
        if (!rows.editing) {
            content()
            return@item
        }
        val label = stringResource(if (hidden) R.string.settings_edit_show else R.string.settings_edit_hide)
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.alpha(if (hidden) DIMMED else 1f)) { content() }
            Box(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = label }
                    .clickable(role = Role.Button) { rows.toggle(item) },
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = EYE_MARGIN)
                    .size(EYE_SIZE)
                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private const val DIMMED = 0.4f
private val EYE_SIZE = 40.dp
private val EYE_MARGIN = 8.dp
