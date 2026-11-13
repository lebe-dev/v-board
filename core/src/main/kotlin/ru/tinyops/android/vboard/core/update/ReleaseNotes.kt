package ru.tinyops.android.vboard.core.update

/** The notes of a GitHub release as plain text for the settings page. */
object ReleaseNotes {
    private val BULLET = Regex("""^[-*]\s+""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")

    fun plain(markdown: String): String {
        val lines = mutableListOf<String>()
        for (raw in markdown.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("**Full Changelog**")) continue
            val text = clean(line.replace(BULLET, ""))
            when {
                BULLET.containsMatchIn(line) -> lines += "• $text"
                raw.startsWith(" ") && lines.isNotEmpty() -> lines[lines.lastIndex] += " $text"
                else -> lines += text
            }
        }
        return lines.joinToString("\n")
    }

    private fun clean(text: String): String =
        text
            .replace(LINK, "$1")
            .replace("**", "")
            .replace("`", "")
            .removePrefix("#")
            .trimStart('#', ' ')
}
