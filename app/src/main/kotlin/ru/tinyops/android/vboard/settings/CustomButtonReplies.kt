package ru.tinyops.android.vboard.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.InsertMode
import ru.tinyops.android.vboard.core.voice.QuickReply
import ru.tinyops.android.vboard.core.voice.chosen

/** The rows of a quick replies command: which phrases it offers and where the chosen one goes. */
internal fun SettingsSectionScope.replyRows(
    command: ButtonCommand,
    state: VoiceSettingsState,
    onChange: (ButtonCommand) -> Unit,
) {
    item {
        ReplyPickerRow(
            replies = state.read { quickReplies.replies },
            selected = command.replyIds,
            onChange = { onChange(command.copy(replyIds = it)) },
        )
    }
    item {
        SettingsChoiceRow(
            title = stringResource(R.string.custom_button_reply_insert),
            selected = command.insert,
            choices =
                InsertMode.entries.map { Choice(it, stringResource(it.label), stringResource(it.replySummary)) },
            onSelected = { onChange(command.copy(insert = it)) },
        )
    }
    if (!state.read { commandReady(command) }) {
        item { SettingsNote(stringResource(R.string.custom_button_replies_not_ready), icon = R.drawable.ic_warning) }
    }
}

/** Which quick replies the button offers, chosen in a dialog among all of them. */
@Composable
private fun ReplyPickerRow(
    replies: List<QuickReply>,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val chosen = replies.chosen(selected)
    val title = stringResource(R.string.custom_button_replies)
    SettingsLinkRow(
        title = title,
        summary =
            if (chosen.isEmpty()) {
                stringResource(R.string.custom_button_replies_none)
            } else {
                chosen.joinToString { it.text.replace(Regex("\\s+"), " ") }
            },
        onClick = { open = true },
    )
    if (!open) return
    var ticked by rememberSaveable { mutableStateOf(selected.toList()) }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            SettingsDialogContent {
                replies.forEach { reply ->
                    CheckRow(reply.text, reply.id in ticked, enabled = true) { on ->
                        ticked = if (on) ticked + reply.id else ticked - reply.id
                    }
                }
                if (replies.isEmpty()) Text(stringResource(R.string.custom_button_replies_empty))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    open = false
                    onChange(replies.map { it.id }.filter { it in ticked })
                },
            ) { Text(stringResource(R.string.feature_save)) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/** Where a quick reply goes for each mode: nothing is sent, so the summaries of a prompt do not fit. */
private val InsertMode.replySummary: Int
    get() =
        when (this) {
            InsertMode.REPLACE_ALL -> R.string.custom_button_reply_insert_all_summary
            InsertMode.REPLACE_SELECTION -> R.string.custom_button_reply_insert_selection_summary
            InsertMode.APPEND -> R.string.custom_button_reply_insert_end_summary
            InsertMode.AT_CURSOR -> R.string.custom_button_reply_insert_cursor_summary
        }
