/*
 * Tiune fork. Licensed under the Apache License, Version 2.0, like the rest
 * of FlorisBoard.
 */

package dev.patrickgold.florisboard.ime.media.gif

import android.content.ClipDescription
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery
import java.nio.ByteBuffer

/** Previews held in memory while the keyboard is up, so scrolling back does
 *  not fetch them again. Never written to disk (KLIPY's terms). */
private val previewCache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray) = value.size
}

private const val MAX_PREVIEW_BYTES = 3 * 1024 * 1024
private const val MAX_SEND_BYTES = 15 * 1024 * 1024

/** A GIF, playing. Fetched and decoded off the main thread; a quiet
 *  placeholder until it arrives, or if it never does. */
@Composable
fun AnimatedGif(file: GifFile, modifier: Modifier = Modifier, label: String? = null) {
    val placeholder = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName).background(Color(0x22888888))
    var drawable by remember(file.url) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(file.url) {
        val bytes = previewCache.get(file.url)
            ?: runCatching { Klipy.fetch(file.url, MAX_PREVIEW_BYTES) }.getOrNull()?.also { previewCache.put(file.url, it) }
            ?: return@LaunchedEffect
        drawable = withContext(Dispatchers.Default) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull()
        }
    }
    // Started here and stopped with this very drawable. The first version
    // read the state inside onDispose — by then the NEW drawable — so every
    // GIF was stopped the moment it arrived and sat on its first frame.
    DisposableEffect(drawable) {
        val shown = drawable as? AnimatedImageDrawable
        shown?.start()
        onDispose { shown?.stop() }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(placeholder.copy(alpha = 0.5f))
            .semantics { if (label != null) contentDescription = label },
    ) {
        drawable?.let { d ->
            AndroidView(
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = { view ->
                    if (view.drawable !== d) view.setImageDrawable(d)
                },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Tiune fork. Putting a GIF into the field the user is in, as the image it
 * is — the content API, the way Gboard does it. The app has to say it takes
 * GIFs (`EditorInfo.contentMimeTypes`); WhatsApp, Messages, Telegram and most
 * chat apps do. Where it does not, nothing is sent and the panel says so,
 * rather than pasting a link the user did not ask for.
 */
object GifSender {
    /** A one-line answer for the panel ("This app doesn't accept GIFs"), or
     *  null. Cleared after a moment. */
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    private var sending = false

    fun send(context: Context, scope: CoroutineScope, item: GifItem, query: String) {
        if (sending) return
        val editorInstance by context.editorInstance()
        val info = editorInstance.activeInfo
        val accepts = info.contentMimeTypes.any { ClipDescription.compareMimeTypes("image/gif", it) }
        if (!accepts) {
            say(scope, "This app doesn't accept GIFs")
            return
        }
        sending = true
        _status.value = "Sending…"
        scope.launch {
            try {
                val bytes = Klipy.fetch(item.send.url, MAX_SEND_BYTES)
                val file = Klipy.saveForSending(context, item, bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider.file", file)
                val content = InputContentInfoCompat(
                    uri,
                    ClipDescription(item.title.ifBlank { "GIF" }, arrayOf("image/gif")),
                    Uri.parse(item.send.url),
                )
                val ic = FlorisImeService.currentInputConnection()
                val ok = ic != null && run {
                    ic.finishComposingText()
                    InputConnectionCompat.commitContent(
                        ic, info.base, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
                    )
                }
                if (ok) {
                    _status.value = null
                    scope.launch { Klipy.reportShare(context, item.slug, query) }
                } else {
                    say(scope, "This app didn't take the GIF")
                }
            } catch (e: GifsNotConfigured) {
                say(scope, "GIFs aren't available yet")
            } catch (e: Exception) {
                say(scope, "Couldn't send that GIF. Check your connection.")
            } finally {
                sending = false
            }
        }
    }

    private fun say(scope: CoroutineScope, text: String) {
        _status.value = text
        scope.launch {
            delay(2_500)
            if (_status.value == text) _status.value = null
        }
    }
}

/** What a list of GIFs is doing: the items so far, and whether more are
 *  coming, failed, or there are none. */
private class GifFeed(val query: String) {
    var items by mutableStateOf<List<GifItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page by mutableIntStateOf(0)
    var hasNext by mutableStateOf(true)

    suspend fun loadMore(context: Context) {
        if (loading || !hasNext) return
        loading = true
        error = null
        try {
            val next = Klipy.page(context, query, page + 1)
            // Every result, in KLIPY's order: its terms forbid reordering or
            // removing any (even a repeat across pages).
            items = items + next.items
            page += 1
            hasNext = next.hasNext && next.items.isNotEmpty()
        } catch (e: GifsNotConfigured) {
            error = "GIFs aren't available in this version yet."
            hasNext = false
        } catch (e: Exception) {
            error = "Couldn't load GIFs. Check your connection."
        } finally {
            loading = false
        }
    }
}

@Composable
private fun panelText(): Color = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName).foreground(Color.Unspecified)

/**
 * Tiune fork. The GIF page of the media panel: trending GIFs, or the results
 * of the search submitted from [GifSearchBar], in a two-column grid that
 * keeps loading as it scrolls. A tap sends the GIF.
 */
@Composable
fun GifGrid(query: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed = remember(query) { GifFeed(query) }
    val gridState = rememberLazyStaggeredGridState()
    val status by GifSender.status.collectAsState()
    val textColor = panelText()

    LaunchedEffect(feed) { feed.loadMore(context) }
    // Endless scroll: the next page once the last few are in view.
    val nearEnd by remember(feed) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            feed.items.isNotEmpty() && last >= feed.items.size - 6
        }
    }
    LaunchedEffect(feed) {
        snapshotFlow { nearEnd }.collect { if (it) feed.loadMore(context) }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        when {
            feed.items.isEmpty() && feed.error != null -> Message(feed.error!!, textColor, retry = feed.hasNext) {
                scope.launch { feed.loadMore(context) }
            }
            feed.items.isEmpty() && !feed.loading && !feed.hasNext ->
                Message("No GIFs for “$query”", textColor)
            feed.items.isEmpty() -> Message("Loading GIFs…", textColor)
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                verticalItemSpacing = 6.dp,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Keyed by position: the list only grows at the end, and a
                // GIF KLIPY repeats on a later page must still get a slot.
                itemsIndexed(feed.items, key = { index, _ -> index }) { _, item ->
                    AnimatedGif(
                        file = item.preview,
                        label = item.title.ifBlank { "GIF" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(item.preview.width.toFloat() / item.preview.height)
                            .clickable { GifSender.send(context, scope, item, query) },
                    )
                }
            }
        }
        status?.let { StatusPill(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** Results as a row above the letters, while a GIF search is being typed.
 *  Shows trending until there is something to search for; waits for a
 *  pause in the typing so every letter is not a request. */
@Composable
fun GifResultsRow(query: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settled by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        delay(450)
        settled = query.trim()
    }
    val feed = remember(settled) { GifFeed(settled) }
    LaunchedEffect(feed) { feed.loadMore(context) }
    val status by GifSender.status.collectAsState()
    val textColor = panelText()
    val scroll = rememberScrollState()
    LaunchedEffect(feed) { scroll.scrollTo(0) }

    Box(modifier = modifier.fillMaxWidth()) {
        val shown = status ?: when {
            feed.items.isEmpty() && feed.error != null -> feed.error
            feed.items.isEmpty() && !feed.loading && !feed.hasNext -> "No GIFs for “$settled”"
            else -> null
        }
        if (shown != null && feed.items.isEmpty()) {
            Text(
                text = shown,
                fontSize = 14.sp,
                color = textColor,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scroll)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The whole page, in KLIPY's order (its terms).
                for (item in feed.items) {
                    AnimatedGif(
                        file = item.preview,
                        label = item.title.ifBlank { "GIF" },
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(item.preview.width.toFloat() / item.preview.height)
                            .clickable { GifSender.send(context, scope, item, settled) },
                    )
                }
            }
            status?.let { StatusPill(it, Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun Message(text: String, color: Color, retry: Boolean = false, onRetry: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, fontSize = 14.sp, color = color, textAlign = TextAlign.Center)
        if (retry) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Try again",
                fontSize = 14.sp,
                color = Color(0xFFF5A623),
                modifier = Modifier.clickable(onClick = onRetry).padding(8.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = Color.White,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC1C1B19))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
