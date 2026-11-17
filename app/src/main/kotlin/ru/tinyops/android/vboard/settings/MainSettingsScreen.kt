package ru.tinyops.android.vboard.settings

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.gesture.SlideEngine
import ru.tinyops.android.vboard.core.input.SpaceDragMode
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.core.voice.find
import ru.tinyops.android.vboard.ui.ThemeSpec
import ru.tinyops.android.vboard.unison.UnisonPrefs
import ru.tinyops.android.vboard.update.UpdateCheck
import ru.tinyops.android.vboard.update.UpdatePrefs

/**
 * The settings root, in sections: appearance (theme, emoji), typing (slide to type, clipboard, the space bar,
 * the fullscreen mode), voice input, text improvement (dictation, proofreading, prompts, servers) and the app
 * itself (integrations, language, backup of all settings, diagnostics, updates, version); the back arrow closes
 * settings.
 * A newer release found by a check is announced by a banner above the sections.
 */
@Composable
internal fun MainSettingsScreen(
    state: VoiceSettingsState,
    theme: ThemeSpec,
    links: MainSettingsLinks,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var announced by remember { mutableStateOf(UpdateCheck.announced(context)) }
    val prefs = remember { KeyboardPrefs(context) }
    var hidden by remember { mutableStateOf(prefs.hiddenSettings) }
    var editing by remember { mutableStateOf(false) }
    val rows =
        HiddenRows(hidden, editing) { item ->
            hidden = if (item.id in hidden) hidden - item.id else hidden + item.id
            prefs.hiddenSettings = hidden
        }
    SettingsPage(
        title = stringResource(R.string.settings_title),
        onBack = onClose,
        actions = {
            TooltipIconButton(
                icon = if (editing) R.drawable.ic_check else R.drawable.ic_edit,
                label = stringResource(if (editing) R.string.settings_edit_done else R.string.settings_edit_rows),
                onClick = { editing = !editing },
            )
        },
    ) {
        announced?.let { release ->
            item {
                UpdateBanner(
                    release = release,
                    onOpen = links.app.updates,
                    onDismiss = {
                        UpdatePrefs(context).dismissedVersion = release.version
                        announced = null
                    },
                )
            }
        }
        if (editing) {
            item { SettingsNote(stringResource(R.string.settings_edit_hint)) }
        }
        if (rows.shows(HideableSection.APPEARANCE)) {
            item {
                SettingsSection(stringResource(R.string.settings_appearance)) {
                    hideable(rows, HideableItem.THEME) {
                        SettingsLinkRow(
                            title = stringResource(R.string.theme_title),
                            summary = stringResource(theme.name),
                            icon = R.drawable.ic_palette,
                            prominent = true,
                            onClick = links.theme,
                        )
                    }
                    hideable(rows, HideableItem.KEYS) {
                        SettingsLinkRow(
                            title = stringResource(R.string.keys_title),
                            summary = stringResource(R.string.keys_link_summary),
                            icon = R.drawable.ic_keyboard,
                            prominent = true,
                            onClick = links.keys,
                        )
                    }
                    hideable(rows, HideableItem.EMOJI) {
                        SettingsLinkRow(
                            title = stringResource(R.string.settings_emoji),
                            summary = stringResource(R.string.settings_emoji_link_summary),
                            icon = R.drawable.ic_emoji,
                            prominent = true,
                            onClick = links.typing.emoji,
                        )
                    }
                }
            }
        }
        if (rows.shows(HideableSection.TYPING)) item { TypingSection(state, links.typing, rows) }
        if (rows.shows(HideableSection.VOICE)) item { VoiceSection(state, links.voice, rows) }
        if (rows.shows(HideableSection.ENHANCEMENT)) item { EnhancementSection(state, links, rows) }
        item { ApplicationSection(links.app) }
    }
}

/** Voice input: its settings, the microphone button and the animations of the voice strip. */
@Composable
private fun VoiceSection(
    state: VoiceSettingsState,
    links: VoiceLinks,
    rows: HiddenRows,
) {
    SettingsSection(stringResource(R.string.settings_voice)) {
        hideable(rows, HideableItem.VOICE_SETTINGS) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_voice),
                summary = stringResource(R.string.settings_voice_link_summary),
                icon = R.drawable.ic_microphone,
                prominent = true,
                onClick = links.settings,
            )
        }
        hideable(rows, HideableItem.MIC_BUTTON) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_mic_button),
                summary = stringResource(R.string.settings_mic_button_summary),
                icon = R.drawable.ic_mic_spark,
                prominent = true,
                onClick = links.micButton,
            )
        }
        hideable(rows, HideableItem.VOICE_ANIMATION) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_voice_animation),
                summary = stringResource(state.read { voiceAnimation }.label),
                icon = R.drawable.ic_auto_awesome,
                prominent = true,
                onClick = links.animation,
            )
        }
    }
}

/** The functions of text improvement, the replacements, their prompts and the servers they send text to. */
@Composable
private fun EnhancementSection(
    state: VoiceSettingsState,
    links: MainSettingsLinks,
    rows: HiddenRows,
) {
    SettingsSection(stringResource(R.string.settings_enhancement)) {
        hideable(rows, HideableItem.DICTATION) {
            SettingsLinkRow(
                title = stringResource(R.string.feature_dictation),
                summary = featureSummary(state, TextFeature.DICTATION),
                icon = R.drawable.ic_microphone,
                prominent = true,
                onClick = links.dictation,
            )
        }
        hideable(rows, HideableItem.PROOFREAD) {
            SettingsLinkRow(
                title = stringResource(R.string.feature_proofread),
                summary = featureSummary(state, TextFeature.PROOFREAD),
                icon = R.drawable.ic_auto_fix_high,
                prominent = true,
                onClick = links.proofread,
            )
        }
        hideable(rows, HideableItem.CUSTOM_BUTTONS) {
            val count = state.read { customButtons.buttons.size }
            SettingsLinkRow(
                title = stringResource(R.string.custom_buttons_title),
                summary =
                    if (count == 0) {
                        stringResource(R.string.custom_buttons_empty)
                    } else {
                        pluralStringResource(R.plurals.custom_buttons_count, count, count)
                    },
                icon = R.drawable.ic_smart_button,
                prominent = true,
                onClick = links.customButtons,
            )
        }
        hideable(rows, HideableItem.DICTIONARY) { DictionaryRow(state, links.text.dictionary) }
        hideable(rows, HideableItem.PROMPTS) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_prompts),
                summary = stringResource(R.string.settings_prompts_link_summary),
                icon = R.drawable.ic_assignment,
                prominent = true,
                onClick = links.text.prompts,
            )
        }
        hideable(rows, HideableItem.SERVERS) {
            SettingsLinkRow(
                title = stringResource(R.string.servers_title),
                summary = serversSummary(state),
                icon = R.drawable.ic_cloud,
                prominent = true,
                onClick = links.text.servers,
            )
        }
    }
}

/** The replacements applied to recognized text, with the number of rules in use. */
@Composable
private fun DictionaryRow(
    state: VoiceSettingsState,
    onOpen: () -> Unit,
) {
    val rules = state.read { dictionary().size }
    val summary =
        if (state.read { dictionaryEnabled }) {
            pluralStringResource(R.plurals.settings_dictionary_rules, rules, rules)
        } else {
            stringResource(R.string.settings_dictionary_off)
        }
    SettingsLinkRow(
        title = stringResource(R.string.dictionary_title),
        summary = summary,
        icon = R.drawable.ic_menu_book,
        prominent = true,
        onClick = onOpen,
    )
}

/** Whether a function sends text, and to which server. */
@Composable
private fun featureSummary(
    state: VoiceSettingsState,
    feature: TextFeature,
): String {
    val server = state.read { if (featureReady(feature)) servers.find(featureServerId(feature))?.displayName else null }
    return if (server == null) {
        stringResource(R.string.feature_off)
    } else {
        stringResource(R.string.feature_on_server, server)
    }
}

@Composable
private fun serversSummary(state: VoiceSettingsState): String {
    val count = state.read { servers.size }
    return if (count == 0) {
        stringResource(R.string.servers_link_empty)
    } else {
        pluralStringResource(R.plurals.servers_link_count, count, count)
    }
}

/**
 * Typing: suggestions, quick replies, autocorrection, slide to type, the clipboard, the cursor movement on the
 * space bar, fullscreen mode and the delete key.
 */
@Composable
private fun TypingSection(
    state: VoiceSettingsState,
    links: TypingLinks,
    rows: HiddenRows,
) {
    val prefs = KeyboardPrefs(LocalContext.current)
    val engine = prefs.slideEngine
    val autocorrectOn = prefs.autoCapitalize || prefs.cleanLinks
    val autocorrect = if (autocorrectOn) R.string.settings_fullscreen_on else R.string.feature_off
    val fullscreen = if (prefs.fullscreenEnabled) R.string.settings_fullscreen_on else R.string.feature_off
    SettingsSection(stringResource(R.string.settings_typing)) {
        hideable(rows, HideableItem.SUGGESTIONS) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_suggestions),
                summary = stringResource(R.string.settings_suggestions_link_summary),
                icon = R.drawable.ic_lightbulb,
                prominent = true,
                onClick = links.suggestions,
            )
        }
        hideable(rows, HideableItem.QUICK_REPLIES) {
            SettingsLinkRow(
                title = stringResource(R.string.quick_replies_title),
                summary = quickRepliesSummary(state),
                icon = R.drawable.ic_chat,
                prominent = true,
                onClick = links.quickReplies,
            )
        }
        hideable(rows, HideableItem.AUTOCORRECT) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_autocorrect),
                summary = stringResource(autocorrect),
                icon = R.drawable.ic_text_fields,
                prominent = true,
                onClick = links.autocorrect,
            )
        }
        hideable(rows, HideableItem.SLIDE_ENGINE) {
            SettingsLinkRow(
                title = stringResource(R.string.slide_engine_title),
                summary = stringResource(if (prefs.slideEnabled) slideEngineLabel(engine) else R.string.feature_off),
                icon = R.drawable.ic_gesture,
                prominent = true,
                onClick = links.slideEngine,
            )
        }
        hideable(rows, HideableItem.CLIPBOARD) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_clipboard),
                summary = stringResource(R.string.settings_clipboard_link_summary),
                icon = R.drawable.ic_content_paste,
                prominent = true,
                onClick = links.clipboard,
            )
        }
        hideable(rows, HideableItem.SPACE_DRAG) { SpaceDragRow() }
        hideable(rows, HideableItem.FULLSCREEN) {
            SettingsLinkRow(
                title = stringResource(R.string.settings_fullscreen),
                summary = stringResource(fullscreen),
                icon = R.drawable.ic_fullscreen,
                prominent = true,
                onClick = links.fullscreen,
            )
        }
        hideable(rows, HideableItem.DELETE_KEY) {
            SettingsLinkRow(
                title = stringResource(R.string.delete_key_title),
                summary = stringResource(deleteHoldLabel(prefs.deleteHold)),
                icon = R.drawable.ic_backspace,
                prominent = true,
                onClick = links.deleteKey,
            )
        }
    }
}

private fun slideEngineLabel(engine: SlideEngine): Int =
    when (engine) {
        SlideEngine.DEFAULT -> R.string.slide_engine_default
        SlideEngine.EXPERIMENTAL -> R.string.slide_engine_experimental
    }

/** The choice of how the space bar moves the cursor. */
@Composable
private fun SpaceDragRow() {
    val prefs = KeyboardPrefs(LocalContext.current)
    var spaceDrag by remember { mutableStateOf(prefs.spaceDragMode) }
    SettingsChoiceRow(
        title = stringResource(R.string.settings_space_drag),
        icon = R.drawable.ic_space_bar,
        prominent = true,
        selected = spaceDrag,
        choices =
            listOf(
                Choice(
                    SpaceDragMode.TRACKPAD,
                    stringResource(R.string.settings_space_drag_trackpad),
                    stringResource(R.string.settings_space_drag_trackpad_summary),
                ),
                Choice(
                    SpaceDragMode.LINEAR,
                    stringResource(R.string.settings_space_drag_linear),
                    stringResource(R.string.settings_space_drag_linear_summary),
                ),
            ),
        onSelected = {
            spaceDrag = it
            prefs.spaceDragMode = it
        },
    )
}

/**
 * The app itself: the integrations with other apps, the language, the backup of all settings, diagnostics, updates,
 * the project page and the version.
 */
@Composable
private fun ApplicationSection(links: AppLinks) {
    val context = LocalContext.current
    val backup = rememberBackupActions()
    val uri = LocalUriHandler.current
    val localeManager = context.getSystemService(LocaleManager::class.java)
    var language by remember { mutableStateOf(localeManager.applicationLocales.toLanguageTags()) }
    SettingsSection(stringResource(R.string.settings_application)) {
        item {
            SettingsLinkRow(
                title = stringResource(R.string.integrations_title),
                summary = integrationsSummary(),
                icon = R.drawable.ic_extension,
                prominent = true,
                onClick = links.integrations,
            )
        }
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.settings_language),
                icon = R.drawable.ic_language,
                prominent = true,
                selected = language,
                choices =
                    listOf(
                        Choice("", stringResource(R.string.settings_language_system)),
                        Choice("en", "English"),
                        Choice("ru", "Русский"),
                    ),
                onSelected = {
                    language = it
                    localeManager.applicationLocales = LocaleList.forLanguageTags(it)
                },
            )
        }
        backupRows(backup)
        item {
            SettingsLinkRow(
                title = stringResource(R.string.diagnostics_title),
                summary = stringResource(R.string.diagnostics_link_summary),
                icon = R.drawable.ic_bug_report,
                prominent = true,
                onClick = links.diagnostics,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.updates_title),
                summary = updatesSummary(),
                icon = R.drawable.ic_download,
                prominent = true,
                onClick = links.updates,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_github),
                icon = R.drawable.ic_code,
                prominent = true,
                external = true,
                onClick = { runCatching { uri.openUri(PROJECT_URL) } },
            )
        }
        item {
            SettingsNote(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH),
            )
        }
    }
}

/** The integrations in use, or what there is to connect. */
@Composable
private fun integrationsSummary(): String {
    val on = UnisonPrefs(LocalContext.current).enabled
    return stringResource(if (on) R.string.unison_title else R.string.integrations_link_summary)
}

private const val PROJECT_URL = "https://github.com/lebe-dev/v-board"
