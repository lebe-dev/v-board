package ru.tinyops.android.vboard.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.diagnostics.SwipeTypingLog
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Diagnostics page for the opt-in free-typing swipe log: toggle with automatic shutoff after four
 * hours, privacy details, buffer count, manual export (share or save as zip) and clear action.
 */
@Composable
internal fun SwipeLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by SwipeTypingLog.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SwipeTypingLog.isRecording() }

    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_ZIP)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            DocumentFiles.write(context, uri) { SwipeTypingLog.writeArchive(context, it) }
                        }
                    }
                saved.onFailure { Log.w(TAG, "Could not save the swipe log archive", it) }
                val message = if (saved.isSuccess) R.string.swipe_log_saved else R.string.swipe_log_save_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

    val share: () -> Unit = {
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { writeShareFile(context) } }
            file
                .onSuccess { launchShare(context, it) }
                .onFailure {
                    Log.w(TAG, "Could not prepare the swipe log archive for sharing", it)
                    Toast.makeText(context, R.string.swipe_log_save_failed, Toast.LENGTH_SHORT).show()
                }
        }
    }

    SettingsPage(
        title = stringResource(R.string.swipe_log_title),
        onBack = onBack,
        actions = {
            if (state.count > 0) {
                TooltipIconButton(R.drawable.ic_share, stringResource(R.string.swipe_log_share), onClick = share)
                TooltipIconButton(R.drawable.ic_delete, stringResource(R.string.swipe_log_clear)) {
                    SwipeTypingLog.clear()
                }
            }
        },
    ) {
        item {
            SettingsSection(null) {
                item {
                    val summary =
                        if (state.enabled && state.untilMillis > 0L) {
                            stringResource(R.string.swipe_log_enable_until, formatClock(state.untilMillis))
                        } else {
                            stringResource(R.string.swipe_log_enable_summary)
                        }
                    SettingsSwitchRow(
                        title = stringResource(R.string.swipe_log_enable),
                        summary = summary,
                        icon = R.drawable.ic_timer,
                        checked = state.enabled,
                        onCheckedChange = { SwipeTypingLog.setEnabled(it) },
                    )
                }
                item {
                    SettingsNote(stringResource(R.string.swipe_log_privacy), icon = R.drawable.ic_info)
                }
            }
        }
        item {
            SwipeLogStatusSection(
                state = state,
                onShare = share,
                onSave = { save.launch(fileName()) },
                onClear = SwipeTypingLog::clear,
            )
        }
    }
}

@Composable
private fun SwipeLogStatusSection(
    state: SwipeTypingLog.State,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsSection(null) {
        if (state.count == 0) {
            item { SettingsNote(stringResource(R.string.swipe_log_empty)) }
            return@SettingsSection
        }
        item {
            SettingsNote(
                stringResource(
                    R.string.swipe_log_status,
                    state.count,
                    SwipeTypingLog.MAX_ENTRIES,
                    state.pickedCount,
                    state.retypedCount,
                ),
                icon = R.drawable.ic_gesture,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.swipe_log_share),
                summary = stringResource(R.string.swipe_log_share_summary),
                icon = R.drawable.ic_share,
                onClick = onShare,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.swipe_log_save),
                summary = stringResource(R.string.swipe_log_save_summary),
                icon = R.drawable.ic_save,
                onClick = onSave,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.swipe_log_clear),
                icon = R.drawable.ic_delete,
                onClick = onClear,
            )
        }
    }
}

private fun writeShareFile(context: Context): File {
    val dir = File(context.cacheDir, EXPORTS_DIR).apply { mkdirs() }
    val target = File(dir, fileName())
    target.outputStream().use { SwipeTypingLog.writeArchive(context, it) }
    return target
}

private fun launchShare(
    context: Context,
    file: File,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.clips", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(MIME_ZIP)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

private fun formatClock(epochMillis: Long): String =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(CLOCK_TIME)

private fun fileName(): String = "vboard-typing-log-${LocalDateTime.now().format(FILE_TIME)}.zip"

private val CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private const val EXPORTS_DIR = "exports"
private const val MIME_ZIP = "application/zip"
private const val TAG = "SwipeLogScreen"
