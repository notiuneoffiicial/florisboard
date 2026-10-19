/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.media

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.media.gif.GifGrid
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.media.MediaSearch
import org.florisboard.lib.snygg.ui.SnyggIcon
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import dev.patrickgold.florisboard.ime.input.InputEventDispatcher
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.media.emoji.EmojiData
import dev.patrickgold.florisboard.ime.media.emoji.EmojiPaletteView
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggRow

@SuppressLint("MutableCollectionMutableState")
@Composable
fun MediaInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val inputFeedbackController = LocalInputFeedbackController.current

    var emojiLayoutDataMap by remember { mutableStateOf(EmojiData.Fallback) }
    LaunchedEffect(Unit) {
        emojiLayoutDataMap = EmojiData.get(context, "ime/media/emoji/root.txt")
    }

    val tab by MediaPanel.tabFlow.collectAsState()
    val gifQuery by MediaPanel.gifQueryFlow.collectAsState()

    // Tiune fork: a search from the panel opens the letters with a search
    // bar on top (MediaSearch, MediaSearchBar), the way Gboard does.
    fun openSearch(kind: MediaSearch.Kind) {
        inputFeedbackController.keyPress(TextKeyData.IME_UI_MODE_TEXT)
        MediaSearch.open(kind, if (kind == MediaSearch.Kind.GIF) gifQuery else "")
        keyboardManager.activeState.batchEdit {
            it.keyboardMode = KeyboardMode.CHARACTERS
            it.imeUiMode = ImeUiMode.TEXT
            // A search is not a sentence: no capital first letter.
            it.inputShiftState = InputShiftState.UNSHIFTED
        }
    }

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        when (tab) {
            // Tiune fork: emoji search is the magnifier at the start of the
            // category row, as in Gboard — no row of its own.
            MediaPanel.Tab.EMOJI -> EmojiPaletteView(
                modifier = Modifier.weight(1f),
                fullEmojiMappings = emojiLayoutDataMap,
                onSearch = { openSearch(MediaSearch.Kind.EMOJI) },
            )
            MediaPanel.Tab.GIF -> {
                // The GIF page has no category row, so its search is the
                // field at its top: "Search KLIPY" (KLIPY's required wording),
                // or the search the grid is showing, with a way back to
                // trending.
                val showingGifSearch = gifQuery.isNotEmpty()
                MediaSearchField(
                    text = if (showingGifSearch) gifQuery else "Search KLIPY",
                    isPlaceholder = !showingGifSearch,
                    onClick = { openSearch(MediaSearch.Kind.GIF) },
                    onClear = if (showingGifSearch) ({ MediaPanel.gifQuery = "" }) else null,
                )
                GifGrid(
                    query = gifQuery,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SnyggRow(
            elementName = FlorisImeUi.MediaBottomRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.keyboardRowBaseHeight * 0.8f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyboardLikeButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                inputEventDispatcher = keyboardManager.inputEventDispatcher,
                keyData = TextKeyData.IME_UI_MODE_TEXT,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Text(
                    text = "ABC",
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // Tiune fork: the panel's two pages, emoji and GIFs.
            MediaTab(
                selected = tab == MediaPanel.Tab.EMOJI,
                label = "Emoji",
                icon = Icons.Outlined.EmojiEmotions,
                onClick = { MediaPanel.tab = MediaPanel.Tab.EMOJI },
            )
            Spacer(modifier = Modifier.width(12.dp))
            MediaTab(
                selected = tab == MediaPanel.Tab.GIF,
                label = "GIFs",
                icon = null,
                onClick = { MediaPanel.tab = MediaPanel.Tab.GIF },
            )
            Spacer(modifier = Modifier.weight(1f))
            KeyboardLikeButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                inputEventDispatcher = keyboardManager.inputEventDispatcher,
                keyData = TextKeyData.DELETE,
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.Backspace, contentDescription = null)
            }
        }
    }
}

/** Tiune fork. The search field at the top of the media panel: a quiet pill
 *  that opens the search with the letters underneath. Text at suggestion
 *  size, not key-label size. */
@Composable
private fun MediaSearchField(
    text: String,
    isPlaceholder: Boolean,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    val textColor = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName).foreground(Color.Unspecified)
    SnyggBox(
        elementName = FlorisImeUi.Key.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        clickAndSemanticsModifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIcon(modifier = Modifier.size(18.dp).alpha(0.6f), imageVector = Icons.Default.Search)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                color = textColor,
                maxLines = 1,
                modifier = Modifier.weight(1f).alpha(if (isPlaceholder) 0.6f else 1f),
            )
            if (onClear != null) {
                SnyggIcon(
                    modifier = Modifier
                        .clickable(onClick = onClear)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .size(18.dp)
                        .alpha(0.6f),
                    imageVector = Icons.Default.Close,
                    contentDescription = "Back to trending",
                )
            }
        }
    }
}

/** Tiune fork. One of the panel's pages in the bottom row, shown by an icon
 *  or, with none, as "GIF"; the page that is up sits in a key-shaped pill. */
@Composable
private fun MediaTab(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    val textColor = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName).foreground(Color.Unspecified)
    SnyggBox(
        elementName = if (selected) FlorisImeUi.Key.elementName else FlorisImeUi.MediaBottomRowButton.elementName,
        modifier = Modifier
            .fillMaxHeight()
            .width(64.dp)
            .padding(vertical = 6.dp),
        clickAndSemanticsModifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            SnyggIcon(
                modifier = Modifier.size(22.dp).alpha(if (selected) 1f else 0.6f),
                imageVector = icon,
            )
        } else {
            // "GIF" in letters, as Gboard has it: the icon for it is a box
            // too small to read at this size.
            Text(
                text = "GIF",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.alpha(if (selected) 1f else 0.6f),
            )
        }
    }
}

@Composable
internal fun KeyboardLikeButton(
    modifier: Modifier = Modifier,
    inputEventDispatcher: InputEventDispatcher,
    keyData: KeyData,
    elementName: String = FlorisImeUi.MediaEmojiKey.elementName,
    content: @Composable () -> Unit,
) {
    val inputFeedbackController = LocalInputFeedbackController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val selector = if (isPressed) {
        SnyggSelector.PRESSED
    } else {
        SnyggSelector.NONE
    }

    SnyggBox(
        elementName = elementName,
        attributes = mapOf(FlorisImeUi.Attr.Code to keyData.code),
        selector = selector,
        clickAndSemanticsModifier = modifier
            .indication(interactionSource, ripple())
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false).also {
                        if (it.pressed != it.previousPressed) it.consume()
                    }
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    inputEventDispatcher.sendDown(keyData)
                    inputFeedbackController.keyPress(keyData)
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        interactionSource.tryEmit(PressInteraction.Release(press))
                        inputEventDispatcher.sendUp(keyData)
                    } else {
                        interactionSource.tryEmit(PressInteraction.Cancel(press))
                        inputEventDispatcher.sendCancel(keyData)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
