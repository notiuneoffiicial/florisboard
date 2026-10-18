/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.EmojiSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.lib.FlorisLocale
import io.github.reactivecircus.cache4k.Cache

/**
 * Provides emoji suggestions within a text input context.
 *
 * This class handles the following tasks:
 * - Initializes and maintains a list of supported emojis.
 * - Generates and returns emoji suggestions based on user input and preferences.
 *
 * @param context The application context.
 */
class EmojiSuggestionProvider(private val context: Context) : SuggestionProvider {
    override val providerId = "org.florisboard.nlp.providers.emoji"

    private val prefs by FlorisPreferenceStore
    private val lettersRegex = "^[A-Za-z]*$".toRegex()

    private val cachedEmojiMappings = Cache.Builder<FlorisLocale, EmojiDataBySkinTone>().build()

    override suspend fun create() {
    }

    override suspend fun preload(subtype: Subtype) {
        subtype.locales().forEach { locale ->
            cachedEmojiMappings.get(locale) {
                EmojiData.get(context, locale).bySkinTone
            }
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean
    ): List<SuggestionCandidate> {
        val preferredSkinTone = prefs.emoji.preferredSkinTone.get()
        val showName = prefs.emoji.suggestionCandidateShowName.get()
        val query = validateInputQuery(content.composingText)?.lowercase() ?: return emptyList()
        val emojis = cachedEmojiMappings.get(subtype.primaryLocale)?.get(preferredSkinTone) ?: emptyList()
        // Tiune fork: emoji the user has picked before come first among
        // equally good matches, the way a phone keyboard learns.
        val history = prefs.emoji.historyData.get()
        val used = (history.pinned + history.recent).map { it.value }
        return withContext(Dispatchers.Default) {
            emojis.asSequence()
                .mapIndexedNotNull { order, emoji ->
                    val tier = matchTier(emoji, query)
                    if (tier == 0) null else Ranked(emoji, tier, rankBonus(emoji, used), order)
                }
                .sortedWith(
                    compareByDescending<Ranked> { it.tier + it.bonus }
                        .thenBy { it.emoji.name.length }
                        .thenBy { it.order }
                )
                .distinctBy { it.emoji.value }
                .take(maxCandidateCount)
                .map {
                    EmojiSuggestionCandidate(
                        emoji = it.emoji,
                        showName = showName,
                        sourceProvider = this@EmojiSuggestionProvider,
                    )
                }
                .toList()
        }
    }

    private class Ranked(val emoji: Emoji, val tier: Int, val bonus: Int, val order: Int)

    /**
     * Tiune fork. How well `query` (lowercase letters) names `emoji`, in tiers
     * 100 apart; 0 is no match. Upstream scored any substring of the name,
     * so ":ear" offered hearts and bears before an ear, and every keyword
     * counted the same — results read as random. Here a match must start
     * at a word: the whole name, then a whole word of the name or a keyword
     * (the CLDR keywords are what people call an emoji: "lol", "love"),
     * then the start of one. A match inside a word only counts for longer
     * queries ("broken" in "heartbroken").
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
        val popularAt = POPULAR.indexOf(emoji.value.removeSuffix("\uFE0F"))
        if (popularAt >= 0) bonus += 50 - popularAt * 50 / POPULAR.size
        return bonus.coerceAtMost(90)
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        val updateHistory = prefs.emoji.suggestionUpdateHistory.get()
        if (!updateHistory || candidate !is EmojiSuggestionCandidate) {
            return
        }
        EmojiHistoryHelper.markEmojiUsed(prefs, candidate.emoji)
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // No-op
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate) = false

    override suspend fun getListOfWords(subtype: Subtype) = emptyList<String>()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String) = 0.0

    override suspend fun destroy() {
        cachedEmojiMappings.invalidateAll()
    }

    /**
     * Validates the user input query for emoji suggestions.
     */
    private fun validateInputQuery(composingText: CharSequence): String? {
        val prefix = prefs.emoji.suggestionType.get().prefix
        val queryMinLength = prefs.emoji.suggestionQueryMinLength.get() + prefix.length
        if (prefix.isNotEmpty() && !composingText.startsWith(prefix)) {
            return null
        }
        if (composingText.length < queryMinLength) {
            return null
        }
        val emojiPartialName = composingText.substring(prefix.length)
        if (!lettersRegex.matches(emojiPartialName)) {
            return null
        }
        return emojiPartialName
    }
}

private fun String.containsWeighted(other: String, ignoreCase: Boolean = false): Double = let { str ->
    if (str.contains(other, ignoreCase = ignoreCase)) {
        other.length.toDouble() / str.length.toDouble()
    } else {
        0.0
    }
}

/**
 * Tiune fork. The emoji people send most, most-used first (after Unicode's
 * published emoji frequency data), without the U+FE0F presentation selector.
 * Ties between equally good matches go to these: ":love" means ❤ before 💌.
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
