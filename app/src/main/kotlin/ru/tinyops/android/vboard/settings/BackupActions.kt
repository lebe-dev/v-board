package ru.tinyops.android.vboard.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.net.Uri
import android.os.LocaleList
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the backup rows do: [export] saves the settings to a file, [import] replaces them with a file's. */
internal class BackupActions(
    val export: () -> Unit,
    val import: () -> Unit,
)

/**
 * Saving all the settings to a JSON file, with the API keys of the servers only when the user ticks the box
 * for them (asked only when some server has a key), and replacing the settings with the ones of such a file,
 * after a warning. Shows the dialogs of both and returns the actions for the rows.
 */
@Composable
internal fun rememberBackupActions(): BackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The chosen file waits for the answer to the warning, also through a rotation.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var exportOptions by rememberSaveable { mutableStateOf(false) }
    // Kept for the answer of the save dialog, which may come back to a recreated screen.
    var exportKeys by rememberSaveable { mutableStateOf(false) }

    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { exportTo(context, uri, exportKeys) } }
                saved.onFailure { Log.w(TAG, "Could not export the settings", it) }
                toast(context, if (saved.isSuccess) R.string.backup_export_done else R.string.backup_export_failed)
            }
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val text = withContext(Dispatchers.IO) { runCatching { readChecked(context, uri) } }
                text.onFailure { Log.w(TAG, "Could not read the settings file", it) }
                if (text.isSuccess) pending = text.getOrNull() else toast(context, R.string.backup_import_invalid)
            }
        }

    val actions =
        BackupActions(
            export = {
                exportKeys = false
                // Without a single key there is nothing to warn about: the save dialog opens at once.
                if (SettingsBackup.hasApiKeys(context)) {
                    exportOptions = true
                } else {
                    export.launch(SettingsBackup.fileName(LocalDateTime.now()))
                }
            },
            // Some file managers mark a .json file as text or as plain bytes.
            import = { import.launch(arrayOf(MIME_JSON, "text/*", "application/octet-stream")) },
        )

    if (exportOptions) {
        ExportOptions(
            includeKeys = exportKeys,
            onIncludeKeys = { exportKeys = it },
            onConfirm = {
                exportOptions = false
                export.launch(SettingsBackup.fileName(LocalDateTime.now()))
            },
            onDismiss = { exportOptions = false },
        )
    }

    pending?.let { text ->
        ImportWarning(
            backup = SettingsBackup.decode(text),
            onConfirm = {
                pending = null
                scope.launch { importFrom(context, text) }
            },
            onDismiss = { pending = null },
        )
    }
    return actions
}

/** The rows that export and import the settings. */
internal fun SettingsSectionScope.backupRows(actions: BackupActions) {
    item {
        SettingsLinkRow(
            title = stringResource(R.string.backup_export),
            summary = stringResource(R.string.backup_export_summary),
            icon = R.drawable.ic_download,
            prominent = true,
            onClick = actions.export,
        )
    }
    item {
        SettingsLinkRow(
            title = stringResource(R.string.backup_import),
            summary = stringResource(R.string.backup_import_summary),
            icon = R.drawable.ic_upload,
            prominent = true,
            onClick = actions.import,
        )
    }
}

/** The export leaves the API keys out unless the user ticks the box under the warning about them. */
@Composable
private fun ExportOptions(
    includeKeys: Boolean,
    onIncludeKeys: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_export_keys_warning))
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = includeKeys, role = Role.Checkbox, onValueChange = onIncludeKeys),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeKeys, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.backup_export_keys), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_export_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

/** The last chance to keep the current settings: the import cannot be undone. */
@Composable
private fun ImportWarning(
    backup: SettingsBackup.Backup,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val made = backup.exportedAt?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
    val source =
        when {
            made != null && backup.appVersion != null ->
                stringResource(R.string.backup_import_source, made, backup.appVersion)
            made != null -> stringResource(R.string.backup_import_source_date, made)
            else -> null
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_confirm_title)) },
        text = {
            val keys = if (backup.apiKeysIncluded) null else stringResource(R.string.backup_import_no_keys)
            Text(listOfNotNull(source, stringResource(R.string.backup_import_confirm_text), keys).joinToString("\n\n"))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_import_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

private fun exportTo(
    context: Context,
    uri: Uri,
    apiKeys: Boolean,
) {
    val language = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    val text =
        SettingsBackup.encode(
            SettingsBackup.read(context, language, apiKeys),
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = OffsetDateTime.now(),
        )
    DocumentFiles.writeText(context, uri, text)
}

/** The text of the file, once it is known to be a settings file this version can import. */
private fun readChecked(
    context: Context,
    uri: Uri,
): String {
    val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
    val bytes = stream.use { it.readNBytes(SettingsBackup.MAX_BYTES + 1) }
    if (bytes.size > SettingsBackup.MAX_BYTES) throw IOException("The file is too large for a settings file")
    return String(bytes, Charsets.UTF_8).also(SettingsBackup::decode)
}

/** Writes the settings, then rebuilds the settings screens so that they show the new values. */
private suspend fun importFrom(
    context: Context,
    text: String,
) {
    val backup = SettingsBackup.decode(text)
    withContext(Dispatchers.IO) { SettingsBackup.write(context, backup) }
    ErrorReporting.sync(context)
    toast(context, R.string.backup_import_done)
    val locales = context.getSystemService(LocaleManager::class.java)
    val language = backup.language
    if (language != null && language != locales.applicationLocales.toLanguageTags()) {
        // The system recreates the activity in the new language.
        locales.applicationLocales = LocaleList.forLanguageTags(language)
        return
    }
    (context as? Activity)?.recreate()
}

private fun toast(
    context: Context,
    message: Int,
) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private const val TAG = "SettingsBackup"
private const val MIME_JSON = "application/json"
