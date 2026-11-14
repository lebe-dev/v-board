package ru.tinyops.android.vboard.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import ru.tinyops.android.vboard.core.prefs.ParseCache
import ru.tinyops.android.vboard.core.voice.AutoStop
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.EnhancementThreshold
import ru.tinyops.android.vboard.core.voice.FeatureConnection
import ru.tinyops.android.vboard.core.voice.PauseLength
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.RecognitionEngine
import ru.tinyops.android.vboard.core.voice.RecordingMode
import ru.tinyops.android.vboard.core.voice.RemoteRecognitionServer
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.ServerProfile
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.core.voice.VoiceAnimation
import ru.tinyops.android.vboard.core.voice.VoiceDictionary
import ru.tinyops.android.vboard.core.voice.find
import ru.tinyops.android.vboard.core.voice.promptPlace
import ru.tinyops.android.vboard.core.voice.withProfile
import ru.tinyops.android.vboard.core.voice.withoutProfile
import java.util.UUID

/** Voice input settings, shared by the keyboard and the settings screen (same process). */
class VoicePrefs(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The collection of prompts and the choice of every place that uses them. */
    val prompts = PromptStore(context, prefs)

    private val parsedServers = ParseCache(ServerProfileJson::parse)

    /** The custom buttons of the strip. */
    val customButtons = CustomButtonStore(prefs)

    /** The phrases quick replies buttons insert. */
    val quickReplies = QuickReplyStore(context, prefs)

    /** What a tap and a hold on the microphone do. */
    val micButton = MicButtonStore(prefs)

    /** False hides the microphone on the strip; dictation is on until the user switches it off. */
    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_VOICE_ENABLED, value) }

    /** How the strip looks while dictating. */
    var voiceAnimation: VoiceAnimation
        get() = VoiceAnimation.fromId(prefs.getString(KEY_VOICE_ANIMATION, null))
        set(value) = prefs.edit { putString(KEY_VOICE_ANIMATION, value.id) }

    /** The color of the microphone icon on the strip; null keeps it like the other icons. */
    var micColor: ButtonColor?
        get() = ButtonColor.fromId(prefs.getString(KEY_MIC_COLOR, null))
        set(value) = prefs.edit { putString(KEY_MIC_COLOR, value?.id) }

    /** The color of the proofread icon on the strip; null keeps it like the other icons. */
    var proofreadColor: ButtonColor?
        get() = ButtonColor.fromId(prefs.getString(KEY_PROOFREAD_COLOR, null))
        set(value) = prefs.edit { putString(KEY_PROOFREAD_COLOR, value?.id) }

    /** The system recognizer until the user switches to Govorun in the settings. */
    var engine: RecognitionEngine
        get() = RecognitionEngine.fromId(prefs.getString(KEY_ENGINE, null))
        set(value) = prefs.edit { putString(KEY_ENGINE, value.id) }

    /** "Гарнитура, если подключена"; Govorun follows a connected headset unless the phone was chosen. */
    var headsetMicrophone: Boolean
        get() = prefs.getBoolean(KEY_HEADSET, true)
        set(value) = prefs.edit { putBoolean(KEY_HEADSET, value) }

    var pause: PauseLength
        get() = PauseLength.fromId(prefs.getString(KEY_PAUSE, null))
        set(value) = prefs.edit { putString(KEY_PAUSE, value.id) }

    var recordingMode: RecordingMode
        get() = RecordingMode.read(prefs.getString(KEY_RECORDING_MODE, null))
        set(value) = prefs.edit { putString(KEY_RECORDING_MODE, value.id) }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    var autoStop: AutoStop
        get() = AutoStop.fromMinutes(prefs.getInt(KEY_AUTO_STOP, AutoStop.DEFAULT.minutes))
        set(value) = prefs.edit { putInt(KEY_AUTO_STOP, value.minutes) }

    var dictionaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_DICTIONARY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_DICTIONARY_ENABLED, value) }

    var dictionaryText: String
        get() = prefs.getString(KEY_DICTIONARY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_DICTIONARY, value) }

    /** The dictionary to apply, or an empty one when replacements are off. */
    fun dictionary(): VoiceDictionary =
        if (dictionaryEnabled) VoiceDictionary.parse(dictionaryText) else VoiceDictionary.EMPTY

    /** "Улучшение текста" works on recognized text and does not depend on how it was recognized. */
    var enhancementEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENHANCEMENT_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENHANCEMENT_ENABLED, value) }

    /**
     * Keep what the prompts return in a buffer of its own next to the clipboard history: the raw and
     * the improved text of every dictation, the answers of proofreading and of the custom buttons.
     */
    var promptResultsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_RESULTS, false)
        set(value) = prefs.edit { putBoolean(KEY_PROMPT_RESULTS, value) }

    /** Shorter text is inserted without the server. */
    var enhancementThreshold: EnhancementThreshold
        get() =
            EnhancementThreshold.fromCharacters(
                prefs.getInt(KEY_ENHANCEMENT_THRESHOLD, EnhancementThreshold.DEFAULT.characters),
            )
        set(value) = prefs.edit { putInt(KEY_ENHANCEMENT_THRESHOLD, value.characters) }

    /** The ready-made servers; text improvement and recognition on a server choose from them. */
    var servers: List<ServerProfile>
        get() = parsedServers(prefs.getString(KEY_SERVERS, null))
        set(value) = prefs.edit { putString(KEY_SERVERS, ServerProfileJson.serialize(value)) }

    /** A new id for a server that is about to be created. */
    fun newServerId(): String = UUID.randomUUID().toString()

    /** Adds the server, or replaces the one with the same id. */
    fun saveServer(profile: ServerProfile) {
        servers = servers.withProfile(profile)
    }

    /**
     * The server of [kind] that functions without a choice of their own use: the one the user marked, or
     * else the first of its kind. Null when there is no server of that kind.
     */
    fun defaultServerId(kind: ServerKind): String? {
        val ofKind = servers.filter { it.kind == kind }
        val marked = prefs.getString(defaultKey(kind), null)
        return (ofKind.firstOrNull { it.id == marked } ?: ofKind.firstOrNull())?.id
    }

    fun setDefaultServer(
        kind: ServerKind,
        id: String,
    ) {
        if (servers.none { it.id == id && it.kind == kind }) return
        prefs.edit { putString(defaultKey(kind), id) }
    }

    private fun defaultKey(kind: ServerKind) = "default_server_${kind.id}"

    /**
     * Deletes the server. What chose it moves to the default server of the same kind; when there is no
     * other server of that kind, the functions that need one are turned off. Returns whether any
     * function was turned off, so the user can be told to add a server.
     */
    fun deleteServer(id: String): Boolean {
        val kind = servers.find(id)?.kind ?: return false
        val wasDefault = defaultServerId(kind) == id
        servers = servers.withoutProfile(id)
        val replacement = defaultServerId(kind)
        var turnedOff = false
        prefs.edit {
            if (wasDefault) {
                if (replacement == null) remove(defaultKey(kind)) else putString(defaultKey(kind), replacement)
            }
            if (kind == ServerKind.TEXT) {
                TextFeature.entries
                    .filter { prefs.getString(featureServerKey(it), null) == id }
                    .forEach { remove(featureServerKey(it)) }
                if (replacement == null) {
                    TextFeature.entries.filter(::featureEnabled).forEach {
                        putBoolean(enabledKey(it), false)
                        turnedOff = true
                    }
                }
            } else {
                if (prefs.getString(KEY_REMOTE_SERVER, null) == id) remove(KEY_REMOTE_SERVER)
                if (replacement == null && remoteEnabled) {
                    putBoolean(KEY_REMOTE_ENABLED, false)
                    turnedOff = true
                }
            }
        }
        return turnedOff
    }

    /** Text improvement runs for this dictation: on, with a complete server chosen. */
    val enhancementReady: Boolean get() = featureReady(TextFeature.DICTATION)

    fun featureEnabled(feature: TextFeature): Boolean = prefs.getBoolean(enabledKey(feature), false)

    fun setFeatureEnabled(
        feature: TextFeature,
        enabled: Boolean,
    ) = prefs.edit { putBoolean(enabledKey(feature), enabled) }

    private fun enabledKey(feature: TextFeature): String =
        if (feature == TextFeature.DICTATION) KEY_ENHANCEMENT_ENABLED else featureKey(feature, "enabled")

    /** The server [feature] has chosen itself; null when it follows the default. */
    fun chosenServerId(feature: TextFeature): String? =
        prefs.getString(featureServerKey(feature), null)?.takeIf { id ->
            servers.any {
                it.id == id &&
                    it.kind == ServerKind.TEXT
            }
        }

    /** The id of the server [feature] sends text to: its own choice or the default; null when none. */
    fun featureServerId(feature: TextFeature): String? = chosenServerId(feature) ?: defaultServerId(ServerKind.TEXT)

    /** Chooses the server of [feature]; null makes it follow the default server. */
    fun setFeatureServer(
        feature: TextFeature,
        id: String?,
    ) = prefs.edit {
        if (id == null) {
            remove(featureServerKey(feature))
            if (defaultServerId(ServerKind.TEXT) == null) putBoolean(enabledKey(feature), false)
        } else {
            putString(featureServerKey(feature), id)
        }
    }

    private fun featureServerKey(feature: TextFeature) = featureKey(feature, "server")

    /**
     * The server, prompt and level of effort the requests of [feature] go out with. For a [held] proofread
     * button the prompt is the one chosen for the hold.
     */
    fun connection(
        feature: TextFeature,
        held: Boolean = false,
    ): FeatureConnection {
        val profile = servers.find(featureServerId(feature)) ?: ServerProfile(id = "")
        val place = if (held) PromptPlace.PROOFREAD_HOLD else feature.promptPlace
        val prompt = prompts.ids(place)?.let { prompts.library.compose(it) }
        return FeatureConnection(
            server =
                profile.forEnhancement(
                    prompt?.takeIf(String::isNotBlank) ?: prompts.text(PromptPlace.ENHANCEMENT),
                ),
            effort = profile.effort,
        )
    }

    /** The server, prompt and level of effort of a prompt [command]; a deleted server means the default one. */
    fun commandConnection(command: ButtonCommand): FeatureConnection {
        val chosen = servers.find(command.serverId)?.takeIf { it.kind == ServerKind.TEXT }
        val profile = chosen ?: servers.find(defaultServerId(ServerKind.TEXT)) ?: ServerProfile(id = "")
        return FeatureConnection(
            server = profile.forEnhancement(prompts.library.compose(command.promptIds)),
            effort = profile.effort,
        )
    }

    /**
     * The command can act: a prompt needs a server and a prompt text, and one that asks for a prompt
     * needs one in the collection to offer; quick replies need a chosen reply that still exists.
     */
    fun commandReady(command: ButtonCommand): Boolean {
        if (command.action == ButtonAction.QUICK_REPLIES) return quickReplies.choice(command).isNotEmpty()
        if (command.action != ButtonAction.PROMPT) return true
        if (command.pickPrompt) {
            val offered = prompts.library.pickable.firstOrNull() ?: return false
            return commandConnection(command.withPrompt(offered.id)).server.isComplete
        }
        val hasPrompt = prompts.library.compose(command.promptIds).isNotBlank()
        return hasPrompt && commandConnection(command).server.isComplete
    }

    /**
     * The [button] with only the commands that can act, which may be none; null when it is turned off.
     * Quick replies open under the button on a tap, so a hold never offers them.
     */
    fun workingButton(button: CustomButton): CustomButton? {
        if (!button.enabled) return null
        val hold = button.hold?.takeIf { it.action != ButtonAction.QUICK_REPLIES && commandReady(it) }
        return button.copy(tap = button.tap?.takeIf(::commandReady), hold = hold)
    }

    /** A hold on the proofread button acts: a prompt is chosen for it and still exists. */
    val proofreadHoldReady: Boolean
        get() = prompts.text(PromptPlace.PROOFREAD_HOLD).isNotBlank()

    /** The prompts [feature] sends: its own choice, or the built-in one until it is made. */
    fun chosenPrompts(feature: TextFeature): List<String> =
        prompts.ids(feature.promptPlace) ?: prompts.ids(PromptPlace.ENHANCEMENT).orEmpty()

    /** A server is chosen and everything needed for a request is filled in, whether or not the feature is on. */
    fun featureConfigured(feature: TextFeature): Boolean {
        val profile = servers.find(featureServerId(feature)) ?: return false
        return profile.isComplete && connection(feature).server.isComplete
    }

    /** [feature] sends text: on and configured. */
    fun featureReady(feature: TextFeature): Boolean = featureEnabled(feature) && featureConfigured(feature)

    private fun featureKey(
        feature: TextFeature,
        name: String,
    ) = "feature_${feature.id}_$name"

    /** "Распознавание на сервере"; off by default, it can be turned on only with a complete server chosen. */
    var remoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_REMOTE_ENABLED, value) }

    /** The recognition server chosen by the user; null when recognition follows the default server. */
    val chosenRecognitionServerId: String?
        get() =
            prefs.getString(KEY_REMOTE_SERVER, null)?.takeIf { id ->
                servers.any {
                    it.id == id &&
                        it.kind == ServerKind.AUDIO
                }
            }

    /** The id of the server speech is recognized on: the chosen one or the default; null when none. */
    var recognitionServerId: String?
        get() = chosenRecognitionServerId ?: defaultServerId(ServerKind.AUDIO)
        set(value) =
            prefs.edit {
                if (value == null) {
                    remove(KEY_REMOTE_SERVER)
                    if (defaultServerId(ServerKind.AUDIO) == null) putBoolean(KEY_REMOTE_ENABLED, false)
                } else {
                    putString(KEY_REMOTE_SERVER, value)
                }
            }

    /** ISO-639-1 hint for the recognition model; empty lets the server detect the language. */
    var remoteLanguage: String
        get() = prefs.getString(KEY_REMOTE_LANGUAGE, null) ?: RemoteRecognitionServer.DEFAULT_LANGUAGE
        set(value) = prefs.edit { putString(KEY_REMOTE_LANGUAGE, value.trim().lowercase()) }

    /** Whether the prompt goes with every phrase; off sends the voice alone. */
    var remotePromptEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_PROMPT_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_REMOTE_PROMPT_ENABLED, value) }

    /** The chosen recognition server with the language and the prompt; empty when no server is chosen. */
    val remoteServer: RemoteRecognitionServer
        get() {
            val profile = servers.find(recognitionServerId) ?: ServerProfile(id = "")
            return profile.forRecognition(remoteLanguage, remotePromptEnabled, prompts.text(PromptPlace.RECOGNITION))
        }

    /** Dictation goes to the recognition server: on, with a complete server chosen. */
    val remoteReady: Boolean
        get() = remoteEnabled && servers.find(recognitionServerId) != null && remoteServer.isComplete

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    internal companion object {
        const val FILE = "voice"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_VOICE_ANIMATION = "voice_animation"
        const val KEY_MIC_COLOR = "mic_color"
        const val KEY_PROOFREAD_COLOR = "proofread_color"
        const val KEY_ENGINE = "engine"
        const val KEY_HEADSET = "headset_microphone"
        const val KEY_PAUSE = "pause"
        const val KEY_RECORDING_MODE = "recording_mode"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_STOP = "auto_stop_minutes"
        const val KEY_DICTIONARY_ENABLED = "dictionary_enabled"
        const val KEY_DICTIONARY = "dictionary"
        const val KEY_ENHANCEMENT_ENABLED = "enhancement_enabled"
        const val KEY_ENHANCEMENT_THRESHOLD = "enhancement_threshold_characters"
        const val KEY_PROMPT_RESULTS = "prompt_results_enabled"
        const val KEY_SERVERS = "servers"
        const val KEY_REMOTE_ENABLED = "remote_enabled"
        const val KEY_REMOTE_SERVER = "remote_server"
        const val KEY_REMOTE_LANGUAGE = "remote_language"
        const val KEY_REMOTE_PROMPT_ENABLED = "remote_prompt_enabled"
    }
}
