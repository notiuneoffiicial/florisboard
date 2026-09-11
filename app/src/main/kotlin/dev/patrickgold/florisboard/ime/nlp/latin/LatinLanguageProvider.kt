/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Tiune fork: a real Latin provider. Upstream's is a stub (its `suggest`
 * returns nothing and its `spell` answers "typo" only to the word "typo"),
 * so the keyboard had a suggestion strip and nothing to put on it.
 *
 * What it does, with `assets/ime/dict/en/`:
 * - **Completions.** The word being typed, finished: the most common
 *   dictionary words that start with it.
 * - **Corrections.** When the word being typed is not one the dictionary
 *   (or the user) knows, the closest known words — one or two edits away,
 *   weighted by how common they are. The best of them is offered as the
 *   auto-commit candidate, which is what makes the space bar correct a
 *   typo ("teh" → "the"); the keyboard's own machinery applies it and lets
 *   backspace undo it.
 * - **Next word.** With nothing typed yet, the words that most often
 *   follow the previous one ("I" → "am", "have", "was" …).
 *
 * The user's own words — what they added to the host app's vocabulary,
 * which reaches here as [FlorisImeService.userWords] — are always valid
 * spellings, never "corrected", and offered as completions first.
 * English only for now; another language's subtype gets no suggestions
 * rather than English ones.
 */
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.latin"
        private const val MAX_NEXT = 3
        private const val MAX_COMPLETIONS = 3
        private const val MAX_CORRECTIONS = 3
    }

    private val appContext by context.appContext()

    override val providerId = ProviderId

    /** The dictionary: frequencies, the words in alphabetical order for
     *  prefix search, and each word's most common followers. */
    private class Dict(
        val freq: HashMap<String, Int>,
        val alpha: Array<String>,
        val byFirst: Map<Char, List<String>>,
        val next: HashMap<String, List<Pair<String, Int>>>,
    )

    @Volatile private var dict: Dict? = null
    private val loading = Mutex()

    override suspend fun create() {}

    override suspend fun preload(subtype: Subtype) {
        if (subtype.primaryLocale.language == "en") ensureLoaded()
    }

    private suspend fun ensureLoaded(): Dict {
        dict?.let { return it }
        return loading.withLock {
            dict ?: withContext(Dispatchers.IO) { load() }.also { dict = it }
        }
    }

    private fun load(): Dict {
        val freq = HashMap<String, Int>(70_000)
        appContext.assets.open("ime/dict/en/words.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                freq[line.substring(0, tab)] = line.substring(tab + 1).toIntOrNull() ?: 0
            }
        }
        val alpha = freq.keys.toTypedArray().also { it.sort() }
        val byFirst = alpha.groupBy { it[0] }
        val next = HashMap<String, List<Pair<String, Int>>>(20_000)
        appContext.assets.open("ime/dict/en/bigrams.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                next[line.substring(0, tab)] = line.substring(tab + 1).split(' ').mapNotNull { item ->
                    val colon = item.lastIndexOf(':')
                    if (colon <= 0) null else item.substring(0, colon) to (item.substring(colon + 1).toIntOrNull() ?: 0)
                }
            }
        }
        flogDebug { "dictionary loaded: ${freq.size} words, ${next.size} bigram heads" }
        return Dict(freq, alpha, byFirst, next)
    }

    private fun known(d: Dict, word: String): Boolean =
        d.freq.containsKey(word) || FlorisImeService.userWords.contains(word)

    /** The dictionary words starting with `prefix`, most common first. */
    private fun completions(d: Dict, prefix: String, limit: Int): List<String> {
        var lo = 0
        var hi = d.alpha.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (d.alpha[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<String>()
        var i = lo
        while (i < d.alpha.size && d.alpha[i].startsWith(prefix)) {
            if (d.alpha[i] != prefix) found.add(d.alpha[i])
            i++
        }
        return found.sortedByDescending { d.freq[it] ?: 0 }.take(limit)
    }

    /** Known words within one edit (two, for longer words) of `word`,
     *  best first: closer beats commoner, then commoner wins. */
    private fun corrections(d: Dict, word: String, limit: Int): List<Pair<String, Int>> {
        val maxDist = if (word.length >= 5) 2 else 1
        val pool = ArrayList<String>()
        d.byFirst[word[0]]?.let { pool.addAll(it) }
        // A wrong first letter is a common typo too; look one row of the
        // keyboard around it when the word is short enough to afford it.
        if (word.length <= 8) {
            for (c in NEIGHBOURS[word[0]] ?: "") d.byFirst[c]?.let { pool.addAll(it) }
        }
        val scored = ArrayList<Triple<String, Int, Int>>()
        for (cand in pool) {
            if (kotlin.math.abs(cand.length - word.length) > maxDist) continue
            val dist = editDistance(word, cand, maxDist)
            if (dist in 1..maxDist) scored.add(Triple(cand, dist, d.freq[cand] ?: 0))
        }
        scored.sortWith(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
        return scored.take(limit).map { it.first to it.second }
    }

    /** Levenshtein with a transposition counted as one edit, bounded. */
    private fun editDistance(a: String, b: String, max: Int): Int {
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > max) return max + 1
        var prev2: IntArray? = null
        var prev = IntArray(m + 1) { it }
        for (i in 1..n) {
            val cur = IntArray(m + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prev2!![j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            prev2 = prev
            prev = cur
        }
        return prev[m]
    }

    /** Present a dictionary word the way the typed word is cased. */
    private fun cased(word: String, like: String): String = when {
        word == "i" || word.startsWith("i'") -> "I" + word.substring(1)
        like.length > 1 && like.all { it.isUpperCase() } -> word.uppercase()
        like.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    /** The last word before the caret, for the next-word lookup. Null when
     *  a sentence has just ended: what follows a full stop is anyone's
     *  guess, and guessing would only be noise. */
    private fun previousWord(before: CharSequence): String? {
        var end = before.length
        while (end > 0 && before[end - 1] == ' ') end--
        if (end == 0) return null
        if (before[end - 1] in ".!?\n") return null
        var start = end
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
        if (start == end) return null
        return before.subSequence(start, end).toString().lowercase()
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // Never let a provider fault take the keyboard down: no suggestion
        // beats no keys. (Nothing typed is ever logged.)
        return try {
            suggestInner(subtype, content, maxCandidateCount)
        } catch (t: Throwable) {
            android.util.Log.w("TiuneNlp", "suggest failed", t)
            emptyList()
        }
    }

    private suspend fun suggestInner(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
    ): List<SuggestionCandidate> {
        if (subtype.primaryLocale.language != "en") return emptyList()
        val d = ensureLoaded()
        val typed = content.composingText.trim()
        if (typed.isEmpty()) {
            val prev = previousWord(content.textBeforeSelection) ?: return emptyList()
            // Only after an English word. The bigrams come from a web corpus,
            // and a lone letter that is a word in some other language brings
            // that language's followers with it — "y" offered "el", "la",
            // "z". "a" and "I" are the two single letters that are words.
            if (prev.length < 2 && prev != "a" && prev != "i") return emptyList()
            val followers = d.next[prev] ?: return emptyList()
            return followers.filter { (w, _) -> known(d, w) }.take(MAX_NEXT).map { (w, c) ->
                WordSuggestionCandidate(text = cased(w, ""), confidence = c.toDouble(), isEligibleForAutoCommit = false, sourceProvider = this)
            }
        }
        if (!typed.all { it.isLetter() || it == '\'' }) return emptyList()
        val lower = typed.lowercase()
        val out = ArrayList<SuggestionCandidate>()
        val seen = HashSet<String>()
        val isKnown = known(d, lower)

        // The user's own words first: a name they taught the app is the
        // completion they want, and it is never a typo.
        for (w in FlorisImeService.userWords) {
            val wl = w.lowercase()
            if (wl != lower && wl.startsWith(lower) && seen.add(wl)) {
                out.add(WordSuggestionCandidate(text = w, confidence = 1.0, isEligibleForAutoCommit = false, sourceProvider = this))
                if (out.size >= 2) break
            }
        }

        // Corrections. A word the dictionary does not know is a typo until
        // proven otherwise. A word it does know can still be one: "teh" is
        // in any web-sized word list, and what gives it away is that "the"
        // is thousands of times more common one edit away. The user's own
        // words are never corrected.
        val typedFreq = d.freq[lower] ?: 0
        val userKnown = FlorisImeService.userWords.any { it.equals(lower, ignoreCase = true) }
        if (!userKnown && lower.length >= 2) {
            val fixes = corrections(d, lower, MAX_CORRECTIONS)
                .filter { (w, _) -> !isKnown || (d.freq[w] ?: 0) >= typedFreq * 200L }
            fixes.forEachIndexed { i, (w, dist) ->
                if (!seen.add(w)) return@forEachIndexed
                // The best fix is what the space bar will apply — only when it
                // is clearly what was meant: one edit away (two on a long
                // word), a word people actually use, and — for a typed word
                // the dictionary knows — one that outweighs it a thousandfold.
                val outweighs = !isKnown || (d.freq[w] ?: 0) >= typedFreq * 1000L
                val auto = i == 0 && FlorisImeService.autoCorrectEnabled() && outweighs &&
                    (dist == 1 || lower.length >= 6) && (d.freq[w] ?: 0) >= 200
                out.add(WordSuggestionCandidate(text = cased(w, typed), confidence = 0.9 - 0.1 * i, isEligibleForAutoCommit = auto, sourceProvider = this))
            }
        }
        // Completions of a word the dictionary already knows have to be worth
        // offering next to it: "hello" is a finished word, and the only
        // longer entry a web-sized list has for it — "hellometro", four
        // hundred times rarer — is noise, not a suggestion. A prefix that
        // is not a word ("th") takes every completion.
        val floor = if (isKnown) typedFreq / 100L else 0L
        for (w in completions(d, lower, MAX_COMPLETIONS)) {
            if ((d.freq[w] ?: 0) < floor) continue
            if (seen.add(w)) out.add(WordSuggestionCandidate(text = cased(w, typed), confidence = 0.5, isEligibleForAutoCommit = false, sourceProvider = this))
        }
        return out.take(maxCandidateCount)
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        if (subtype.primaryLocale.language != "en") return SpellingResult.unspecified()
        val d = ensureLoaded()
        val lower = word.lowercase()
        if (lower.isEmpty() || !lower.all { it.isLetter() || it == '\'' } || known(d, lower)) return SpellingResult.validWord()
        val fixes = corrections(d, lower, maxSuggestionCount).map { cased(it.first, word) }
        return if (fixes.isEmpty()) SpellingResult.validWord() else SpellingResult.typo(fixes.toTypedArray())
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return dict?.alpha?.toList() ?: emptyList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return (dict?.freq?.get(word.lowercase()) ?: 0).toDouble()
    }

    override suspend fun destroy() {
        dict = null
    }
}

/** The keys around each letter on a QWERTY keyboard, for first-letter typos. */
private val NEIGHBOURS: Map<Char, String> = mapOf(
    'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg", 'y' to "tugh", 'u' to "yihj",
    'i' to "uojk", 'o' to "ipkl", 'p' to "ol", 'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc",
    'g' to "ftyhbv", 'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kop", 'z' to "asx", 'x' to "zsdc",
    'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn", 'n' to "bhjm", 'm' to "njk",
)
