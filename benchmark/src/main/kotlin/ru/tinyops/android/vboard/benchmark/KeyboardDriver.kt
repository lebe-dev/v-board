package ru.tinyops.android.vboard.benchmark

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.rules.ExternalResource

internal const val TARGET_PACKAGE = "ru.tinyops.android.vboard"

/** Restores the user's choice of keyboard after a test. */
internal class ActiveKeyboardRule : ExternalResource() {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var previous = ""

    override fun before() {
        previous = device.executeShellCommand("settings get secure default_input_method").trim()
    }

    override fun after() {
        if (previous.isNotEmpty() && previous != "null") device.executeShellCommand("ime set $previous")
    }
}

/** Opens a text field and works the keyboard the way a person does. */
internal class KeyboardDriver(
    private val device: UiDevice,
) {
    /**
     * Shows the text field and waits for the keyboard to appear; returns the area of the keys.
     * The keyboard is selected every time: benchmarks restart it with a force-stop, after which
     * the system falls back to its default input method.
     */
    fun open(): Rect {
        check(!isLocked()) { "The phone is locked: unlock it and keep the screen on." }
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent =
            Intent(instrumentation.context, EditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.context.startActivity(intent)
        val keys = device.wait(Until.findObject(By.res(TARGET_PACKAGE, KEYS_ID)), TIMEOUT_MILLIS)
        return checkNotNull(keys) { "The keyboard did not appear: is it the active input method?" }.visibleBounds
    }

    fun close() {
        device.pressHome()
        device.waitForIdle()
    }

    /** Taps letters of the top two rows, which exist in every layout. */
    fun type(keys: Rect) {
        repeat(TYPED_KEYS) { index ->
            val point = keys.letter(row = index % 2, column = index * COLUMN_STEP % COLUMNS)
            device.click(point.x, point.y)
            Thread.sleep(TAP_PAUSE_MILLIS)
        }
        device.waitForIdle()
    }

    /** Draws gestures across the letters and waits for each to be decoded and committed. */
    fun swipe(keys: Rect) {
        for (path in GESTURES) {
            val points = path.map { (row, column) -> keys.letter(row, column) }.toTypedArray()
            device.swipe(points, STEPS_PER_SEGMENT)
            Thread.sleep(GESTURE_PAUSE_MILLIS)
        }
        device.waitForIdle()
    }

    private fun isLocked(): Boolean =
        InstrumentationRegistry
            .getInstrumentation()
            .context
            .getSystemService(KeyguardManager::class.java)
            .isKeyguardLocked

    private fun Rect.letter(
        row: Int,
        column: Int,
    ): Point =
        Point(
            left + width() * (2 * column + 1) / (2 * COLUMNS),
            top + height() * (2 * row + 1) / (2 * ROWS),
        )

    private companion object {
        const val IME = "$TARGET_PACKAGE/.KeyboardService"
        const val KEYS_ID = "keyboard_keys"
        const val TIMEOUT_MILLIS = 10_000L
        const val ROWS = 4
        const val COLUMNS = 10
        const val COLUMN_STEP = 3
        const val TYPED_KEYS = 24
        const val TAP_PAUSE_MILLIS = 120L
        const val STEPS_PER_SEGMENT = 12
        const val GESTURE_PAUSE_MILLIS = 300L

        /** Paths as (row, column) stops: short, medium and long words. */
        val GESTURES =
            listOf(
                listOf(0 to 2, 1 to 5, 0 to 4),
                listOf(1 to 5, 0 to 2, 1 to 8, 1 to 8, 0 to 8),
                listOf(0 to 7, 2 to 5, 0 to 4, 0 to 3, 1 to 0, 2 to 6, 1 to 2, 0 to 8, 2 to 5, 0 to 4, 1 to 3, 0 to 1),
                listOf(0 to 4, 1 to 1, 2 to 2, 0 to 3, 1 to 7, 0 to 6, 2 to 4, 1 to 0, 0 to 9, 2 to 1, 0 to 2, 1 to 5),
            )
    }
}
