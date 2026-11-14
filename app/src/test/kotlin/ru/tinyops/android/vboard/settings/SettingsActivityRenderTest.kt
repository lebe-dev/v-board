package ru.tinyops.android.vboard.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.clipboard.ClipboardPrefs
import ru.tinyops.android.vboard.core.emoji.EmojiHidden
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.MicButton
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing
import ru.tinyops.android.vboard.core.voice.ServerProfile
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.emoji.EmojiPrefs
import ru.tinyops.android.vboard.ui.ButtonIcons
import ru.tinyops.android.vboard.voice.VoicePrefs
import java.io.File

/** Opens the settings pages and writes `app/build/screenshots/settings-*.png` for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1600dp-420dpi")
class SettingsActivityRenderTest {
    private fun render(
        root: View,
        name: String,
    ) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun open(screen: String): Intent =
        Intent(
            RuntimeEnvironment.getApplication(),
            SettingsActivity::class.java,
        ).putExtra(SettingsActivity.EXTRA_SCREEN, screen)

    @Test
    fun `theme picker renders`() {
        Robolectric.buildActivity(SettingsActivity::class.java, open("THEME")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-theme-light")
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun `theme picker renders in the dark theme`() {
        Robolectric.buildActivity(SettingsActivity::class.java, open("THEME")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-theme-dark")
        }
    }

    @Test
    fun `voice settings render`() {
        Robolectric.buildActivity(SettingsActivity::class.java, open("VOICE")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-voice-light")
        }
    }

    @Test
    fun `the microphone button page renders with a mode the menu hides`() {
        VoicePrefs(RuntimeEnvironment.getApplication()).micButton.button =
            MicButton(
                tap = MicMode(MicButton.TAP_ID, MicProcessing.PROMPTS, ending = MicEnding.REPEAT_TAP),
                hold =
                    listOf(
                        MicMode("plain", MicProcessing.NONE, ending = MicEnding.AFTER_PAUSE),
                        MicMode("improved", MicProcessing.ENHANCE),
                    ),
            )
        Robolectric.buildActivity(SettingsActivity::class.java, open("MIC_BUTTON")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-mic-button-light")
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun `the microphone button page renders in the dark theme with the default modes`() {
        Robolectric.buildActivity(SettingsActivity::class.java, open("MIC_BUTTON")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-mic-button-dark")
        }
    }

    @Test
    fun `the fullscreen editor is off by default`() {
        assertFalse(KeyboardPrefs(RuntimeEnvironment.getApplication()).fullscreenEnabled)
    }

    @Test
    fun `the clipboard is on by default and only the switch stays when it is off`() {
        val prefs = ClipboardPrefs(RuntimeEnvironment.getApplication())
        assertTrue(prefs.enabled)
        assertEquals(null, prefs.iconColor)
        Robolectric.buildActivity(SettingsActivity::class.java, open("CLIPBOARD")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-clipboard-on")
        }
        prefs.enabled = false
        Robolectric.buildActivity(SettingsActivity::class.java, open("CLIPBOARD")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-clipboard-off")
        }
    }

    @Test
    fun `a switched off clipboard neither records nor shows chips`() {
        val prefs = ClipboardPrefs(RuntimeEnvironment.getApplication())
        assertTrue(prefs.recording && prefs.chipsShown)
        prefs.enabled = false
        assertFalse(prefs.recording || prefs.chipsShown)
        assertTrue("what the user chose is kept", prefs.keepHistory && prefs.showOnStrip)
    }

    @Test
    fun `the fullscreen editor page renders`() {
        KeyboardPrefs(RuntimeEnvironment.getApplication()).apply {
            fullscreenEnabled = true
            fullscreenColor = ButtonColor.TEAL
        }
        Robolectric.buildActivity(SettingsActivity::class.java, open("FULLSCREEN")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-fullscreen")
        }
    }

    @Test
    @Config(qualifiers = "+h2200dp")
    fun `main settings render with the fullscreen editor on`() {
        KeyboardPrefs(RuntimeEnvironment.getApplication()).fullscreenEnabled = true
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-main-fullscreen")
        }
    }

    @Test
    @Config(qualifiers = "+h3200dp")
    fun `main settings end with the backup and the app`() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-main-backup")
        }
    }

    @Test
    fun `main settings render`() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            val root = controller.get().window.decorView
            assertTrue(root.width > 0 && root.height > 0)
            render(root, "settings-main-light")
        }
    }

    @Test
    @Config(qualifiers = "+night")
    fun `main settings render in the dark theme`() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-main-dark")
        }
    }

    private fun renderPage(
        name: String,
        page: @Composable (VoiceSettingsState) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        // The theme of SettingsActivity: no action bar above the page.
        controller.get().setTheme(R.style.Theme_Settings)
        controller.setup().use {
            val activity = controller.get()
            val state = VoiceSettingsState(VoicePrefs(activity))
            activity.setContent { SettingsTheme { page(state) } }
            ShadowLooper.idleMainLooper()
            val root = activity.window.decorView
            assertTrue(root.width > 0 && root.height > 0)
            render(root, name)
        }
    }

    private fun connectServer() {
        val prefs = VoicePrefs(RuntimeEnvironment.getApplication())
        prefs.saveServer(ServerProfile("main", "Main", "https://server.example/v1", "gpt-4o-mini"))
        TextFeature.entries.forEach { prefs.setFeatureServer(it, "main") }
        prefs.recognitionServerId = "main"
    }

    @Test
    fun `main page renders`() {
        connectServer()
        Robolectric.buildActivity(SettingsActivity::class.java, open("MAIN")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-main-light")
        }
    }

    @Test
    fun `proofreading page renders`() {
        connectServer()
        VoicePrefs(RuntimeEnvironment.getApplication()).setFeatureEnabled(TextFeature.PROOFREAD, true)
        Robolectric.buildActivity(SettingsActivity::class.java, open("PROOFREAD_FEATURE")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-proofread-light")
        }
    }

    @Test
    fun `proofreading page without a server renders`() {
        Robolectric.buildActivity(SettingsActivity::class.java, open("PROOFREAD_FEATURE")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-proofread-empty-light")
        }
    }

    @Test
    fun `dictation page renders`() {
        connectServer()
        Robolectric.buildActivity(SettingsActivity::class.java, open("DICTATION_FEATURE")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-dictation-light")
        }
    }

    private fun addButtonsOfBothRows(prefs: VoicePrefs) {
        val store = prefs.customButtons
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.CLEAR), name = "Keyboard one"))
        store.save(
            store.add(ButtonVisibility.FULLSCREEN).copy(tap = ButtonCommand(ButtonAction.COPY), name = "Panel one"),
        )
        store.save(
            store.add(ButtonVisibility.FULLSCREEN).copy(
                tap = ButtonCommand(ButtonAction.CLEAR),
                placement = ButtonPlacement.RIGHT,
                name = "Panel two",
            ),
        )
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.COPY), name = "Turned off", enabled = false))
        store.save(store.add().copy(name = "No action"))
    }

    @Test
    fun `custom buttons page renders both rows with the fullscreen mode on`() {
        val context = RuntimeEnvironment.getApplication()
        KeyboardPrefs(context).fullscreenEnabled = true
        addButtonsOfBothRows(VoicePrefs(context))
        renderPage("settings-custom-buttons-both") { CustomButtonsScreen(it, onOpen = {}, onBack = {}) }
    }

    @Test
    fun `custom buttons page renders with the fullscreen mode off`() {
        addButtonsOfBothRows(VoicePrefs(RuntimeEnvironment.getApplication()))
        renderPage("settings-custom-buttons-fullscreen-off") { CustomButtonsScreen(it, onOpen = {}, onBack = {}) }
    }

    @Test
    fun `custom buttons page renders`() {
        val prefs = VoicePrefs(RuntimeEnvironment.getApplication())
        prefs.customButtons.add()
        prefs.customButtons.save(
            prefs.customButtons.add().copy(
                tap = ButtonCommand(ButtonAction.CLEAR),
                hold = ButtonCommand(ButtonAction.COPY),
                color = ButtonColor.RED,
                name = "Очистка",
            ),
        )
        ButtonPlacement.entries.forEach { placement ->
            prefs.customButtons.save(
                prefs.customButtons.add().copy(
                    tap = ButtonCommand(ButtonAction.COPY),
                    icon = "star",
                    color = ButtonColor.BLUE,
                    placement = placement,
                ),
            )
        }
        prefs.customButtons.save(
            prefs.customButtons.add().copy(tap = ButtonCommand(ButtonAction.CLEAR), placement = ButtonPlacement.RIGHT),
        )
        renderPage("settings-custom-buttons") { CustomButtonsScreen(it, onOpen = {}, onBack = {}) }
    }

    @Test
    fun `prompt button page renders`() {
        connectServer()
        val store = VoicePrefs(RuntimeEnvironment.getApplication()).customButtons
        val id = store.add().id
        store.save(
            CustomButton(
                id,
                tap = ButtonCommand(ButtonAction.PROMPT),
                hold = ButtonCommand(ButtonAction.COPY),
                color = ButtonColor.GREEN,
            ),
        )
        renderPage(
            "settings-custom-button",
        ) { CustomButtonScreen(id, it, onOpenServers = {}, onOpen = {}, onBack = {}) }
    }

    @Test
    fun `every icon of a custom button can be drawn`() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(ButtonIcons.all.size >= 75)
        assertEquals(
            ButtonIcons.all.size,
            ButtonIcons.all
                .map { it.first }
                .toSet()
                .size,
        )
        ButtonIcons.all.forEach { (_, icon) -> assertNotNull(context.getDrawable(icon)) }
    }

    @Test
    fun `slide to type engine page renders`() {
        Robolectric.buildActivity(SettingsActivity::class.java, open("SLIDE_ENGINE")).setup().use { controller ->
            ShadowLooper.idleMainLooper()
            render(controller.get().window.decorView, "settings-slide-engine")
        }
    }

    @Test
    fun `dictionary page renders`() = renderPage("settings-dictionary") { DictionaryScreen(it, onBack = {}) }

    @Test
    fun `servers page renders`() {
        connectServer()
        renderPage("settings-servers") { ServersScreen(it, onBack = {}) }
    }

    @Test
    fun `servers page without servers renders`() =
        renderPage("settings-servers-empty") { ServersScreen(it, onBack = {}) }

    @Test
    @Config(qualifiers = "+night")
    fun `remote recognition page renders in the dark theme`() =
        renderPage("settings-remote-dark") { RemoteRecognitionScreen(it, onOpenServers = {}, onBack = {}) }

    @Test
    fun `remote recognition page renders the prompt field`() {
        val prefs = VoicePrefs(RuntimeEnvironment.getApplication())
        prefs.remotePromptEnabled = true
        renderPage("settings-remote-prompt") { RemoteRecognitionScreen(it, onOpenServers = {}, onBack = {}) }
    }

    @Test
    fun `prompts page renders`() = renderPage("settings-prompts") { PromptsScreen(it, onBack = {}) }

    @Test
    fun `emoji page renders`() =
        renderPage("settings-emoji") {
            EmojiScreen(onOpenGroups = {}, onOpenSingle = {}, onBack = {})
        }

    @Test
    @Config(qualifiers = "+night")
    fun `emoji subcategories page renders`() {
        val prefs = EmojiPrefs(RuntimeEnvironment.getApplication())
        prefs.hidden = EmojiHidden(subcategories = setOf("sports"))
        renderPage("settings-emoji-groups-dark") { EmojiGroupsScreen(onBack = {}) }
    }

    @Test
    @Config(qualifiers = "+night")
    fun `page for removing single emoji renders`() {
        val prefs = EmojiPrefs(RuntimeEnvironment.getApplication())
        prefs.hidden = EmojiHidden().toggleEmoji("😀").toggleEmoji("😍")
        renderPage("settings-emoji-single-dark") { EmojiSinglePickScreen(onBack = {}) }
    }
}
