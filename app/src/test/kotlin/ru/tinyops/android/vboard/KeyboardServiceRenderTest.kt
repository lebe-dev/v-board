package ru.tinyops.android.vboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.clipboard.ClipboardPrefs
import ru.tinyops.android.vboard.core.input.SpaceDragMode
import ru.tinyops.android.vboard.core.layout.KeyType
import ru.tinyops.android.vboard.core.layout.PlacedKey
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ServerProfile
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.ui.KeyboardView
import ru.tinyops.android.vboard.ui.SuggestionStripView
import ru.tinyops.android.vboard.voice.ProofreadState
import ru.tinyops.android.vboard.voice.VoicePrefs
import ru.tinyops.android.vboard.voice.VoiceState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Renders the real input view of [KeyboardService] and checks its colors. The PNGs are
 * written to `app/build/screenshots/` for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-night-420dpi")
class KeyboardServiceRenderTest {
    private val service = Robolectric.buildService(KeyboardService::class.java).create().get()

    private fun startInput(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = EditorInfo.IME_ACTION_SEARCH,
    ): Pair<ViewGroup, KeyboardView> {
        val root = service.onCreateInputView() as ViewGroup
        val info =
            EditorInfo().apply {
                this.inputType = inputType
                this.imeOptions = imeOptions
            }
        service.onStartInputView(info, false)
        val width =
            RuntimeEnvironment
                .getApplication()
                .resources.displayMetrics.widthPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root to root.getChildAt(1) as KeyboardView
    }

    private fun render(
        root: View,
        name: String,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun color(id: Int): Int = service.getColor(id)

    /** Dictionaries load on a background thread; gestures are enabled once they are ready. */
    private fun awaitGestures(keyboard: KeyboardView) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!keyboard.gestureEnabled && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
        assertTrue("dictionary did not load", keyboard.gestureEnabled)
    }

    /** A pixel inside the key's shape but away from its label or icon. */
    private fun Bitmap.keyFill(
        keyboard: KeyboardView,
        key: PlacedKey,
    ): Int {
        val b = key.bounds
        val x = b.left + b.width * 0.15f
        val y = keyboard.top + b.centerY
        return getPixel(x.toInt(), y.toInt())
    }

    @Test
    fun `english letters render with the dark theme colors`() {
        val (root, keyboard) = startInput()
        val bitmap = render(root, "en-dark")
        val keys = requireNotNull(keyboard.geometry).keys
        val letter = keys.first { it.spec.output == "q" }
        val shift = keys.first { it.spec.type == KeyType.SHIFT }
        val enter = keys.first { it.spec.type == KeyType.ENTER }

        assertEquals(color(R.color.kb_background), bitmap.getPixel(2, 2))
        assertEquals(color(R.color.kb_key), bitmap.keyFill(keyboard, letter))
        assertEquals(color(R.color.kb_function_key), bitmap.keyFill(keyboard, shift))
        assertEquals(color(R.color.kb_action_key), bitmap.keyFill(keyboard, enter))
        assertEquals(
            10,
            keyboard.spec!!
                .rows
                .first()
                .keys.size,
        )
    }

    @Test
    fun `the trackpad mode shows the keys without labels, all in one color`() {
        KeyboardPrefs(service).spaceDragMode = SpaceDragMode.TRACKPAD
        val (root, keyboard) = startInput()
        val keys = keyboard.geometry!!.keys
        val space = keys.first { it.spec.type == KeyType.SPACE }
        val letter = keys.first { it.spec.output == "q" }
        val enter = keys.first { it.spec.type == KeyType.ENTER }

        fun Bitmap.keyCenter(key: PlacedKey) =
            getPixel(key.bounds.centerX.toInt(), (keyboard.top + key.bounds.centerY).toInt())
        val typing = render(root, "en-before-trackpad-dark")
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, space.bounds.centerX, space.bounds.centerY, 0)
        keyboard.onTouchEvent(down)
        down.recycle()
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        val bitmap = render(root, "trackpad-dark")
        val skeleton = color(R.color.kb_key)
        assertEquals(skeleton, bitmap.keyFill(keyboard, letter))
        assertEquals(skeleton, bitmap.keyCenter(letter))
        assertEquals(skeleton, bitmap.keyFill(keyboard, enter))
        assertEquals(skeleton, bitmap.keyCenter(enter))
        assertEquals(skeleton, bitmap.keyFill(keyboard, space))
        assertTrue(typing.keyCenter(enter) != skeleton)

        val up = MotionEvent.obtain(0, 500, MotionEvent.ACTION_UP, space.bounds.centerX, space.bounds.centerY, 0)
        keyboard.onTouchEvent(up)
        up.recycle()
        KeyboardPrefs(service).spaceDragMode = SpaceDragMode.LINEAR
        assertEquals(color(R.color.kb_action_key), render(root, "after-trackpad-dark").keyFill(keyboard, enter))
    }

    @Test
    fun `russian layout has eleven keys in the top row`() {
        val (root, keyboard) = startInput()
        // Switch through the globe key.
        keyboard.listener!!.onKey(
            keyboard.geometry!!
                .keys
                .first { it.spec.type == KeyType.LANGUAGE }
                .spec,
        )
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        render(root, "ru-dark")
        val top = keyboard.geometry!!.keys.filter { it.bounds.top < keyboard.height / 4f }
        assertEquals("йцукенгшщзх", top.joinToString("") { it.spec.output })
        // Restore the default language for other tests sharing preferences.
        keyboard.listener!!.onKey(
            keyboard.geometry!!
                .keys
                .first { it.spec.type == KeyType.LANGUAGE }
                .spec,
        )
    }

    @Test
    fun `number fields open the symbols page`() {
        val (root, keyboard) =
            startInput(
                inputType = InputType.TYPE_CLASS_NUMBER,
                imeOptions = EditorInfo.IME_ACTION_DONE,
            )
        render(root, "symbols-dark")
        val top = keyboard.geometry!!.keys.filter { it.bounds.top < keyboard.height / 4f }
        assertEquals("1234567890", top.joinToString("") { it.spec.output })
        assertEquals(EnterAction.DONE, keyboard.enterAction)
    }

    @Test
    fun `the strip takes the chosen colors, and none until they are chosen`() {
        val application = RuntimeEnvironment.getApplication()
        val strip = { root: ViewGroup -> root.getChildAt(0) as SuggestionStripView }
        val (plain, _) = startInput()
        assertNull(strip(plain).micColor)
        assertNull(strip(plain).proofreadColor)
        assertNull(strip(plain).clipboardColor)
        VoicePrefs(application).micColor = ButtonColor.RED
        VoicePrefs(application).proofreadColor = ButtonColor.GREEN
        ClipboardPrefs(application).iconColor = ButtonColor.TEAL
        KeyboardPrefs(application).apply {
            fullscreenEnabled = true
            fullscreenColor = ButtonColor.ORANGE
        }
        val (colored, _) = startInput()
        assertEquals(ButtonColor.RED, strip(colored).micColor)
        assertEquals(ButtonColor.GREEN, strip(colored).proofreadColor)
        assertEquals(ButtonColor.TEAL, strip(colored).clipboardColor)
        render(colored, "strip-colored-icons-dark")
    }

    @Test
    fun `a switched off clipboard leaves the strip`() {
        ClipboardPrefs(RuntimeEnvironment.getApplication()).enabled = false
        val (root, _) = startInput()
        assertNull((root.getChildAt(0) as SuggestionStripView).clipboardPlacement)
    }

    @Test
    fun `the panel of the fullscreen editor renders`() {
        val editor = service.onCreateExtractTextView()
        val width =
            RuntimeEnvironment
                .getApplication()
                .resources.displayMetrics.widthPixels
        editor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width / 2, View.MeasureSpec.EXACTLY),
        )
        editor.layout(0, 0, editor.measuredWidth, editor.measuredHeight)
        render(editor, "fullscreen-editor-dark")
    }

    @Test
    fun `gesture trail and suggestions render`() {
        val (root, keyboard) = startInput()
        awaitGestures(keyboard)
        (root.getChildAt(0) as SuggestionStripView).setSuggestions(listOf("hello", "help", "hell"))
        val keys = requireNotNull(keyboard.geometry).keys
        val path = "hello".map { c -> keys.first { it.spec.output == c.toString() }.bounds }
        val start = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            x: Float,
            y: Float,
            offset: Long,
        ) {
            val event = MotionEvent.obtain(start, start + offset, action, x, y, 0)
            keyboard.onTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, path[0].centerX, path[0].centerY, 0)
        for (i in 1 until path.size) {
            for (step in 1..STEPS) {
                val t = step / STEPS.toFloat()
                val x = path[i - 1].centerX + (path[i].centerX - path[i - 1].centerX) * t
                val y = path[i - 1].centerY + (path[i].centerY - path[i - 1].centerY) * t
                send(MotionEvent.ACTION_MOVE, x, y, (i * STEPS + step) * 8L)
            }
        }
        val bitmap = render(root, "gesture-dark")
        // The head of the trail is drawn opaque in the trail color, on top of the "o" key.
        val head = path.last()
        val pixel = bitmap.getPixel(head.centerX.toInt(), (keyboard.top + head.centerY).toInt())
        assertEquals(color(R.color.kb_trail), pixel)
    }

    private fun turnOnProofreading() {
        val prefs = VoicePrefs(RuntimeEnvironment.getApplication())
        prefs.saveServer(ServerProfile("main", "Main", "https://server.example/v1", "m"))
        prefs.setFeatureServer(TextFeature.PROOFREAD, "main")
        prefs.setFeatureEnabled(TextFeature.PROOFREAD, true)
    }

    @Test
    fun `the proofread button appears when it is on and the field has text`() {
        val (root, _) = startInput()
        val strip = root.getChildAt(0) as SuggestionStripView
        service.onUpdateSelection(0, 0, 5, 5, -1, -1)
        assertNull("off by default", strip.proofreadState)

        turnOnProofreading()
        startInput().first.let { next ->
            val shown = next.getChildAt(0) as SuggestionStripView
            assertNull("the field is empty", shown.proofreadState)
            service.onUpdateSelection(0, 0, 5, 5, -1, -1)
            assertEquals(ProofreadState.IDLE, shown.proofreadState)
            shown.setSuggestions(listOf("привет", "при", "прием"))
            render(next, "strip-proofread-typing-dark")
            shown.setSuggestions(emptyList())
            render(next, "strip-proofread-idle-dark")
            shown.proofreadState = ProofreadState.WORKING
            render(next, "strip-proofread-working-dark")
            shown.proofreadState = ProofreadState.UNDO
            render(next, "strip-proofread-undo-dark")
        }
    }

    @Test
    fun `a password field has no proofread button`() {
        turnOnProofreading()
        val (root, _) = startInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        service.onUpdateSelection(0, 0, 5, 5, -1, -1)
        assertNull((root.getChildAt(0) as SuggestionStripView).proofreadState)
    }

    @Test
    fun `strip shows settings and microphone, and the voice panel while dictating`() {
        val (root, _) = startInput()
        val strip = root.getChildAt(0) as SuggestionStripView
        strip.setSuggestions(listOf("привет", "при", "прием"))
        render(root, "strip-idle-dark")
        strip.voiceState = VoiceState(VoiceState.Phase.LISTENING, speech = true, preview = "Привет, как дела?")
        val bitmap = render(root, "strip-voice-dark")
        // The active microphone sits on an accent disc in the top right corner.
        val discX = strip.width - strip.height / 2
        val discY = strip.height / 2 - strip.height / 4
        assertEquals(color(R.color.kb_voice_active), bitmap.getPixel(discX, discY))
    }

    @Test
    fun `a recent copied text shows as chips with the link found in it`() {
        val clipboard = service.getSystemService(android.content.ClipboardManager::class.java)
        val clip =
            android.content.ClipData.newPlainText(
                "t",
                "Когда мне нужно было обработать данные, я арендовал виртуалку на vast.ai, в итоге быстро",
            )
        clipboard.setPrimaryClip(clip)
        // The shadow clipboard stamps clips with the test clock.
        val copiedAt = clipboard.primaryClip?.description?.timestamp ?: 0L
        service.clock = { copiedAt + 1_000L }
        val (root, _) = startInput()
        val strip = root.getChildAt(0) as SuggestionStripView
        render(root, "strip-chips-dark")
        assertEquals(SuggestionStripView.Zone.CHIP_1, strip.zoneAt(strip.width * 0.62f))
    }

    @Test
    fun `microphone is hidden in password fields but the clipboard button stays`() {
        val (root, _) =
            startInput(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val strip = root.getChildAt(0) as SuggestionStripView
        assertEquals(SuggestionStripView.Zone.CLIPBOARD, strip.zoneAt(strip.width - 1f))
    }

    @Test
    fun `long press popup renders above the key`() {
        val (root, keyboard) = startInput()
        val key = requireNotNull(keyboard.geometry).keys.first { it.spec.output == "e" }
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, key.bounds.centerX, key.bounds.centerY, 0)
        keyboard.onTouchEvent(event)
        event.recycle()
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        val bitmap = render(root, "popup-dark")
        val popupY = keyboard.top + key.bounds.top - key.bounds.height * 0.5f
        assertEquals(color(R.color.kb_popup_selected), bitmap.getPixel((key.bounds.left + 4).toInt(), popupY.toInt()))
    }

    @Test
    fun `keyboard height fits four rows plus the suggestion strip`() {
        val (root, keyboard) = startInput()
        val density = service.resources.displayMetrics.density
        assertTrue("height=${root.height}", root.height > 280 * density && root.height < 320 * density)
        assertTrue(keyboard.top > 0)
    }

    @Test
    fun `keys stay clear of the navigation bar`() {
        val (root, keyboard) = startInput()
        val navBar = 63
        val cutout = 20
        val insets =
            WindowInsets
                .Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navBar))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(cutout, 0, 0, 0))
                .build()
        root.dispatchApplyWindowInsets(insets)
        val width = root.width
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals(navBar, root.paddingBottom)
        assertEquals(root.height - navBar, keyboard.bottom)
        assertEquals(cutout, keyboard.left)
        val bitmap = render(root, "navigation-bar-dark")
        assertEquals(color(R.color.kb_background), bitmap.getPixel(width / 2, root.height - navBar / 2))
    }

    @Test
    fun `lowercase letters are centered vertically on their keys`() {
        val (root, keyboard) = startInput()
        val bitmap = render(root, "label-centering")
        for (letter in listOf("x", "c", "z")) {
            val key = keyboard.geometry!!.keys.first { it.spec.output == letter }
            val (top, bottom) = bitmap.labelRows(keyboard, key)
            val glyphCenter = keyboard.top + (top + bottom) / 2f
            assertEquals("$letter glyph rows $top..$bottom", keyboard.top + key.bounds.centerY, glyphCenter, 1.5f)
        }
    }

    /** First and last rows (in view coordinates) holding bright label pixels inside the key. */
    private fun Bitmap.labelRows(
        keyboard: KeyboardView,
        key: PlacedKey,
    ): Pair<Int, Int> {
        val b = key.bounds
        val rows =
            (b.top.toInt() until b.bottom.toInt()).filter { y ->
                (b.left.toInt() until b.right.toInt()).any { x ->
                    android.graphics.Color.red(getPixel(x, keyboard.top + y)) > LABEL_BRIGHTNESS
                }
            }
        return rows.first() to rows.last()
    }

    @Test
    fun `keys stay clear of the ime navigation buttons`() {
        val (root, keyboard) = startInput()
        val handle = 63
        val buttons = 126
        val insets =
            WindowInsets
                .Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, handle))
                .setInsets(WindowInsets.Type.tappableElement(), Insets.of(0, 0, 0, buttons))
                .build()
        root.dispatchApplyWindowInsets(insets)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals(buttons, root.paddingBottom)
        assertEquals(root.height - buttons, keyboard.bottom)
    }

    private companion object {
        const val STEPS = 6
        const val LABEL_BRIGHTNESS = 0xC0
    }
}
