package ru.tinyops.android.vboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.unison.UnisonAvailability
import ru.tinyops.android.vboard.unison.UnisonPrefs

/** The apps the keyboard works with: the clipboard sync through Unison, off by default. */
@Composable
internal fun IntegrationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { UnisonPrefs(context) }
    val availability = remember { UnisonAvailability.of(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    SettingsPage(title = stringResource(R.string.integrations_title), onBack = onBack) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.unison_title),
                        summary = stringResource(R.string.unison_summary),
                        icon = R.drawable.ic_computer,
                        prominent = true,
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.enabled = it
                        },
                    )
                }
                availabilityNote(availability)?.let { note -> item { SettingsNote(stringResource(note)) } }
                if (enabled) item { SettingsNote(stringResource(R.string.unison_note)) }
            }
        }
    }
}

private fun availabilityNote(availability: UnisonAvailability): Int? =
    when (availability) {
        UnisonAvailability.MISSING -> R.string.unison_status_missing
        UnisonAvailability.REFUSED -> R.string.unison_status_refused
        UnisonAvailability.READY -> null
    }
