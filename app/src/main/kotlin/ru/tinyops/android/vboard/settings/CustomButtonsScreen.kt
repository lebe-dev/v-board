package ru.tinyops.android.vboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.clipboard.ClipboardPrefs
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.InsertMode
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.core.voice.find
import ru.tinyops.android.vboard.core.voice.membersOf
import ru.tinyops.android.vboard.core.voice.withArrangedPart
import ru.tinyops.android.vboard.ui.ButtonIcons
import ru.tinyops.android.vboard.ui.KeyboardThemes
import kotlin.math.roundToInt

/** "Настраиваемые кнопки": the buttons of each row, on a model of it and in a list. */
@Composable
internal fun CustomButtonsScreen(
    state: VoiceSettingsState,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val keyboardPrefs = KeyboardPrefs(LocalContext.current)
    val sections = ButtonSections(state.read { customButtons.buttons }, keyboardPrefs.fullscreenEnabled)
    var secondRow by remember { mutableStateOf(keyboardPrefs.stripSecondRow) }
    var widthPercent by remember { mutableIntStateOf(keyboardPrefs.stripButtonWidth) }
    val strip =
        StripLook(
            secondRow = secondRow,
            widthPercent = widthPercent,
            onSecondRow = {
                secondRow = it
                keyboardPrefs.stripSecondRow = it
            },
            onWidth = {
                widthPercent = it
                keyboardPrefs.stripButtonWidth = it
            },
        )
    val arrange: (List<CustomButton>) -> Unit = { arranged ->
        state.update { customButtons.arrange(customButtons.buttons.withArrangedPart(arranged)) }
    }
    SettingsPage(title = stringResource(R.string.custom_buttons_title), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.custom_buttons_note), icon = R.drawable.ic_info) }
        if (sections.keyboardSectionShown) {
            item {
                KeyboardButtonsSection(sections, strip, state, arrange, onOpen)
            }
        }
        if (sections.panelSectionShown) {
            item {
                FullscreenButtonsSection(sections, state, arrange, onOpen)
            }
        }
    }
}

/** How the strip lays its buttons out, and the changes to it. */
private class StripLook(
    val secondRow: Boolean,
    val widthPercent: Int,
    val onSecondRow: (Boolean) -> Unit,
    val onWidth: (Int) -> Unit,
)

@Composable
private fun KeyboardButtonsSection(
    sections: ButtonSections,
    strip: StripLook,
    state: VoiceSettingsState,
    arrange: (List<CustomButton>) -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val proofread = state.read { featureReady(TextFeature.PROOFREAD) }
    val clipboard = ClipboardPrefs(context).run { iconPlacement.takeIf { enabled } }
    val fullscreen = KeyboardPrefs(context).run { fullscreenPlacement.takeIf { fullscreenEnabled } }
    SettingsSection(stringResource(R.string.custom_buttons_section_keyboard)) {
        if (sections.keyboardOnModel.isNotEmpty()) {
            item {
                SettingsTileContent {
                    ButtonsPreview(
                        buttons = sections.keyboardOnModel,
                        shape =
                            StripShape(
                                widthScale = strip.widthPercent / PERCENT,
                                secondRow = strip.secondRow,
                                proofread = proofread,
                                clipboard = clipboard,
                                fullscreen = fullscreen,
                            ),
                        names = { it.title(state) },
                        onArrange = arrange,
                        onOpen = onOpen,
                    )
                }
            }
        }
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.custom_buttons_second_row),
                summary = stringResource(R.string.custom_buttons_second_row_summary),
                checked = strip.secondRow,
                onCheckedChange = strip.onSecondRow,
            )
        }
        item { ButtonWidthRow(strip.widthPercent, strip.onWidth) }
        buttonRows(sections.keyboard, state, onOpen)
        if (sections.keyboardCanAdd) {
            addRow(ButtonVisibility.WITH_TEXT, state, onOpen)
            addGroupRow(ButtonVisibility.WITH_TEXT, state, onOpen)
        }
    }
}

/** How wide the buttons of the strip are: narrower ones stand closer, so that more of them fit. */
@Composable
private fun ButtonWidthRow(
    percent: Int,
    onChange: (Int) -> Unit,
) {
    val steps = (KeyboardPrefs.MAX_BUTTON_WIDTH - KeyboardPrefs.MIN_BUTTON_WIDTH) / KeyboardPrefs.BUTTON_WIDTH_STEP - 1
    val label = stringResource(R.string.custom_buttons_width)
    SettingsTileContent {
        Text(
            stringResource(R.string.custom_buttons_width_value, label, percent),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.custom_buttons_width_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = KeyboardPrefs.MIN_BUTTON_WIDTH.toFloat()..KeyboardPrefs.MAX_BUTTON_WIDTH.toFloat(),
            steps = steps,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private const val PERCENT = 100f

@Composable
private fun FullscreenButtonsSection(
    sections: ButtonSections,
    state: VoiceSettingsState,
    arrange: (List<CustomButton>) -> Unit,
    onOpen: (String) -> Unit,
) {
    SettingsSection(stringResource(R.string.custom_buttons_section_fullscreen)) {
        if (sections.panelOffNote) {
            item { SettingsNote(stringResource(R.string.custom_buttons_fullscreen_off), icon = R.drawable.ic_warning) }
        }
        if (sections.panelPreviewShown) {
            item {
                SettingsTileContent {
                    FullscreenPanelPreview(
                        buttons = sections.panelOnModel,
                        names = { it.title(state) },
                        onArrange = arrange,
                        onOpen = onOpen,
                    )
                }
            }
        }
        buttonRows(sections.panel, state, onOpen)
        if (sections.panelCanAdd) {
            addRow(ButtonVisibility.FULLSCREEN, state, onOpen)
            addGroupRow(ButtonVisibility.FULLSCREEN, state, onOpen)
        }
    }
}

private fun SettingsSectionScope.buttonRows(
    buttons: List<CustomButton>,
    state: VoiceSettingsState,
    onOpen: (String) -> Unit,
) {
    buttons.forEach { button ->
        item { ButtonRow(button, state, onClick = { onOpen(button.id) }) }
    }
}

/** Adds a button to the row of this section: [visibility] is what puts it there. */
private fun SettingsSectionScope.addRow(
    visibility: ButtonVisibility,
    state: VoiceSettingsState,
    onOpen: (String) -> Unit,
) {
    item {
        SettingsLinkRow(
            title = stringResource(R.string.custom_button_add),
            icon = R.drawable.ic_add,
            onClick = { onOpen(state.read { customButtons.add(visibility) }.id) },
        )
    }
}

/** Adds a group of buttons to the row of this section. */
private fun SettingsSectionScope.addGroupRow(
    visibility: ButtonVisibility,
    state: VoiceSettingsState,
    onOpen: (String) -> Unit,
) {
    item {
        SettingsLinkRow(
            title = stringResource(R.string.custom_button_group_add),
            icon = R.drawable.ic_folder,
            onClick = { onOpen(state.read { customButtons.addGroup(visibility) }.id) },
        )
    }
}

@Composable
private fun ButtonRow(
    button: CustomButton,
    state: VoiceSettingsState,
    onClick: () -> Unit,
) {
    val members = if (button.group) state.read { customButtons.buttons.membersOf(button.id).size } else 0
    val tap = button.tap?.let { stringResource(R.string.custom_button_tap_is, commandName(it, state)) }
    val hold = button.hold?.let { stringResource(R.string.custom_button_hold_is, commandName(it, state)) }
    val summary =
        when {
            !button.enabled -> stringResource(R.string.custom_button_off)
            button.group && members == 0 -> stringResource(R.string.custom_button_group_empty)
            button.group -> stringResource(R.string.custom_button_group_count, members)
            button.main == null -> stringResource(R.string.custom_button_nothing)
            state.read { workingButton(button) }?.main == null -> stringResource(R.string.custom_button_not_ready)
            else -> listOfNotNull(tap, hold).joinToString(" · ")
        }
    val badge = LocalBadgeColors.current
    CompositionLocalProvider(LocalBadgeColors provides (button.color?.let { colorBadge(it) } ?: badge)) {
        SettingsLinkRow(
            title = button.title(state),
            summary = summary,
            icon = ButtonIcons.of(button),
            prominent = true,
            onClick = onClick,
        )
    }
}

/** The round badge of a button with a color: filled with it, so that the buttons are told apart at a glance. */
@Composable
private fun colorBadge(color: ButtonColor): BadgeColors {
    val fill = Color(color.tint(MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE, rememberThemeAccent()))
    return BadgeColors(container = fill, content = if (fill.luminance() < HALF_LUMINANCE) Color.White else Color.Black)
}

/** What the button is called: the name it was given, or else what it does. */
@Composable
internal fun CustomButton.title(state: VoiceSettingsState): String =
    name.trim().ifEmpty {
        when {
            group -> stringResource(R.string.custom_button_group_title)
            else -> main?.let { commandName(it, state) } ?: stringResource(R.string.custom_button_title)
        }
    }

/** The titles of the prompts a command sends, or the name of its action. */
@Composable
private fun commandName(
    command: ButtonCommand,
    state: VoiceSettingsState,
): String {
    if (command.action == ButtonAction.PROMPT &&
        command.pickPrompt
    ) {
        return stringResource(R.string.custom_button_pick_name)
    }
    val titles = state.read { prompts.library.titles(command.promptIds) }
    return if (command.action == ButtonAction.PROMPT && titles.isNotEmpty()) {
        titles.joinToString()
    } else {
        stringResource(command.action.label)
    }
}

/** The page of one button; changes are stored at once, as everywhere in the settings. */
@Composable
internal fun CustomButtonScreen(
    id: String,
    state: VoiceSettingsState,
    onOpenServers: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    // Without the button the page is on its way out: it was just deleted.
    val all = state.read { customButtons.buttons }
    val button = all.firstOrNull { it.id == id } ?: return
    val save: (CustomButton) -> Unit = { changed -> state.update { customButtons.save(changed) } }
    val member = button.groupId != null && all.any { it.group && it.id == button.groupId }
    val groups = all.filter { it.group && it.fullscreenOnly == button.fullscreenOnly && it.id != button.id }
    // A member is reached from the page of its group, which is where back goes.
    val groupId = button.groupId.takeIf { member }
    val leave: () -> Unit = if (groupId != null) ({ onOpen(groupId) }) else onBack
    if (groupId != null) BackHandler(onBack = leave)
    SettingsPage(title = button.title(state), onBack = leave) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title =
                            stringResource(
                                if (button.group) {
                                    R.string.custom_button_group_enabled
                                } else {
                                    R.string.custom_button_enabled
                                },
                            ),
                        summary =
                            stringResource(
                                if (button.group) {
                                    R.string.custom_button_group_enabled_summary
                                } else {
                                    R.string.custom_button_enabled_summary
                                },
                            ),
                        checked = button.enabled,
                        onCheckedChange = { save(button.copy(enabled = it)) },
                    )
                }
            }
        }
        item { NameSection(button, state) { save(button.copy(name = it)) } }
        item { LookSection(button, positioned = !member, save) }
        if (button.group) {
            item { MembersSection(button, all.membersOf(button.id), state, onOpen) }
        } else {
            if (groups.isNotEmpty()) item { GroupSection(button, groups, state, save) }
            item {
                CommandSection(R.string.custom_button_tap, button.tap, state, onOpenServers) {
                    save(button.copy(tap = it))
                }
            }
            item {
                CommandSection(R.string.custom_button_hold, button.hold, state, onOpenServers, tapOnly = false) {
                    save(button.copy(hold = it))
                }
            }
        }
        item {
            ManageSection(
                title = if (button.group) R.string.custom_button_group_delete else R.string.custom_button_delete,
                confirm = button.group,
                onDelete = {
                    leave()
                    state.update { customButtons.delete(id) }
                },
            )
        }
    }
}

/** The members of a group, each opening its own page, and a way to add one. */
@Composable
private fun MembersSection(
    group: CustomButton,
    members: List<CustomButton>,
    state: VoiceSettingsState,
    onOpen: (String) -> Unit,
) {
    SettingsSection(stringResource(R.string.custom_button_group_members)) {
        buttonRows(members, state, onOpen)
        if (members.size >= CustomButton.MAX_PER_GROUP) return@SettingsSection
        item {
            SettingsLinkRow(
                title = stringResource(R.string.custom_button_group_add_button),
                icon = R.drawable.ic_add,
                onClick = {
                    onOpen(state.read { customButtons.add(group.visibility, group.id) }.id)
                },
            )
        }
    }
}

/** Which group of the same row the button belongs to, if any. */
@Composable
private fun GroupSection(
    button: CustomButton,
    groups: List<CustomButton>,
    state: VoiceSettingsState,
    onChange: (CustomButton) -> Unit,
) {
    val none = Choice<String?>(null, stringResource(R.string.custom_button_group_none))
    val titles = groups.map { Choice<String?>(it.id, it.title(state)) }
    SettingsSection(null) {
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.custom_button_group_choice),
                selected = button.groupId,
                choices = listOf(none) + titles,
                onSelected = { onChange(button.copy(groupId = it)) },
            )
        }
    }
}

/** The name is kept here while it is typed: what is stored comes back too late for the cursor. */
@Composable
private fun NameSection(
    button: CustomButton,
    state: VoiceSettingsState,
    onChange: (String) -> Unit,
) {
    var name by rememberSaveable(button.id) { mutableStateOf(button.name) }
    val fallback = button.copy(name = "").title(state)
    SettingsSection(null) {
        item {
            SettingsTileContent {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(CustomButton.MAX_NAME_LENGTH)
                        onChange(name)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.custom_button_name)) },
                    placeholder = { Text(fallback) },
                )
            }
        }
    }
}

@Composable
private fun LookSection(
    button: CustomButton,
    positioned: Boolean,
    onChange: (CustomButton) -> Unit,
) {
    SettingsSection(stringResource(R.string.custom_button_look)) {
        item { IconRow(button, onChange) }
        item { IconColorRow(button.color) { onChange(button.copy(color = it)) } }
        if (!positioned) return@SettingsSection
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.custom_button_placement),
                selected = button.placement,
                choices = ButtonPlacement.entries.map { Choice(it, stringResource(it.label)) },
                onSelected = { onChange(button.copy(placement = it)) },
            )
        }
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.custom_button_visibility),
                selected = button.visibility,
                choices =
                    listOf(
                        Choice(
                            ButtonVisibility.WITH_TEXT,
                            stringResource(R.string.custom_button_visibility_text),
                            stringResource(R.string.custom_button_visibility_text_summary),
                        ),
                        Choice(
                            ButtonVisibility.ALWAYS,
                            stringResource(R.string.custom_button_visibility_always),
                            stringResource(R.string.custom_button_visibility_always_summary),
                        ),
                        Choice(
                            ButtonVisibility.FULLSCREEN,
                            stringResource(R.string.custom_button_visibility_fullscreen),
                            stringResource(R.string.custom_button_visibility_fullscreen_summary),
                        ),
                    ),
                onSelected = { onChange(button.copy(visibility = it)) },
            )
        }
    }
}

/** The current icon; the choice among all of them opens as a dialog, as it takes a lot of room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconRow(
    button: CustomButton,
    onChange: (CustomButton) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = ButtonIcons.of(button)
    SettingsLinkRow(
        title = stringResource(R.string.custom_button_icon),
        icon = current,
        prominent = true,
        onClick = { open = true },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.custom_button_icon_title)) },
        text = {
            SettingsDialogContent {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ButtonIcons.all.forEachIndexed { index, (name, icon) ->
                        IconOption(
                            icon = icon,
                            label = stringResource(R.string.custom_button_icon_option, index + 1),
                            selected = icon == current,
                            onClick = {
                                open = false
                                onChange(button.copy(icon = name))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun IconOption(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.primaryContainer else Color.Transparent
    val tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .size(OPTION_SIZE)
                .background(background, CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint)
    }
}

/** The accent of the keyboard theme that is selected, which is what a button of the theme color shows. */
@Composable
internal fun rememberThemeAccent(): Int {
    val context = LocalContext.current
    return remember(context) { KeyboardThemes.selected(context).palette(context).actionKey }
}

/** The colors as circles in the tone the keyboard of this theme will show; first comes the absence of one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconColorRow(
    selected: ButtonColor?,
    onChange: (ButtonColor?) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE
    val accent = rememberThemeAccent()
    Column(Modifier.fillMaxWidth().padding(SettingsSpacing.Row)) {
        Text(stringResource(R.string.custom_button_color), style = MaterialTheme.typography.bodyLarge)
        FlowRow(Modifier.fillMaxWidth()) {
            ColorOption(null, dark, accent, selected == null) { onChange(null) }
            ButtonColor.entries.forEach { color ->
                ColorOption(color, dark, accent, selected == color) { onChange(color) }
            }
        }
    }
}

@Composable
private fun ColorOption(
    color: ButtonColor?,
    dark: Boolean,
    accent: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(color.label)
    val ring = if (selected) colors.onSurface else Color.Transparent
    Box(
        modifier =
            Modifier
                .size(OPTION_SIZE)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(COLOR_RING_INSET)
                .border(COLOR_RING_WIDTH, ring, CircleShape)
                .padding(COLOR_DOT_INSET),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) {
            Box(Modifier.size(COLOR_DOT_SIZE).border(1.dp, colors.outline, CircleShape))
            Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = colors.outline)
        } else {
            Box(Modifier.size(COLOR_DOT_SIZE).background(Color(color.tint(dark, accent)), CircleShape))
        }
    }
}

/**
 * What a gesture does: nothing or an action, for a prompt where it goes and how the answer returns, and for
 * quick replies which of them are offered and where they go. Quick replies are offered only for a tap.
 */
@Composable
private fun CommandSection(
    title: Int,
    command: ButtonCommand?,
    state: VoiceSettingsState,
    onOpenServers: () -> Unit,
    tapOnly: Boolean = true,
    onChange: (ButtonCommand?) -> Unit,
) {
    val none =
        Choice<ButtonAction?>(
            null,
            stringResource(R.string.custom_button_no_action),
            stringResource(R.string.custom_button_no_action_summary),
        )
    val actions =
        ButtonAction.entries
            .filter { tapOnly || it != ButtonAction.QUICK_REPLIES || command?.action == it }
            .map { Choice<ButtonAction?>(it, stringResource(it.label), stringResource(it.summary)) }
    SettingsSection(stringResource(title)) {
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.custom_button_action),
                selected = command?.action,
                choices = listOf(none) + actions,
                onSelected = { action ->
                    val next = action?.let { command?.copy(action = it) ?: ButtonCommand(it) }
                    // A new list of quick replies starts with all of them, which is what is usually wanted.
                    val all = state.read { quickReplies.replies.map { it.id } }
                    val filled = next?.takeIf { it.action == ButtonAction.QUICK_REPLIES && it.replyIds.isEmpty() }
                    onChange(filled?.copy(replyIds = all) ?: next)
                },
            )
        }
        when (command?.action) {
            ButtonAction.PROMPT -> promptRows(command, state, onOpenServers, onChange)
            ButtonAction.QUICK_REPLIES -> replyRows(command, state, onChange)
            else -> Unit
        }
    }
}

private fun SettingsSectionScope.promptRows(
    command: ButtonCommand,
    state: VoiceSettingsState,
    onOpenServers: () -> Unit,
    onChange: (ButtonCommand) -> Unit,
) {
    item {
        ServerPickerRow(
            state = state,
            kind = ServerKind.TEXT,
            selected = command.serverId,
            onSelected = { onChange(command.copy(serverId = it)) },
            onOpenServers = onOpenServers,
        )
    }
    if (state.read { servers.find(command.serverId ?: defaultServerId(ServerKind.TEXT))?.insecure } == true) {
        item { SettingsNote(stringResource(R.string.server_http_warning), icon = R.drawable.ic_warning) }
    }
    item {
        SettingsSwitchRow(
            title = stringResource(R.string.custom_button_pick_prompt),
            summary = stringResource(R.string.custom_button_pick_prompt_summary),
            checked = command.pickPrompt,
            onCheckedChange = { onChange(command.copy(pickPrompt = it)) },
        )
    }
    // The prompts chosen before stay stored, so turning the choice off brings them back.
    if (!command.pickPrompt) {
        item {
            PromptPickerRow(
                title = stringResource(R.string.server_prompt),
                library = state.read { prompts.library },
                selected = command.promptIds,
                onChange = { onChange(command.copy(promptIds = it)) },
            )
        }
    }
    item {
        SettingsChoiceRow(
            title = stringResource(R.string.custom_button_insert),
            selected = command.insert,
            choices = InsertMode.entries.map { Choice(it, stringResource(it.label), stringResource(it.summary)) },
            onSelected = { onChange(command.copy(insert = it)) },
        )
    }
    if (!state.read { commandReady(command) }) {
        val note = if (command.pickPrompt) R.string.custom_button_pick_not_ready else R.string.custom_button_not_ready
        item { SettingsNote(stringResource(note), icon = R.drawable.ic_warning) }
    }
    item { SettingsNote(stringResource(R.string.server_notice), icon = R.drawable.ic_info) }
}

@Composable
private fun ManageSection(
    title: Int,
    confirm: Boolean,
    onDelete: () -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.custom_button_manage)) {
        item {
            SettingsLinkRow(
                title = stringResource(title),
                icon = R.drawable.ic_delete,
                onClick = { if (confirm) asking = true else onDelete() },
            )
        }
    }
    if (!asking) return
    AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.custom_button_group_delete_title)) },
        text = { Text(stringResource(R.string.custom_button_group_delete_text)) },
        confirmButton = {
            TextButton(
                onClick = {
                    asking = false
                    onDelete()
                },
            ) { Text(stringResource(R.string.custom_button_group_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { asking = false }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

private val ButtonAction.label: Int
    get() =
        when (this) {
            ButtonAction.CLEAR -> R.string.custom_button_action_clear
            ButtonAction.COPY -> R.string.custom_button_action_copy
            ButtonAction.PASTE -> R.string.custom_button_action_paste
            ButtonAction.PROMPT -> R.string.custom_button_action_prompt
            ButtonAction.QUICK_REPLIES -> R.string.custom_button_action_quick_replies
        }

private val ButtonAction.summary: Int
    get() =
        when (this) {
            ButtonAction.CLEAR -> R.string.custom_button_action_clear_summary
            ButtonAction.COPY -> R.string.custom_button_action_copy_summary
            ButtonAction.PASTE -> R.string.custom_button_action_paste_summary
            ButtonAction.PROMPT -> R.string.custom_button_action_prompt_summary
            ButtonAction.QUICK_REPLIES -> R.string.custom_button_action_quick_replies_summary
        }

internal val InsertMode.label: Int
    get() =
        when (this) {
            InsertMode.REPLACE_ALL -> R.string.custom_button_insert_all
            InsertMode.REPLACE_SELECTION -> R.string.custom_button_insert_selection
            InsertMode.APPEND -> R.string.custom_button_insert_end
            InsertMode.AT_CURSOR -> R.string.custom_button_insert_cursor
        }

private val InsertMode.summary: Int
    get() =
        when (this) {
            InsertMode.REPLACE_ALL -> R.string.custom_button_insert_all_summary
            InsertMode.REPLACE_SELECTION -> R.string.custom_button_insert_selection_summary
            InsertMode.APPEND -> R.string.custom_button_insert_end_summary
            InsertMode.AT_CURSOR -> R.string.custom_button_insert_cursor_summary
        }

private val ButtonColor?.label: Int
    get() =
        when (this) {
            null -> R.string.custom_button_color_none
            ButtonColor.RED -> R.string.custom_button_color_red
            ButtonColor.ORANGE -> R.string.custom_button_color_orange
            ButtonColor.YELLOW -> R.string.custom_button_color_yellow
            ButtonColor.GREEN -> R.string.custom_button_color_green
            ButtonColor.TEAL -> R.string.custom_button_color_teal
            ButtonColor.BLUE -> R.string.custom_button_color_blue
            ButtonColor.PURPLE -> R.string.custom_button_color_purple
            ButtonColor.PINK -> R.string.custom_button_color_pink
            ButtonColor.THEME -> R.string.custom_button_color_theme
        }

private const val HALF_LUMINANCE = 0.5f
private val OPTION_SIZE = 48.dp
private val COLOR_RING_INSET = 4.dp
private val COLOR_RING_WIDTH = 2.dp
private val COLOR_DOT_INSET = 4.dp
private val COLOR_DOT_SIZE = 28.dp
