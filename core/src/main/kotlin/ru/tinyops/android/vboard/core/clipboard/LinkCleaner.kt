package ru.tinyops.android.vboard.core.clipboard

/** Removes ad tracking parameters (utm_*, Google click identifiers) from the links of a text. */
object LinkCleaner {
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}»\"'"
    private const val UTM_PREFIX = "utm_"

    private val TRACKING =
        setOf(
            "gclid",
            "gclsrc",
            "dclid",
            "gbraid",
            "wbraid",
            "gad_source",
            "gad_campaignid",
            "srsltid",
            "_ga",
            "_gl",
        )

    private val url = Regex("(?i:https?://|www\\.)[^\\s<>\"']+")

    /** The same instance comes back when no link had anything to remove. */
    fun clean(text: String): String {
        var changed = false
        val result =
            url.replace(text) { match ->
                val raw = match.value
                val link = raw.trimEnd { it in TRAILING_PUNCTUATION }
                val cleaned = cleanLink(link) ?: return@replace raw
                changed = true
                cleaned + raw.substring(link.length)
            }
        return if (changed) result else text
    }

    /** The link without its tracking parameters, or null when it has none. */
    private fun cleanLink(link: String): String? {
        val queryStart = link.indexOf('?')
        if (queryStart < 0) return null
        val fragmentStart = link.indexOf('#', queryStart).takeIf { it >= 0 } ?: link.length
        val params = link.substring(queryStart + 1, fragmentStart).split('&')
        val kept = params.filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
        if (kept.size == params.count { it.isNotEmpty() }) return null
        val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return link.substring(0, queryStart) + query + link.substring(fragmentStart)
    }

    private fun isTracking(name: String): Boolean {
        val key = name.lowercase()
        return key.startsWith(UTM_PREFIX) || key in TRACKING
    }
}
