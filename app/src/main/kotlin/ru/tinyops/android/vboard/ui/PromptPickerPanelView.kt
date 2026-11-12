package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.layout.KeyStyle
import ru.tinyops.android.vboard.core.voice.SavedPrompt

/**
 * The prompts of the collection that replace the keys when a custom button asks which one to send: a
 * header with back and one card per prompt, its title in the prompt's color above the start of its text.
 */
@SuppressLint("ViewConstructor")
class PromptPickerPanelView(
    context: Context,
    private val theme: KeyboardTheme,
) : LinearLayout(context) {
    interface Listener {
        fun onPromptPickerBack()

        fun onPromptPicked(id: String)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val darkTheme = ColorUtils.calculateLuminance(theme.stripIcon) > HALF_LUMINANCE
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll =
        ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    init {
        orientation = VERTICAL
        addView(header(), LayoutParams(LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT)))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Shows [prompts] from the top of the list. */
    fun open(prompts: List<SavedPrompt>) {
        content.removeAllViews()
        content.setPadding(dp(SIDE), 0, dp(SIDE), dp(SIDE))
        prompts.forEach { content.addView(card(it), cardParams()) }
        scroll.scrollTo(0, 0)
    }

    private fun header(): View =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE), 0, dp(SIDE), 0)
            addView(backButton(), LayoutParams(dp(BUTTON), dp(BUTTON)))
            addView(
                TextView(context).apply {
                    setText(R.string.prompt_choose_title)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SIZE)
                    setTextColor(theme.suggestion)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(GAP * 2) },
            )
        }

    private fun backButton(): ImageView =
        ImageView(context).apply {
            setImageDrawable(
                requireNotNull(
                    context.getDrawable(R.drawable.ic_arrow_back),
                ).mutate().apply { setTint(theme.stripIcon) },
            )
            setPadding(dp(BUTTON_PADDING), dp(BUTTON_PADDING), dp(BUTTON_PADDING), dp(BUTTON_PADDING))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.keyColor(KeyStyle.LETTER, false))
                }
            contentDescription = context.getString(R.string.settings_back)
            setOnClickListener { listener?.onPromptPickerBack() }
        }

    private fun cardParams() =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(GAP) }

    private fun card(prompt: SavedPrompt): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(CARD_PADDING), dp(CARD_PADDING), dp(CARD_PADDING), dp(CARD_PADDING))
            background =
                GradientDrawable().apply {
                    setColor(theme.keyColor(KeyStyle.LETTER, false))
                    cornerRadius = dp(CARD_RADIUS).toFloat()
                }
            addView(
                TextView(context).apply {
                    text = prompt.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TITLE_SIZE)
                    setTextColor(prompt.color?.tint(darkTheme) ?: theme.suggestion)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(context).apply {
                    text = prompt.text.replace('\n', ' ')
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SIZE)
                    setTextColor(theme.hint)
                    maxLines = TEXT_LINES
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            setOnClickListener { listener?.onPromptPicked(prompt.id) }
        }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val HALF_LUMINANCE = 0.5
        const val HEADER_HEIGHT = 56
        const val BUTTON = 44
        const val BUTTON_PADDING = 10
        const val SIDE = 8
        const val GAP = 8
        const val TITLE_SIZE = 22f
        const val CARD_TITLE_SIZE = 16f
        const val CARD_TEXT_SIZE = 13f
        const val CARD_PADDING = 14
        const val CARD_RADIUS = 16
        const val TEXT_LINES = 2
    }
}
