package ru.tinyops.android.vboard.core.voice

import java.util.regex.Pattern

/**
 * "Словарь и автозамены", applied to recognized text before it is inserted. A port of Govorun's
 * dictionary (shared/core/src/dictionary) with the same text format and replacement rules:
 *
 *  - one rule per line, `from=to` or `from → to`; `#` starts a comment;
 *  - several spellings of one word are separated by commas: `джимейл, гмайл=Gmail`;
 *  - a line without `=` removes the word; matching ignores case and only whole words are replaced;
 *  - a replacement made only of `\n` and `\t` inserts a line break or a tab and swallows the
 *    punctuation and spaces around the spoken command;
 *  - `ё=е` replaces the letter inside words too;
 *  - rules apply in order, so longer and more specific ones go first.
 */
class VoiceDictionary private constructor(
    private val rules: List<Rule>,
) {
    /** Number of usable rules (spelling variants count separately). */
    val size: Int get() = rules.size

    fun apply(text: String): String {
        if (rules.isEmpty() || text.isBlank()) return text
        var result = text
        var layoutApplied = false
        for (rule in rules) {
            if (!rule.pattern.matcher(result).find()) continue
            val next = if (rule.yo) result.replace('ё', 'е').replace('Ё', 'Е') else rule.replace(result)
            if (next != result) {
                layoutApplied = layoutApplied || rule.layout
                result = next
            }
        }
        return tidy(result, layoutApplied)
    }

    private class Rule(
        from: String,
        val to: String,
        val yo: Boolean,
        val layout: Boolean,
    ) {
        val pattern: Pattern =
            Pattern.compile(
                Pattern.quote(from) + if (layout) LAYOUT_PUNCTUATION else "",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )

        fun replace(text: String): String {
            val result = StringBuilder()
            var offset = 0
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val before = text.getOrNull(matcher.start() - 1)
                val after = text.getOrNull(matcher.end())
                if (before?.isLetterOrDigit() == true || after?.isLetterOrDigit() == true) continue
                var start = matcher.start()
                var end = matcher.end()
                if (layout) {
                    // A spoken line break takes the spaces around it, never other line breaks.
                    start = offset + text.substring(offset, start).trimEnd(::isHorizontalSpace).length
                    end = text.length - text.substring(end).trimStart(::isHorizontalSpace).length
                }
                result.append(text, offset, start).append(to)
                offset = end
            }
            return result.append(text, offset, text.length).toString()
        }
    }

    /** One parsed line of the dictionary text. */
    data class Entry(
        val from: String,
        val to: String,
    ) {
        /** The spoken variants, separated by commas. */
        val variants: List<String> get() = from.split(',').map(String::trim).filter(String::isNotEmpty)

        val usable: Boolean
            get() =
                from.isNotBlank() &&
                    from.length <= MAX_PART_LENGTH &&
                    to.length <= MAX_PART_LENGTH &&
                    '=' !in from &&
                    ARROW !in from
    }

    companion object {
        val EMPTY = VoiceDictionary(emptyList())

        const val MAX_ENTRIES = 5_000
        private const val MAX_PART_LENGTH = 500
        private const val ARROW = " → "
        private const val LAYOUT_PUNCTUATION = "[.,!?;:…]*"

        /** Builds the dictionary from its text; lines it cannot use are skipped, never fatal. */
        fun parse(raw: String): VoiceDictionary {
            val rules = ArrayList<Rule>()
            var entries = 0
            for (entry in entries(raw)) {
                val variants = entry.variants
                if (!entry.usable || entries >= MAX_ENTRIES || rules.size + variants.size > MAX_ENTRIES) continue
                entries++
                val layout = layoutValue(entry.to)
                for (from in variants) {
                    rules +=
                        Rule(
                            from = from,
                            to = layout ?: entry.to,
                            yo = from.lowercase() == "ё" && entry.to.lowercase() == "е",
                            layout = layout != null,
                        )
                }
            }
            return VoiceDictionary(rules)
        }

        /** The rule lines of [raw], comments and blank lines left out. */
        fun entries(raw: String): List<Entry> = raw.lines().mapNotNull(::entry)

        private fun entry(line: String): Entry? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
            val arrow = trimmed.indexOf(ARROW)
            val (from, to) =
                when {
                    arrow >= 0 -> trimmed.substring(0, arrow) to trimmed.substring(arrow + ARROW.length)
                    '=' in trimmed -> trimmed.substringBefore('=') to trimmed.substringAfter('=')
                    else -> trimmed to ""
                }
            return from.trim().takeIf(String::isNotEmpty)?.let { Entry(it, to.trim()) }
        }

        /** `\n` and `\t` sequences that make up the whole replacement; anything else stays literal. */
        private fun layoutValue(value: String): String? {
            if (value.isEmpty() || value.length % 2 != 0) return null
            val result = StringBuilder()
            for (index in value.indices step 2) {
                if (value[index] != '\\') return null
                result.append(
                    when (value[index + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> return null
                    },
                )
            }
            return result.toString()
        }

        private fun isHorizontalSpace(c: Char) = c.isWhitespace() && c != '\r' && c != '\n'

        /** Trims the ends (keeping inserted line breaks and tabs) and collapses repeated spaces. */
        private fun tidy(
            text: String,
            layoutApplied: Boolean,
        ): String {
            val trimmed = text.trim { it.isWhitespace() && !(layoutApplied && (it == '\n' || it == '\t')) }
            val result = StringBuilder(trimmed.length)
            for (c in trimmed) {
                if (c == ' ' && result.lastOrNull() == ' ') continue
                result.append(c)
            }
            return result.toString()
        }
    }
}
