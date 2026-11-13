package ru.tinyops.android.vboard.core.voice

import ru.tinyops.android.vboard.core.input.CursorWindow

/** What a custom button of the strip does with the text of the field. */
enum class ButtonAction(
    val id: String,
) {
    CLEAR("clear"),
    COPY("copy"),
    PASTE("paste"),
    PROMPT("prompt"),

    /** Opens the list of the chosen quick replies; a tap only, as the list stands under the button. */
    QUICK_REPLIES("quick_replies"),
    ;

    companion object {
        fun fromId(id: String?): ButtonAction? = entries.firstOrNull { it.id == id }
    }
}

/** Where the answer of a prompt goes, and with that what is sent to the server. */
enum class InsertMode(
    val id: String,
) {
    /** The whole text is sent and the answer replaces it. */
    REPLACE_ALL("replace_all"),

    /** The selection, or the whole text without one, is sent and the answer replaces it. */
    REPLACE_SELECTION("replace_selection"),

    /** The selection or the whole text is sent and the answer goes to the end of the field. */
    APPEND("append"),

    /** The selection or the whole text is sent and the answer goes to the cursor, after a selection. */
    AT_CURSOR("at_cursor"),
    ;

    companion object {
        fun fromId(id: String?): InsertMode = entries.firstOrNull { it.id == id } ?: REPLACE_SELECTION
    }
}

/** Which part of its row a custom button goes to: the strip, or the fullscreen panel for a button shown only there. */
enum class ButtonPlacement(
    val id: String,
) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
    ;

    companion object {
        fun fromId(id: String?): ButtonPlacement = entries.firstOrNull { it.id == id } ?: LEFT
    }
}

/** When a custom button is shown; on the strip word suggestions take its place either way. */
enum class ButtonVisibility(
    val id: String,
) {
    ALWAYS("always"),
    WITH_TEXT("with_text"),

    /** Only on the panel of the fullscreen mode, never on the strip. */
    FULLSCREEN("fullscreen"),
    ;

    companion object {
        fun fromId(id: String?): ButtonVisibility = entries.firstOrNull { it.id == id } ?: WITH_TEXT
    }
}

/** A color of a custom button's icon, in the tones that stand out on a light and on a dark keyboard. */
enum class ButtonColor(
    val id: String,
    private val onLight: Int,
    private val onDark: Int,
) {
    RED("red", 0xFFD32F2F.toInt(), 0xFFE57373.toInt()),
    ORANGE("orange", 0xFFD84E00.toInt(), 0xFFFFB74D.toInt()),
    YELLOW("yellow", 0xFFA67C00.toInt(), 0xFFFFF176.toInt()),
    GREEN("green", 0xFF2E7D32.toInt(), 0xFF81C784.toInt()),
    TEAL("teal", 0xFF00796B.toInt(), 0xFF4DB6AC.toInt()),
    BLUE("blue", 0xFF1565C0.toInt(), 0xFF64B5F6.toInt()),
    PURPLE("purple", 0xFF6A1B9A.toInt(), 0xFFBA68C8.toInt()),
    PINK("pink", 0xFFC2185B.toInt(), 0xFFF48FB1.toInt()),
    ;

    /** The ARGB color for a keyboard or a page with a dark background or a light one. */
    fun tint(dark: Boolean): Int = if (dark) onDark else onLight

    companion object {
        fun fromId(id: String?): ButtonColor? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One thing a button does. A prompt command sends its [promptIds] to the server [serverId], or to the
 * default server when that is null or was deleted. With [pickPrompt] it asks on every use which prompt
 * of the collection to send instead. A quick replies command offers its [replyIds] and inserts the chosen
 * one as [insert] says.
 */
data class ButtonCommand(
    val action: ButtonAction,
    val serverId: String? = null,
    val promptIds: List<String> = emptyList(),
    val insert: InsertMode = InsertMode.REPLACE_SELECTION,
    val pickPrompt: Boolean = false,
    val replyIds: List<String> = emptyList(),
) {
    /** The same command sending only the prompt [id] that the user chose. */
    fun withPrompt(id: String): ButtonCommand = copy(promptIds = listOf(id), pickPrompt = false)
}

/**
 * A button the user put on the strip: what it does on a tap and on a hold, either of which may be
 * nothing. [name] tells it from the others in the settings and may be empty. [icon] names one of the icons on offer, null means the one of the action; without a [color]
 * the icon looks like the others of the strip. A button that is not [enabled] keeps its settings and is
 * shown nowhere on the keyboard.
 *
 * A [group] does nothing itself: a tap on it opens a small popup with its members, the buttons whose
 * [groupId] is its id. A member keeps its own commands, icon and color; where it stands and when it is shown
 * is decided by its group. A group is never a member of another group.
 *
 * A button with [secondRow] stands in the second row of the strip even when the strip has room left.
 */
data class CustomButton(
    val id: String,
    val tap: ButtonCommand? = null,
    val hold: ButtonCommand? = null,
    val icon: String? = null,
    val color: ButtonColor? = null,
    val placement: ButtonPlacement = ButtonPlacement.LEFT,
    val visibility: ButtonVisibility = ButtonVisibility.WITH_TEXT,
    val name: String = "",
    val enabled: Boolean = true,
    val group: Boolean = false,
    val groupId: String? = null,
    val secondRow: Boolean = false,
) {
    /** The button stands on the panel of the fullscreen mode instead of the strip. */
    val fullscreenOnly: Boolean get() = visibility == ButtonVisibility.FULLSCREEN

    /** Whether the button is on the strip of a field with or without text. */
    fun shownFor(fieldHasText: Boolean): Boolean =
        when (visibility) {
            ButtonVisibility.ALWAYS -> true
            ButtonVisibility.WITH_TEXT -> fieldHasText
            ButtonVisibility.FULLSCREEN -> false
        }

    /** The command that stands for the button where only one can be named. */
    val main: ButtonCommand? get() = tap ?: hold

    companion object {
        /**
         * How many buttons a section of the settings takes: the strip and the panel of the fullscreen mode
         * each count their own. Those that do not fit on the screen are kept and not shown.
         */
        const val MAX_PER_SECTION = 20
        const val MAX_NAME_LENGTH = 40

        /** How many buttons a group takes. */
        const val MAX_PER_GROUP = 12
    }
}

/**
 * Where the custom buttons stand in the free part of the strip.
 *
 * @property start the start of the room the buttons were given
 * @property end its end
 * @property lefts the left edge of every button that fits, in the order of the list
 * @property gapStart the start of the widest free stretch left between the buttons
 * @property gapEnd its end
 */
class ButtonPlaces(
    val lefts: FloatArray,
    val size: Float,
    val start: Float,
    val end: Float,
    val gapStart: Float,
    val gapEnd: Float,
) {
    /** The index of the button under [x], or -1. */
    fun indexAt(x: Float): Int = lefts.indexOfFirst { x >= it && x < it + size }

    companion object {
        /**
         * Places the first buttons that fit between [start] and [end]: those on the left go from the start,
         * those on the right end at the end, and those in the center keep to the middle as far as the others
         * leave room.
         */
        fun of(
            placements: List<ButtonPlacement>,
            start: Float,
            end: Float,
            size: Float,
        ): ButtonPlaces {
            val shown = placements.take(((end - start) / size).toInt().coerceAtLeast(0))
            val leftEnd = start + shown.count { it == ButtonPlacement.LEFT } * size
            val rightStart = end - shown.count { it == ButtonPlacement.RIGHT } * size
            val centerWidth = shown.count { it == ButtonPlacement.CENTER } * size
            val centerStart = ((start + end - centerWidth) / 2f).coerceIn(leftEnd, rightStart - centerWidth)
            val next = floatArrayOf(start, centerStart, rightStart)
            val lefts =
                FloatArray(shown.size) { index ->
                    val part = shown[index].ordinal
                    next[part].also { next[part] = it + size }
                }
            val before = centerStart - leftEnd
            val after = rightStart - (centerStart + centerWidth)
            return when {
                centerWidth == 0f -> ButtonPlaces(lefts, size, start, end, leftEnd, rightStart)
                before >= after -> ButtonPlaces(lefts, size, start, end, leftEnd, centerStart)
                else -> ButtonPlaces(lefts, size, start, end, centerStart + centerWidth, rightStart)
            }
        }
    }
}

/** The buttons that stand on their own: not a member of a group that exists. */
fun List<CustomButton>.topLevel(): List<CustomButton> {
    val groups = filter { it.group }.mapTo(HashSet()) { it.id }
    return filter { it.group || it.groupId !in groups }
}

/** The members of the group [groupId], in the order of the list. */
fun List<CustomButton>.membersOf(groupId: String): List<CustomButton> = filter { !it.group && it.groupId == groupId }

/** The list without the button [id]; for a group its members go too. */
fun List<CustomButton>.withoutButton(id: String): List<CustomButton> = filterNot { it.id == id || it.groupId == id }

/** Adds the button, or replaces the one with the same id keeping its position. */
fun List<CustomButton>.withButton(button: CustomButton): List<CustomButton> =
    if (any { it.id == button.id }) map { if (it.id == button.id) button else it } else this + button

/** The list with the buttons of [arranged] in their new order and places; the others stay where they stand. */
fun List<CustomButton>.withArrangedPart(arranged: List<CustomButton>): List<CustomButton> {
    val ids = arranged.mapTo(HashSet()) { it.id }
    val next = arranged.iterator()
    return map { if (it.id in ids && next.hasNext()) next.next() else it }
}

/** Moves the button to the end of the list, where the buttons that do not fit are. */
fun List<CustomButton>.withButtonLast(id: String): List<CustomButton> {
    val button = firstOrNull { it.id == id } ?: return this
    return filterNot { it.id == id } + button
}

/**
 * The arrangement for a button dragged so that its center is at [center]: of all the places it could take
 * among the buttons that fit between [start] and [end], the nearest one. The current arrangement wins a tie,
 * so a button at rest does not jump.
 *
 * @param last what stands after the buttons on the strip and always keeps its places there
 */
fun List<CustomButton>.withButtonDropped(
    id: String,
    center: Float,
    start: Float,
    end: Float,
    size: Float,
    last: List<ButtonPlacement> = emptyList(),
): List<CustomButton> {
    val button = firstOrNull { it.id == id } ?: return this
    val fit = ((end - start) / size).toInt() - last.size
    if (fit <= 0) return this
    val others = filterNot { it.id == id }

    fun distance(
        list: List<CustomButton>,
        index: Int,
    ): Float {
        if (index >= fit) return Float.MAX_VALUE
        val placements = list.take(fit).map { it.placement } + last
        val left = ButtonPlaces.of(placements, start, end, size).lefts[index]
        return kotlin.math.abs(left + size / 2f - center)
    }
    var best = this
    var nearest = distance(this, indexOf(button))
    for (placement in ButtonPlacement.entries) {
        for (index in 0..minOf(others.size, fit - 1)) {
            val candidate = others.toMutableList().apply { add(index, button.copy(placement = placement)) }
            val away = distance(candidate, index)
            if (away < nearest) {
                nearest = away
                best = candidate
            }
        }
    }
    return best
}

/**
 * How many of the first buttons stand on the strip: as many as [fit], and not those from the first one
 * that was put in the second row.
 */
fun List<CustomButton>.stripCount(fit: Int): Int {
    val pinned = indexOfFirst { it.secondRow }
    return minOf(fit.coerceAtLeast(0), if (pinned < 0) size else pinned)
}

/**
 * The arrangement for a button dragged onto the strip so that its center is at [center]. The button leaves
 * the second row; the buttons of the second row stay after those of the strip.
 */
fun List<CustomButton>.withButtonDroppedOnStrip(
    id: String,
    center: Float,
    start: Float,
    end: Float,
    size: Float,
    last: List<ButtonPlacement> = emptyList(),
): List<CustomButton> {
    val button = firstOrNull { it.id == id } ?: return this
    val others = filterNot { it.id == id }
    val onStrip = others.take(others.stripCount(((end - start) / size).toInt() - last.size))
    val placed = (onStrip + button.copy(secondRow = false)).withButtonDropped(id, center, start, end, size, last)
    return placed + others.drop(onStrip.size)
}

/**
 * The arrangement for a button dragged below the strip so that its center is at [center]: the first [above]
 * of the other buttons keep the strip, and the button takes the nearest place among the rest, which stand
 * between [start] and [end] of the second row. The button stays in the second row even if the strip has room.
 */
fun List<CustomButton>.withButtonDroppedBelow(
    id: String,
    center: Float,
    start: Float,
    end: Float,
    size: Float,
    above: Int,
): List<CustomButton> {
    if (none { it.id == id }) return this
    val head = filterNot { it.id == id }.take(above)
    val headIds = head.mapTo(HashSet()) { it.id }
    val below = filterNot { it.id in headIds }
    return head +
        below.withButtonDropped(id, center, start, end, size).map { if (it.id == id) it.copy(secondRow = true) else it }
}

/**
 * What a prompt button sends and which range of the editor its answer takes: [start] and [end] are
 * equal when the answer is only inserted.
 *
 * @property separated a space goes before the answer, which would otherwise stick to the word before it
 */
data class ButtonRequest(
    val text: String,
    val start: Int,
    val end: Int,
    val separated: Boolean = false,
) {
    /** What goes into the editor for this [answer]. */
    fun replacement(answer: String): String = if (separated) " $answer" else answer

    companion object {
        /** Null when there is nothing to send or the editor does not tell where its text is. */
        fun of(
            window: CursorWindow,
            mode: InsertMode,
        ): ButtonRequest? {
            val source = ProofreadTarget.of(if (mode == InsertMode.REPLACE_ALL) wholeOf(window) else window)
            source ?: return null
            return when (mode) {
                InsertMode.REPLACE_ALL ->
                    ButtonRequest(source.text, window.offset, window.offset + window.text.length)
                InsertMode.REPLACE_SELECTION -> ButtonRequest(source.text, source.start, source.end)
                InsertMode.APPEND -> insertion(source.text, window, window.text.length)
                InsertMode.AT_CURSOR -> insertion(source.text, window, window.selectionEnd)
            }
        }

        /** Whether the text the request needs may reach past [limit] characters on a side of the cursor. */
        fun tooLong(
            window: CursorWindow,
            mode: InsertMode,
            limit: Int,
        ): Boolean {
            val needsWhole = mode == InsertMode.REPLACE_ALL || mode == InsertMode.APPEND
            return ProofreadTarget.tooLong(if (needsWhole) wholeOf(window) else window, limit)
        }

        private fun insertion(
            text: String,
            window: CursorWindow,
            position: Int,
        ): ButtonRequest {
            val separated = position > 0 && !window.text[position - 1].isWhitespace()
            return ButtonRequest(text, window.offset + position, window.offset + position, separated)
        }

        /** The same window with the selection collapsed to its end, so that it stands for the whole text. */
        private fun wholeOf(window: CursorWindow) =
            CursorWindow(window.text, window.selectionEnd, window.selectionEnd, window.offset)
    }
}
