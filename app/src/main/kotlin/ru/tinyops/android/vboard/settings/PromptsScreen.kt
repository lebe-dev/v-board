package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.PromptLibrary
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.SavedPrompt

/**
 * "Промпты": the collection of prompts. A prompt is a short name and a text; where it is used is chosen
 * on the pages of voice input and text improvement, so the same prompt can serve several of them.
 */
@Composable
internal fun PromptsScreen(
    state: VoiceSettingsState,
    onBack: () -> Unit,
) {
    val library = state.read { prompts.library }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var colorId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    fun open(prompt: SavedPrompt) {
        title = prompt.title
        text = prompt.text
        colorId = prompt.color?.id
        editing = prompt.id
    }
    SettingsPage(title = stringResource(R.string.settings_prompts), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.prompts_note), icon = R.drawable.ic_info) }
        item {
            SettingsSection(stringResource(R.string.prompts_section)) {
                library.prompts.forEach { prompt ->
                    item {
                        PromptRow(
                            title =
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = prompt.color.tone())) { append(prompt.title) }
                                },
                            summary = AnnotatedString(prompt.text.preview()),
                            onClick = { open(prompt) },
                        )
                    }
                }
                if (library.prompts.isEmpty()) item { SettingsNote(stringResource(R.string.prompts_empty)) }
                item {
                    SettingsLinkRow(
                        title = stringResource(R.string.prompt_add),
                        icon = R.drawable.ic_add,
                        onClick = { open(SavedPrompt(state.read { prompts.newId() }, "", "")) },
                    )
                }
            }
        }
        item { PromptResultsSection(state) }
    }

    val id = editing
    if (id != null) {
        val exists = library.find(id) != null
        val draft = SavedPrompt(id, title, text, ButtonColor.fromId(colorId))
        PromptEditor(
            draft = draft,
            exists = exists,
            onDraft = {
                title = it.title.take(SavedPrompt.MAX_TITLE_LENGTH)
                text = it.text.take(SavedPrompt.MAX_TEXT_LENGTH)
                colorId = it.color?.id
            },
            onSave = {
                state.update { prompts.save(draft) }
                editing = null
            },
            onDelete = {
                deleting = id
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    val doomed = deleting?.let(library::find)
    if (doomed != null) {
        DeletePromptDialog(
            prompt = doomed,
            places = state.read { prompts.usage(doomed.id) },
            usedByButtons =
                state.read {
                    customButtons.buttons.any {
                        doomed.id in
                            it.tap?.promptIds.orEmpty() + it.hold?.promptIds.orEmpty()
                    }
                },
            onConfirm = {
                state.update { prompts.delete(doomed.id) }
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/** The buffer of prompt results next to the clipboard history; off until the user turns it on. */
@Composable
private fun PromptResultsSection(state: VoiceSettingsState) {
    val keep = state.read { promptResultsEnabled }
    SettingsSection(stringResource(R.string.prompt_results_section)) {
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.prompt_results_keep),
                summary = stringResource(R.string.prompt_results_keep_summary),
                checked = keep,
                onCheckedChange = { state.update { promptResultsEnabled = it } },
            )
        }
    }
}

@Composable
private fun PromptEditor(
    draft: SavedPrompt,
    exists: Boolean,
    onDraft: (SavedPrompt) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (exists) R.string.prompt_edit else R.string.prompt_new)) },
        text = {
            SettingsDialogContent {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { onDraft(draft.copy(title = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.prompt_name)) },
                )
                IconColorRow(draft.color) { onDraft(draft.copy(color = it)) }
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = { onDraft(draft.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = EDITOR_MIN_LINES,
                    maxLines = EDITOR_MAX_LINES,
                    label = { Text(stringResource(R.string.prompt_text)) },
                    supportingText = { Text(stringResource(R.string.prompt_text_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = draft.valid, onClick = onSave) { Text(stringResource(R.string.feature_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            if (exists) TextButton(onClick = onDelete) { Text(stringResource(R.string.prompt_delete)) }
        },
    )
}

/** Says where the prompt is in use, because deleting it changes those places. */
@Composable
private fun DeletePromptDialog(
    prompt: SavedPrompt,
    places: List<PromptPlace>,
    usedByButtons: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val buttons = if (usedByButtons) listOf(stringResource(R.string.custom_buttons_title)) else emptyList()
    val names = (places.map { stringResource(it.label) } + buttons).joinToString()
    val message =
        if (names.isEmpty()) {
            stringResource(R.string.prompt_delete_unused, prompt.title)
        } else {
            stringResource(R.string.prompt_delete_used, prompt.title, names)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_delete_title)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.prompt_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/**
 * A row that shows which prompts a place uses and opens the choice among the collection. At least one
 * prompt must be ticked, unless the choice is [optional] and may stay empty.
 */
@Composable
internal fun PromptPickerRow(
    title: String,
    library: PromptLibrary,
    selected: List<String>?,
    onChange: (List<String>) -> Unit,
    enabled: Boolean = true,
    optional: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val chosen = library.prompts.filter { it.id in selected.orEmpty() }
    val dark = isDarkSurface()
    val accent = rememberThemeAccent()
    val summary =
        if (chosen.isEmpty()) {
            AnnotatedString(stringResource(R.string.prompt_picker_none))
        } else {
            buildAnnotatedString {
                chosen.forEachIndexed { index, prompt ->
                    if (index > 0) append(", ")
                    val tone = prompt.color?.let { Color(it.tint(dark, accent)) }
                    if (tone ==
                        null
                    ) {
                        append(prompt.title)
                    } else {
                        withStyle(SpanStyle(color = tone)) { append(prompt.title) }
                    }
                }
            }
        }
    PromptRow(AnnotatedString(title), summary, enabled) { open = true }
    if (!open) return
    var ticked by rememberSaveable { mutableStateOf(selected.orEmpty().toList()) }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            SettingsDialogContent {
                library.prompts.forEach { prompt ->
                    CheckRow(prompt.title, prompt.id in ticked, enabled = true, textColor = prompt.color.tone()) { on ->
                        ticked = if (on) ticked + prompt.id else ticked - prompt.id
                    }
                }
                if (library.prompts.isEmpty()) Text(stringResource(R.string.prompt_picker_empty))
                if (ticked.size > 1) {
                    SettingsNote(stringResource(R.string.prompt_picker_merge), icon = R.drawable.ic_info)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = optional || ticked.any { library.find(it) != null },
                onClick = {
                    open = false
                    onChange(library.prompts.map { it.id }.filter { it in ticked })
                },
            ) { Text(stringResource(R.string.feature_save)) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

private val PromptPlace.label: Int
    get() =
        when (this) {
            PromptPlace.RECOGNITION -> R.string.prompt_place_recognition
            PromptPlace.ENHANCEMENT -> R.string.prompt_place_enhancement
            PromptPlace.DICTATION -> R.string.prompt_place_dictation
            PromptPlace.PROOFREAD -> R.string.prompt_place_proofread
            PromptPlace.PROOFREAD_HOLD -> R.string.prompt_place_proofread_hold
        }

/** A link row whose title or summary may carry the colors of prompts. */
@Composable
private fun PromptRow(
    title: AnnotatedString,
    summary: AnnotatedString,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        supportingContent = { Text(summary) },
        colors = rowColors(enabled),
    )
}

/** The color a prompt title is shown in, in the tone that reads on the current background; unspecified without one. */
@Composable
private fun ButtonColor?.tone(): Color {
    val accent = rememberThemeAccent()
    return this?.let { Color(it.tint(isDarkSurface(), accent)) } ?: Color.Unspecified
}

@Composable
private fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE

private fun String.preview(): String {
    val flat = trim().replace(Regex("\\s+"), " ")
    return if (flat.length > PREVIEW_LENGTH) flat.take(PREVIEW_LENGTH).trimEnd() + "…" else flat
}

private const val HALF_LUMINANCE = 0.5f
private const val PREVIEW_LENGTH = 80
private const val EDITOR_MIN_LINES = 4
private const val EDITOR_MAX_LINES = 10
