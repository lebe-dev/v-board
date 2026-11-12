package ru.tinyops.android.vboard.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.SwipeModelAssets
import ru.tinyops.android.vboard.WordPairs
import ru.tinyops.android.vboard.WordPairsDownload
import ru.tinyops.android.vboard.core.gesture.SlideEngine

/** The choice of the slide to type implementation. A build without the model cannot offer the experimental one. */
@Composable
internal fun SlideEngineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = KeyboardPrefs(context)
    val modelPresent = remember { SwipeModelAssets.isPresent(context.assets) }
    var engine by remember { mutableStateOf(prefs.slideEngine) }
    var enabled by remember { mutableStateOf(prefs.slideEnabled) }
    val select: (SlideEngine) -> Unit = {
        engine = it
        prefs.slideEngine = it
    }
    SettingsPage(title = stringResource(R.string.slide_engine_title), onBack = onBack) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.slide_enabled),
                        summary = stringResource(R.string.slide_enabled_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.slideEnabled = it
                        },
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.slide_engine_section)) {
                item {
                    SettingsRadioRow(
                        title = stringResource(R.string.slide_engine_default),
                        summary = stringResource(R.string.slide_engine_default_summary),
                        selected = engine == SlideEngine.DEFAULT,
                        enabled = enabled,
                        onClick = { select(SlideEngine.DEFAULT) },
                    )
                }
                item {
                    SettingsRadioRow(
                        title = stringResource(R.string.slide_engine_experimental),
                        summary =
                            stringResource(
                                if (modelPresent) {
                                    R.string.slide_engine_experimental_summary
                                } else {
                                    R.string.slide_engine_experimental_missing
                                },
                            ),
                        selected = engine == SlideEngine.EXPERIMENTAL,
                        enabled = modelPresent && enabled,
                        onClick = { select(SlideEngine.EXPERIMENTAL) },
                    )
                }
            }
        }
        if (offersWordPairs(enabled, modelPresent, engine) && WordPairs.isDownloadable(context)) {
            item { WordPairsSection() }
        }
    }
}

/** The table matters only to the experimental engine, and only while slide to type is on. */
private fun offersWordPairs(
    enabled: Boolean,
    modelPresent: Boolean,
    engine: SlideEngine,
) = enabled && modelPresent && engine == SlideEngine.EXPERIMENTAL

/** The word pair table of a release build: downloaded on request, deleted to free the space. */
@Composable
private fun WordPairsSection() {
    val context = LocalContext.current
    val state by WordPairsDownload.state.collectAsState()
    // The file is checked again whenever the download changes its state, and after a deletion.
    var deletions by remember { mutableStateOf(0) }
    val installed = remember(state, deletions) { WordPairs.installed(context) != null }
    var confirmDelete by remember { mutableStateOf(false) }
    // Rounded up: the space it asks for must not fall short.
    val megabytes = ((WordPairs.release.size + BYTES_PER_MB - 1) / BYTES_PER_MB).toInt()
    SettingsSection(stringResource(R.string.word_pairs_section)) {
        item {
            when (val current = state) {
                is WordPairsDownload.State.Running ->
                    SettingsLinkRow(
                        title =
                            stringResource(
                                R.string.word_pairs_downloading,
                                (current.done * PERCENT / current.total.coerceAtLeast(1)).toInt(),
                            ),
                        summary = stringResource(R.string.word_pairs_downloading_summary),
                        onClick = WordPairsDownload::cancel,
                    )
                else ->
                    if (installed) {
                        SettingsLinkRow(
                            title = stringResource(R.string.word_pairs_installed),
                            summary = stringResource(R.string.word_pairs_installed_summary, megabytes),
                            onClick = { confirmDelete = true },
                        )
                    } else {
                        SettingsLinkRow(
                            title = stringResource(R.string.word_pairs_download),
                            summary =
                                when {
                                    current is WordPairsDownload.State.Failed && current.noSpace ->
                                        stringResource(R.string.word_pairs_no_space, megabytes)
                                    current is WordPairsDownload.State.Failed ->
                                        stringResource(R.string.word_pairs_failed)
                                    else -> stringResource(R.string.word_pairs_download_summary, megabytes)
                                },
                            onClick = { WordPairsDownload.start(context) },
                        )
                    }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.word_pairs_delete_title)) },
            text = { Text(stringResource(R.string.word_pairs_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    WordPairs.delete(context)
                    deletions++
                    confirmDelete = false
                }) { Text(stringResource(R.string.word_pairs_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

private const val BYTES_PER_MB = 1024L * 1024L
private const val PERCENT = 100L
