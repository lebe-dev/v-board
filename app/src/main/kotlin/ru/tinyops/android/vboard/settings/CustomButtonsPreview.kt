package ru.tinyops.android.vboard.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonPlaces
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.stripCount
import ru.tinyops.android.vboard.core.voice.withButtonDroppedBelow
import ru.tinyops.android.vboard.core.voice.withButtonDroppedOnStrip
import ru.tinyops.android.vboard.core.voice.withButtonLast
import ru.tinyops.android.vboard.ui.ButtonIcons
import kotlin.math.roundToInt

/** The button under the finger and where the others stand around it; nothing is stored until the drop. */
private class PreviewDrag(
    val id: String,
    val list: List<CustomButton>,
    val x: Float,
    val y: Float,
)

/**
 * The row a preview models.
 *
 * @property left the icons that stay at its start
 * @property right the icons that stay at its end
 * @property moving the icons that keep their places after the custom buttons, and those places
 * @property overflowLabel what the row of the buttons that do not fit is called
 */
private class PreviewRow(
    val left: List<Int>,
    val right: List<Int>,
    val moving: List<Pair<Int, ButtonPlacement>>,
    val overflowLabel: Int,
)

/**
 * What stands on the strip besides the custom buttons, and how it lays them out.
 *
 * @property widthScale the width of a button that can be moved, as a share of the strip's height
 * @property secondRow the buttons that do not fit on the strip go to a row under it
 * @property proofread the proofread button takes its place on the strip
 * @property clipboard the part of the strip the clipboard button stands in, null without it
 * @property fullscreen the part of the strip the fullscreen button stands in, null without it
 */
internal class StripShape(
    val widthScale: Float = 1f,
    val secondRow: Boolean = false,
    val proofread: Boolean = false,
    val clipboard: ButtonPlacement? = null,
    val fullscreen: ButtonPlacement? = null,
)

/** What the fingers do to an icon of the preview. */
private class IconGestures(
    val onStart: () -> Unit,
    val onMove: (Float, Float) -> Unit,
    val onEnd: (Boolean) -> Unit,
    val onClick: () -> Unit,
)

/**
 * The strip in the proportions it has on this screen, in pixels of the preview. As on the keyboard, the
 * clipboard and fullscreen buttons always keep their places and come after the custom buttons.
 *
 * @property row the height of the strip, and the width of the buttons at its ends
 * @property size the width of a button that can be moved
 * @property width the width of the whole strip and of its second row
 * @property secondRow the buttons that do not fit on the strip go to a row under it
 */
private class PreviewGeometry(
    val row: Float,
    val size: Float,
    val start: Float,
    val end: Float,
    val width: Float,
    val secondRow: Boolean,
    val fixed: List<ButtonPlacement>,
    val belowRows: Float,
) {
    val fit: Int = (((end - start) / size).toInt() - fixed.size).coerceAtLeast(0)

    /** How many buttons a row of its own holds: the second one, or one of those that do not fit. */
    val perRow: Int = (width / size).toInt().coerceAtLeast(1)

    /** How many of the buttons stand on the strip: the second row begins at the first button put there. */
    fun onStrip(list: List<CustomButton>): Int = if (secondRow) list.stripCount(fit) else fit

    /** With the second row on the model always shows it, so that a button can be dragged down into it. */
    fun rows(): Int = if (secondRow) 2 else 1

    /** How many buttons stand on the strip and in its second row. */
    fun shown(list: List<CustomButton>): Int = minOf(list.size, onStrip(list) + if (secondRow) perRow else 0)

    /** Where the rows of the buttons that do not fit start, under the strip and the guides. */
    val overflowTop: Float get() = rows() * row + belowRows

    fun places(list: List<CustomButton>) =
        ButtonPlaces.of(list.take(onStrip(list)).map { it.placement } + fixed, start, end, size)

    fun secondPlaces(list: List<CustomButton>) =
        ButtonPlaces.of(list.drop(onStrip(list)).take(perRow).map { it.placement }, 0f, width, size)

    /** Where the button at [index] stands: on the strip, in its second row or among those that do not fit. */
    fun position(
        index: Int,
        list: List<CustomButton>,
        places: ButtonPlaces,
        second: ButtonPlaces,
    ): Pair<Float, Float> {
        val strip = onStrip(list)
        if (index < strip) return places.lefts[index] to 0f
        if (index < shown(list)) return second.lefts[index - strip] to row
        val rest = index - shown(list)
        return (rest % perRow) * size to overflowTop + (rest / perRow) * row
    }
}

/**
 * The custom buttons on a model of the strip. Dragging an icon moves it among the others and between the
 * left, the center and the right of the strip, to its second row, or into the rows of buttons that do not
 * fit; a tap opens it.
 *
 * @param shape what else stands on the strip and how wide the buttons are
 * @param names what a screen reader calls a button
 */
@Composable
internal fun ButtonsPreview(
    buttons: List<CustomButton>,
    shape: StripShape,
    names: @Composable (CustomButton) -> String,
    onArrange: (List<CustomButton>) -> Unit,
    onOpen: (String) -> Unit,
) {
    RowPreview(
        row =
            PreviewRow(
                left = listOf(R.drawable.ic_settings),
                right = listOfNotNull(R.drawable.ic_auto_fix_high.takeIf { shape.proofread }, R.drawable.ic_mic),
                moving =
                    listOfNotNull(
                        shape.clipboard?.let { R.drawable.ic_content_paste to it },
                        shape.fullscreen?.let { R.drawable.ic_fullscreen to it },
                    ),
                overflowLabel = R.string.custom_buttons_overflow,
            ),
        widthScale = shape.widthScale,
        secondRow = shape.secondRow,
        buttons = buttons,
        names = names,
        onArrange = onArrange,
        onOpen = onOpen,
    )
}

/**
 * The buttons shown only in the fullscreen mode on a model of its panel; they are dragged as on the strip.
 *
 * @param names what a screen reader calls a button
 */
@Composable
internal fun FullscreenPanelPreview(
    buttons: List<CustomButton>,
    names: @Composable (CustomButton) -> String,
    onArrange: (List<CustomButton>) -> Unit,
    onOpen: (String) -> Unit,
) {
    RowPreview(
        row =
            PreviewRow(
                left = listOf(R.drawable.ic_select_all, R.drawable.ic_undo, R.drawable.ic_content_copy),
                right = listOf(R.drawable.ic_fullscreen_exit),
                moving = emptyList(),
                overflowLabel = R.string.custom_buttons_overflow_panel,
            ),
        buttons = buttons,
        names = names,
        onArrange = onArrange,
        onOpen = onOpen,
    )
}

@Composable
private fun RowPreview(
    row: PreviewRow,
    buttons: List<CustomButton>,
    names: @Composable (CustomButton) -> String,
    onArrange: (List<CustomButton>) -> Unit,
    onOpen: (String) -> Unit,
    widthScale: Float = 1f,
    secondRow: Boolean = false,
) {
    val screenWidth = LocalResources.current.displayMetrics.widthPixels
    val stripHeight = with(LocalDensity.current) { dimensionResource(R.dimen.kb_suggestion_height).toPx() }
    var drag by remember { mutableStateOf<PreviewDrag?>(null) }
    val shown = drag?.list ?: buttons
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val rowSize = stripHeight * width / screenWidth
        val size = rowSize * widthScale
        val geometry =
            PreviewGeometry(
                row = rowSize,
                size = size,
                start = row.left.size * rowSize,
                end = width - row.right.size * rowSize,
                width = width,
                secondRow = secondRow,
                fixed = row.moving.map { it.second },
                belowRows = with(density) { (GUIDE_HEIGHT + OVERFLOW_LABEL_HEIGHT).toPx() },
            )
        val places = geometry.places(shown)
        val second = geometry.secondPlaces(shown)
        val rows = geometry.rows()
        val left = shown.size - geometry.shown(shown)
        val overflowRows = (left + geometry.perRow - 1) / geometry.perRow
        val rowDp = with(density) { rowSize.toDp() }
        val buttonSize = with(density) { size.toDp() }
        val stripDp = rowDp * rows
        val total = stripDp + GUIDE_HEIGHT + if (left > 0) OVERFLOW_LABEL_HEIGHT + rowDp * overflowRows else 0.dp
        Box(Modifier.fillMaxWidth().height(total)) {
            RowBackdrop(rowDp, rows, row, width)
            val fixedStart = geometry.onStrip(shown)
            row.moving.forEachIndexed { index, (icon, _) ->
                places.lefts.getOrNull(fixedStart + index)?.let { MovingIcon(icon, rowDp, buttonSize, it) }
            }
            ZoneGuides(stripDp, shown.firstOrNull { it.id == drag?.id }?.placement)
            if (left > 0) {
                Text(
                    stringResource(row.overflowLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = stripDp + GUIDE_HEIGHT),
                )
            }
            shown.forEachIndexed { index, button ->
                key(button.id) {
                    val (x, y) = geometry.position(index, shown, places, second)
                    DraggableIcon(
                        button = button,
                        width = buttonSize,
                        height = rowDp,
                        target = IntOffset(x.roundToInt(), y.roundToInt()),
                        dragged = drag?.takeIf { it.id == button.id },
                        name = names(button),
                        gestures =
                            IconGestures(
                                onStart = { drag = PreviewDrag(button.id, shown, x, y) },
                                onMove = { dx, dy -> drag = drag?.let { moved(it, dx, dy, geometry) } },
                                onEnd = { cancelled ->
                                    val finished = drag
                                    drag = null
                                    if (!cancelled && finished != null && finished.list != buttons) {
                                        onArrange(finished.list)
                                    }
                                },
                                onClick = { onOpen(button.id) },
                            ),
                    )
                }
            }
        }
    }
}

private fun moved(
    drag: PreviewDrag,
    dx: Float,
    dy: Float,
    geometry: PreviewGeometry,
): PreviewDrag {
    val x = drag.x + dx
    val y = drag.y + dy
    val center = x + geometry.size / 2f
    val middle = y + geometry.row / 2f
    val list = drag.list
    val dropped =
        when {
            list.size > geometry.shown(list) && middle > geometry.overflowTop -> list.withButtonLast(drag.id)
            geometry.rows() == 2 && middle > geometry.row ->
                list.withButtonDroppedBelow(
                    drag.id,
                    center,
                    0f,
                    geometry.width,
                    geometry.size,
                    list.filterNot { it.id == drag.id }.let { it.take(geometry.onStrip(it)) }.size,
                )
            else ->
                list.withButtonDroppedOnStrip(
                    drag.id,
                    center,
                    geometry.start,
                    geometry.end,
                    geometry.size,
                    geometry.fixed,
                )
        }
    return PreviewDrag(drag.id, dropped, x, y)
}

/** What stays put whatever the buttons do; the second row, when there is one, joins the strip from below. */
@Composable
private fun RowBackdrop(
    size: Dp,
    rows: Int,
    row: PreviewRow,
    width: Float,
) {
    val density = LocalDensity.current
    val tint = MaterialTheme.colorScheme.outline
    val sizePx = with(density) { size.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(size * rows)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(STRIP_CORNER)),
    ) {
        row.left.forEachIndexed { index, icon ->
            FixedIcon(icon, tint, size, IntOffset((index * sizePx).roundToInt(), 0))
        }
        row.right.forEachIndexed { index, icon ->
            val left = width - (row.right.size - index) * sizePx
            FixedIcon(icon, tint, size, IntOffset(left.roundToInt(), 0))
        }
    }
}

/** The clipboard and fullscreen buttons move as the custom buttons around them do. */
@Composable
private fun MovingIcon(
    icon: Int,
    row: Dp,
    width: Dp,
    left: Float,
) {
    val x by animateFloatAsState(left, label = "fixed button x")
    FixedIcon(
        icon,
        MaterialTheme.colorScheme.outline,
        row,
        IntOffset(x.roundToInt(), 0),
        width,
    )
}

@Composable
private fun FixedIcon(
    icon: Int,
    tint: Color,
    size: Dp,
    at: IntOffset,
    width: Dp = size,
) {
    Box(Modifier.offset { at }.size(width, size), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size / 2))
    }
}

/** The names of the three parts of the strip; the part the dragged button is in stands out. */
@Composable
private fun ZoneGuides(
    top: Dp,
    active: ButtonPlacement?,
) {
    Row(Modifier.fillMaxWidth().offset(y = top).height(GUIDE_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        ButtonPlacement.entries.forEach { placement ->
            Text(
                stringResource(placement.label),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (placement == active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun DraggableIcon(
    button: CustomButton,
    width: Dp,
    height: Dp,
    target: IntOffset,
    dragged: PreviewDrag?,
    name: String,
    gestures: IconGestures,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < HALF_LUMINANCE
    val accent = rememberThemeAccent()
    val tint = button.color?.let { Color(it.tint(dark, accent)) } ?: colors.onSurface
    val x by animateFloatAsState(target.x.toFloat(), label = "button x")
    val y by animateFloatAsState(target.y.toFloat(), label = "button y")
    // The gesture outlives recompositions, and what it does depends on the latest arrangement.
    val latest by rememberUpdatedState(gestures)
    Box(
        modifier =
            Modifier
                .offset {
                    if (dragged != null) {
                        IntOffset(dragged.x.roundToInt(), dragged.y.roundToInt())
                    } else {
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                }.size(width, height)
                // Before the scale: inside it the finger would seem to travel a shorter way and the icon would lag.
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { latest.onStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            latest.onMove(amount.x, amount.y)
                        },
                        onDragEnd = { latest.onEnd(false) },
                        onDragCancel = { latest.onEnd(true) },
                    )
                }.clickable(onClick = gestures.onClick)
                .scale(if (dragged != null) DRAGGED_SCALE else 1f)
                .background(if (dragged != null) colors.primaryContainer else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(ButtonIcons.of(button)),
            contentDescription = name,
            tint = tint,
            modifier = Modifier.size(height * ICON_SHARE),
        )
    }
}

/** The label of a place on the strip. */
internal val ButtonPlacement.label: Int
    get() =
        when (this) {
            ButtonPlacement.LEFT -> R.string.custom_button_placement_left
            ButtonPlacement.CENTER -> R.string.custom_button_placement_center
            ButtonPlacement.RIGHT -> R.string.custom_button_placement_right
        }

private const val HALF_LUMINANCE = 0.5f
private const val DRAGGED_SCALE = 1.15f

/** As on the keyboard, an icon is half as high as the strip, however narrow its button is. */
private const val ICON_SHARE = 0.5f

private val GUIDE_HEIGHT = 24.dp
private val OVERFLOW_LABEL_HEIGHT = 24.dp
private val STRIP_CORNER = 16.dp
