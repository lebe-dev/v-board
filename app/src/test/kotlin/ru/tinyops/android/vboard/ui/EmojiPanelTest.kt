package ru.tinyops.android.vboard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.core.emoji.EmojiCategory
import ru.tinyops.android.vboard.core.emoji.EmojiHidden
import ru.tinyops.android.vboard.core.emoji.EmojiPins
import ru.tinyops.android.vboard.core.emoji.EmojiRecents
import ru.tinyops.android.vboard.emoji.EmojiAssets
import ru.tinyops.android.vboard.emoji.EmojiPrefs
import ru.tinyops.android.vboard.emoji.subcategoryTitle
import java.io.File
import java.util.concurrent.TimeUnit

/** The emoji picker: what it lists, what a tap and a hold do, and PNGs in `app/build/screenshots/` for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-night-420dpi")
class EmojiPanelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val assets = EmojiAssets(context.assets)
    private val prefs = EmojiPrefs(context)
    private val density = context.resources.displayMetrics.density
    private val picked = mutableListOf<String>()
    private var backs = 0
    private var backspaces = 0

    private fun panel(): EmojiPanelView {
        val panel = EmojiPanelView(context, KeyboardTheme(context), assets, prefs)
        panel.listener =
            object : EmojiPanelView.Listener {
                override fun onEmojiBack() {
                    backs++
                }

                override fun onEmojiPick(text: String) {
                    picked += text
                }

                override fun onBackspace() {
                    backspaces++
                }
            }
        panel.open("ABC")
        layout(panel)
        return panel
    }

    private fun layout(panel: EmojiPanelView) {
        val width = context.resources.displayMetrics.widthPixels
        val height = (density * 330).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, width, height)
    }

    // The panel: content column (chips, grid), fade, back button, backspace, popup layer.
    private fun column(panel: EmojiPanelView) = panel.getChildAt(0) as ViewGroup

    private fun chipsOf(panel: EmojiPanelView) = (column(panel).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup

    private fun chipLabels(panel: EmojiPanelView) =
        (0 until chipsOf(panel).childCount).map { (chipsOf(panel).getChildAt(it) as TextView).text.toString() }

    private fun gridOf(panel: EmojiPanelView) = column(panel).getChildAt(1) as EmojiGridView

    private fun backOf(panel: EmojiPanelView) = panel.getChildAt(2)

    private fun backspaceOf(panel: EmojiPanelView) = panel.getChildAt(3)

    private fun popupLayerOf(panel: EmojiPanelView) = panel.getChildAt(4) as ViewGroup

    private fun popupOf(panel: EmojiPanelView) = popupLayerOf(panel).getChildAt(0) as ViewGroup

    private fun cellX(
        grid: EmojiGridView,
        column: Int,
    ) = (column + 0.5f) * grid.width / grid.columns

    private fun cellY(
        grid: EmojiGridView,
        row: Int,
    ) = (row + 0.5f) * grid.width / grid.columns

    /** Taps the cell at [column], [row], counted from the top of an unscrolled grid. */
    private fun tapCell(
        grid: EmojiGridView,
        column: Int,
        row: Int = 0,
    ) = tap(grid, cellX(grid, column), cellY(grid, row))

    private fun holdCell(
        grid: EmojiGridView,
        column: Int,
        row: Int = 0,
    ) = hold(grid, cellX(grid, column), cellY(grid, row))

    // The first row under the title of the first group of the chip of the user's own emoji.
    private fun mineY(grid: EmojiGridView) = 26 * density + cellY(grid, 0)

    // The first row of the recent emoji, under [pinRows] rows of pins and two titles.
    private fun recentY(
        grid: EmojiGridView,
        pinRows: Int,
    ) = mineY(grid) + 26 * density + pinRows * grid.width / grid.columns

    private fun tapMine(
        grid: EmojiGridView,
        column: Int,
    ) = tap(grid, cellX(grid, column), mineY(grid))

    private fun holdMine(
        grid: EmojiGridView,
        column: Int,
    ) = hold(grid, cellX(grid, column), mineY(grid))

    /** Holds the cell at column [from] of a row and carries it to column [to], then lifts the finger. */
    private fun drag(
        grid: EmojiGridView,
        from: Int,
        to: Int,
        lift: Int = MotionEvent.ACTION_UP,
        y: Float = mineY(grid),
    ) {
        val now = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            time: Long,
            x: Float,
        ) {
            val event = MotionEvent.obtain(now, now + time, action, x, y, 0)
            grid.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0, cellX(grid, from))
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
        send(MotionEvent.ACTION_MOVE, 710, cellX(grid, to))
        send(lift, 720, cellX(grid, to))
    }

    private fun popupLabels(panel: EmojiPanelView) =
        (0 until popupOf(panel).childCount).map { popupOf(panel).getChildAt(it).contentDescription.toString() }

    private fun popupShown(panel: EmojiPanelView) = popupLayerOf(panel).visibility == View.VISIBLE

    private fun choose(
        panel: EmojiPanelView,
        label: String,
    ) {
        val actions = popupOf(panel)
        (0 until actions.childCount)
            .map { actions.getChildAt(it) }
            .first { it.contentDescription == label }
            .performClick()
        layout(panel)
    }

    private fun tap(
        view: View,
        x: Float,
        y: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 30)) {
            val event = MotionEvent.obtain(now, time, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun hold(
        view: View,
        x: Float,
        y: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        view.dispatchTouchEvent(down)
        down.recycle()
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
        val up = MotionEvent.obtain(now, now + 700, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(up)
        up.recycle()
    }

    private fun render(
        view: View,
        name: String,
    ) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#FF1B1B1B"))
        view.draw(canvas)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the catalog and the pictures are bundled`() {
        assertTrue(assets.catalog.all.size > 1500)
        assertNotNull("run `just emoji`", assets.picture(assets.catalog.find("😀")!!))
    }

    @Test
    fun `tapping a cell commits its emoji`() {
        tapCell(gridOf(panel()), 0)
        assertEquals(listOf("😀"), picked)
    }

    @Test
    fun `there is no chip of your own emoji while there is nothing to show`() {
        val labels = chipLabels(panel())
        assertEquals("Smileys", labels.first())
        assertTrue("Mine" !in labels)
    }

    @Test
    fun `recent emoji open the picker under their own chip, the last used first`() {
        prefs.recents = EmojiRecents().use("🐸").use("🔥")
        val panel = panel()
        assertEquals("Mine", chipLabels(panel).first())
        tapMine(gridOf(panel), 0)
        assertEquals(listOf("🔥"), picked)
    }

    @Test
    fun `a picked emoji becomes recent`() {
        tapCell(gridOf(panel()), 0)
        assertEquals(listOf("😀"), prefs.recents.items)
    }

    @Test
    fun `an incognito field leaves the recent emoji as they were`() {
        val panel = panel()
        panel.open("ABC", rememberRecents = false)
        layout(panel)
        tapCell(gridOf(panel), 0)
        assertEquals(listOf("😀"), picked)
        assertEquals(emptyList<String>(), prefs.recents.items)
    }

    @Test
    fun `pinned emoji go first and are not repeated among the recent ones`() {
        prefs.recents = EmojiRecents().use("🐸").use("🍕").use("🔥")
        prefs.pins = EmojiPins().toggle("🇷🇺").toggle("🍕")
        val grid = gridOf(panel())
        tapMine(grid, 0)
        tapMine(grid, 1)
        tap(grid, cellX(grid, 0), recentY(grid, pinRows = 1))
        tap(grid, cellX(grid, 1), recentY(grid, pinRows = 1))
        assertEquals(listOf("🍕", "🇷🇺", "🔥", "🐸"), picked)
    }

    @Test
    fun `unpinning puts a recent emoji back among the recent ones`() {
        prefs.recents = EmojiRecents().use("🐸").use("🔥")
        prefs.pins = EmojiPins().toggle("🔥")
        val panel = panel()
        holdMine(gridOf(panel), 0)
        choose(panel, "Unpin")
        val grid = gridOf(panel)
        tapMine(grid, 0)
        tapMine(grid, 1)
        assertEquals(listOf("🔥", "🐸"), picked)
    }

    @Test
    fun `all the recent emoji are shown under their chip`() {
        val smileys = assets.catalog.of(EmojiCategory.SMILEYS).take(30)
        prefs.recents = smileys.fold(EmojiRecents()) { recents, emoji -> recents.use(emoji.text) }
        val grid = gridOf(panel())
        tap(grid, cellX(grid, 0), mineY(grid) + 3f * grid.width / grid.columns)
        assertEquals(smileys.reversed()[3 * grid.columns].text, picked.single())
    }

    @Test
    fun `the chip of your own emoji shows nothing else`() {
        prefs.recents = EmojiRecents().use("🐸").use("🔥")
        val panel = panel()
        val grid = gridOf(panel)
        render(panel, "emoji-panel-mine-dark")
        // The catalog would continue here if it followed the recent emoji.
        tap(grid, cellX(grid, 0), mineY(grid) + 2f * grid.width / grid.columns)
        assertTrue("picked=$picked", picked.isEmpty())
    }

    @Test
    fun `a tap on a small title picks nothing`() {
        prefs.pins = EmojiPins().toggle("🔥")
        val grid = gridOf(panel())
        tap(grid, cellX(grid, 0), 5 * density)
        assertTrue("picked=$picked", picked.isEmpty())
    }

    @Test
    fun `a chip of a category leaves your own emoji and the first chip comes back to them`() {
        prefs.recents = EmojiRecents().use("🔥")
        val panel = panel()
        val grid = gridOf(panel)
        chipsOf(panel).getChildAt(1).performClick()
        tapCell(grid, 0)
        chipsOf(panel).getChildAt(0).performClick()
        tapMine(grid, 0)
        assertEquals(listOf("😀", "🔥"), picked)
    }

    @Test
    fun `the last pin unpinned among your own emoji leads to the catalog`() {
        prefs.pins = EmojiPins().toggle("🔥")
        val panel = panel()
        holdMine(gridOf(panel), 0)
        choose(panel, "Unpin")
        tapCell(gridOf(panel), 0)
        assertEquals(listOf("😀"), picked)
    }

    @Test
    fun `a picked emoji is remembered as recent`() {
        tapCell(gridOf(panel()), 0)
        assertEquals(listOf("😀"), prefs.recents.items)
    }

    @Test
    fun `holding an emoji opens a small popup and picks nothing`() {
        val panel = panel()
        assertTrue(!popupShown(panel))
        holdCell(gridOf(panel), 0)
        assertTrue(popupShown(panel))
        assertEquals(listOf("Pin", "Hide"), popupLabels(panel))
        assertTrue("a hold is not a tap", picked.isEmpty())
        val box = popupOf(panel)
        val layer = popupLayerOf(panel)
        layer.measure(
            View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panel.height, View.MeasureSpec.EXACTLY),
        )
        layer.layout(0, 0, panel.width, panel.height)
        assertTrue(
            "inside the panel: ${box.left}..${box.right}, ${box.top}",
            box.left >= 0 && box.right <= panel.width && box.top >= 0,
        )
        render(panel, "emoji-panel-popup-dark")
    }

    @Test
    fun `while the popup is open a tap on an emoji picks nothing and a tap outside closes it`() {
        val panel = panel()
        val grid = gridOf(panel)
        holdCell(grid, 0)
        layout(panel)
        // The popup layer takes the touch, so the emoji under it is not picked.
        tap(panel, cellX(grid, 0), cellY(grid, 0) + grid.top)
        assertTrue("picked=$picked", picked.isEmpty())
        assertTrue(popupShown(panel))
        // A view that is not attached to a window cannot post its click, so it is performed directly.
        popupLayerOf(panel).performClick()
        assertTrue(!popupShown(panel))
    }

    @Test
    fun `pinning from the popup puts the emoji first among your own`() {
        val panel = panel()
        holdCell(gridOf(panel), 1)
        choose(panel, "Pin")
        assertEquals(listOf("😃"), prefs.pins.items)
        assertTrue(!popupShown(panel))
        assertEquals("Mine", chipLabels(panel).first())
        tapCell(gridOf(panel), 1)
        chipsOf(panel).getChildAt(0).performClick()
        tapMine(gridOf(panel), 0)
        assertEquals(listOf("😃", "😃"), picked)
    }

    @Test
    fun `pinning from the middle of the grid does not move the emoji on screen`() {
        val panel = panel()
        val grid = gridOf(panel)
        grid.scrollToSection(3)
        val y = cellY(grid, 1)
        tap(grid, cellX(grid, 0), y)
        val before = picked.removeLast()
        hold(grid, cellX(grid, 0), y)
        choose(panel, "Pin")
        tap(grid, cellX(grid, 0), y)
        assertEquals(before, picked.single())
    }

    @Test
    fun `holding a pinned emoji offers to unpin it`() {
        prefs.pins = EmojiPins().toggle("🔥")
        val panel = panel()
        holdMine(gridOf(panel), 0)
        assertEquals(listOf("Unpin", "Hide"), popupLabels(panel))
        choose(panel, "Unpin")
        assertEquals(emptyList<String>(), prefs.pins.items)
        assertEquals("Smileys", chipLabels(panel).first())
    }

    @Test
    fun `a pinned emoji can be dragged to another place`() {
        prefs.pins = EmojiPins(listOf("🍕", "🔥", "🐸", "🇷🇺"))
        val panel = panel()
        val grid = gridOf(panel)
        drag(grid, from = 0, to = 2)
        assertEquals(listOf("🔥", "🐸", "🍕", "🇷🇺"), prefs.pins.items)
        assertTrue("a drag closes the popup", !popupShown(panel))
        assertTrue("a drag picks nothing", picked.isEmpty())
        tapMine(grid, 2)
        assertEquals(listOf("🍕"), picked)
    }

    @Test
    fun `a carried pin follows the finger over a plate left in its place`() {
        prefs.recents = EmojiRecents().use("🐸").use("🌋")
        prefs.pins = EmojiPins(listOf("🍕", "🔥", "🇷🇺"))
        val panel = panel()
        val grid = gridOf(panel)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cellX(grid, 0), mineY(grid), 0)
        grid.dispatchTouchEvent(down)
        down.recycle()
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
        val move = MotionEvent.obtain(now, now + 710, MotionEvent.ACTION_MOVE, cellX(grid, 1), mineY(grid), 0)
        grid.dispatchTouchEvent(move)
        move.recycle()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        render(panel, "emoji-panel-drag-dark")
        assertEquals("nothing is saved before the finger is lifted", listOf("🍕", "🔥", "🇷🇺"), prefs.pins.items)
    }

    @Test
    fun `the new order of the pins is still there when the picker opens again`() {
        prefs.pins = EmojiPins(listOf("🍕", "🔥"))
        val panel = panel()
        drag(gridOf(panel), from = 1, to = 0)
        panel.open("ABC")
        layout(panel)
        tapMine(gridOf(panel), 0)
        assertEquals(listOf("🔥"), picked)
    }

    @Test
    fun `dragging past the last pin stops at the last pin`() {
        prefs.recents = EmojiRecents().use("🐸").use("🌋")
        prefs.pins = EmojiPins(listOf("🍕", "🔥"))
        val grid = gridOf(panel())
        drag(grid, from = 0, to = 3)
        assertEquals(listOf("🔥", "🍕"), prefs.pins.items)
        tap(grid, cellX(grid, 0), recentY(grid, pinRows = 1))
        assertEquals(listOf("🌋"), picked)
    }

    @Test
    fun `a cancelled drag changes nothing`() {
        prefs.pins = EmojiPins(listOf("🍕", "🔥"))
        val grid = gridOf(panel())
        drag(grid, from = 0, to = 1, lift = MotionEvent.ACTION_CANCEL)
        assertEquals(listOf("🍕", "🔥"), prefs.pins.items)
        tapMine(grid, 0)
        assertEquals(listOf("🍕"), picked)
    }

    @Test
    fun `a recent emoji cannot be dragged, its popup stays`() {
        prefs.recents = EmojiRecents().use("🐸").use("🌋")
        prefs.pins = EmojiPins(listOf("🍕"))
        val panel = panel()
        val grid = gridOf(panel)
        drag(grid, from = 0, to = 1, y = recentY(grid, pinRows = 1))
        assertEquals(listOf("🍕"), prefs.pins.items)
        assertTrue(popupShown(panel))
    }

    @Test
    fun `dragging keeps the place of a pinned emoji that is hidden`() {
        prefs.pins = EmojiPins(listOf("🍕", "🔥", "🐸"))
        prefs.hidden = EmojiHidden().toggleEmoji("🔥")
        drag(gridOf(panel()), from = 0, to = 1)
        assertEquals(listOf("🐸", "🔥", "🍕"), prefs.pins.items)
    }

    @Test
    fun `any number of emoji can be pinned`() {
        val first = assets.catalog.of(EmojiCategory.SMILEYS).take(12)
        prefs.pins = first.fold(EmojiPins()) { pins, emoji -> pins.toggle(emoji.text) }
        tapMine(gridOf(panel()), 0)
        assertEquals(12, prefs.pins.items.size)
        assertEquals(listOf(first.last().text), picked)
    }

    @Test
    fun `hiding from the popup removes the emoji from the picker`() {
        prefs.recents = EmojiRecents().use("🐸").use("🔥")
        val panel = panel()
        holdMine(gridOf(panel), 0)
        choose(panel, "Hide")
        assertTrue("🔥" in prefs.hidden.emoji)
        tapMine(gridOf(panel), 0)
        assertEquals(listOf("🐸"), picked)
        assertTrue(!popupShown(panel))
    }

    @Test
    fun `a hidden category has no chip`() {
        val all = chipsOf(panel()).childCount
        prefs.hidden = EmojiHidden().toggleCategory(EmojiCategory.FLAGS)
        assertEquals(all - 1, chipsOf(panel()).childCount)
    }

    @Test
    fun `a hidden emoji is not in the grid and a tap lands on the next one`() {
        prefs.hidden = EmojiHidden().toggleEmoji("😀")
        tapCell(gridOf(panel()), 0)
        assertEquals(listOf("😃"), picked)
    }

    @Test
    fun `hidden emoji leave your own emoji too`() {
        prefs.recents = EmojiRecents().use("🐸").use("🔥")
        prefs.pins = EmojiPins().toggle("🇷🇺")
        prefs.hidden = EmojiHidden().toggleEmoji("🔥").toggleCategory(EmojiCategory.FLAGS)
        tapMine(gridOf(panel()), 0)
        assertEquals(listOf("🐸"), picked)
    }

    @Test
    fun `a hidden subcategory is gone from the grid`() {
        val first = assets.catalog.of(EmojiCategory.SMILEYS).first()
        prefs.hidden = EmojiHidden(subcategories = setOf(first.subcategory))
        tapCell(gridOf(panel()), 0)
        assertTrue(assets.catalog.find(picked.single())!!.subcategory != first.subcategory)
    }

    @Test
    fun `every subcategory of the catalog has a name`() {
        val unnamed =
            assets.catalog.categories
                .flatMap { assets.catalog.subcategories(it) }
                .filter { subcategoryTitle(it) == null }
        assertEquals(emptyList<String>(), unnamed)
    }

    @Test
    fun `the back button and backspace report to the listener`() {
        val panel = panel()
        backOf(panel).performClick()
        assertEquals(1, backs)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        backspaceOf(panel).dispatchTouchEvent(down)
        val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 1f, 1f, 0)
        backspaceOf(panel).dispatchTouchEvent(up)
        assertEquals(1, backspaces)
    }

    @Test
    fun `a chip is as tall as its strip, so its pill is whole and easy to touch`() {
        val chips = chipsOf(panel())
        assertEquals((44 * density).toInt(), chips.getChildAt(0).height)
        assertEquals((44 * density).toInt(), chips.getChildAt(1).height)
    }

    @Test
    fun `the buttons float above the bottom of the grid`() {
        val panel = panel()
        val grid = gridOf(panel)
        assertEquals(panel.height, grid.bottom)
        assertTrue(backOf(panel).top in grid.top until grid.bottom)
    }

    @Test
    fun `a chip scrolls the grid to its section, and the last emoji clears the buttons`() {
        prefs.recents = EmojiRecents().use("🐸").use("🔥")
        prefs.pins = EmojiPins().toggle("🍕")
        val panel = panel()
        render(panel, "emoji-panel-dark")
        chipsOf(panel).getChildAt(4).performClick()
        render(panel, "emoji-panel-food-dark")
        tapCell(gridOf(panel), 0)
        assertEquals(
            assets.catalog
                .of(EmojiCategory.FOOD)
                .first()
                .text,
            picked.single(),
        )
    }
}
