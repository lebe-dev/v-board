package ru.tinyops.android.vboard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.KeyboardService
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.clipboard.ClipChip
import ru.tinyops.android.vboard.core.clipboard.ClipKind
import ru.tinyops.android.vboard.core.input.SuggestionTimeout
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.voice.ProofreadState
import ru.tinyops.android.vboard.voice.VoicePrefs
import java.io.File

/** Checks which buttons the keyboard puts on the strip and writes `app/build/screenshots/strip-*.png`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class CustomButtonsStripTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun findStrip(view: View): SuggestionStripView? {
        if (view is SuggestionStripView) return view
        if (view !is ViewGroup) return null
        return (0 until view.childCount).firstNotNullOfOrNull { findStrip(view.getChildAt(it)) }
    }

    private fun stripIn(
        inputType: Int,
        cursor: Int,
    ): SuggestionStripView {
        val service = Robolectric.buildService(KeyboardService::class.java).create().get()
        service.setInputView(service.onCreateInputView())
        service.onStartInputView(
            EditorInfo().apply {
                this.inputType = inputType
                initialSelEnd = cursor
            },
            false,
        )
        return requireNotNull(findStrip(service.window.window!!.decorView))
    }

    private fun addClearButton() {
        val store = VoicePrefs(context).customButtons
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.CLEAR)))
    }

    @Test
    fun `a field with text shows the buttons that are set up`() {
        addClearButton()
        val strip = stripIn(InputType.TYPE_CLASS_TEXT, cursor = 3)
        assertEquals(listOf(R.drawable.ic_broom), strip.customButtons.map { it.icon })
    }

    @Test
    fun `a button without an action is shown all the same`() {
        val store = VoicePrefs(context).customButtons
        val added = store.add()
        val strip = stripIn(InputType.TYPE_CLASS_TEXT, cursor = 3)
        assertEquals(listOf(added.id), strip.customButtons.map { it.id })
    }

    @Test
    fun `a turned off button is not shown`() {
        val store = VoicePrefs(context).customButtons
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.COPY), enabled = false))
        store.save(store.add().copy(enabled = false))
        assertEquals(emptyList<StripButton>(), stripIn(InputType.TYPE_CLASS_TEXT, cursor = 3).customButtons)
    }

    @Test
    fun `an empty field shows only the buttons meant to be always there`() {
        addClearButton()
        val store = VoicePrefs(context).customButtons
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.COPY), visibility = ButtonVisibility.ALWAYS))
        val strip = stripIn(InputType.TYPE_CLASS_TEXT, cursor = 0)
        layOut(strip)
        assertEquals(listOf(R.drawable.ic_content_copy), strip.customButtons.map { it.icon })
    }

    @Test
    fun `a fullscreen button stays off the strip, also in a field with text`() {
        addClearButton()
        val store = VoicePrefs(context).customButtons
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.COPY), visibility = ButtonVisibility.FULLSCREEN))
        val strip = stripIn(InputType.TYPE_CLASS_TEXT, cursor = 3)
        assertEquals(listOf(R.drawable.ic_broom), strip.customButtons.map { it.icon })
    }

    @Test
    fun `a password field has no buttons even if they are meant to be always there`() {
        val store = VoicePrefs(context).customButtons
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.COPY), visibility = ButtonVisibility.ALWAYS))
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(emptyList<StripButton>(), stripIn(password, cursor = 0).customButtons)
    }

    @Test
    fun `the keyboard takes the suggestion timeout from the settings`() {
        KeyboardPrefs(context).suggestionTimeout = SuggestionTimeout.SECONDS_10
        assertEquals(10_000L, stripIn(InputType.TYPE_CLASS_TEXT, cursor = 0).suggestionTimeoutMillis)
    }

    @Test
    fun `a password field shows no buttons`() {
        addClearButton()
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(emptyList<StripButton>(), stripIn(password, cursor = 3).customButtons)
    }

    @Test
    fun `the strip renders its custom buttons`() {
        val strip = SuggestionStripView(context, KeyboardTheme(context))
        strip.customButtons =
            listOf(
                StripButton("a", R.drawable.ic_broom, ProofreadState.IDLE, ButtonColor.RED),
                StripButton("b", R.drawable.ic_content_copy, ProofreadState.UNDO, placement = ButtonPlacement.CENTER),
                StripButton(
                    "c",
                    R.drawable.ic_translate,
                    ProofreadState.WORKING,
                    ButtonColor.BLUE,
                    ButtonPlacement.RIGHT,
                ),
            )
        strip.proofreadState = ProofreadState.IDLE
        render(strip, "strip-custom-buttons")
        strip.customButtons = strip.customButtons.take(1)
        strip.setClipChips(listOf(ClipChip(ClipKind.TEXT, "Привет, мир", "Привет, мир")))
        render(strip, "strip-custom-buttons-chip")
    }

    @Test
    fun `the buttons of the second row stay where they are when the proofread button appears`() {
        for (count in 1..16) {
            val strip = SuggestionStripView(context, KeyboardTheme(context))
            strip.secondRow = true
            strip.customButtons = List(count) { StripButton("b$it", R.drawable.ic_broom, ProofreadState.IDLE) }
            layOut(strip)
            val before = strip.firstRowCount
            strip.proofreadState = ProofreadState.IDLE
            layOut(strip)
            assertEquals("with $count buttons", before, strip.firstRowCount)
        }
    }

    /** Buttons are told apart by the room the strip has, so it needs its size. */
    private fun layOut(strip: View) {
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
    }

    private fun render(
        strip: View,
        name: String,
    ) {
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        val bitmap = Bitmap.createBitmap(strip.width, strip.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        strip.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `a group is one button of the strip that carries its members`() {
        val store = VoicePrefs(context).customButtons
        val group =
            store.addGroup().let {
                store.save(it.copy(visibility = ButtonVisibility.ALWAYS))
                it
            }
        store.save(store.add(groupId = group.id).copy(tap = ButtonCommand(ButtonAction.CLEAR)))
        store.save(store.add(groupId = group.id).copy(tap = ButtonCommand(ButtonAction.COPY)))
        val strip = stripIn(InputType.TYPE_CLASS_TEXT, cursor = 0)
        assertEquals(listOf(group.id), strip.customButtons.map { it.id })
        assertEquals(
            listOf(R.drawable.ic_broom, R.drawable.ic_content_copy),
            strip.customButtons[0].members.map { it.icon },
        )
    }

    @Test
    fun `a group without a working member is not shown`() {
        val store = VoicePrefs(context).customButtons
        store.save(store.addGroup().let { it.copy(visibility = ButtonVisibility.ALWAYS) })
        val strip = stripIn(InputType.TYPE_CLASS_TEXT, cursor = 0)
        assertEquals(emptyList<String>(), strip.customButtons.map { it.id })
    }

    private fun View.texts(): List<String> =
        when (this) {
            is android.widget.TextView -> listOf(text.toString())
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
            else -> emptyList()
        }

    @Test
    fun `a group takes its own icon and color, and its popup lists the members by name under its title`() {
        val store = VoicePrefs(context).customButtons
        val group =
            store.addGroup().let {
                store.save(
                    it.copy(
                        visibility = ButtonVisibility.ALWAYS,
                        name = "Texts",
                        icon = "star",
                        color = ButtonColor.PURPLE,
                    ),
                )
                it
            }
        store.save(store.add(groupId = group.id).copy(tap = ButtonCommand(ButtonAction.CLEAR), name = "Wipe"))
        store.save(store.add(groupId = group.id).copy(tap = ButtonCommand(ButtonAction.COPY)))
        val strip = stripIn(InputType.TYPE_CLASS_TEXT, cursor = 0)
        val shown = strip.customButtons.single()
        assertEquals(R.drawable.ic_star, shown.icon)
        assertEquals(ButtonColor.PURPLE, shown.color)
        assertEquals("Texts", shown.description)

        val content = ButtonGroupPopup(context, KeyboardTheme(context)) { _, _ -> }.content(shown)
        assertEquals(listOf("Texts", "Wipe", "Copy"), content.texts())
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(spec, spec)
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "strip-group-popup.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
