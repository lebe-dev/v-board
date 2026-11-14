package ru.tinyops.android.vboard.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import ru.tinyops.android.vboard.core.layout.KeyStyle
import ru.tinyops.android.vboard.core.voice.LevelFollower
import ru.tinyops.android.vboard.core.voice.VoiceAnimation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The bubble that takes the place of the strip while dictating: a rounded rectangle of the key color with soft
 * edges, filled with the colors of the theme in motion. The aurora drifts along its bottom and rises with the
 * voice; the drops roll through its middle, merge and swell with the voice.
 */
internal class VoiceBubble(
    theme: KeyboardTheme,
) {
    var animation: VoiceAnimation = VoiceAnimation.DEFAULT

    private val base = theme.keyColor(KeyStyle.LETTER, false)
    private val colors = intArrayOf(theme.voiceActive, theme.trail, theme.voiceStatus, theme.voiceActive, theme.trail)

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base }
    private val outline = Path()
    private val edgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = base
            style = Paint.Style.STROKE
        }

    /** Soft spots: a unit circle with the color fading out, placed and stretched by the canvas. */
    private val spots = colors.map { spot(it, floatArrayOf(AURORA_ALPHA, AURORA_ALPHA * HALF, 0f)) }
    private val drops = colors.map { spot(it, floatArrayOf(1f, 1f, 0f)) }

    /** Turns the blurred drops into solid shapes that flow together where they touch. */
    private val gooPaint =
        Paint().apply {
            val matrix = ColorMatrix()
            // The colors stay, the alpha is stretched: a soft edge becomes a sharp one.
            matrix.array[ALPHA_SCALE] = GOO_CONTRAST
            matrix.array[ALPHA_OFFSET] = GOO_OFFSET
            colorFilter = ColorMatrixColorFilter(matrix)
        }

    private val follower = LevelFollower()
    private var lastFrame = 0L

    /** The time of the motion; the drops run faster while the voice is loud. */
    private var clock = 0.0

    private val bounds = RectF()

    /** A new dictation starts calm. */
    fun reset() {
        follower.reset()
        lastFrame = 0L
    }

    /**
     * Draws one frame into [left]..[bottom]. [level] is the loudness of the microphone, [now] the frame time;
     * with [moving] off the picture stays still.
     */
    @Suppress("LongParameterList")
    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        now: Long,
        level: Float,
        moving: Boolean,
    ) {
        val elapsed = if (lastFrame == 0L) 0L else (now - lastFrame).coerceIn(0L, MAX_FRAME_MILLIS)
        lastFrame = now
        val loud = follower.next(level, elapsed)
        if (moving) clock += elapsed * (1.0 + if (animation == VoiceAnimation.DROPS) loud * DROP_SPEEDUP else 0f)
        bounds.set(left, top, right, bottom)
        val radius = bounds.height() * CORNER
        outline.rewind()
        outline.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.withClip(outline) {
            drawRoundRect(bounds, radius, radius, basePaint)
            if (animation == VoiceAnimation.DROPS) drawDrops(this, loud) else drawAurora(this, loud)
            drawEdge(this, radius)
        }
    }

    private fun drawAurora(
        canvas: Canvas,
        loud: Float,
    ) {
        val width = bounds.width()
        val height = bounds.height()
        // In silence the spots hide below the edge, leaving a glowing line; a word lifts them.
        val centerY = bounds.bottom + height * AURORA_SINK - height * AURORA_LIFT * loud
        for ((index, paint) in spots.withIndex().take(AURORA_SPOTS)) {
            val wave = sin(2 * PI * clock / AURORA_PERIODS[index] + index)
            val x = bounds.centerX() + width * AURORA_SWAY[index] * wave.toFloat()
            val stretch = 1f + AURORA_STRETCH * cos(2 * PI * clock / AURORA_PERIODS[index]).toFloat()
            spot(canvas, paint, x, centerY, width * AURORA_WIDTH * stretch, height * AURORA_HEIGHT)
        }
    }

    private fun drawDrops(
        canvas: Canvas,
        loud: Float,
    ) {
        val width = bounds.width()
        val height = bounds.height()
        val goo = canvas.saveLayer(bounds, gooPaint)
        for ((index, paint) in drops.withIndex()) {
            // The paths of the drops swing between two places with an easing, each at its own pace.
            val phase = (1 - cos(2 * PI * clock / DROP_PERIODS[index])) / 2
            val from = DROP_FROM[index]
            val share = from + (DROP_TO[index] - from) * phase.toFloat()
            val x = bounds.centerX() + width * share
            val bob = height * DROP_BOB * loud * sin(2 * PI * clock / DROP_BOB_PERIOD + index).toFloat()
            val size = height * DROP_SIZES[index] / 2 * (1f + DROP_SWELL * loud) / DROP_VISIBLE
            spot(canvas, paint, x, bounds.centerY() + bob, size, size)
        }
        canvas.restoreToCount(goo)
    }

    /** A blurred line of the key color along the edge, so the colors fade before they reach it. */
    private fun drawEdge(
        canvas: Canvas,
        radius: Float,
    ) {
        val blur = bounds.height() * EDGE_BLUR
        if (edgePaint.strokeWidth != blur * 2) {
            edgePaint.strokeWidth = blur * 2
            edgePaint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(bounds, radius, radius, edgePaint)
    }

    private fun spot(
        canvas: Canvas,
        paint: Paint,
        x: Float,
        y: Float,
        radiusX: Float,
        radiusY: Float,
    ) {
        canvas.withTranslation(x, y) {
            scale(radiusX, radiusY)
            drawCircle(0f, 0f, 1f, paint)
        }
    }

    private fun spot(
        color: Int,
        alphas: FloatArray,
    ): Paint {
        val stops = alphas.map { ColorUtils.setAlphaComponent(color, (it * MAX_ALPHA).toInt()) }.toIntArray()
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, 1f, stops, SPOT_STOPS, Shader.TileMode.CLAMP)
        }
    }

    private companion object {
        const val MAX_ALPHA = 255
        const val HALF = 0.6f
        const val CORNER = 0.35f
        const val EDGE_BLUR = 0.12f
        const val MAX_FRAME_MILLIS = 100L
        val SPOT_STOPS = floatArrayOf(0f, 0.5f, 1f)

        const val AURORA_SPOTS = 4
        const val AURORA_ALPHA = 0.95f
        const val AURORA_WIDTH = 0.26f
        const val AURORA_HEIGHT = 0.75f
        const val AURORA_STRETCH = 0.15f
        const val AURORA_SINK = 0.35f
        const val AURORA_LIFT = 0.4f
        val AURORA_PERIODS = doubleArrayOf(5_000.0, 6_500.0, 4_200.0, 7_500.0)
        val AURORA_SWAY = floatArrayOf(0.3f, 0.28f, 0.1f, 0.4f)

        /** Where the alpha factor and offset sit in the 4x5 color matrix. */
        const val ALPHA_SCALE = 18
        const val ALPHA_OFFSET = 19
        const val GOO_CONTRAST = 18f
        const val GOO_OFFSET = -7f * MAX_ALPHA
        const val DROP_SPEEDUP = 1.5f
        const val DROP_SWELL = 0.35f
        const val DROP_BOB = 0.12f
        const val DROP_BOB_PERIOD = 900.0

        /** The share of the blurred spot that stays solid after the goo filter. */
        const val DROP_VISIBLE = 0.8f
        val DROP_SIZES = floatArrayOf(0.55f, 0.42f, 0.38f, 0.48f, 0.3f)
        val DROP_PERIODS = doubleArrayOf(5_000.0, 4_000.0, 6_000.0, 4_600.0, 3_400.0)
        val DROP_FROM = floatArrayOf(-0.09f, 0.17f, -0.26f, 0.32f, -0.38f)
        val DROP_TO = floatArrayOf(0.12f, -0.06f, 0.03f, 0.2f, -0.17f)
    }
}
