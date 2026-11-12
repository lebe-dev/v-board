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

/**
 * The settings root, in sections: appearance (theme, emoji), typing (slide to type, clipboard, the space bar,
 * the fullscreen mode), voice input, text improvement (dictation, proofreading, prompts, servers) and the app
 * itself (language, backup of all settings, diagnostics, version); the back arrow closes settings.
 */
@Composable
internal fun MainSettingsScreen(
    state: VoiceSettingsState,
    theme: ThemeSpec,
    links: MainSettingsLinks,
    onClose: () -> Unit,
) {
    SettingsPage(title = stringResource(R.string.settings_title), onBack = onClose) {
        item {
            SettingsSection(stringResource(R.string.settings_appearance)) {
                item {
                    SettingsLinkRow(
                        title = stringResource(R.string.theme_title),
                        summary = stringResource(theme.name),
                        icon = R.drawable.ic_palette,
                        prominent = true,
                        onClick = links.theme,
                    )
                }
                item {
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
        item { TypingSection(links.typing) }
        item {
            SettingsSection(stringResource(R.string.settings_voice)) {
                item {
                    SettingsLinkRow(
                        title = stringResource(R.string.settings_voice),
                        summary = stringResource(R.string.settings_voice_link_summary),
                        icon = R.drawable.ic_microphone,
                        prominent = true,
                        onClick = links.voice,
                    )
                }
            }
        }
        item { EnhancementSection(state, links) }
        item { ApplicationSection(links.diagnostics) }
    }
}

/** The functions of text improvement, the replacements, their prompts and the servers they send text to. */
@Composable
private fun EnhancementSection(
    state: VoiceSettingsState,
    links: MainSettingsLinks,
) {
    SettingsSection(stringResource(R.string.settings_enhancement)) {
        item {
            SettingsLinkRow(
                title = stringResource(R.string.feature_dictation),
                summary = featureSummary(state, TextFeature.DICTATION),
                icon = R.drawable.ic_microphone,
                prominent = true,
                onClick = links.dictation,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.feature_proofread),
                summary = featureSummary(state, TextFeature.PROOFREAD),
                icon = R.drawable.ic_auto_fix_high,
                prominent = true,
                onClick = links.proofread,
            )
        }
        item {
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
        item { DictionaryRow(state, links.text.dictionary) }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_prompts),
                summary = stringResource(R.string.settings_prompts_link_summary),
                icon = R.drawable.ic_assignment,
                prominent = true,
                onClick = links.text.prompts,
            )
        }
        item {
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

/** Typing: autocorrection, slide to type, the clipboard, the cursor movement on the space bar, fullscreen mode. */
@Composable
private fun TypingSection(links: TypingLinks) {
    val prefs = KeyboardPrefs(LocalContext.current)
    val engine = prefs.slideEngine
    val autocorrect = if (prefs.autoCapitalize) R.string.settings_fullscreen_on else R.string.feature_off
    val fullscreen = if (prefs.fullscreenEnabled) R.string.settings_fullscreen_on else R.string.feature_off
    SettingsSection(stringResource(R.string.settings_typing)) {
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_suggestions),
                summary = stringResource(R.string.settings_suggestions_link_summary),
                icon = R.drawable.ic_lightbulb,
                prominent = true,
                onClick = links.suggestions,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_autocorrect),
                summary = stringResource(autocorrect),
                icon = R.drawable.ic_text_fields,
                prominent = true,
                onClick = links.autocorrect,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.slide_engine_title),
                summary = stringResource(if (prefs.slideEnabled) slideEngineLabel(engine) else R.string.feature_off),
                icon = R.drawable.ic_gesture,
                prominent = true,
                onClick = links.slideEngine,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_clipboard),
                summary = stringResource(R.string.settings_clipboard_link_summary),
                icon = R.drawable.ic_content_paste,
                prominent = true,
                onClick = links.clipboard,
            )
        }
        item { SpaceDragRow() }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_fullscreen),
                summary = stringResource(fullscreen),
                icon = R.drawable.ic_fullscreen,
                prominent = true,
                onClick = links.fullscreen,
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

/** The app itself: the language, the backup of all settings, diagnostics, the project page and the version. */
@Composable
private fun ApplicationSection(onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val backup = rememberBackupActions()
    val uri = LocalUriHandler.current
    val localeManager = context.getSystemService(LocaleManager::class.java)
    var language by remember { mutableStateOf(localeManager.applicationLocales.toLanguageTags()) }
    SettingsSection(stringResource(R.string.settings_application)) {
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
                onClick = onOpenDiagnostics,
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

private const val PROJECT_URL = "https://github.com/lebe-dev/v-board"
