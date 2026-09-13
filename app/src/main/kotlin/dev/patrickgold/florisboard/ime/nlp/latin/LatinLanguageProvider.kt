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
 * - **Contractions.** "dont" is not a typo of "done", it is "don't" with
 *   the apostrophe left out. A table of those comes before the edit
 *   distance, and the contractions themselves are in the dictionary at
 *   the frequency they have in conversation, so "it's" beats "its" when
 *   a typo could be either.
 * - **Next word.** With nothing typed yet, the words that most often
 *   follow the previous one ("I" → "am", "have", "was" …).
 *
 * Three kinds of word are never corrected:
 * - The user's own words — what they added to the host app's vocabulary,
 *   which reaches here as [FlorisImeService.userWords] — are always valid
 *   spellings and offered as completions first.
 * - Roman Urdu and Hindi (`assets/ime/dict/roman_urdu.txt`): "mein",
 *   "tum", "kya", "rahe" are not misspelt English, and when the words
 *   before the caret are that language the space bar stops correcting
 *   altogether, so a word the list does not have survives too.
 * - A word the user put back after a correction ([AutocorrectMemory]).
 *
 * English only for now; another language's subtype gets no suggestions
 * rather than English ones.
 */
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.latin"
        private const val MAX_NEXT = 3
        private const val MAX_COMPLETIONS = 3
        private const val MAX_CORRECTIONS = 3

        /** Above this, a word is English for the purpose of deciding what
         *  language the sentence is in. "hai" and "mein" are in a web-sized
         *  English list too, thousands of times rarer than this. */
        private const val ENGLISH_FLOOR = 20_000
    }

    private val appContext by context.appContext()

    override val providerId = ProviderId

    /** The dictionary: frequencies, the words in alphabetical order for
     *  prefix search, each word's most common followers, and the Roman
     *  Urdu/Hindi words. */
    private class Dict(
        val freq: HashMap<String, Int>,
        val alpha: Array<String>,
        val byFirst: Map<Char, List<String>>,
        val next: HashMap<String, List<Pair<String, Int>>>,
        val roman: HashSet<String>,
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
        // The word list has almost no apostrophes (the corpora behind it
        // strip them), so the contractions go in here at conversational
        // frequencies — see the note on CONTRACTION_FREQ.
        for ((word, f) in CONTRACTION_FREQ) {
            if ((freq[word] ?: 0) < f) freq[word] = f
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
        val roman = HashSet<String>(4_000)
        appContext.assets.open("ime/dict/roman_urdu.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val w = line.trim()
                if (w.isNotEmpty()) roman.add(w)
            }
        }
        flogDebug { "dictionary loaded: ${freq.size} words, ${next.size} bigram heads, ${roman.size} roman urdu" }
        return Dict(freq, alpha, byFirst, next, roman)
    }

    private fun userKnown(word: String): Boolean =
        FlorisImeService.userWords.any { it.equals(word, ignoreCase = true) }

    /** A valid spelling: English, Roman Urdu/Hindi, or the user's own. */
    private fun known(d: Dict, word: String): Boolean =
        d.freq.containsKey(word) || d.roman.contains(word) || userKnown(word)

    /** Words that are never corrected, whatever the dictionary says. */
    private fun ownWord(d: Dict, word: String): Boolean =
        d.roman.contains(word) || userKnown(word) || AutocorrectMemory.isInsisted(word)

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

    /** The finished words just before the caret, nearest first, at most
     *  `limit`. `before` must not include the word being typed. */
    private fun recentWords(before: CharSequence, limit: Int): List<String> {
        val out = ArrayList<String>(limit)
        var end = before.length
        while (out.size < limit) {
            while (end > 0 && !before[end - 1].isLetter()) end--
            if (end == 0) break
            var start = end
            while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
            out.add(before.subSequence(start, end).toString().lowercase())
            end = start
        }
        return out
    }

    /**
     * Whether the sentence the user is in the middle of is Roman Urdu or
     * Hindi rather than English. The previous word being on the Roman list
     * decides it; otherwise the last three words vote, and a word English
     * does not know counts for the other side — the list cannot hold every
     * spelling of every word, and an unknown word after unknown words is
     * far more likely that language than three typos in a row.
     */
    private fun romanContext(d: Dict, content: EditorContent): Boolean {
        val before = content.textBeforeSelection.removeSuffix(content.composingText)
        val recent = recentWords(before, 3)
        if (recent.isEmpty()) return false
        var roman = 0
        var english = 0
        for ((i, w) in recent.withIndex()) {
            val isEnglish = (d.freq[w] ?: 0) >= ENGLISH_FLOOR
            val isRoman = d.roman.contains(w) && !isEnglish
            if (i == 0 && isRoman) return true
            when {
                isRoman -> roman++
                isEnglish || userKnown(w) -> english++
                !d.freq.containsKey(w) -> roman++
            }
        }
        return roman > english
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
            if (d.roman.contains(prev) && (d.freq[prev] ?: 0) < ENGLISH_FLOOR) return emptyList()
            val followers = d.next[prev] ?: return emptyList()
            return followers.filter { (w, _) -> known(d, w) }.take(MAX_NEXT).map { (w, c) ->
                WordSuggestionCandidate(text = cased(w, ""), confidence = c.toDouble(), isEligibleForAutoCommit = false, sourceProvider = this, forInput = "")
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
                out.add(WordSuggestionCandidate(text = w, confidence = 1.0, isEligibleForAutoCommit = false, sourceProvider = this, forInput = typed))
                if (out.size >= 2) break
            }
        }

        // The user typing a word back that the space bar had changed, in
        // the middle of what they wrote, is them putting it right.
        if (content.textAfterSelection.isNotBlank() && AutocorrectMemory.wasApplied(lower)) {
            AutocorrectMemory.insist(lower)
        }
        // Whether the space bar may change this word at all.
        val autoAllowed = FlorisImeService.autoCorrectEnabled() &&
            !ownWord(d, lower) && !romanContext(d, content)

        // Contractions. "dont", "im", "cant" are words with the apostrophe
        // left out, and the nearest dictionary word is not the fix
        // ("done", "in", "cent"). The ambiguous ones — "its", "well",
        // "were" — are real words, so they are offered, not applied.
        val contraction = CONTRACTIONS[lower]
        if (contraction != null && !userKnown(lower) && !AutocorrectMemory.isInsisted(lower)) {
            val auto = autoAllowed && !AMBIGUOUS_CONTRACTIONS.contains(lower)
            seen.add(contraction)
            out.add(WordSuggestionCandidate(text = cased(contraction, typed), confidence = 0.95, isEligibleForAutoCommit = auto, sourceProvider = this, forInput = typed))
        }

        // Corrections. A word the dictionary does not know is a typo until
        // proven otherwise. A word it does know can still be one: "teh" is
        // in any web-sized word list, and what gives it away is that "the"
        // is thousands of times more common one edit away. The user's own
        // words, Roman Urdu and words the user insisted on are never
        // corrected.
        val typedFreq = d.freq[lower] ?: 0
        if (!ownWord(d, lower) && lower.length >= 2) {
            val fixes = corrections(d, lower, MAX_CORRECTIONS)
                .filter { (w, _) -> !isKnown || (d.freq[w] ?: 0) >= typedFreq * 200L }
            var first = true
            for ((w, dist) in fixes) {
                if (!seen.add(w)) continue
                // The best fix is what the space bar will apply — only when it
                // is clearly what was meant: one edit away (two on a long
                // word), a word people actually use, and — for a typed word
                // the dictionary knows — one that outweighs it a thousandfold.
                val outweighs = !isKnown || (d.freq[w] ?: 0) >= typedFreq * 1000L
                val auto = first && contraction == null && autoAllowed && outweighs &&
                    (dist == 1 || lower.length >= 6) && (d.freq[w] ?: 0) >= 200
                out.add(WordSuggestionCandidate(text = cased(w, typed), confidence = if (first) 0.9 else 0.8, isEligibleForAutoCommit = auto, sourceProvider = this, forInput = typed))
                first = false
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
            if (seen.add(w)) out.add(WordSuggestionCandidate(text = cased(w, typed), confidence = 0.5, isEligibleForAutoCommit = false, sourceProvider = this, forInput = typed))
        }
        return out.take(maxCandidateCount)
    }

    /**
     * Whether the word being typed in [content] is a misspelling, decided
     * now, without suspending: the dictionary is in memory once loaded.
     * Returns the fixes to offer (possibly none) for a typo, or null for a
     * word that is fine — known to English, Roman Urdu, the user, or the
     * one they insisted on; or in a Roman Urdu sentence, where an unknown
     * word is a word, not a mistake. Null too while the dictionary is
     * still loading. Used to underline the word when it is finished.
     */
    fun typoSuggestionsOrNull(subtype: Subtype, content: EditorContent): Array<String>? {
        if (subtype.primaryLocale.language != "en") return null
        val d = dict ?: return null
        val typed = content.composingText.trim()
        if (typed.length < 2 || !typed.all { it.isLetter() || it == '\'' }) return null
        val lower = typed.lowercase()
        if (known(d, lower) || AutocorrectMemory.isInsisted(lower)) return null
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return null // an acronym
        if (romanContext(d, content)) return null
        val fixes = ArrayList<String>(4)
        CONTRACTIONS[lower]?.let { fixes.add(cased(it, typed)) }
        for ((w, _) in corrections(d, lower, MAX_CORRECTIONS)) {
            val c = cased(w, typed)
            if (!fixes.contains(c)) fixes.add(c)
        }
        return fixes.toTypedArray()
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
        val fixes = ArrayList<String>()
        CONTRACTIONS[lower]?.let { fixes.add(cased(it, word)) }
        for ((w, _) in corrections(d, lower, maxSuggestionCount)) {
            val c = cased(w, word)
            if (!fixes.contains(c)) fixes.add(c)
        }
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

/**
 * Contractions at the frequency they have in conversation, on the scale of
 * `words.txt` (where "the" is 28.9 million and "its" 657 thousand). The
 * word list behind the keyboard was built from corpora that strip
 * apostrophes, so "don't" arrived at 25 thousand and "it's" not at all —
 * which made "its" the fix for every typo of "it's". Messages are
 * conversation; these are the numbers of a subtitle-sized corpus.
 */
private val CONTRACTION_FREQ: Map<String, Int> = mapOf(
    "i'm" to 2_400_000, "it's" to 2_200_000, "don't" to 2_100_000, "that's" to 1_200_000,
    "you're" to 900_000, "can't" to 850_000, "i'll" to 700_000, "what's" to 650_000,
    "i've" to 600_000, "didn't" to 600_000, "he's" to 550_000, "let's" to 500_000,
    "there's" to 500_000, "she's" to 450_000, "we're" to 450_000, "i'd" to 400_000,
    "they're" to 380_000, "isn't" to 380_000, "doesn't" to 350_000, "won't" to 320_000,
    "you'll" to 300_000, "we'll" to 300_000, "wasn't" to 300_000, "you've" to 280_000,
    "here's" to 250_000, "wouldn't" to 220_000, "aren't" to 200_000, "haven't" to 200_000,
    "couldn't" to 200_000, "we've" to 200_000, "who's" to 200_000, "where's" to 200_000,
    "you'd" to 150_000, "how's" to 150_000, "ain't" to 150_000, "he'll" to 120_000,
    "shouldn't" to 120_000, "ma'am" to 120_000, "he'd" to 100_000, "hasn't" to 90_000,
    "weren't" to 90_000, "we'd" to 90_000, "it'll" to 90_000, "hadn't" to 80_000,
    "they'll" to 80_000, "they've" to 80_000, "she'll" to 80_000, "o'clock" to 80_000,
    "y'all" to 60_000, "she'd" to 60_000, "they'd" to 60_000, "that'll" to 50_000,
    "would've" to 40_000, "could've" to 40_000, "should've" to 40_000, "one's" to 40_000,
    "that'd" to 30_000, "everyone's" to 30_000, "someone's" to 30_000, "it'd" to 20_000,
    "who'd" to 20_000, "how'd" to 20_000, "what'd" to 20_000, "when's" to 20_000,
    "everybody's" to 20_000, "mustn't" to 15_000, "there'll" to 15_000, "where'd" to 15_000,
    "somebody's" to 15_000, "nobody's" to 15_000, "must've" to 10_000, "what'll" to 10_000,
    "why's" to 10_000, "might've" to 8_000, "who'll" to 8_000, "needn't" to 5_000,
    "who've" to 5_000,
)

/** Typed without the apostrophe → the contraction. */
private val CONTRACTIONS: Map<String, String> = buildMap {
    for (c in CONTRACTION_FREQ.keys) put(c.replace("'", ""), c)
}

/**
 * The stripped forms that are words in their own right. These get the
 * contraction as a suggestion, never as the space bar's correction: "its",
 * "well", "were" are what most people who type them mean. Gboard changes
 * "ill" to "I'll", and so do we; "id" stays, because people send each other
 * their ids all day.
 */
private val AMBIGUOUS_CONTRACTIONS: Set<String> = setOf(
    "its", "id", "well", "were", "hell", "shell", "wed", "shed", "lets", "ones",
)
