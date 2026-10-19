/*
 * Tiune fork. Licensed under the Apache License, Version 2.0, like the rest
 * of FlorisBoard.
 */

package dev.patrickgold.florisboard.ime.media.emoji

/**
 * Tiune fork. Ranking emoji by what they are called, for the emoji search
 * (MediaSearch, EmojiSearchBar) and the ":word" suggestions alike.
 */
object EmojiSearch {
    /**
     * The emoji in `emojis` that `query` names, best first. Empty when
     * nothing matches. `used` is the user's history (values), most recent
     * first; it orders equally good matches.
     */
    fun rank(emojis: List<Emoji>, query: String, used: List<String>): List<Emoji> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return emojis.asSequence()
            .mapIndexedNotNull { order, emoji ->
                val tier = queryTier(emoji, q)
                if (tier == 0) null else Ranked(emoji, tier, rankBonus(emoji, used), order)
            }
            .sortedWith(
                compareByDescending<Ranked> { it.tier + it.bonus }
                    .thenBy { it.emoji.name.length }
                    .thenBy { it.order }
            )
            .map { it.emoji }
            .distinctBy { it.value }
            .toList()
    }

    /** What the search shows before anything is typed: the user's own recent
     *  emoji, then the ones people send most. */
    fun suggestions(emojis: List<Emoji>, used: List<String>, limit: Int): List<Emoji> {
        val byValue = emojis.associateBy { it.value.removeSuffix("️") }
        return (used.map { it.removeSuffix("️") } + POPULAR)
            .distinct()
            .mapNotNull { byValue[it] }
            .take(limit)
    }

    private class Ranked(val emoji: Emoji, val tier: Int, val bonus: Int, val order: Int)

    /** Several words must each name the emoji ("red heart", "thumbs up"); the
     *  weakest of them decides how good the match is. */
    private fun queryTier(emoji: Emoji, query: String): Int {
        val words = query.split(' ').filter { it.isNotEmpty() }
        if (words.size <= 1) return matchTier(emoji, query)
        if (emoji.name.lowercase() == query) return 1000
        var weakest = Int.MAX_VALUE
        for (word in words) {
            val tier = matchTier(emoji, word)
            if (tier == 0) return 0
            weakest = minOf(weakest, tier)
        }
        return weakest
    }

    /**
     * How well `query` (one lowercase word) names `emoji`, in tiers 100
     * apart; 0 is no match. Upstream scored any substring of the name, so
     * ":ear" offered hearts and bears before an ear, and every keyword counted
     * the same — results read as random. Here a match must start at a word:
     * the whole name, then a whole word of the name or a keyword (the CLDR
     * keywords are what people call an emoji: "lol", "love"), then the start
     * of one. A match inside a word only counts for longer queries ("broken"
     * in "heartbroken").
     */
    private fun matchTier(emoji: Emoji, query: String): Int {
        val name = emoji.name.lowercase()
        if (name == query) return 1000
        val words = name.split(' ', '-', ':', ',', '.', '’', '\'').filter { it.isNotEmpty() }
        val keywords = emoji.keywords.map { it.lowercase() }.filter { it.isNotEmpty() }
        return when {
            query in words || query in keywords -> 800
            name.startsWith(query) -> 600
            words.any { it.startsWith(query) } -> 500
            keywords.any { it.startsWith(query) } -> 400
            query.length >= 4 && (name.contains(query) || keywords.any { it.contains(query) }) -> 100
            else -> 0
        }
    }

    /** Below 100, so it orders emoji within a tier and never lifts a worse
     *  match over a better one. */
    private fun rankBonus(emoji: Emoji, used: List<String>): Int {
        var bonus = 0
        val usedAt = used.indexOf(emoji.value)
        if (usedAt >= 0) bonus += 40 - (usedAt.coerceAtMost(39))
        val popularAt = POPULAR.indexOf(emoji.value.removeSuffix("️"))
        if (popularAt >= 0) bonus += 50 - popularAt * 50 / POPULAR.size
        return bonus.coerceAtMost(90)
    }

    /**
     * The emoji people send most, most-used first (after Unicode's published
     * emoji frequency data), without the U+FE0F presentation selector. Ties
     * between equally good matches go to these: "love" means ❤ before 💌.
     */
    private val POPULAR = listOf(
        "😂", "❤", "🤣", "👍", "😭", "🙏", "😘", "🥰", "😍", "😊", "🎉", "😁", "💕", "🥺", "😅", "🔥",
        "☺", "🤦", "♥", "🤷", "🙄", "😆", "🤗", "😉", "🎂", "🤔", "👏", "🙂", "😳", "🥳", "😎", "👌",
        "💜", "😔", "💪", "✨", "💖", "👀", "😋", "😏", "😢", "👉", "💗", "😩", "💯", "🌹", "💞", "🎈",
        "💙", "😃", "😡", "💐", "😜", "🙈", "🤞", "😄", "🤤", "🙌", "🤪", "❣", "😀", "💋", "💀", "👇",
        "💔", "😌", "💓", "🤩", "🙃", "😬", "😱", "😴", "🤭", "😐", "🌞", "😒", "😇", "🌸", "😈", "🎶",
        "✌", "🎊", "🥵", "😞", "💚", "☀", "🖤", "💰", "😚", "👑", "🎁", "💥", "🙋", "☹", "😑", "🥴",
        "👈", "💩", "✅", "👋", "🤝", "😮", "🤯", "😤", "🥲", "🫶", "😥", "🌟", "⭐", "👊", "💛", "🧡",
    )
}
