package ru.tinyops.android.vboard.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.WordPairs
import ru.tinyops.android.vboard.core.update.ReleaseNotes
import ru.tinyops.android.vboard.update.AvailableRelease
import ru.tinyops.android.vboard.update.CheckInterval
import ru.tinyops.android.vboard.update.UpdateCheck
import ru.tinyops.android.vboard.update.UpdateDownload
import ru.tinyops.android.vboard.update.UpdateInstaller
import ru.tinyops.android.vboard.update.UpdatePrefs
import ru.tinyops.android.vboard.update.UpdateSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Updates from the GitHub releases: the background check (off by default), the manual check and the
 * download and install of a newer release.
 */
@Composable
internal fun UpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val check by UpdateCheck.state.collectAsState()
    // Every check stores what it found; the page shows the stored release.
    val release = remember(check) { UpdateCheck.available(context) }
    SettingsPage(title = stringResource(R.string.updates_title), onBack = onBack) {
        item { AutoCheckSection() }
        item { CheckSection(check) }
        if (release != null) item { ReleaseSection(release) }
    }
}

/** The switch of the background check and how often it runs. */
@Composable
private fun AutoCheckSection() {
    val context = LocalContext.current
    val prefs = remember { UpdatePrefs(context) }
    var auto by remember { mutableStateOf(prefs.autoCheck) }
    var interval by remember { mutableStateOf(prefs.interval) }
    var beta by remember { mutableStateOf(prefs.betaReleases) }
    SettingsSection(null) {
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.updates_auto),
                summary = stringResource(R.string.updates_auto_summary),
                icon = R.drawable.ic_refresh,
                prominent = true,
                checked = auto,
                onCheckedChange = {
                    auto = it
                    prefs.autoCheck = it
                    UpdateSchedule.sync(context)
                    if (it) UpdateCheck.start(context)
                },
            )
        }
        if (auto) {
            item {
                SettingsChoiceRow(
                    title = stringResource(R.string.updates_interval),
                    icon = R.drawable.ic_timer,
                    prominent = true,
                    selected = interval,
                    choices =
                        listOf(
                            Choice(CheckInterval.DAILY, stringResource(R.string.updates_interval_daily)),
                            Choice(CheckInterval.WEEKLY, stringResource(R.string.updates_interval_weekly)),
                        ),
                    onSelected = {
                        interval = it
                        prefs.interval = it
                        UpdateSchedule.sync(context)
                    },
                )
            }
        }
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.updates_beta),
                summary = stringResource(R.string.updates_beta_summary),
                icon = R.drawable.ic_bug_report,
                prominent = true,
                checked = beta,
                onCheckedChange = {
                    beta = it
                    prefs.betaReleases = it
                    UpdateCheck.start(context)
                },
            )
        }
    }
}

/** The installed version, the manual check and its result. */
@Composable
private fun CheckSection(check: UpdateCheck.State) {
    val context = LocalContext.current
    val lastCheck = remember(check) { UpdatePrefs(context).lastCheck }
    SettingsSection(null) {
        item {
            SettingsStatusRow(
                title = stringResource(R.string.updates_installed, BuildConfig.VERSION_NAME),
                tone = StatusTone.NEUTRAL,
                icon = R.drawable.ic_info,
                summary =
                    if (lastCheck == 0L) {
                        stringResource(R.string.updates_never_checked)
                    } else {
                        stringResource(R.string.updates_last_check, formatTime(lastCheck))
                    },
            )
        }
        item {
            when (check) {
                UpdateCheck.State.Checking ->
                    SettingsStatusRow(stringResource(R.string.updates_checking), StatusTone.PROGRESS)
                UpdateCheck.State.UpToDate ->
                    SettingsStatusRow(
                        stringResource(R.string.updates_up_to_date),
                        StatusTone.SUCCESS,
                        R.drawable.ic_check_circle,
                    )
                else ->
                    SettingsLinkRow(
                        title = stringResource(R.string.updates_check),
                        summary =
                            stringResource(R.string.updates_check_failed).takeIf { check == UpdateCheck.State.Failed },
                        icon = R.drawable.ic_refresh,
                        onClick = { UpdateCheck.start(context) },
                    )
            }
        }
    }
}

/** A newer release: what is new, its install and its page. */
@Composable
private fun ReleaseSection(release: AvailableRelease) {
    val uri = LocalUriHandler.current
    val notes = remember(release) { ReleaseNotes.plain(release.notes) }
    SettingsSection(stringResource(R.string.updates_release_section, release.version)) {
        if (notes.isNotEmpty()) item { SettingsNote(notes) }
        item {
            if (release.apk == null) {
                SettingsNote(stringResource(R.string.updates_no_apk), icon = R.drawable.ic_warning)
            } else {
                InstallRow(release, release.apk.size)
            }
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.updates_release_page),
                icon = R.drawable.ic_code,
                external = true,
                onClick = { runCatching { uri.openUri(release.pageUrl) } },
            )
        }
    }
}

/** Downloads the APK and hands it to the installer, first sending the user to allow installs when needed. */
@Composable
private fun InstallRow(
    release: AvailableRelease,
    size: Long,
) {
    val context = LocalContext.current
    val state by UpdateDownload.state.collectAsState()
    var allowed by remember { mutableStateOf(UpdateInstaller.allowed(context)) }
    var askedToAllow by remember { mutableStateOf(false) }
    // The new version fetches its own word pairs in place of the downloaded ones.
    val withPairs = remember { WordPairs.installed(context) != null }
    // The user comes back from the system screen that allows installs.
    LifecycleResumeEffect(Unit) {
        allowed = UpdateInstaller.allowed(context)
        onPauseOrDispose {}
    }
    // Only an activity in the foreground may open the confirmation of the installer.
    LaunchedEffect(state) {
        UpdateDownload.takeConfirmation()?.let { runCatching { context.startActivity(it) } }
    }
    when (val current = state) {
        is UpdateDownload.State.Running ->
            SettingsLinkRow(
                title =
                    stringResource(
                        R.string.updates_downloading,
                        (current.done * PERCENT / current.total.coerceAtLeast(1)).toInt(),
                    ),
                summary = stringResource(R.string.updates_downloading_summary),
                icon = R.drawable.ic_download,
                onClick = UpdateDownload::cancel,
            )
        UpdateDownload.State.Installing ->
            SettingsStatusRow(stringResource(R.string.updates_installing), StatusTone.PROGRESS)
        is UpdateDownload.State.Confirm ->
            SettingsLinkRow(
                title = stringResource(R.string.updates_confirm),
                summary = stringResource(R.string.updates_confirm_summary),
                icon = R.drawable.ic_download,
                onClick = { runCatching { context.startActivity(current.intent) } },
            )
        else ->
            SettingsLinkRow(
                title = stringResource(R.string.updates_install),
                summary =
                    when {
                        current is UpdateDownload.State.Failed -> stringResource(current.reason.message())
                        !allowed && askedToAllow -> stringResource(R.string.updates_permission_summary)
                        withPairs -> stringResource(R.string.updates_install_summary_pairs, megabytes(size))
                        else -> stringResource(R.string.updates_install_summary, megabytes(size))
                    },
                icon = R.drawable.ic_download,
                onClick = {
                    if (allowed) {
                        UpdateDownload.start(context, release)
                    } else {
                        askedToAllow = true
                        openInstallPermission(context)
                    }
                },
            )
    }
}

private fun UpdateDownload.Failure.message(): Int =
    when (this) {
        UpdateDownload.Failure.NETWORK -> R.string.updates_failed_network
        UpdateDownload.Failure.DAMAGED -> R.string.updates_failed_damaged
        UpdateDownload.Failure.FOREIGN -> R.string.updates_failed_foreign
        UpdateDownload.Failure.INSTALL -> R.string.updates_failed_install
    }

private fun openInstallPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
}

/** The banner at the top of the settings root about a newer release; closing it hides this version. */
@Composable
internal fun UpdateBanner(
    release: AvailableRelease,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(BANNER_CORNER),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = BANNER_PADDING, top = SettingsSpacing.Row, bottom = SettingsSpacing.Row),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.updates_banner_title, release.version),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.updates_banner_summary), style = MaterialTheme.typography.bodyMedium)
            }
            TooltipIconButton(R.drawable.ic_close, stringResource(R.string.updates_banner_dismiss), onDismiss)
        }
    }
}

/** What the row of the settings root says about updates. */
@Composable
internal fun updatesSummary(): String {
    val context = LocalContext.current
    val prefs = remember { UpdatePrefs(context) }
    val available = remember { UpdateCheck.available(context) }
    return when {
        available != null -> stringResource(R.string.updates_link_available, available.version)
        !prefs.autoCheck -> stringResource(R.string.updates_link_off)
        prefs.interval == CheckInterval.WEEKLY -> stringResource(R.string.updates_link_weekly)
        else -> stringResource(R.string.updates_link_daily)
    }
}

private fun formatTime(millis: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

// Rounded up, as the space it takes.
private fun megabytes(bytes: Long): Int = ((bytes + BYTES_PER_MB - 1) / BYTES_PER_MB).toInt()

private const val BYTES_PER_MB = 1024L * 1024L
private const val PERCENT = 100L
private val BANNER_CORNER = 28.dp
private val BANNER_PADDING = 20.dp
