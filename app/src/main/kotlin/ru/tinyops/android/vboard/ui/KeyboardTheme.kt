package ru.tinyops.android.vboard.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.SparseArray
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.layout.KeyStyle

/** Colors (from the selected [KeyboardPalette]), dimensions and icons of the keyboard. */
class KeyboardTheme(
    private val context: Context,
    palette: KeyboardPalette = KeyboardPalette.fromResources(context),
    private val shapes: KeyShapes = KeyShapes(),
) {
    val background = palette.background
    val keyShadow = palette.keyShadow
    val label = palette.label
    val hint = palette.hint
    val actionIcon = palette.actionIcon
    val preview = palette.preview

    /** Surfaces that float above the keyboard background: halfway between it and a letter key. */
    val raised = ColorUtils.blendARGB(palette.background, palette.key, RAISED_BLEND)
    val popupSelected = palette.popupSelected
    val popupSelectedLabel = palette.popupSelectedLabel
    val trail = palette.trail
    val suggestion = palette.suggestion
    val suggestionDivider = palette.suggestionDivider
    val suggestionPressed = palette.suggestionPressed
    val stripIcon = palette.stripIcon
    val keyIcon = palette.keyIcon
    val voiceActive = palette.voiceActive
    val voiceActiveIcon = palette.voiceActiveIcon
    val voiceHalo = palette.voiceHalo
    val voiceStatus = palette.voiceStatus

    val rowHeight = dimen(R.dimen.kb_row_height)
    val keyGapHorizontal = dimen(R.dimen.kb_key_gap_horizontal)
    val keyGapVertical = dimen(R.dimen.kb_key_gap_vertical)
    val paddingHorizontal = dimen(R.dimen.kb_padding_horizontal)
    val paddingTop = dimen(R.dimen.kb_padding_top)
    val paddingBottom = dimen(R.dimen.kb_padding_bottom)
    val keyRadius = dimen(R.dimen.kb_key_radius)
    val keyShadowOffset = dimen(R.dimen.kb_key_shadow)
    val trailWidth = dimen(R.dimen.kb_trail_width)
    val suggestionHeight = dimen(R.dimen.kb_suggestion_height)
    val suggestionTextSize = dimen(R.dimen.kb_suggestion_text)
    val stripIconSize = dimen(R.dimen.kb_strip_icon)

    private val keyColors =
        mapOf(
            KeyStyle.LETTER to (palette.key to palette.keyPressed),
            KeyStyle.FUNCTION to (palette.functionKey to palette.functionKeyPressed),
            KeyStyle.MODE to (palette.functionKey to palette.functionKeyPressed),
            KeyStyle.ACTION to (palette.actionKey to palette.actionKeyPressed),
        )

    /** Whether a key of this style is a pill rather than a rounded square. */
    fun isPill(style: KeyStyle): Boolean =
        when (style) {
            KeyStyle.MODE -> !shapes.squareMode
            KeyStyle.ACTION -> !shapes.squareAction
            else -> false
        }

    private val icons = SparseArray<Drawable>()

    fun keyColor(
        style: KeyStyle,
        pressed: Boolean,
    ): Int = keyColors.getValue(style).let { if (pressed) it.second else it.first }

    /** A cached, tinted copy of a vector icon. */
    fun icon(
        @DrawableRes id: Int,
        tint: Int,
    ): Drawable {
        val icon =
            icons[id] ?: requireNotNull(context.getDrawable(id)).mutate().also { icons.put(id, it) }
        icon.setTint(tint)
        return icon
    }

    /** The window background. */
    fun backgroundDrawable(): Drawable = background.toDrawable()

    private fun dimen(
        @DimenRes id: Int,
    ): Float = context.resources.getDimension(id)

    private companion object {
        const val RAISED_BLEND = 0.5f
    }
}
