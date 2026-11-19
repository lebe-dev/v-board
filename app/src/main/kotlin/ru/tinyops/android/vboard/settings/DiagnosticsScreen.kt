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
import ru.tinyops.android.vboard.diagnostics.ErrorReporting

/**
 * Diagnostics: the switch that lets the app send technical error reports to the developer (off by default, shown
 * only in builds that can send them), the link to the local application log and the slide to type calibration.
 */
@Composable
internal fun DiagnosticsScreen(
    onOpenLog: () -> Unit,
    onOpenCalibration: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = KeyboardPrefs(context)
    var sendErrors by remember { mutableStateOf(prefs.sendErrors) }
    SettingsPage(title = stringResource(R.string.diagnostics_title), onBack = onBack) {
        item {
            SettingsSection(null) {
                if (ErrorReporting.available) {
                    item {
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_send_errors),
                            summary = stringResource(R.string.settings_send_errors_summary),
                            icon = R.drawable.ic_bug_report,
                            prominent = true,
                            checked = sendErrors,
                            onCheckedChange = {
                                sendErrors = it
                                prefs.sendErrors = it
                                ErrorReporting.sync(context)
                            },
                        )
                    }
                }
                item {
                    val logState = if (prefs.logErrors) R.string.app_log_link_on else R.string.feature_off
                    SettingsLinkRow(
                        title = stringResource(R.string.app_log_title),
                        summary = stringResource(logState),
                        icon = R.drawable.ic_article,
                        prominent = true,
                        onClick = onOpenLog,
                    )
                }
                item {
                    SettingsLinkRow(
                        title = stringResource(R.string.swipe_calibration_title),
                        summary = stringResource(R.string.swipe_calibration_link_summary),
                        icon = R.drawable.ic_gesture,
                        prominent = true,
                        onClick = onOpenCalibration,
                    )
                }
            }
        }
    }
}
