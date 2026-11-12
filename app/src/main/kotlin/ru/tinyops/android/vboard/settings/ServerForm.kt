package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.core.voice.ServerPreset

/** Address, model, key and proxy: the fields of both server forms. */
internal data class EndpointDraft(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val proxyEnabled: Boolean = false,
    val proxyUrl: String = "",
)

/** Which of the filled-in [EndpointDraft] values cannot be used. */
internal class EndpointErrors(
    val endpoint: Boolean,
    val proxy: Boolean,
)

/** What differs between the forms that use [EndpointFields]. */
internal class EndpointFormStyle(
    val endpointPlaceholder: String,
    val presets: List<ServerPreset>,
    /** A feature sets its model in a row of its own, whichever server it uses. */
    val showModel: Boolean = true,
)

/** Keyboard options of a field holding a technical value: no capitals, no autocorrection. */
internal fun plainKeyboard(type: KeyboardType = KeyboardType.Text) =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = type,
        imeAction = ImeAction.Next,
    )

/**
 * The [EndpointDraft] text fields; [extra] goes between the key and the proxy. Lives inside a
 * [SettingsTileContent]. An invalid value is explained under its field, not only colored. A key that is
 * already [storedApiKey] is never shown: it can only be replaced.
 */
@Composable
internal fun EndpointFields(
    draft: EndpointDraft,
    enabled: Boolean,
    errors: EndpointErrors,
    style: EndpointFormStyle,
    onDraft: (EndpointDraft) -> Unit,
    storedApiKey: String = "",
    extra: @Composable () -> Unit = {},
) {
    val endpointError = draft.endpoint.isNotBlank() && errors.endpoint
    OutlinedTextField(
        value = draft.endpoint,
        onValueChange = { onDraft(draft.copy(endpoint = it)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.server_endpoint)) },
        placeholder = { Text(style.endpointPlaceholder) },
        supportingText =
            if (endpointError) {
                { Text(stringResource(R.string.server_endpoint_error)) }
            } else {
                null
            },
        keyboardOptions = plainKeyboard(KeyboardType.Uri),
        isError = endpointError,
    )
    EndpointPresets(style.presets, draft.endpoint, enabled) { onDraft(draft.copy(endpoint = it.endpoint)) }
    if (style.showModel) {
        OutlinedTextField(
            value = draft.model,
            onValueChange = { onDraft(draft.copy(model = it.take(SelfHostedServer.MAX_MODEL_LENGTH))) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.server_model)) },
            keyboardOptions = plainKeyboard(),
        )
    }
    ApiKeyField(
        value = draft.apiKey,
        stored = storedApiKey,
        enabled = enabled,
        onRestore = { onDraft(draft.copy(apiKey = storedApiKey)) },
    ) { onDraft(draft.copy(apiKey = it.take(SelfHostedServer.MAX_API_KEY_LENGTH))) }
    extra()
    CheckRow(
        text = stringResource(R.string.server_proxy),
        checked = draft.proxyEnabled,
        enabled = enabled,
    ) { onDraft(draft.copy(proxyEnabled = it)) }
    if (draft.proxyEnabled) {
        val proxyError = draft.proxyUrl.isNotBlank() && errors.proxy
        OutlinedTextField(
            value = draft.proxyUrl,
            onValueChange = { onDraft(draft.copy(proxyUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.server_proxy_url)) },
            placeholder = { Text("http://host:8080 · socks5://host:1080") },
            supportingText = {
                Text(stringResource(if (proxyError) R.string.server_proxy_error else R.string.server_proxy_summary))
            },
            keyboardOptions = plainKeyboard(KeyboardType.Uri),
            isError = proxyError,
        )
    }
}

/** Well-known services under the address field: typing a URL on a phone is slow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndpointPresets(
    presets: List<ServerPreset>,
    endpoint: String,
    enabled: Boolean,
    onSelect: (ServerPreset) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.Small),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = preset.matches(endpoint),
                onClick = { onSelect(preset) },
                label = { Text(preset.name) },
                enabled = enabled,
            )
        }
    }
}

/**
 * The API key. A saved key is never revealed or copyable: the field shows only that a key exists and
 * offers to replace it. A key being typed is hidden by default, with a button to show it for checking.
 */
@Composable
private fun ApiKeyField(
    value: String,
    stored: String,
    enabled: Boolean,
    onRestore: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    var replacing by rememberSaveable { mutableStateOf(false) }
    if (stored.isNotEmpty() && !replacing) {
        OutlinedTextField(
            value = SAVED_KEY_MASK,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.server_api_key_saved)) },
            trailingIcon = {
                TooltipIconButton(icon = R.drawable.ic_edit, label = stringResource(R.string.server_api_key_replace)) {
                    replacing = true
                    onValueChange("")
                }
            },
        )
        return
    }
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.server_api_key)) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = plainKeyboard(KeyboardType.Password),
        trailingIcon = {
            Row {
                TooltipIconButton(
                    icon = if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                    label = stringResource(if (visible) R.string.server_api_key_hide else R.string.server_api_key_show),
                ) { visible = !visible }
                if (stored.isNotEmpty()) {
                    TooltipIconButton(
                        icon = R.drawable.ic_close,
                        label = stringResource(R.string.server_api_key_keep),
                    ) {
                        replacing = false
                        visible = false
                        onRestore()
                    }
                }
            }
        },
    )
}

/** Stands in for a saved key; the key itself never reaches the screen. */
private const val SAVED_KEY_MASK = "••••••••••••"

/** A checkbox with its label; the whole row is the touch target. */
@Composable
internal fun CheckRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    textColor: Color = Color.Unspecified,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(SettingsSpacing.Gap))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color =
                textColor
                    .takeOrElse { MaterialTheme.colorScheme.onSurface }
                    .copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        )
    }
}
