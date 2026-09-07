/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.voice

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.snygg.ui.SnyggBox

/**
 * Tiune fork. The voice-input panel, shown in place of the keyboard rows
 * while [ImeUiMode.VOICE] is active.
 *
 * The panel itself is a plain Android View built by the embedding app's
 * service ([FlorisImeService.createVoiceInputView]), so the app that owns the
 * speech engine also owns what the user sees of it. It is given exactly the
 * height the keyboard had, so nothing on screen jumps when the mode changes.
 * If no panel is supplied the mode quietly falls back to the keys.
 */
@Composable
fun VoiceInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val view = remember { FlorisImeService.voiceInputViewOrNull(context) }

    if (view == null) {
        LaunchedEffect(Unit) {
            keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
        }
        return
    }

    SnyggBox(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        AndroidView(
            factory = { view },
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.imeUiHeight()),
        )
    }
}
