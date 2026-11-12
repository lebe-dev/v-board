package ru.tinyops.android.vboard.emoji

import androidx.annotation.StringRes
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.emoji.EmojiCategory

/** The name of a category in the picker and in the settings. */
@get:StringRes
val EmojiCategory.title: Int
    get() =
        when (this) {
            EmojiCategory.SMILEYS -> R.string.emoji_smileys
            EmojiCategory.PEOPLE -> R.string.emoji_people
            EmojiCategory.NATURE -> R.string.emoji_nature
            EmojiCategory.FOOD -> R.string.emoji_food
            EmojiCategory.ACTIVITIES -> R.string.emoji_activities
            EmojiCategory.TRAVEL -> R.string.emoji_travel
            EmojiCategory.OBJECTS -> R.string.emoji_objects
            EmojiCategory.SYMBOLS -> R.string.emoji_symbols
            EmojiCategory.FLAGS -> R.string.emoji_flags
        }

/** The short name of a category on its chip in the picker. */
@get:StringRes
val EmojiCategory.chipTitle: Int
    get() =
        when (this) {
            EmojiCategory.SMILEYS -> R.string.emoji_chip_smileys
            EmojiCategory.PEOPLE -> R.string.emoji_chip_people
            EmojiCategory.NATURE -> R.string.emoji_chip_nature
            EmojiCategory.FOOD -> R.string.emoji_chip_food
            EmojiCategory.ACTIVITIES -> R.string.emoji_chip_activities
            EmojiCategory.TRAVEL -> R.string.emoji_chip_travel
            EmojiCategory.OBJECTS -> R.string.emoji_chip_objects
            EmojiCategory.SYMBOLS -> R.string.emoji_chip_symbols
            EmojiCategory.FLAGS -> R.string.emoji_chip_flags
        }

/** Well-known emoji of a category, shown next to its name in the settings. */
val EmojiCategory.samples: List<String>
    get() =
        when (this) {
            EmojiCategory.SMILEYS -> listOf("😀", "😍", "😭", "😎")
            EmojiCategory.PEOPLE -> listOf("👋", "👍", "🙏", "💪")
            EmojiCategory.NATURE -> listOf("🐶", "🐱", "🌸", "🌲")
            EmojiCategory.FOOD -> listOf("🍎", "🍕", "🍔", "☕")
            EmojiCategory.ACTIVITIES -> listOf("⚽", "🎮", "🎨", "🏆")
            EmojiCategory.TRAVEL -> listOf("🚗", "✈️", "🏠", "🌍")
            EmojiCategory.OBJECTS -> listOf("💡", "📱", "🎁", "🔑")
            EmojiCategory.SYMBOLS -> listOf("❤️", "✅", "⚠️", "💯")
            EmojiCategory.FLAGS -> listOf("🏁", "🇷🇺", "🇺🇸", "🇯🇵")
        }

private val GROUP_TITLES: Map<String, Int> =
    mapOf(
        "faces" to R.string.emoji_group_faces,
        "animal-faces" to R.string.emoji_group_animal_faces,
        "hearts-emotions" to R.string.emoji_group_hearts_emotions,
        "hands" to R.string.emoji_group_hands,
        "body" to R.string.emoji_group_body,
        "people" to R.string.emoji_group_people,
        "people-sports" to R.string.emoji_group_people_sports,
        "family" to R.string.emoji_group_family,
        "animals" to R.string.emoji_group_animals,
        "plants" to R.string.emoji_group_plants,
        "fruit-vegetables" to R.string.emoji_group_fruit_vegetables,
        "meals" to R.string.emoji_group_meals,
        "sweets" to R.string.emoji_group_sweets,
        "drinks" to R.string.emoji_group_drinks,
        "places" to R.string.emoji_group_places,
        "transport" to R.string.emoji_group_transport,
        "time-weather" to R.string.emoji_group_time_weather,
        "events" to R.string.emoji_group_events,
        "awards" to R.string.emoji_group_awards,
        "sports" to R.string.emoji_group_sports,
        "games" to R.string.emoji_group_games,
        "crafts" to R.string.emoji_group_crafts,
        "clothing" to R.string.emoji_group_clothing,
        "music" to R.string.emoji_group_music,
        "devices" to R.string.emoji_group_devices,
        "office" to R.string.emoji_group_office,
        "money" to R.string.emoji_group_money,
        "tools" to R.string.emoji_group_tools,
        "signs" to R.string.emoji_group_signs,
        "arrows-math" to R.string.emoji_group_arrows_math,
        "keys-letters" to R.string.emoji_group_keys_letters,
        "shapes" to R.string.emoji_group_shapes,
        "misc-flags" to R.string.emoji_group_misc_flags,
        "country-flags" to R.string.emoji_group_country_flags,
    )

/** The name of a subcategory (a group such as sports or games); null for an id the app does not know. */
@StringRes
fun subcategoryTitle(id: String): Int? = GROUP_TITLES[id]
