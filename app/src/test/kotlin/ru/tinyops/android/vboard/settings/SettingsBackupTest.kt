package ru.tinyops.android.vboard.settings

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.core.input.DeleteHoldMode
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.MicButton
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing
import ru.tinyops.android.vboard.core.voice.QuickReply
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.ThemeMode
import ru.tinyops.android.vboard.update.CheckInterval
import ru.tinyops.android.vboard.update.UpdatePrefs
import ru.tinyops.android.vboard.voice.VoicePrefs
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsBackupTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val exportedAt = OffsetDateTime.of(2026, 11, 8, 14, 5, 9, 0, ZoneOffset.ofHours(3))

    private fun prefs(file: String) = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    @Before
    fun clear() {
        SettingsBackup.FILES.keys.forEach { prefs(it).edit(commit = true) { clear() } }
    }

    private fun export(
        language: String = "",
        apiKeys: Boolean = true,
    ): String =
        SettingsBackup.encode(
            SettingsBackup.read(context, language, apiKeys),
            appVersion = "1.2.3",
            exportedAt = exportedAt,
        )

    private fun servers(vararg entries: Pair<String, String>): String =
        JSONArray(
            entries.map { (id, key) -> JSONObject().put("id", id).put("name", id).put("api_key", key) },
        ).toString()

    private fun storedKeys(): Map<String, String> {
        val array = JSONArray(prefs("voice").getString("servers", "[]"))
        return (0 until array.length()).associate {
            array.getJSONObject(it).getString("id") to array.getJSONObject(it).getString("api_key")
        }
    }

    @Test
    fun `only a server with a key asks about keys`() {
        assertFalse(SettingsBackup.hasApiKeys(context))
        prefs("voice").edit(commit = true) { putString("servers", servers("a" to "", "b" to "  ")) }
        assertFalse(SettingsBackup.hasApiKeys(context))
        prefs("voice").edit(commit = true) { putString("servers", servers("a" to "", "b" to "sk-b")) }
        assertTrue(SettingsBackup.hasApiKeys(context))
    }

    @Test
    fun `api keys stay out of the file unless the user agrees`() {
        prefs("voice").edit(commit = true) { putString("servers", servers("a" to "sk-secret", "b" to "")) }

        val without = export(apiKeys = false)
        assertFalse("sk-secret" in without)
        assertFalse(JSONObject(without).getBoolean("api_keys"))
        val names =
            JSONArray(
                SettingsBackup
                    .decode(without)
                    .preferences
                    .getValue("voice")
                    .getValue("servers") as String,
            )
        assertEquals("a", names.getJSONObject(0).getString("name"))

        val with = export(apiKeys = true)
        assertTrue("sk-secret" in with)
        assertTrue(JSONObject(with).getBoolean("api_keys"))
    }

    @Test
    fun `a file without keys keeps the keys of the same servers`() {
        prefs("voice").edit(commit = true) { putString("servers", servers("a" to "sk-a", "gone" to "sk-gone")) }
        val file = JSONObject(export(apiKeys = false))
        val voice = file.getJSONObject("preferences").getJSONObject("voice")
        voice.getJSONObject("servers").put("value", servers("a" to "", "new" to ""))

        SettingsBackup.write(context, SettingsBackup.decode(file.toString()))

        assertEquals(mapOf("a" to "sk-a", "new" to ""), storedKeys())
    }

    @Test
    fun `a file with keys replaces them`() {
        prefs("voice").edit(commit = true) { putString("servers", servers("a" to "sk-old")) }
        val file = JSONObject(export(apiKeys = true))
        file
            .getJSONObject("preferences")
            .getJSONObject("voice")
            .getJSONObject("servers")
            .put("value", servers("a" to ""))

        SettingsBackup.write(context, SettingsBackup.decode(file.toString()))

        assertEquals(mapOf("a" to ""), storedKeys())
    }

    @Test
    fun `a file that does not say counts as one with keys`() {
        val file = JSONObject(export(apiKeys = false))
        file.remove("api_keys")
        assertTrue(SettingsBackup.decode(file.toString()).apiKeysIncluded)
    }

    @Test
    fun `every preference file of the app is in the backup`() {
        val name = Regex("""const val (?:FILE|PREFS) = "([^"]+)"""")
        val opened = Regex("""getSharedPreferences\(\s*([^,]+),""")
        val sources =
            File("src/main/kotlin")
                .walk()
                .filter { it.extension == "kt" && it.name != "SettingsBackup.kt" }
                .map { it.readText() }
                .filter { "getSharedPreferences(" in it }
                .toList()
        // Any other way to name a file would slip past the list below.
        val names = sources.flatMap { source -> opened.findAll(source).map { it.groupValues[1].trim() } }.toSet()
        assertEquals(setOf("FILE", "PREFS"), names)
        val files = sources.flatMap { source -> name.findAll(source).map { it.groupValues[1] } }.toSet()
        assertTrue("no preference files found", files.isNotEmpty())
        assertEquals(files, SettingsBackup.FILES.keys)
    }

    @Test
    fun `the file name carries the date and the time`() {
        assertEquals(
            "vboard-settings-2026-11-08_14-05-09.json",
            SettingsBackup.fileName(LocalDateTime.of(2026, 11, 8, 14, 5, 9)),
        )
    }

    @Test
    fun `every kind of value survives the round trip`() {
        prefs("voice").edit(commit = true) {
            putBoolean("voice_enabled", false)
            putInt("auto_stop_minutes", 7)
            putLong("some_long", 1L shl 40)
            putFloat("some_float", 0.25f)
            putString("servers", "[{\"id\":\"a\",\"apiKey\":\"secret\"}]")
            putStringSet("some_set", setOf("x", "y"))
        }
        prefs("keyboard").edit(commit = true) { putString("theme", "dark") }
        val text = export()
        clear()

        SettingsBackup.write(context, SettingsBackup.decode(text))

        val voice = prefs("voice")
        assertFalse(voice.getBoolean("voice_enabled", true))
        assertEquals(7, voice.getInt("auto_stop_minutes", 0))
        assertEquals(1L shl 40, voice.getLong("some_long", 0))
        assertEquals(0.25f, voice.getFloat("some_float", 0f))
        assertEquals("[{\"id\":\"a\",\"apiKey\":\"secret\"}]", voice.getString("servers", null))
        assertEquals(setOf("x", "y"), voice.getStringSet("some_set", null))
        assertEquals("dark", prefs("keyboard").getString("theme", null))
    }

    @Test
    fun `the settings of every screen survive the round trip`() {
        val voice = VoicePrefs(context)
        val reply = QuickReply(voice.quickReplies.newId(), "On my way")
        voice.quickReplies.save(reply)
        val button =
            voice.customButtons.add().copy(
                tap = ButtonCommand(ButtonAction.QUICK_REPLIES, replyIds = listOf(reply.id)),
                name = "Replies",
            )
        voice.customButtons.save(button)
        val micButton =
            MicButton(
                tap = MicMode(MicButton.TAP_ID, MicProcessing.NONE, ending = MicEnding.REPEAT_TAP),
                hold = listOf(MicMode("english", MicProcessing.PROMPTS, listOf("p"), MicEnding.AFTER_PAUSE)),
            )
        voice.micButton.button = micButton
        KeyboardPrefs(context).deleteHold = DeleteHoldMode.WORDS
        KeyboardThemes.select(context, KeyboardThemes.MINT)
        KeyboardThemes.selectMode(context, ThemeMode.entries.last())
        KeyboardThemes.setEnterOnly(context, true)
        KeyboardThemes.setContrastIcons(context, night = true, on = true)
        KeyboardThemes.setSquareModeKey(context, true)
        KeyboardThemes.setSquareActionKey(context, true)
        UpdatePrefs(context).apply {
            autoCheck = true
            interval = CheckInterval.WEEKLY
        }
        val replies = voice.quickReplies.replies
        val theme = KeyboardThemes.selected(context)
        val text = export()
        clear()

        SettingsBackup.write(context, SettingsBackup.decode(text))

        val restored = VoicePrefs(context)
        assertEquals(replies, restored.quickReplies.replies)
        assertEquals(listOf(reply), restored.quickReplies.choice(button.tap!!))
        assertEquals(listOf(button), restored.customButtons.buttons)
        assertEquals(micButton, restored.micButton.button)
        assertEquals(DeleteHoldMode.WORDS, KeyboardPrefs(context).deleteHold)
        assertEquals(theme, KeyboardThemes.selected(context))
        assertTrue(theme.enterOnly && theme.whiteIcons && theme.shapes.squareMode && theme.shapes.squareAction)
        assertEquals(listOf(KeyboardThemes.MINT), KeyboardThemes.recent(context).map { it.id })
        assertTrue(UpdatePrefs(context).autoCheck)
        assertEquals(CheckInterval.WEEKLY, UpdatePrefs(context).interval)
    }

    @Test
    fun `an emptied list of quick replies stays empty after an import`() {
        val replies = VoicePrefs(context).quickReplies
        replies.replies.forEach { replies.delete(it.id) }
        val text = export()
        clear()

        SettingsBackup.write(context, SettingsBackup.decode(text))

        assertEquals(emptyList<QuickReply>(), VoicePrefs(context).quickReplies.replies)
    }

    @Test
    fun `the update check of this phone stays out of the file`() {
        prefs("updates").edit(commit = true) {
            putBoolean("auto_check", true)
            putString("latest_release", "{}")
            putLong("last_check", 42)
            putString("dismissed_version", "9.9.9")
        }
        val updates = JSONObject(export()).getJSONObject("preferences").getJSONObject("updates")
        assertEquals(setOf("auto_check"), updates.keys().asSequence().toSet())
    }

    @Test
    fun `the export names the format, the version and the time`() {
        val json = JSONObject(export(language = "ru"))
        assertEquals("vboard-settings", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals("1.2.3", json.getString("app_version"))
        assertEquals("2026-11-08T14:05:09+03:00", json.getString("exported_at"))
        assertEquals("ru", json.getString("app_language"))
    }

    @Test
    fun `import replaces the settings instead of merging them`() {
        prefs("clipboard_prefs").edit(commit = true) { putBoolean("enabled", false) }
        val text = export()
        prefs("clipboard_prefs").edit(commit = true) { putString("icon_color", "red") }

        SettingsBackup.write(context, SettingsBackup.decode(text))

        assertFalse(prefs("clipboard_prefs").contains("icon_color"))
        assertFalse(prefs("clipboard_prefs").getBoolean("enabled", true))
    }

    @Test
    fun `history and crash markers stay on the phone`() {
        prefs("emoji_prefs").edit(commit = true) {
            putString("recents", "A")
            putString("pins", "P")
        }
        prefs("keyboard_prefs").edit(commit = true) { putBoolean("neural_model_loading", true) }
        val json = JSONObject(export())
        val preferences = json.getJSONObject("preferences")
        assertFalse(preferences.getJSONObject("emoji_prefs").has("recents"))
        assertFalse(preferences.getJSONObject("keyboard_prefs").has("neural_model_loading"))

        prefs("emoji_prefs").edit(commit = true) { putString("recents", "B") }
        SettingsBackup.write(context, SettingsBackup.decode(json.toString()))

        assertEquals("B", prefs("emoji_prefs").getString("recents", null))
        assertEquals("P", prefs("emoji_prefs").getString("pins", null))
        assertTrue(prefs("keyboard_prefs").getBoolean("neural_model_loading", false))
    }

    @Test
    fun `unknown preference files are ignored`() {
        val json = JSONObject(export())
        json.getJSONObject("preferences").put(
            "foreign",
            JSONObject().put("k", JSONObject().put("type", "string").put("value", "v")),
        )
        SettingsBackup.write(context, SettingsBackup.decode(json.toString()))
        assertFalse(prefs("foreign").contains("k"))
    }

    @Test
    fun `the decoded backup tells when and by which version it was made`() {
        val backup = SettingsBackup.decode(export(language = "en"))
        assertEquals(exportedAt, backup.exportedAt)
        assertEquals("1.2.3", backup.appVersion)
        assertEquals("en", backup.language)
    }

    @Test
    fun `an export without a language keeps the current one`() {
        val json = JSONObject(export())
        json.remove("app_language")
        assertNull(SettingsBackup.decode(json.toString()).language)
    }

    @Test
    fun `broken and foreign files are rejected`() {
        val valid = JSONObject(export())
        val broken =
            listOf(
                "not json",
                "[]",
                JSONObject(valid.toString()).put("format", "other").toString(),
                JSONObject(valid.toString()).put("version", 2).toString(),
                JSONObject(valid.toString()).apply { remove("preferences") }.toString(),
                JSONObject(valid.toString())
                    .apply {
                        getJSONObject("preferences").put(
                            "voice",
                            JSONObject().put("k", JSONObject().put("type", "blob").put("value", 1)),
                        )
                    }.toString(),
                JSONObject(valid.toString())
                    .apply {
                        getJSONObject("preferences").put(
                            "voice",
                            JSONObject().put("k", JSONObject().put("type", "boolean").put("value", "yes")),
                        )
                    }.toString(),
            )
        broken.forEach { text ->
            assertThrows(text, SettingsBackup.InvalidBackup::class.java) { SettingsBackup.decode(text) }
        }
    }
}
