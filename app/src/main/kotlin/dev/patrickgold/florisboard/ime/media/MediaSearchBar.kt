/*
 * Tiune fork. Licensed under the Apache License, Version 2.0, like the rest
 * of FlorisBoard.
 */

package dev.patrickgold.florisboard.ime.media

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import dev.patrickgold.florisboard.ime.media.emoji.EmojiData
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistoryHelper
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSearch
import dev.patrickgold.florisboard.ime.media.gif.GifResultsRow
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/** The text in the search field: the size of the suggestions, not of a key
 *  label — the field inherits the key style for its shape only. */
private val SEARCH_TEXT_SIZE = 16.sp

/** Tiune's amber, the colour of every accent on the keyboard. */
private val Caret = Color(0xFFF5A623)

/** As many results as one swipe along the row is worth; past this the match
 *  is a stretch anyway. */
private const val MAX_RESULTS = 60

/**
 * Tiune fork. A media search, in place of the Smartbar while [MediaSearch] is
 * open: the results on top, the search itself underneath — the order Gboard
 * uses, so the row of letters is right below what is being typed. Emoji go
 * into the field on a tap; GIFs are sent; either way the search stays for the
 * next one. The back arrow returns to the panel it came from.
 */
@Composable
fun MediaSearchBar() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val query by MediaSearch.queryFlow.collectAsState()
    val kind by MediaSearch.kindFlow.collectAsState()
    val textColor = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName).foreground(Color.Unspecified)

    SnyggColumn(FlorisImeUi.Smartbar.elementName) {
        when (kind) {
            MediaSearch.Kind.EMOJI -> EmojiResultsRow(query)
            // Taller than a row of emoji: a GIF has to be big enough to see
            // what it is before it is sent to someone.
            MediaSearch.Kind.GIF -> GifResultsRow(
                query = query,
                modifier = Modifier.height(FlorisImeSizing.smartbarHeight * 1.8f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarSharedActionsToggle.elementName,
                onClick = {
                    MediaPanel.tab = if (kind == MediaSearch.Kind.GIF) MediaPanel.Tab.GIF else MediaPanel.Tab.EMOJI
                    MediaSearch.close()
                    keyboardManager.activeState.imeUiMode = ImeUiMode.MEDIA
                },
                modifier = Modifier
                    .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
                    .aspectRatio(1f),
            ) {
                SnyggIcon(
                    imageVector = Icons.AutoMirrored.Default.ArrowBack,
                    contentDescription = if (kind == MediaSearch.Kind.GIF) "Back to GIFs" else "Back to emoji",
                )
            }
            SnyggBox(
                elementName = FlorisImeUi.Key.elementName,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    SnyggIcon(
                        modifier = Modifier.alpha(0.6f).size(18.dp),
                        imageVector = Icons.Default.Search,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (query.isEmpty()) {
                        Caret()
                        Text(
                            modifier = Modifier.alpha(0.55f),
                            // "Search KLIPY" is the wording KLIPY's terms
                            // require in a field that searches it.
                            text = if (kind == MediaSearch.Kind.GIF) "Search KLIPY" else "Search emoji",
                            fontSize = SEARCH_TEXT_SIZE,
                            color = textColor,
                            maxLines = 1,
                        )
                    } else {
                        // The end of a long search, which is where the
                        // typing happens.
                        Text(
                            modifier = Modifier.weight(1f, fill = false),
                            text = if (query.length > 28) "…" + query.takeLast(28) else query,
                            fontSize = SEARCH_TEXT_SIZE,
                            color = textColor,
                            maxLines = 1,
                        )
                        Caret()
                        Spacer(Modifier.weight(1f))
                        SnyggIconButton(
                            onClick = { MediaSearch.clear() },
                            modifier = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight),
                        ) {
                            SnyggIcon(
                                modifier = Modifier.alpha(0.6f).size(18.dp),
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Emoji results as a row: the best match first, the user's own recent ones
 *  before anything is typed. */
@Composable
private fun EmojiResultsRow(query: String) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    val subtypeManager by context.subtypeManager()
    val scope = rememberCoroutineScope()

    val skinTone by prefs.emoji.preferredSkinTone.collectAsState()
    val history by prefs.emoji.historyData.collectAsState()
    val subtype by subtypeManager.activeSubtypeFlow.collectAsState()
    // Only emoji this phone can draw: an empty box is worse than no result.
    val glyphs = remember { Paint().apply { typeface = Typeface.DEFAULT } }

    val results by produceState(emptyList<Emoji>(), query, skinTone, history, subtype) {
        value = withContext(Dispatchers.Default) {
            val all = EmojiData.get(context, subtype.primaryLocale).bySkinTone[skinTone].orEmpty()
            val used = (history.pinned + history.recent).map { it.value }
            val found = if (query.isBlank()) {
                EmojiSearch.suggestions(all, used, MAX_RESULTS)
            } else {
                EmojiSearch.rank(all, query, used)
            }
            found.asSequence().filter { glyphs.hasGlyph(it.value) }.take(MAX_RESULTS).toList()
        }
    }

    val resultsScroll = rememberScrollState()
    // A new search starts at its best match, not wherever the last one was.
    LaunchedEffect(query) { resultsScroll.scrollTo(0) }

    fun pick(emoji: Emoji) {
        editorInstance.commitText(emoji.value)
        scope.launch { EmojiHistoryHelper.markEmojiUsed(prefs, emoji) }
    }

        SnyggRow(
            elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight)
                .horizontalScroll(resultsScroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (results.isEmpty() && query.isNotBlank()) {
                SnyggText(
                    elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-text",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = "No emoji for “${query.trim()}”",
                )
            }
            for (emoji in results) {
                SnyggBox(
                    elementName = FlorisImeUi.SmartbarCandidateWord.elementName,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1.1f),
                    clickAndSemanticsModifier = Modifier
                        .clickable { pick(emoji) }
                        .semantics { contentDescription = emoji.name },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emoji.value, fontSize = 24.sp)
                }
            }
        }
}

/** A blinking text caret, so it reads as a box being typed into. */
@Composable
private fun Caret() {
    val blink = rememberInfiniteTransition(label = "caret")
    val alpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .width(2.dp)
            .height(20.dp)
            .alpha(alpha)
            .background(Caret),
    )
}
