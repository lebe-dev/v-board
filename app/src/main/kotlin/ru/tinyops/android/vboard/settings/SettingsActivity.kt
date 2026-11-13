package ru.tinyops.android.vboard.settings

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.vboard.core.voice.GovorunApi
import ru.tinyops.android.vboard.core.voice.RecognitionEngine
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.ThemeChoice
import ru.tinyops.android.vboard.ui.ThemeMode
import ru.tinyops.android.vboard.update.UpdateSchedule
import ru.tinyops.android.vboard.voice.VoicePrefs
import ru.tinyops.android.vboard.voice.govorun.GovorunConnection

/**
 * Keyboard settings, opened by the cog on the keyboard, from the launcher or from the system
 * keyboard settings: the theme picker and the voice input settings, modeled on Govorun's.
 */
class SettingsActivity : ComponentActivity() {
    private val microphoneGranted = mutableStateOf(false)
    private val recognition = mutableStateOf(RecognitionStatus.SYSTEM)
    private val theme = mutableStateOf(ThemeChoice(KeyboardThemes.find(KeyboardThemes.CLASSIC), ThemeMode.SYSTEM))
    private val scope = MainScope()
    private val govorun by lazy { GovorunConnection(this) }
    private var statusCheck: Job? = null
    private lateinit var state: VoiceSettingsState

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneGranted.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        state = VoiceSettingsState(VoicePrefs(this))
        refreshMicrophone()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false)) {
            requestMicrophone()
        }
        val start =
            when {
                intent.getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false) -> Screen.VOICE
                else -> Screen.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SCREEN) } ?: Screen.MAIN
            }
        theme.value = KeyboardThemes.selected(this)
        // Also picks up the update settings of an imported backup.
        UpdateSchedule.sync(this)
        setContent {
            SettingsTheme(theme.value) {
                SettingsNavigation(
                    start = start,
                    theme = theme.value,
                    onTheme = { theme.value = it },
                    state = state,
                    voiceStatus = VoiceStatus(microphoneGranted.value, recognition.value),
                    onRequestMicrophone = ::requestMicrophone,
                    onConnectGovorun = ::requestGovorunAccess,
                    onRecognitionChanged = ::refreshRecognition,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMicrophone()
        // Also covers the return from Govorun's permission dialog and from an install.
        refreshRecognition()
    }

    override fun onStop() {
        super.onStop()
        statusCheck?.cancel()
        govorun.release()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshMicrophone() {
        microphoneGranted.value =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophone() {
        if (!microphoneGranted.value) permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Shows whether dictation can work with the recognition the user has chosen. */
    private fun refreshRecognition() {
        statusCheck?.cancel()
        if (state.read { remoteReady }) {
            recognition.value = RecognitionStatus.REMOTE
            return
        }
        if (state.read { engine } == RecognitionEngine.SYSTEM) {
            govorun.release()
            recognition.value =
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    RecognitionStatus.SYSTEM
                } else {
                    RecognitionStatus.SYSTEM_UNAVAILABLE
                }
            return
        }
        if (!GovorunConnection.isInstalled(this)) {
            recognition.value = RecognitionStatus.NOT_INSTALLED
            return
        }
        recognition.value = RecognitionStatus.CHECKING
        statusCheck = scope.launch { recognition.value = govorunStatus() }
    }

    private suspend fun govorunStatus(): RecognitionStatus {
        val service = govorun.connect() ?: return RecognitionStatus.UNAVAILABLE
        return withContext(Dispatchers.IO) {
            runCatching {
                when {
                    !GovorunApi.supports(service.protocolVersion, service.capabilities) ->
                        RecognitionStatus.UNAVAILABLE
                    service.getAuthorizationStatus(packageName) == GovorunApi.AUTHORIZATION_GRANTED ->
                        RecognitionStatus.READY
                    else -> RecognitionStatus.NEEDS_ACCESS
                }
            }.getOrDefault(RecognitionStatus.UNAVAILABLE)
        }
    }

    /** Govorun shows its own dialog; the answer is read back in [onResume]. */
    private fun requestGovorunAccess() {
        scope.launch {
            val service = govorun.connect()
            if (service == null) {
                recognition.value = RecognitionStatus.UNAVAILABLE
                return@launch
            }
            runCatching {
                val request = withContext(Dispatchers.IO) { service.createAuthorizationRequest(packageName) }

                // The request belongs to Govorun, which is in the background: this visible activity lends it the start.
                @Suppress("DEPRECATION") // The replacement needs API 36; the app supports API 34.
                val options =
                    ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                startIntentSender(request.intentSender, null, 0, 0, 0, options.toBundle())
            }.onFailure {
                if (it is CancellationException) {
                    Log.w(TAG, "Could not open the Govorun permission dialog", it)
                } else {
                    AppLog.error(TAG, "Could not open the Govorun permission dialog", it)
                    ErrorReporting.capture(it)
                }
                recognition.value = RecognitionStatus.UNAVAILABLE
            }
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"

        /** Ask for the microphone permission right away (the keyboard cannot ask itself). */
        const val EXTRA_REQUEST_MICROPHONE = "request_microphone"

        /** The screen to open instead of the main one: a [Screen] name, e.g. `THEME`. */
        const val EXTRA_SCREEN = "screen"
    }
}

internal enum class RecognitionStatus {
    SYSTEM,
    SYSTEM_UNAVAILABLE,
    CHECKING,
    READY,
    REMOTE,
    NOT_INSTALLED,
    NEEDS_ACCESS,
    UNAVAILABLE,
}

/** Reads and writes [VoicePrefs] so that every change recomposes the screens that show it. */
internal class VoiceSettingsState(
    private val prefs: VoicePrefs,
) {
    private var version by mutableIntStateOf(0)

    // Reading the version subscribes the caller to every later update.
    fun <T> read(block: VoicePrefs.() -> T): T = prefs.block().also { version }

    fun update(block: VoicePrefs.() -> Unit) {
        prefs.block()
        version++
    }
}

/** What the voice settings page shows about the microphone and the recognition. */
internal class VoiceStatus(
    val microphoneGranted: Boolean,
    val recognition: RecognitionStatus,
)

/** The typing pages the settings root links to. */
internal class TypingLinks(
    val clipboard: () -> Unit,
    val emoji: () -> Unit,
    val suggestions: () -> Unit,
    val autocorrect: () -> Unit,
    val slideEngine: () -> Unit,
    val fullscreen: () -> Unit,
    val deleteKey: () -> Unit,
)

/** Where the rows of the settings root lead. */
internal class MainSettingsLinks(
    val theme: () -> Unit,
    val keys: () -> Unit,
    val voice: () -> Unit,
    val dictation: () -> Unit,
    val proofread: () -> Unit,
    val customButtons: () -> Unit,
    val typing: TypingLinks,
    val text: TextLinks,
    val app: AppLinks,
)

/** The pages about the app itself the settings root links to. */
internal class AppLinks(
    val diagnostics: () -> Unit,
    val updates: () -> Unit,
)

/** The pages of text improvement the settings root links to. */
internal class TextLinks(
    val prompts: () -> Unit,
    val servers: () -> Unit,
    val dictionary: () -> Unit,
)

/** The sub-pages the voice settings link to and the actions they trigger. */
internal class SettingsLinks(
    val remote: () -> Unit,
    val connectGovorun: () -> Unit,
    val engineChanged: () -> Unit,
)

internal enum class Screen(
    val parent: Screen? = null,
) {
    MAIN,
    THEME(MAIN),
    KEYS(MAIN),
    VOICE(MAIN),
    CLIPBOARD(MAIN),
    FULLSCREEN(MAIN),
    EMOJI(MAIN),
    EMOJI_GROUPS(EMOJI),
    EMOJI_SINGLE(EMOJI),
    DICTIONARY(MAIN),
    DICTATION_FEATURE(MAIN),
    PROOFREAD_FEATURE(MAIN),
    CUSTOM_BUTTONS(MAIN),
    CUSTOM_BUTTON(CUSTOM_BUTTONS),
    REMOTE(VOICE),
    SLIDE_ENGINE(MAIN),
    SUGGESTIONS(MAIN),
    AUTOCORRECT(MAIN),
    DELETE_KEY(MAIN),
    PROMPTS(MAIN),
    SERVERS(MAIN),
    DIAGNOSTICS(MAIN),
    APP_LOG(DIAGNOSTICS),
    UPDATES(MAIN),
}

@Composable
private fun AppearancePage(
    screen: Screen,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    onBack: () -> Unit,
) {
    if (screen == Screen.THEME) ThemeScreen(theme, onTheme, onBack) else KeysScreen(theme, onTheme, onBack)
}

// The root of the settings takes everything the pages get from the activity.
@Suppress("LongParameterList")
@Composable
private fun SettingsNavigation(
    start: Screen,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    state: VoiceSettingsState,
    voiceStatus: VoiceStatus,
    onRequestMicrophone: () -> Unit,
    onConnectGovorun: () -> Unit,
    onRecognitionChanged: () -> Unit,
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var screen by rememberSaveable { mutableStateOf(start) }
    // The list of servers opens from the main page and from every page that chooses a server.
    var serversFrom by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var editedButton by rememberSaveable { mutableStateOf("") }
    val openServers: () -> Unit = {
        serversFrom = screen
        screen = Screen.SERVERS
    }
    val back: () -> Unit = {
        // The status line depends on where recognition runs, and a server may have been deleted.
        if (screen == Screen.REMOTE || screen == Screen.SERVERS) onRecognitionChanged()
        if (screen == Screen.SERVERS) {
            screen = serversFrom
        } else {
            screen.parent?.let { screen = it }
        }
    }
    // On the top level the back arrow and the system back both finish the activity.
    if (screen.parent != null) BackHandler(onBack = back)
    AnimatedContent(
        targetState = screen,
        transitionSpec = { sharedAxisX(forward = targetState == Screen.SERVERS || targetState.parent == initialState) },
        label = "settings",
    ) { current ->
        when (current) {
            Screen.MAIN ->
                MainSettingsScreen(
                    state = state,
                    theme = theme.spec,
                    links = mainLinks(navigate = { screen = it }, openServers),
                    onClose = { backDispatcher?.onBackPressed() },
                )
            Screen.THEME, Screen.KEYS -> AppearancePage(current, theme, onTheme, back)
            Screen.VOICE ->
                VoiceSettingsScreen(
                    state = state,
                    microphoneGranted = voiceStatus.microphoneGranted,
                    recognition = voiceStatus.recognition,
                    onRequestMicrophone = onRequestMicrophone,
                    links =
                        SettingsLinks(
                            remote = { screen = Screen.REMOTE },
                            connectGovorun = onConnectGovorun,
                            engineChanged = onRecognitionChanged,
                        ),
                    onBack = back,
                )
            Screen.DIAGNOSTICS -> DiagnosticsScreen(onOpenLog = { screen = Screen.APP_LOG }, onBack = back)
            Screen.FULLSCREEN, Screen.APP_LOG, Screen.UPDATES -> StandalonePage(current, back)
            Screen.CLIPBOARD, Screen.EMOJI, Screen.EMOJI_GROUPS, Screen.EMOJI_SINGLE, Screen.SLIDE_ENGINE,
            Screen.AUTOCORRECT, Screen.SUGGESTIONS, Screen.DELETE_KEY,
            ->
                TypingPage(current, navigate = { screen = it }, onBack = back)
            Screen.CUSTOM_BUTTONS ->
                CustomButtonsScreen(
                    state = state,
                    onOpen = {
                        editedButton = it
                        screen = Screen.CUSTOM_BUTTON
                    },
                    onBack = back,
                )
            Screen.CUSTOM_BUTTON ->
                CustomButtonScreen(editedButton, state, openServers, onOpen = { editedButton = it }, onBack = back)
            Screen.DICTATION_FEATURE, Screen.PROOFREAD_FEATURE, Screen.PROMPTS, Screen.SERVERS, Screen.DICTIONARY ->
                EnhancementPage(current, state, openServers, back)
            Screen.REMOTE -> RemoteRecognitionScreen(state, onOpenServers = openServers, onBack = back)
        }
    }
}

/** Where the rows of the settings root lead. */
private fun mainLinks(
    navigate: (Screen) -> Unit,
    openServers: () -> Unit,
) = MainSettingsLinks(
    theme = { navigate(Screen.THEME) },
    keys = { navigate(Screen.KEYS) },
    voice = { navigate(Screen.VOICE) },
    dictation = { navigate(Screen.DICTATION_FEATURE) },
    proofread = { navigate(Screen.PROOFREAD_FEATURE) },
    customButtons = { navigate(Screen.CUSTOM_BUTTONS) },
    typing =
        TypingLinks(
            clipboard = { navigate(Screen.CLIPBOARD) },
            emoji = { navigate(Screen.EMOJI) },
            suggestions = { navigate(Screen.SUGGESTIONS) },
            autocorrect = { navigate(Screen.AUTOCORRECT) },
            slideEngine = { navigate(Screen.SLIDE_ENGINE) },
            fullscreen = { navigate(Screen.FULLSCREEN) },
            deleteKey = { navigate(Screen.DELETE_KEY) },
        ),
    text =
        TextLinks(
            prompts = { navigate(Screen.PROMPTS) },
            servers = openServers,
            dictionary = { navigate(Screen.DICTIONARY) },
        ),
    app =
        AppLinks(
            diagnostics = { navigate(Screen.DIAGNOSTICS) },
            updates = { navigate(Screen.UPDATES) },
        ),
)

/** The pages without sub-pages: the fullscreen mode, the application log and the updates. */
@Composable
private fun StandalonePage(
    page: Screen,
    onBack: () -> Unit,
) {
    when (page) {
        Screen.APP_LOG -> AppLogScreen(onBack)
        Screen.UPDATES -> UpdatesScreen(onBack)
        else -> FullscreenScreen(onBack)
    }
}

/** The pages of typing and the emoji picker. */
@Composable
private fun TypingPage(
    page: Screen,
    navigate: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    when (page) {
        Screen.CLIPBOARD -> ClipboardScreen(onBack)
        Screen.EMOJI ->
            EmojiScreen(
                onOpenGroups = { navigate(Screen.EMOJI_GROUPS) },
                onOpenSingle = { navigate(Screen.EMOJI_SINGLE) },
                onBack = onBack,
            )
        Screen.EMOJI_GROUPS -> EmojiGroupsScreen(onBack)
        Screen.EMOJI_SINGLE -> EmojiSinglePickScreen(onBack)
        Screen.AUTOCORRECT -> AutocorrectScreen(onBack)
        Screen.SUGGESTIONS -> SuggestionsScreen(onBack)
        Screen.DELETE_KEY -> DeleteKeyScreen(onBack)
        else -> SlideEngineScreen(onBack)
    }
}

/** The pages of text improvement and the dictionary. */
@Composable
private fun EnhancementPage(
    page: Screen,
    state: VoiceSettingsState,
    openServers: () -> Unit,
    onBack: () -> Unit,
) {
    when (page) {
        Screen.DICTATION_FEATURE -> TextFeatureScreen(TextFeature.DICTATION, state, openServers, onBack)
        Screen.PROOFREAD_FEATURE -> TextFeatureScreen(TextFeature.PROOFREAD, state, openServers, onBack)
        Screen.PROMPTS -> PromptsScreen(state, onBack)
        Screen.DICTIONARY -> DictionaryScreen(state, onBack)
        else -> ServersScreen(state, onBack)
    }
}

/** The M3 shared axis X transition between a page and its sub-page. */
private fun sharedAxisX(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    val slide = tween<IntOffset>(SHARED_AXIS_MS, easing = FastOutSlowInEasing)
    val enter =
        slideInHorizontally(slide) { sign * it / SHARED_AXIS_SHIFT } +
            fadeIn(tween(SHARED_AXIS_FADE_MS, delayMillis = SHARED_AXIS_FADE_MS, easing = LinearOutSlowInEasing))
    val exit =
        slideOutHorizontally(slide) { -sign * it / SHARED_AXIS_SHIFT } +
            fadeOut(tween(SHARED_AXIS_FADE_MS, easing = FastOutLinearInEasing))
    return enter togetherWith exit
}

private const val SHARED_AXIS_MS = 300
private const val SHARED_AXIS_FADE_MS = 100

/** The pages move by a tenth of the width: enough to show the direction, not a full slide. */
private const val SHARED_AXIS_SHIFT = 10

/**
 * Material 3 in the wallpaper colors of Android 12+, following the system dark mode unless the
 * keyboard [theme] fixes light or dark. The theme accent only colors the icon badges.
 */
@Composable
internal fun SettingsTheme(
    theme: ThemeChoice = ThemeChoice(KeyboardThemes.find(KeyboardThemes.CLASSIC), ThemeMode.SYSTEM),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = settingsMode(theme.mode) ?: isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val view = LocalView.current
    if (!view.isInEditMode) {
        // The bars are transparent (edge to edge): their icons must contrast with the page, not the system mode.
        SideEffect {
            val controls = WindowCompat.getInsetsController((view.context as Activity).window, view)
            controls.isAppearanceLightStatusBars = !dark
            controls.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalBadgeColors provides badgeColors(theme.spec, context, dark)) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
