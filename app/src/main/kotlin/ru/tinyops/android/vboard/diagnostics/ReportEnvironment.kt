package ru.tinyops.android.vboard.diagnostics

import android.os.Build

/** The Sentry environment of a report: the phone model as a slug, for example `google-pixel-11-pro`. */
internal object ReportEnvironment {
    private val separators = Regex("[^a-z0-9]+")

    fun current(): String = of(Build.MANUFACTURER, Build.MODEL)

    fun of(
        manufacturer: String,
        model: String,
    ): String {
        val brand = slug(manufacturer)
        val name = slug(model)
        val full =
            when {
                brand.isEmpty() -> name
                name.isEmpty() -> brand
                name == brand || name.startsWith("$brand-") -> name
                else -> "$brand-$name"
            }
        return full.ifEmpty { "unknown-device" }
    }

    private fun slug(text: String): String = text.lowercase().replace(separators, "-").trim('-')
}
