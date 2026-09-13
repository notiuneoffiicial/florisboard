/*
 * Tiune fork. Licensed under the Apache License, Version 2.0, like the rest
 * of FlorisBoard.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * What the user has told the keyboard about its corrections, for the field
 * they are typing in right now.
 *
 * When the space bar changes a word and the user puts it back — with the
 * backspace right after, or by going into the word and retyping it — that
 * is the user saying "I meant that". From then on, in this field, the word
 * is theirs: it is not corrected again and not marked as a typo. Gboard
 * behaves the same way. The memory is per field session on purpose: a
 * word insisted on in one message is not a spelling for life.
 */
object AutocorrectMemory {
    /** Lower-cased typed words the user kept after a correction. */
    private val insisted = HashSet<String>()

    /** Lower-cased typed word → lower-cased word it was corrected to, for
     *  every correction applied in this session. Used to recognise the
     *  user typing the original back. */
    private val applied = HashMap<String, String>()

    @Synchronized fun startSession() {
        insisted.clear()
        applied.clear()
    }

    @Synchronized fun isInsisted(word: String): Boolean = insisted.contains(word.lowercase())

    @Synchronized fun insist(word: String) {
        val w = word.lowercase()
        if (w.isNotEmpty()) insisted.add(w)
    }

    @Synchronized fun recordApplied(typed: String, corrected: String) {
        val t = typed.lowercase()
        if (t.isNotEmpty()) applied[t] = corrected.lowercase()
    }

    /** True when `word` is one the keyboard corrected earlier in this
     *  session — seeing it typed again as-is means the user wants it. */
    @Synchronized fun wasApplied(word: String): Boolean = applied.containsKey(word.lowercase())
}
