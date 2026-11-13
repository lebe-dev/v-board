package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.EnhancementEffort
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.core.voice.ServerAddress
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.ServerPresets
import ru.tinyops.android.vboard.core.voice.ServerProfile
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.core.voice.find
import java.net.URI

/** Keeps the server being edited through a rotation; nothing is a closed editor. */
private val DraftSaver =
    listSaver<ServerProfile?, Any>(
        save = {
            if (it == null) {
                emptyList()
            } else {
                listOf(
                    it.id,
                    it.name,
                    it.kind.id,
                    it.endpoint,
                    it.model,
                    it.apiKey,
                    it.effort.id,
                    it.proxyEnabled,
                    it.proxyUrl,
                )
            }
        },
        restore = {
            if (it.isEmpty()) {
                null
            } else {
                ServerProfile(
                    id = it[0] as String,
                    name = it[1] as String,
                    kind = ServerKind.fromId(it[2] as String),
                    endpoint = it[3] as String,
                    model = it[4] as String,
                    apiKey = it[5] as String,
                    effort = EnhancementEffort.fromId(it[6] as String),
                    proxyEnabled = it[7] as Boolean,
                    proxyUrl = it[8] as String,
                )
            }
        },
    )

/**
 * "Сервера": the only place where servers are set up. A server is an OpenAI-compatible API with a model,
 * a key and a level of effort; dictation, proofreading and recognition on a server choose from this list.
 */
@Composable
internal fun ServersScreen(
    state: VoiceSettingsState,
    onBack: () -> Unit,
) {
    val servers = state.read { servers }
    var editing by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf<ServerProfile?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var asDefault by rememberSaveable { mutableStateOf(false) }
    var emptiedKind by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPage(title = stringResource(R.string.servers_title), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.servers_note), icon = R.drawable.ic_info) }
        item {
            SettingsSection(stringResource(R.string.servers_section)) {
                servers.forEach { server ->
                    item {
                        SettingsLinkRow(
                            title = server.displayName,
                            summary =
                                serverRowSummary(
                                    server,
                                    state.read { defaultServerId(server.kind) } == server.id,
                                ),
                            icon = R.drawable.ic_cloud,
                            onClick = {
                                asDefault = state.read { defaultServerId(server.kind) } == server.id
                                editing = server
                            },
                        )
                    }
                }
                if (servers.isEmpty()) item { SettingsNote(stringResource(R.string.servers_empty)) }
                item {
                    val add = {
                        asDefault = false
                        editing = ServerProfile(id = state.read { newServerId() })
                    }
                    SettingsLinkRow(stringResource(R.string.server_add), icon = R.drawable.ic_add, onClick = add)
                }
            }
        }
    }

    editing?.let { draft ->
        ServerEditor(
            draft = draft,
            exists = servers.find(draft.id) != null,
            storedApiKey = servers.find(draft.id)?.apiKey.orEmpty(),
            onDraft = { editing = it },
            isDefault = asDefault,
            onDefault = { asDefault = it },
            actions =
                EditorActions(
                    onSave = {
                        state.update {
                            saveServer(draft)
                            if (asDefault) setDefaultServer(draft.kind, draft.id)
                        }
                        editing = null
                    },
                    onDelete = {
                        deleting = draft.id
                        editing = null
                    },
                    onDismiss = { editing = null },
                ),
        )
    }
    servers.find(deleting)?.let { doomed ->
        DeleteServerDialog(
            server = doomed,
            users = serverUsers(state, doomed.id),
            onConfirm = {
                var turnedOff = false
                state.update { turnedOff = deleteServer(doomed.id) }
                if (turnedOff) emptiedKind = doomed.kind.id
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    emptiedKind?.let { NoServersLeftDialog(it) { emptiedKind = null } }
}

/** Tells that a function was turned off because the last server of its kind is gone. */
@Composable
private fun NoServersLeftDialog(
    kindId: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_none_left_title)) },
        text = {
            Text(
                stringResource(
                    if (ServerKind.fromId(kindId) == ServerKind.AUDIO) {
                        R.string.server_none_left_audio
                    } else {
                        R.string.server_none_left_text
                    },
                ),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.server_ok)) } },
    )
}

/** The model and the host: enough to tell servers with the same name apart. */
internal fun serverSummary(server: ServerProfile): String {
    val host = runCatching { URI(server.endpoint.trim()).host }.getOrNull().orEmpty()
    return listOf(server.model, host).filter(String::isNotBlank).joinToString(" · ")
}

/** The kind, the model, the host and a mark for the default server: one line under the name. */
@Composable
private fun serverRowSummary(
    server: ServerProfile,
    isDefault: Boolean,
): String =
    listOfNotNull(
        kindLabel(server.kind),
        serverSummary(server).takeIf(String::isNotBlank),
        stringResource(R.string.server_default_mark).takeIf { isDefault },
    ).joinToString(" · ")

/** The kind of a server as the user reads it. */
@Composable
internal fun kindLabel(kind: ServerKind): String =
    stringResource(if (kind == ServerKind.AUDIO) R.string.server_kind_audio else R.string.server_kind_text)

/** The names of what sends data to the server, shown before it is deleted. */
@Composable
private fun serverUsers(
    state: VoiceSettingsState,
    id: String,
): List<String> {
    val features =
        TextFeature.entries
            .filter { state.read { featureServerId(it) } == id }
            .map { stringResource(featureTitle(it)) }
    val recognition =
        if (state.read { recognitionServerId } == id) listOf(stringResource(R.string.remote_title)) else emptyList()
    return features + recognition
}

internal fun featureTitle(feature: TextFeature): Int =
    if (feature == TextFeature.DICTATION) R.string.feature_dictation else R.string.feature_proofread

/** What the buttons of the server editor do. */
private class EditorActions(
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
private fun ServerEditor(
    draft: ServerProfile,
    exists: Boolean,
    storedApiKey: String,
    onDraft: (ServerProfile) -> Unit,
    isDefault: Boolean,
    onDefault: (Boolean) -> Unit,
    actions: EditorActions,
) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(if (exists) R.string.server_edit else R.string.server_new)) },
        text = {
            ServerFormBody(draft, isDefault, onDraft, onDefault, storedApiKey)
        },
        confirmButton = {
            TextButton(enabled = draft.normalized().isComplete, onClick = actions.onSave) {
                Text(stringResource(R.string.feature_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            if (exists) {
                TextButton(
                    onClick = actions.onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.server_delete)) }
            }
        },
    )
}

@Composable
private fun ServerFormBody(
    draft: ServerProfile,
    isDefault: Boolean,
    onDraft: (ServerProfile) -> Unit,
    onDefault: (Boolean) -> Unit,
    storedApiKey: String,
) {
    val connection = EndpointDraft(draft.endpoint, draft.model, draft.apiKey, draft.proxyEnabled, draft.proxyUrl)
    SettingsDialogContent {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it.take(ServerProfile.MAX_NAME_LENGTH))) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.server_name)) },
            placeholder = { Text(draft.copy(name = "").displayName) },
        )
        KindChoice(draft.kind) { onDraft(draft.copy(kind = it)) }
        EndpointFields(
            draft = connection,
            enabled = true,
            errors =
                EndpointErrors(
                    endpoint = ServerAddress.chat(draft.endpoint) == null,
                    proxy = draft.proxy == null,
                ),
            style =
                EndpointFormStyle(
                    "https://server.example/v1",
                    if (draft.kind == ServerKind.AUDIO) ServerPresets.TRANSCRIPTION else ServerPresets.CHAT,
                ),
            storedApiKey = storedApiKey,
            onDraft = {
                onDraft(
                    draft.copy(
                        endpoint = it.endpoint,
                        model = it.model.take(SelfHostedServer.MAX_MODEL_LENGTH),
                        apiKey = it.apiKey,
                        proxyEnabled = it.proxyEnabled,
                        proxyUrl = it.proxyUrl,
                    ),
                )
            },
        ) {
            if (draft.kind == ServerKind.TEXT) EffortChoice(draft.effort) { onDraft(draft.copy(effort = it)) }
        }
        CheckRow(
            text =
                stringResource(
                    if (draft.kind ==
                        ServerKind.AUDIO
                    ) {
                        R.string.server_default_audio
                    } else {
                        R.string.server_default_text
                    },
                ),
            checked = isDefault,
            enabled = true,
            onChange = onDefault,
        )
        Text(
            stringResource(R.string.server_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (draft.insecure) {
            Text(
                stringResource(R.string.server_http_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "Тип": text or audio, chosen before the address because it decides which services are suggested. */
@Composable
private fun KindChoice(
    selected: ServerKind,
    onSelected: (ServerKind) -> Unit,
) {
    val kinds = ServerKind.entries
    Text(stringResource(R.string.server_kind), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = kind == selected,
                onClick = { onSelected(kind) },
                shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                label = { Text(kindLabel(kind), maxLines = 1) },
            )
        }
    }
}

/** "Уровень усилий": three levels visible at once, with the explanation under them. */
@Composable
private fun ColumnScope.EffortChoice(
    selected: EnhancementEffort,
    onSelected: (EnhancementEffort) -> Unit,
) {
    val levels = effortChoices()
    Text(stringResource(R.string.server_effort), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        levels.forEachIndexed { index, level ->
            SegmentedButton(
                selected = level.value == selected,
                onClick = { onSelected(level.value) },
                shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                label = { Text(level.label, maxLines = 1) },
            )
        }
    }
    Text(
        stringResource(R.string.server_effort_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeleteServerDialog(
    server: ServerProfile,
    users: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_delete_title)) },
        text = {
            Text(
                if (users.isEmpty()) {
                    stringResource(R.string.server_delete_unused, server.displayName)
                } else {
                    stringResource(R.string.server_delete_used, server.displayName, users.joinToString())
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.server_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** The levels of "Уровень усилий". */
@Composable
internal fun effortChoices(): List<Choice<EnhancementEffort>> =
    listOf(
        Choice(EnhancementEffort.LOW, stringResource(R.string.server_effort_low)),
        Choice(EnhancementEffort.MEDIUM, stringResource(R.string.server_effort_medium)),
        Choice(EnhancementEffort.HIGH, stringResource(R.string.server_effort_high)),
    )

/**
 * The choice of one of the ready-made servers for a function. Without any server the row leads to the
 * list, where the first one is added.
 */
@Composable
internal fun ServerPickerRow(
    state: VoiceSettingsState,
    kind: ServerKind,
    selected: String?,
    onSelected: (String?) -> Unit,
    onOpenServers: () -> Unit,
) {
    val servers = state.read { servers.filter { it.kind == kind } }
    if (servers.isEmpty()) {
        SettingsLinkRow(
            title = stringResource(R.string.server_picker_title),
            summary = stringResource(R.string.server_picker_empty),
            icon = R.drawable.ic_cloud,
            prominent = true,
            onClick = onOpenServers,
        )
        return
    }
    val default = servers.find(state.read { defaultServerId(kind) })
    val followDefault =
        Choice<String?>(
            null,
            stringResource(R.string.server_picker_default),
            default?.displayName,
        )
    val choices =
        listOf(followDefault) +
            servers.map { Choice<String?>(it.id, it.displayName, serverSummary(it)) }
    SettingsChoiceRow(
        title = stringResource(R.string.server_picker_title),
        icon = R.drawable.ic_cloud,
        prominent = true,
        selected = servers.find(selected)?.id,
        choices = choices,
        onSelected = onSelected,
    )
}
