package ru.tinyops.android.vboard.settings

import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.gesture.calibration.CalibrationScore
import ru.tinyops.android.vboard.core.gesture.calibration.CalibrationWords
import ru.tinyops.android.vboard.core.gesture.calibration.SwipeSample
import ru.tinyops.android.vboard.core.gesture.calibration.SwipeSampleArchive
import ru.tinyops.android.vboard.diagnostics.SwipeCalibration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The slide to type calibration: asks for words one by one, the keyboard records each gesture made in the
 * field of this page, and the result is saved as a zip for the slide to type evaluation.
 */
@Composable
internal fun SwipeCalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by SwipeCalibration.state.collectAsState()
    val ownKeyboard = rememberOwnKeyboardSelected()
    val scope = rememberCoroutineScope()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_ZIP)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val samples = SwipeCalibration.state.value.samples
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            DocumentFiles.write(context, uri) { SwipeSampleArchive.write(samples, meta(context), it) }
                        }
                    }
                saved.onFailure { Log.w(TAG, "Could not save the calibration file", it) }
                val message =
                    if (saved.isSuccess) R.string.swipe_calibration_saved else R.string.swipe_calibration_save_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    SettingsPage(
        title = stringResource(R.string.swipe_calibration_title),
        onBack = onBack,
        actions = {
            if (state.started) {
                TooltipIconButton(R.drawable.ic_delete, stringResource(R.string.swipe_calibration_clear)) {
                    SwipeCalibration.clear()
                }
            }
        },
    ) {
        if (!ownKeyboard && (!state.started || state.current != null)) {
            item { KeyboardWarning(onChoose = { KeyboardSelection.choose(context) }) }
        }
        item {
            when {
                !state.started -> IntroSection(slideEnabled = KeyboardPrefs(context).slideEnabled)
                state.current != null -> PromptSection(state)
                else -> ResultSection(state.samples, onSave = { save.launch(fileName()) })
            }
        }
    }
}

/** Whether V-Board is the keyboard of the system now, checked again on every switch and whenever the page returns. */
@Composable
private fun rememberOwnKeyboardSelected(): Boolean {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(KeyboardSelection.isOwnSelected(context)) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { selected = KeyboardSelection.isOwnSelected(context) }
    DisposableEffect(context) {
        val stop = KeyboardSelection.observe(context) { selected = KeyboardSelection.isOwnSelected(context) }
        onDispose { stop() }
    }
    return selected
}

/** Another keyboard does not record gestures: says so and offers to switch. */
@Composable
private fun KeyboardWarning(onChoose: () -> Unit) {
    SettingsSection(null) {
        item {
            SettingsNote(
                stringResource(R.string.swipe_calibration_other_keyboard),
                icon = R.drawable.ic_warning,
                color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.swipe_calibration_choose_keyboard),
                icon = R.drawable.ic_keyboard,
                onClick = onChoose,
            )
        }
    }
}

@Composable
private fun IntroSection(slideEnabled: Boolean) {
    SettingsSection(null) {
        item {
            SettingsNote(
                stringResource(R.string.swipe_calibration_intro),
                icon = R.drawable.ic_info,
            )
        }
        item { SettingsNote(stringResource(R.string.swipe_calibration_privacy)) }
        if (!slideEnabled) {
            item {
                SettingsNote(
                    stringResource(R.string.swipe_calibration_slide_off),
                    icon = R.drawable.ic_warning,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.swipe_calibration_start),
                icon = R.drawable.ic_play_arrow,
                onClick = { SwipeCalibration.start(CalibrationWords.RUSSIAN) },
            )
        }
    }
}

/** The word to swipe and the field the keyboard records gestures in; the field empties after every gesture. */
@Composable
private fun PromptSection(state: SwipeCalibration.State) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(state.index) { text = "" }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var fieldFocused by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(fieldFocused, windowFocused) {
        SwipeCalibration.listening = fieldFocused && windowFocused
        onDispose { SwipeCalibration.listening = false }
    }
    SettingsSection(null) {
        item {
            SettingsTileContent {
                Text(
                    stringResource(R.string.swipe_calibration_progress, state.index + 1, state.words.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(state.current.orEmpty(), style = MaterialTheme.typography.displaySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.swipe_calibration_field)) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { fieldFocused = it.isFocused },
                )
            }
        }
        if (state.wrongLanguage) {
            item {
                SettingsNote(
                    stringResource(R.string.swipe_calibration_wrong_language),
                    icon = R.drawable.ic_warning,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.swipe_calibration_skip),
                icon = R.drawable.ic_refresh,
                onClick = SwipeCalibration::skip,
            )
        }
        if (state.samples.isNotEmpty()) {
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.swipe_calibration_undo),
                    icon = R.drawable.ic_undo,
                    onClick = SwipeCalibration::undo,
                )
            }
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.swipe_calibration_finish),
                    icon = R.drawable.ic_flag,
                    onClick = SwipeCalibration::finish,
                )
            }
        }
    }
}

@Composable
private fun ResultSection(
    samples: List<SwipeSample>,
    onSave: () -> Unit,
) {
    val score = CalibrationScore.of(samples)
    SettingsSection(null) {
        item {
            SettingsNote(
                stringResource(R.string.swipe_calibration_result, score.first, score.shown, score.total),
                icon = R.drawable.ic_check_circle,
            )
        }
        if (samples.isNotEmpty()) {
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.swipe_calibration_save),
                    summary = stringResource(R.string.swipe_calibration_save_summary),
                    icon = R.drawable.ic_save,
                    onClick = onSave,
                )
            }
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.swipe_calibration_restart),
                icon = R.drawable.ic_play_arrow,
                onClick = { SwipeCalibration.start(CalibrationWords.RUSSIAN) },
            )
        }
    }
}

private fun meta(context: Context): Map<String, String> {
    val metrics = context.resources.displayMetrics
    return linkedMapOf(
        "app" to BuildConfig.VERSION_NAME,
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to Build.VERSION.SDK_INT.toString(),
        "density" to metrics.density.toString(),
        "xdpi" to metrics.xdpi.toString(),
        "ydpi" to metrics.ydpi.toString(),
        "saved" to Instant.now().toString(),
    )
}

private fun fileName(): String = "vboard-swipes-${LocalDateTime.now().format(FILE_TIME)}.zip"

private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private const val MIME_ZIP = "application/zip"
private const val TAG = "SwipeCalibration"
