package ru.tinyops.android.vboard.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorLogEntry
import ru.tinyops.android.vboard.diagnostics.LogReport
import java.time.LocalDateTime

/**
 * The error log: the switch (off by default), a note that it stays on this phone and the recorded errors,
 * newest first. The top bar copies, shares and clears the log.
 */
@Composable
internal fun AppLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = KeyboardPrefs(context)
    var enabled by remember { mutableStateOf(prefs.logErrors) }
    var entries by remember { mutableStateOf(AppLog.entries()) }
    val scope = rememberCoroutineScope()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_TEXT)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text = report(context, entries)
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { DocumentFiles.writeText(context, uri, text) } }
                saved.onFailure { Log.w(TAG, "Could not save the log file", it) }
                toast(context, if (saved.isSuccess) R.string.app_log_saved else R.string.app_log_save_failed)
            }
        }
    val copy = {
        context
            .getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(null, report(context, entries)))
        toast(context, R.string.app_log_copied)
    }
    SettingsPage(
        title = stringResource(R.string.app_log_title),
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) {
                TooltipIconButton(R.drawable.ic_content_copy, stringResource(R.string.app_log_copy), onClick = copy)
                TooltipIconButton(R.drawable.ic_share, stringResource(R.string.app_log_share)) {
                    share(context, report(context, entries))
                }
                TooltipIconButton(R.drawable.ic_delete, stringResource(R.string.app_log_clear)) {
                    AppLog.clear()
                    entries = emptyList()
                }
            }
        },
    ) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.app_log_enable),
                        summary = stringResource(R.string.app_log_enable_summary),
                        icon = R.drawable.ic_bug_report,
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.logErrors = it
                        },
                    )
                }
                item {
                    SettingsNote(stringResource(R.string.app_log_local_note), icon = R.drawable.ic_info)
                }
            }
        }
        item {
            if (entries.isEmpty()) {
                SettingsSection(null) { item { SettingsNote(stringResource(R.string.app_log_empty)) } }
                return@item
            }
            ExportSection(onCopy = copy, onSave = { save.launch(LogReport.fileName(LocalDateTime.now())) })
            SettingsSection(stringResource(R.string.app_log_entries)) {
                entries.forEach { entry -> item { EntryTile(entry) } }
            }
        }
    }
}

/** The visible buttons for taking the log off the phone. */
@Composable
private fun ExportSection(
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsSection(null) {
        item {
            SettingsLinkRow(
                title = stringResource(R.string.app_log_copy_button),
                icon = R.drawable.ic_content_copy,
                onClick = onCopy,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.app_log_save),
                summary = stringResource(R.string.app_log_save_summary),
                icon = R.drawable.ic_save,
                onClick = onSave,
            )
        }
    }
}

/** One error: when and where it happened, what the code said and, on a tap, the whole stack trace. */
@Composable
private fun EntryTile(entry: ErrorLogEntry) {
    var expanded by remember { mutableStateOf(false) }
    val hasStack = entry.stack != null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hasStack) { expanded = !expanded }
            .padding(SettingsSpacing.Row),
    ) {
        Text(
            "${LogReport.formatTime(entry.time)} · ${entry.tag}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
        if (entry.stack != null) {
            Text(
                entry.stack,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun report(
    context: Context,
    entries: List<ErrorLogEntry>,
): String = LogReport.render(LogReport.header(context), entries)

private fun toast(
    context: Context,
    message: Int,
) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun share(
    context: Context,
    text: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

private const val COLLAPSED_LINES = 4
private const val MIME_TEXT = "text/plain"
private const val TAG = "AppLogScreen"
