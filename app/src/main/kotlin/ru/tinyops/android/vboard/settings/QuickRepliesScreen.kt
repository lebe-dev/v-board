package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.QuickReply

/**
 * "Быстрые ответы": the phrases a custom button can offer. They are edited here and chosen on the page of a
 * button with the quick replies action, which the link at the bottom leads to.
 */
@Composable
internal fun QuickRepliesScreen(
    state: VoiceSettingsState,
    onOpenButtons: () -> Unit,
    onBack: () -> Unit,
) {
    val replies = state.read { quickReplies.replies }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }

    fun open(reply: QuickReply) {
        text = reply.text
        editing = reply.id
    }
    SettingsPage(title = stringResource(R.string.quick_replies_title), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.quick_replies_note), icon = R.drawable.ic_info) }
        item {
            SettingsSection(stringResource(R.string.quick_replies_section)) {
                replies.forEachIndexed { index, reply ->
                    item {
                        ReplyRow(
                            reply = reply,
                            first = index == 0,
                            last = index == replies.lastIndex,
                            onClick = { open(reply) },
                            onMove = { by -> state.update { quickReplies.move(reply.id, by) } },
                        )
                    }
                }
                if (replies.isEmpty()) item { SettingsNote(stringResource(R.string.quick_replies_empty)) }
                if (replies.size < QuickReply.MAX_COUNT) {
                    item {
                        SettingsLinkRow(
                            title = stringResource(R.string.quick_reply_add),
                            icon = R.drawable.ic_add,
                            onClick = { open(QuickReply(state.read { quickReplies.newId() }, "")) },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.quick_replies_buttons_section)) {
                item {
                    SettingsLinkRow(
                        title = stringResource(R.string.custom_buttons_title),
                        summary = stringResource(R.string.quick_replies_buttons_summary),
                        icon = R.drawable.ic_smart_button,
                        prominent = true,
                        onClick = onOpenButtons,
                    )
                }
            }
        }
    }

    val id = editing ?: return
    ReplyEditor(
        text = text,
        exists = replies.any { it.id == id },
        onText = { text = it.take(QuickReply.MAX_LENGTH) },
        onSave = {
            state.update { quickReplies.save(QuickReply(id, text)) }
            editing = null
        },
        onDelete = {
            state.update { quickReplies.delete(id) }
            editing = null
        },
        onDismiss = { editing = null },
    )
}

/** A phrase, opening its editor, with the arrows that move it up and down the list. */
@Composable
private fun ReplyRow(
    reply: QuickReply,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
) {
    ListItem(
        headlineContent = { Text(reply.text, maxLines = PREVIEW_LINES, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        trailingContent = {
            Row {
                if (!first) {
                    TooltipIconButton(R.drawable.ic_arrow_upward, stringResource(R.string.quick_reply_move_up)) {
                        onMove(-1)
                    }
                }
                if (!last) {
                    TooltipIconButton(R.drawable.ic_arrow_downward, stringResource(R.string.quick_reply_move_down)) {
                        onMove(1)
                    }
                }
            }
        },
        colors = rowColors(enabled = true),
    )
}

@Composable
private fun ReplyEditor(
    text: String,
    exists: Boolean,
    onText: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (exists) R.string.quick_reply_edit else R.string.quick_reply_new)) },
        text = {
            SettingsDialogContent {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = EDITOR_MIN_LINES,
                    maxLines = EDITOR_MAX_LINES,
                    label = { Text(stringResource(R.string.quick_reply_text)) },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = onSave) { Text(stringResource(R.string.feature_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            if (exists) TextButton(onClick = onDelete) { Text(stringResource(R.string.quick_reply_delete)) }
        },
    )
}

/** What the row of the settings root says about the phrases. */
@Composable
internal fun quickRepliesSummary(state: VoiceSettingsState): String {
    val count = state.read { quickReplies.replies.size }
    return if (count == 0) {
        stringResource(R.string.quick_replies_empty)
    } else {
        pluralStringResource(R.plurals.quick_replies_count, count, count)
    }
}

private const val PREVIEW_LINES = 2
private const val EDITOR_MIN_LINES = 2
private const val EDITOR_MAX_LINES = 8
