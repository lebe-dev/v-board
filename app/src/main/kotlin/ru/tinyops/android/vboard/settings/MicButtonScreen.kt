package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.MicButton
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing
import ru.tinyops.android.vboard.core.voice.PromptLibrary
import ru.tinyops.android.vboard.voice.label
import ru.tinyops.android.vboard.voice.micHoldChoices

/** "Кнопка микрофона": what a tap does and which modes the menu of a hold offers. */
@Composable
internal fun MicButtonScreen(
    state: VoiceSettingsState,
    onBack: () -> Unit,
) {
    val button = state.read { micButton.button }
    val library = state.read { prompts.library }
    val working = state.read { micHoldChoices().map { it.mode.id }.toSet() }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    fun save(next: MicButton) = state.update { micButton.button = next }
    SettingsPage(title = stringResource(R.string.settings_mic_button), onBack = onBack) {
        item { TapSection(button, library, onChange = { save(button.copy(tap = it)) }) }
        item {
            HoldSection(
                button = button,
                library = library,
                working = working,
                onOpen = { editing = it },
                onAdd = {
                    val mode = state.read { micButton.newHoldMode() }
                    save(button.withHold(mode))
                    editing = mode.id
                },
                onMove = { id, by -> save(button.movedHold(id, by)) },
            )
        }
    }

    val mode = button.hold.firstOrNull { it.id == editing } ?: return
    ModeEditor(
        mode = mode,
        library = library,
        onSave = {
            save(button.withHold(it))
            editing = null
        },
        onDelete = {
            save(button.withoutHold(mode.id))
            editing = null
        },
        onDismiss = { editing = null },
    )
}

@Composable
private fun TapSection(
    button: MicButton,
    library: PromptLibrary,
    onChange: (MicMode) -> Unit,
) {
    val tap = button.tap
    SettingsSection(stringResource(R.string.mic_button_tap)) {
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.mic_mode_processing),
                selected = tap.processing,
                choices = processingChoices(withDefault = true),
                onSelected = { onChange(tap.copy(processing = it)) },
            )
        }
        if (tap.processing == MicProcessing.PROMPTS) {
            item {
                PromptPickerRow(
                    title = stringResource(R.string.mic_mode_prompts_title),
                    library = library,
                    selected = tap.promptIds,
                    onChange = { onChange(tap.copy(promptIds = it)) },
                )
            }
        }
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.settings_mode),
                selected = tap.ending,
                choices = endingChoices(),
                onSelected = { onChange(tap.copy(ending = it)) },
            )
        }
    }
}

@Composable
private fun HoldSection(
    button: MicButton,
    library: PromptLibrary,
    working: Set<String>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onMove: (String, Int) -> Unit,
) {
    val context = LocalContext.current
    SettingsSection(stringResource(R.string.mic_button_hold)) {
        item { SettingsNote(stringResource(R.string.mic_button_hold_note), icon = R.drawable.ic_info) }
        button.hold.forEachIndexed { index, mode ->
            item {
                ModeRow(
                    label = mode.label(context, library),
                    available = mode.id in working,
                    first = index == 0,
                    last = index == button.hold.lastIndex,
                    onClick = { onOpen(mode.id) },
                    onMove = { by -> onMove(mode.id, by) },
                )
            }
        }
        if (button.hold.isEmpty()) item { SettingsNote(stringResource(R.string.mic_button_hold_empty)) }
        if (!button.holdFull) {
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.mic_button_add),
                    icon = R.drawable.ic_add,
                    onClick = onAdd,
                )
            }
        }
    }
}

/** A mode of the menu, opening its editor, with the arrows that move it up and down the list. */
@Composable
private fun ModeRow(
    label: String,
    available: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent =
            if (available) {
                null
            } else {
                {
                    Text(
                        stringResource(R.string.mic_button_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
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

/** A mode of the menu: its processing, its prompts and its way to end, applied on saving. */
@Composable
private fun ModeEditor(
    mode: MicMode,
    library: PromptLibrary,
    onSave: (MicMode) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var processing by rememberSaveable(mode.id) { mutableStateOf(mode.processing) }
    var prompts by rememberSaveable(mode.id) { mutableStateOf(mode.promptIds) }
    var ending by rememberSaveable(mode.id) { mutableStateOf(mode.ending) }
    val needsPrompts = processing == MicProcessing.PROMPTS
    val promptsChosen = prompts.any { library.find(it) != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mic_button_edit)) },
        text = {
            SettingsDialogContent {
                DialogHeading(stringResource(R.string.mic_mode_processing))
                processingChoices(withDefault = false).forEach {
                    SettingsRadioRow(it.label, processing == it.value, { processing = it.value }, it.description)
                }
                if (needsPrompts) {
                    DialogHeading(stringResource(R.string.mic_mode_prompts_title))
                    library.prompts.forEach { prompt ->
                        CheckRow(prompt.title, prompt.id in prompts, enabled = true) { on ->
                            prompts = if (on) prompts + prompt.id else prompts - prompt.id
                        }
                    }
                    if (library.prompts.isEmpty()) Text(stringResource(R.string.prompt_picker_empty))
                }
                DialogHeading(stringResource(R.string.settings_mode))
                endingChoices().forEach {
                    SettingsRadioRow(it.label, ending == it.value, { ending = it.value }, it.description)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !needsPrompts || promptsChosen,
                onClick = {
                    val kept = if (needsPrompts) library.prompts.map { it.id }.filter { it in prompts } else emptyList()
                    onSave(mode.copy(processing = processing, promptIds = kept, ending = ending))
                },
            ) { Text(stringResource(R.string.feature_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.mic_button_delete)) }
        },
    )
}

@Composable
private fun DialogHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** The ways to process the text; "as in the settings" only for a tap, the menu offers explicit choices. */
@Composable
private fun processingChoices(withDefault: Boolean): List<Choice<MicProcessing>> =
    listOfNotNull(
        if (withDefault) {
            Choice(
                MicProcessing.DEFAULT,
                stringResource(R.string.mic_mode_as_settings),
                stringResource(R.string.mic_mode_default_summary),
            )
        } else {
            null
        },
        Choice(
            MicProcessing.NONE,
            stringResource(R.string.mic_mode_none),
            stringResource(R.string.mic_mode_none_summary),
        ),
        Choice(
            MicProcessing.ENHANCE,
            stringResource(R.string.mic_mode_enhance),
            stringResource(R.string.mic_mode_enhance_summary),
        ),
        Choice(
            MicProcessing.PROMPTS,
            stringResource(R.string.mic_mode_prompts),
            stringResource(R.string.mic_mode_prompts_summary),
        ),
    )

@Composable
private fun endingChoices(): List<Choice<MicEnding>> =
    listOf(
        Choice(MicEnding.DEFAULT, stringResource(R.string.mic_mode_as_settings)),
        Choice(MicEnding.AFTER_PAUSE, stringResource(R.string.settings_mode_after_pause)),
        Choice(MicEnding.REPEAT_TAP, stringResource(R.string.settings_mode_repeat_tap)),
    )
