/*
 * Tiune fork. Licensed under the Apache License, Version 2.0, like the rest
 * of FlorisBoard.
 */

package dev.patrickgold.florisboard.ime.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiune fork. Which page of the media panel is up: emoji or GIFs. The bottom
 * row's two tabs switch it; it is remembered while the keyboard stays up, so
 * coming back from a search lands on the page it started from.
 */
object MediaPanel {
    enum class Tab { EMOJI, GIF }

    private val _tab = MutableStateFlow(Tab.EMOJI)
    val tabFlow = _tab.asStateFlow()
    var tab: Tab
        get() = _tab.value
        set(v) { _tab.value = v }

    /** The GIF search the panel's grid shows; empty for trending. Set when a
     *  search is submitted with the keyboard's search key. */
    private val _gifQuery = MutableStateFlow("")
    val gifQueryFlow = _gifQuery.asStateFlow()
    var gifQuery: String
        get() = _gifQuery.value
        set(v) { _gifQuery.value = v.trim() }

    /** Back to emoji and trending, for the next field. */
    fun reset() {
        _tab.value = Tab.EMOJI
        _gifQuery.value = ""
    }
}

/**
 * Tiune fork. Typing a search from the media panel, the way Gboard does: a
 * search bar takes the place of the Smartbar, the letters come back
 * underneath, and what is typed goes into the search instead of the field.
 *
 * This object is the state: whether a search is open, for emoji or GIFs, and
 * what has been typed. KeyboardManager routes keys here while it is open;
 * TextInputLayout shows the matching bar in place of the Smartbar. It closes
 * on its own back button, when the user goes to another panel, and whenever
 * the keyboard leaves the field — never carried into the next one.
 */
object MediaSearch {
    enum class Kind { EMOJI, GIF }

    private val _isActive = MutableStateFlow(false)
    val isActiveFlow = _isActive.asStateFlow()
    val isActive: Boolean get() = _isActive.value

    private val _kind = MutableStateFlow(Kind.EMOJI)
    val kindFlow = _kind.asStateFlow()
    val kind: Kind get() = _kind.value

    private val _query = MutableStateFlow("")
    val queryFlow = _query.asStateFlow()
    val query: String get() = _query.value

    /** Keeps a mistyped search from growing without bound. */
    private const val MAX_QUERY_LENGTH = 40

    fun open(kind: Kind, query: String = "") {
        _kind.value = kind
        _query.value = query.take(MAX_QUERY_LENGTH)
        _isActive.value = true
    }

    fun close() {
        _isActive.value = false
        _query.value = ""
    }

    fun type(text: String) {
        if (text.isEmpty()) return
        // One space between words, never a leading one.
        val next = (query + text).replace(Regex("\\s+"), " ").trimStart()
        _query.value = next.take(MAX_QUERY_LENGTH)
    }

    fun backspace(wholeWord: Boolean = false) {
        val q = query
        if (q.isEmpty()) return
        _query.value = if (wholeWord) {
            q.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
        } else {
            // Whole code points, so a pasted accent never leaves half a letter.
            q.substring(0, q.offsetByCodePoints(q.length, -1))
        }
    }

    fun clear() {
        _query.value = ""
    }
}
