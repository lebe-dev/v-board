package ru.tinyops.android.vboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.SwipeModelAssets
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
    }
}
