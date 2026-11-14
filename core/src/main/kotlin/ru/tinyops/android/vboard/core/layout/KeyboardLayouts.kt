package ru.tinyops.android.vboard.core.layout

/** Static definitions of every keyboard page. */
object KeyboardLayouts {
    private const val DIGITS = "1234567890"
    private const val WIDE = 1.5f
    private const val NUMPAD_WIDE = 2f

    // A Russian vowel followed by a combining acute accent is the stressed vowel.
    private val RU_STRESSED = "аеиоуыэюя".associate { it.toString() to "$it\u0301" }

    // Starts with the emoji picker: holding the comma and letting go opens it.
    private val COMMA_ALTERNATES =
        listOf(KeySpec.EMOJI_ALTERNATE, ",", "&", "%", "+", "\"", "-", ":", "'", "@", ";", "/", "(", ")", "#", "!", "?")
    private val CURRENCIES = listOf("$", "€", "£", "¥", "₽", "¢", "₹", "₱")
    private val RU_DOUBLE_QUOTES = listOf("«", "»", "„", "“", "”")
    private val EN_DOUBLE_QUOTES = listOf("“", "”", "„", "«", "»")
    private val SYMBOL_ALTERNATES =
        mapOf(
            "'" to listOf("‘", "’", "‚", "‹", "›"),
            "-" to listOf("–", "—", "−", "·"),
            "!" to listOf("¡"),
            "?" to listOf("¿"),
            "#" to listOf("№"),
            "&" to listOf("§"),
            "+" to listOf("±"),
            "(" to listOf("[", "{", "<"),
            ")" to listOf("]", "}", ">"),
            "/" to listOf("÷", "\\"),
            "*" to listOf("†", "‡", "★"),
            ":" to listOf("…"),
            "0" to listOf("°", "⁰", "ⁿ"),
            "1" to listOf("¹", "½", "⅓", "¼"),
            "2" to listOf("²", "⅔"),
            "3" to listOf("³", "¾"),
            "4" to listOf("⁴"),
            "=" to listOf("≠", "≈", "∞"),
            "%" to listOf("‰"),
            "<" to listOf("≤", "«", "‹"),
            ">" to listOf("≥", "»", "›"),
            "~" to listOf("≈"),
            "°" to listOf("′", "″"),
            "[" to listOf("{", "(", "<"),
            "]" to listOf("}", ")", ">"),
        )

    // Russian has eleven letters per row, so its third row narrows the letters a little to give
    // shift and backspace room, and its bottom row widens the keys around a shorter space bar.
    // Every value is a binary fraction, so the rows still add up to exactly eleven units.
    private const val RU_NARROW_LETTER = 0.9375f
    private const val RU_EDGE_KEY = 1.28125f
    private val RU_BOTTOM = BottomWidths(mode = 1.625f, small = 1.125f, space = 4.375f, action = 1.625f)
    private val DEFAULT_BOTTOM = BottomWidths(mode = WIDE, small = 1f, space = 4f, action = WIDE)

    private data class BottomWidths(
        val mode: Float,
        val small: Float,
        val space: Float,
        val action: Float,
    )

    fun spec(
        language: Language,
        mode: KeyboardMode,
    ): KeyboardSpec =
        when (mode) {
            KeyboardMode.LETTERS -> letters(language)
            KeyboardMode.SYMBOLS -> symbols(language)
            KeyboardMode.MORE_SYMBOLS -> moreSymbols(language)
            KeyboardMode.NUMBERS -> numbers(language)
        }

    private fun letters(language: Language): KeyboardSpec =
        when (language) {
            Language.ENGLISH -> english()
            Language.RUSSIAN -> russian()
        }

    private fun english(): KeyboardSpec =
        KeyboardSpec(
            language = Language.ENGLISH,
            mode = KeyboardMode.LETTERS,
            rows =
                listOf(
                    KeyRow(topLetterRow("qwertyuiop")),
                    KeyRow(letterKeys("asdfghjkl"), leftPadding = 0.5f, rightPadding = 0.5f),
                    KeyRow(listOf(shift(WIDE)) + letterKeys("zxcvbnm") + backspace(WIDE)),
                    bottomRow(Language.ENGLISH, KeyboardMode.LETTERS, DEFAULT_BOTTOM),
                ),
        )

    private fun russian(): KeyboardSpec {
        val extra = mapOf("е" to listOf("ё"), "ь" to listOf("ъ"))
        return KeyboardSpec(
            language = Language.RUSSIAN,
            mode = KeyboardMode.LETTERS,
            rows =
                listOf(
                    KeyRow(topLetterRow("йцукенгшщзх", extra, RU_STRESSED)),
                    KeyRow(letterKeys("фывапролджэ", extra, RU_STRESSED)),
                    KeyRow(
                        listOf(shift(RU_EDGE_KEY)) +
                            letterKeys("ячсмитьбю", extra, RU_STRESSED, RU_NARROW_LETTER) +
                            backspace(RU_EDGE_KEY),
                    ),
                    bottomRow(Language.RUSSIAN, KeyboardMode.LETTERS, RU_BOTTOM),
                ),
        )
    }

    private fun symbols(language: Language): KeyboardSpec {
        val currency = if (language == Language.RUSSIAN) "₽" else "$"
        return KeyboardSpec(
            language = language,
            mode = KeyboardMode.SYMBOLS,
            rows =
                listOf(
                    KeyRow(characterKeys(DIGITS, language)),
                    KeyRow(characterKeys("@#${currency}_&-+()/", language)),
                    KeyRow(
                        listOf(modeKey(KeyType.MORE_SYMBOLS, "=\\<", KeyStyle.FUNCTION)) +
                            characterKeys("*\"':;!?", language) +
                            backspace(WIDE),
                    ),
                    bottomRow(language, KeyboardMode.SYMBOLS, DEFAULT_BOTTOM),
                ),
        )
    }

    private fun moreSymbols(language: Language): KeyboardSpec {
        val currencies = if (language == Language.RUSSIAN) "$€£¥" else "£€¥¢"
        return KeyboardSpec(
            language = language,
            mode = KeyboardMode.MORE_SYMBOLS,
            rows =
                listOf(
                    KeyRow(characterKeys("~`|•√π÷×¶∆", language)),
                    KeyRow(characterKeys("$currencies^°={}\\", language)),
                    KeyRow(
                        listOf(modeKey(KeyType.SYMBOLS, "?123", KeyStyle.FUNCTION)) +
                            characterKeys("%©®™✓[]", language) +
                            backspace(WIDE),
                    ),
                    moreSymbolsBottomRow(language),
                ),
        )
    }

    /** Angle brackets take the places of the comma and the period, the number pad that of the language key. */
    private fun moreSymbolsBottomRow(language: Language): KeyRow {
        val widths = DEFAULT_BOTTOM
        return KeyRow(
            listOf(
                alphabet(language, widths.mode),
                characterKey("<", language, widths.small, KeyStyle.FUNCTION),
                KeySpec(KeyType.NUMBERS, label = "1234", width = widths.small),
                KeySpec(KeyType.SPACE, label = language.displayName, output = " ", width = widths.space),
                characterKey(">", language, widths.small, KeyStyle.FUNCTION),
                KeySpec(KeyType.ENTER, output = "\n", width = widths.action, style = KeyStyle.ACTION),
            ),
        )
    }

    /** Digits in a phone grid in the middle, arithmetic on the left, editing keys on the right. */
    private fun numbers(language: Language): KeyboardSpec {
        fun operators(chars: String) = chars.map { characterKey(it.toString(), language, 1f, KeyStyle.FUNCTION) }

        fun digits(chars: String) = chars.map { characterKey(it.toString(), language, NUMPAD_WIDE) }
        return KeyboardSpec(
            language = language,
            mode = KeyboardMode.NUMBERS,
            rows =
                listOf(
                    KeyRow(
                        operators("+-") + digits("123") + characterKey("%", language, NUMPAD_WIDE, KeyStyle.FUNCTION),
                    ),
                    KeyRow(
                        operators("*/") + digits("456") +
                            KeySpec(KeyType.SPACE, label = "␣", output = " ", width = NUMPAD_WIDE),
                    ),
                    KeyRow(
                        listOf(
                            KeySpec(KeyType.SYMBOLS, label = "?123", width = NUMPAD_WIDE, style = KeyStyle.FUNCTION),
                        ) +
                            digits("789") + backspace(NUMPAD_WIDE),
                    ),
                    KeyRow(
                        listOf(alphabet(language, NUMPAD_WIDE)) +
                            listOf(",", "0", ".").map {
                                val style = if (it == "0") KeyStyle.LETTER else KeyStyle.FUNCTION
                                characterKey(it, language, NUMPAD_WIDE, style)
                            } +
                            KeySpec(KeyType.ENTER, output = "\n", width = NUMPAD_WIDE, style = KeyStyle.ACTION),
                    ),
                ),
        )
    }

    private fun bottomRow(
        language: Language,
        mode: KeyboardMode,
        widths: BottomWidths,
    ): KeyRow {
        val switch =
            if (mode == KeyboardMode.LETTERS) {
                KeySpec(KeyType.SYMBOLS, label = "?123", width = widths.mode, style = KeyStyle.MODE)
            } else {
                alphabet(language, widths.mode)
            }
        return KeyRow(
            listOf(
                switch,
                KeySpec(
                    KeyType.CHARACTER,
                    label = ",",
                    alternates = COMMA_ALTERNATES,
                    width = widths.small,
                    style = KeyStyle.FUNCTION,
                ),
                KeySpec(KeyType.LANGUAGE, width = widths.small),
                KeySpec(KeyType.SPACE, label = language.displayName, output = " ", width = widths.space),
                KeySpec(
                    KeyType.CHARACTER,
                    label = ".",
                    alternates = listOf(",", "?", "!", "'", "\"", "-", ":", ";", "(", ")"),
                    width = widths.small,
                    style = KeyStyle.FUNCTION,
                ),
                KeySpec(KeyType.ENTER, output = "\n", width = widths.action, style = KeyStyle.ACTION),
            ),
        )
    }

    /** Top letter row: the first ten keys carry the digits as hints and long-press alternates. */
    private fun topLetterRow(
        letters: String,
        extra: Map<String, List<String>> = emptyMap(),
        stressed: Map<String, String> = emptyMap(),
    ): List<KeySpec> =
        letters.mapIndexed { index, c ->
            val label = c.toString()
            val digit = DIGITS.getOrNull(index)?.toString()
            KeySpec(
                type = KeyType.CHARACTER,
                label = label,
                hint = digit,
                alternates = extra[label].orEmpty() + listOfNotNull(digit) + listOfNotNull(stressed[label]),
            )
        }

    private fun letterKeys(
        letters: String,
        extra: Map<String, List<String>> = emptyMap(),
        stressed: Map<String, String> = emptyMap(),
        width: Float = 1f,
    ): List<KeySpec> =
        letters.map { c ->
            val label = c.toString()
            KeySpec(
                KeyType.CHARACTER,
                label = label,
                alternates = extra[label].orEmpty() + listOfNotNull(stressed[label]),
                width = width,
            )
        }

    private fun characterKeys(
        characters: String,
        language: Language,
    ): List<KeySpec> = characters.map { characterKey(it.toString(), language) }

    private fun characterKey(
        label: String,
        language: Language,
        width: Float = 1f,
        style: KeyStyle = KeyStyle.LETTER,
    ) = KeySpec(
        KeyType.CHARACTER,
        label = label,
        alternates = symbolAlternates(label, language),
        width = width,
        style = style,
    )

    private fun alphabet(
        language: Language,
        width: Float,
    ): KeySpec {
        val label = if (language == Language.RUSSIAN) "АБВ" else "ABC"
        return KeySpec(KeyType.ALPHABET, label = label, width = width, style = KeyStyle.MODE)
    }

    /** Long-press alternates of a symbol key; the first one is preselected. */
    private fun symbolAlternates(
        symbol: String,
        language: Language,
    ): List<String> {
        if (symbol == "\"") {
            return if (language == Language.RUSSIAN) RU_DOUBLE_QUOTES else EN_DOUBLE_QUOTES
        }
        if (symbol in CURRENCIES) return CURRENCIES.filter { it != symbol }
        return SYMBOL_ALTERNATES[symbol].orEmpty()
    }

    private fun modeKey(
        type: KeyType,
        label: String,
        style: KeyStyle,
    ) = KeySpec(type, label = label, width = WIDE, style = style)

    private fun shift(width: Float) = KeySpec(KeyType.SHIFT, width = width, style = KeyStyle.FUNCTION)

    private fun backspace(width: Float) = KeySpec(KeyType.BACKSPACE, width = width, style = KeyStyle.FUNCTION)
}
