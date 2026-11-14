package ru.tinyops.android.vboard

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import ru.tinyops.android.vboard.core.gesture.SlideEngine
import ru.tinyops.android.vboard.core.layout.KeySpec
import ru.tinyops.android.vboard.core.layout.KeyType
import ru.tinyops.android.vboard.core.layout.KeyboardMode
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.ui.KeyboardView

/** Typing through the real service into a field: what ends up in the text, not how it looks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class KeyboardServiceTypingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private lateinit var service: KeyboardService

    /** A field backed by a real editable, recording the editor actions it is asked for. */
    private inner class Field : BaseInputConnection(EditText(context), true) {
        val actions = ArrayList<Int>()

        val text: String get() = editable.toString()

        val composing: String
            get() {
                val content = requireNotNull(editable)
                val start = getComposingSpanStart(content)
                val end = getComposingSpanEnd(content)
                return if (start < 0) "" else content.substring(start, end)
            }

        override fun performEditorAction(actionCode: Int): Boolean {
            actions += actionCode
            return true
        }
    }

    private fun createService() {
        service = Robolectric.buildService(KeyboardService::class.java).create().get()
    }

    private fun start(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = EditorInfo.IME_ACTION_SEARCH,
    ): Pair<Field, KeyboardView> {
        if (!::service.isInitialized) createService()
        val field = Field()
        ReflectionHelpers.setField(service, "mStartedInputConnection", field)
        val info =
            EditorInfo().apply {
                this.inputType = inputType
                this.imeOptions = imeOptions
            }
        // The system sets it before starting the input; the enter key reads the action from it.
        ReflectionHelpers.setField(service, "mInputEditorInfo", info)
        val root = service.onCreateInputView() as ViewGroup
        service.onStartInputView(info, false)
        val width = context.resources.displayMetrics.widthPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return field to root.getChildAt(1) as KeyboardView
    }

    private fun type(word: String) = word.forEach { service.onKey(KeySpec(KeyType.CHARACTER, it.toString())) }

    private fun press(type: KeyType) = service.onKey(KeySpec(type))

    /** Dictionaries load on a background thread; gestures are enabled once they are ready. */
    private fun awaitDictionary(keyboard: KeyboardView) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!keyboard.gestureEnabled && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
        assertTrue("dictionary did not load", keyboard.gestureEnabled)
    }

    private fun awaitText(
        field: Field,
        expected: String,
    ) {
        val deadline = System.currentTimeMillis() + 30_000
        while (field.text != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
        assertEquals(expected, field.text)
    }

    @Test
    fun `typed letters compose a word and space commits it`() {
        val (field, _) = start()

        type("hi")
        assertEquals("hi", field.composing)

        press(KeyType.SPACE)
        assertEquals("hi ", field.text)
        assertEquals("", field.composing)
    }

    @Test
    fun `the number pad opens from the second symbols page and types digits`() {
        val (field, keyboard) = start()

        press(KeyType.SYMBOLS)
        press(KeyType.MORE_SYMBOLS)
        press(KeyType.NUMBERS)
        assertEquals(KeyboardMode.NUMBERS, keyboard.spec?.mode)

        type("42")
        assertEquals("42", field.text)
    }

    @Test
    fun `backspace edits the word being typed`() {
        val (field, _) = start()

        type("hey")
        service.onBackspace()

        assertEquals("he", field.text)
        assertEquals("he", field.composing)
    }

    @Test
    fun `a picked suggestion replaces the typed word and adds a space`() {
        val (field, keyboard) = start()
        awaitDictionary(keyboard)

        type("hel")
        service.onSuggestionPicked("hello")

        assertEquals("hello ", field.text)
    }

    @Test
    fun `enter commits the word and performs the action of the field`() {
        val (field, _) = start(imeOptions = EditorInfo.IME_ACTION_SEARCH)

        type("cats")
        press(KeyType.ENTER)

        assertEquals("cats", field.text)
        assertEquals("", field.composing)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), field.actions)
    }

    @Test
    fun `a password is typed without composing`() {
        val (field, _) = start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        type("pw")

        assertEquals("pw", field.text)
        assertEquals("", field.composing)
    }

    @Test
    fun `inserted text lands after the word being typed`() {
        val (field, _) = start()

        type("ab")
        service.insertText(" dictated")

        assertEquals("ab dictated", field.text)
        assertEquals("", field.composing)
    }

    @Test
    fun `the language key switches the layout and the choice survives a restart`() {
        val (_, keyboard) = start()
        assertEquals(Language.ENGLISH, keyboard.geometry?.spec?.language)

        press(KeyType.LANGUAGE)
        assertEquals(Language.RUSSIAN, keyboard.geometry?.spec?.language)

        service.onDestroy()
        createService()
        val (_, again) = start()
        assertEquals(Language.RUSSIAN, again.geometry?.spec?.language)
    }

    @Test
    fun `a slide across the letters of a word types it`() {
        val (field, keyboard) = start()
        awaitDictionary(keyboard)
        val keys = requireNotNull(keyboard.geometry).keys
        val path = "hello".map { c -> keys.first { it.spec.output == c.toString() }.bounds }
        val down = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            x: Float,
            y: Float,
            offset: Long,
        ) {
            val event = MotionEvent.obtain(down, down + offset, action, x, y, 0)
            keyboard.onTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, path[0].centerX, path[0].centerY, 0)
        var offset = 0L
        for (i in 1 until path.size) {
            for (step in 1..STEPS) {
                val t = step / STEPS.toFloat()
                val x = path[i - 1].centerX + (path[i].centerX - path[i - 1].centerX) * t
                val y = path[i - 1].centerY + (path[i].centerY - path[i - 1].centerY) * t
                offset += 8
                send(MotionEvent.ACTION_MOVE, x, y, offset)
            }
        }
        send(MotionEvent.ACTION_UP, path.last().centerX, path.last().centerY, offset + 8)

        awaitText(field, "hello")
        assertEquals("the slid word stays replaceable by its alternatives", "hello", field.composing)
    }

    @Test
    fun `a model that crashed while loading turns the experimental engine off`() {
        context
            .getSharedPreferences("keyboard", Context.MODE_PRIVATE)
            .edit()
            .putString("language", "ru")
            .commit()
        val prefs = KeyboardPrefs(context)
        prefs.slideEngine = SlideEngine.EXPERIMENTAL
        // The mark the guard leaves while the model loads: the process died in the middle of it.
        prefs.neuralModelLoading = true

        val (_, keyboard) = start()
        awaitDictionary(keyboard)

        assertEquals(SlideEngine.DEFAULT, prefs.slideEngine)
        assertFalse(prefs.neuralModelLoading)
    }

    private companion object {
        const val STEPS = 6
    }
}
