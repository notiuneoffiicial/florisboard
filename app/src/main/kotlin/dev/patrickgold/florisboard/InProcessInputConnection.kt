/*
 * Copyright (C) 2026 The Tiune Authors
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

package dev.patrickgold.florisboard

import android.os.Bundle
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText

/**
 * Tiune fork: the input connection to a field in the keyboard's OWN process,
 * with every call that waits for an answer turned into one that does not.
 *
 * The keyboard and the host app share a process, and therefore a main
 * thread. When the keyboard opens on one of the host's own fields — a
 * WebView — a read such as [getSelectedText] is answered by that WebView,
 * which needs the main thread to answer; the keyboard is holding the main
 * thread waiting for the answer. Each read times out after seconds, the
 * reads come in threes on every start and selection change, and Android
 * declares the app not responding. On the phone this was "the app crashes
 * whenever the add-a-word sheet opens" and "dictating into Tiune's own
 * field does nothing".
 *
 * Writes (commit, delete, set selection) are one-way and go through
 * untouched. Only the reads — the framework's `CompletableFuture`-backed
 * methods — are answered here, with "nothing", which every caller in this
 * keyboard already handles: the fields in question are the host's small
 * ones, where suggestions and caps mode are not missed.
 */
internal class InProcessInputConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = null
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean = false
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
}
