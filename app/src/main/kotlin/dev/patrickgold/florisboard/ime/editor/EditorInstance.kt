/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.editor

import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.SuggestionSpan
import android.view.KeyEvent
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.latin.AutocorrectMemory
import dev.patrickgold.florisboard.ime.text.composing.Appender
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.android.showShortToastSync

class EditorInstance(context: Context) : AbstractEditorInstance(context) {
    companion object {
        private const val SPACE = " "
    }

    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val clipboardManager by context.clipboardManager()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()
    private val nlpManager by context.nlpManager()

    private val activeState get() = keyboardManager.activeState
    val autoSpace = AutoSpaceState()
    val phantomSpace = PhantomSpaceState()
    val massSelection = MassSelectionState()

    // ---- Tiune fork: what the space bar changed, showing it, taking it back ----

    /** A correction the keyboard just applied: what was typed, what it
     *  became, the separator that followed and where the caret is after
     *  it. Alive until the next key or a tap elsewhere. */
    class Revertable(val typed: String, val corrected: String, val separator: String, val caret: Int)

    /** Set by [noteAutocorrect] just before the candidate replaces the
     *  word; consumed by the separator commit that follows. */
    private var justCorrected: Pair<String, String>? = null
    private var justCorrectedStart = -1

    var revertable: Revertable? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var highlightGen = 0

    // ---- Tiune fork: a word the user went back into is theirs ----
    //
    // Gboard never auto-corrects a word the cursor was moved into: going
    // back to fix a word is the user overruling the keyboard, and
    // "fixing" the fix again is the one thing that must not happen. So:
    // after any caret move we did not cause, the word under the caret (or
    // the word typed over a selection) is *resumed* — the space bar leaves
    // it alone, it gets no red line, and it is remembered as insisted on.

    /** True after the caret moved somewhere the editor did not put it,
     *  until the next key says what that was for. */
    private var caretMoved = false

    /** Start of the word being edited after such a move; -1 for a fresh
     *  word typed somewhere new. */
    private var resumedStart = -1

    fun isComposingResumed(): Boolean {
        val c = activeContent
        if (!c.composing.isValid || c.composingText.isBlank()) return false
        return caretMoved || (resumedStart >= 0 && c.composing.start == resumedStart)
    }

    /** The first letter after a caret move decides: into a word, or over a
     *  selection, and that word is resumed; anywhere else it is fresh. */
    private fun settleResumed() {
        if (!caretMoved) return
        caretMoved = false
        val c = activeContent
        resumedStart = when {
            c.selection.isSelectionMode -> c.selection.start
            c.composing.isValid && c.composingText.isNotBlank() -> c.composing.start
            else -> -1
        }
    }

    private fun endResumed() {
        caretMoved = false
        resumedStart = -1
    }

    private fun currentInputConnection() = FlorisImeService.currentInputConnection()

    override fun handleStartInputView(editorInfo: FlorisEditorInfo, isRestart: Boolean) {
        // Tiune fork: a new field is a new conversation with autocorrect.
        if (!isRestart) AutocorrectMemory.startSession()
        dropAutocorrectState(finishHighlight = false)
        endResumed()
        if (!prefs.correction.rememberCapsLockState.get()) {
            activeState.inputShiftState = InputShiftState.UNSHIFTED
        }
        activeState.isActionsOverflowVisible = false
        activeState.isActionsEditorVisible = false
        super.handleStartInputView(editorInfo, isRestart)
        val keyboardMode = when (editorInfo.inputAttributes.type) {
            InputAttributes.Type.NUMBER -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.NUMERIC
            }
            InputAttributes.Type.PHONE -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.PHONE
            }
            InputAttributes.Type.TEXT -> {
                activeState.keyVariation = when (editorInfo.inputAttributes.variation) {
                    InputAttributes.Variation.EMAIL_ADDRESS,
                    InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                    -> {
                        KeyVariation.EMAIL_ADDRESS
                    }
                    InputAttributes.Variation.PASSWORD,
                    InputAttributes.Variation.VISIBLE_PASSWORD,
                    InputAttributes.Variation.WEB_PASSWORD,
                    -> {
                        KeyVariation.PASSWORD
                    }
                    InputAttributes.Variation.URI -> {
                        KeyVariation.URI
                    }
                    else -> {
                        KeyVariation.NORMAL
                    }
                }
                KeyboardMode.CHARACTERS
            }
            else -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.CHARACTERS
            }
        }
        activeState.keyboardMode = keyboardMode
        activeState.isComposingEnabled = when (keyboardMode) {
            KeyboardMode.NUMERIC,
            KeyboardMode.PHONE,
            KeyboardMode.PHONE2,
            -> false
            else -> activeState.keyVariation != KeyVariation.PASSWORD &&
                prefs.suggestion.enabled.get()// &&
            //!instance.inputAttributes.flagTextAutoComplete &&
            //!instance.inputAttributes.flagTextNoSuggestions
        }
        activeState.isIncognitoMode = when (prefs.suggestion.incognitoMode.get()) {
            IncognitoMode.FORCE_OFF -> false
            IncognitoMode.FORCE_ON -> true
            IncognitoMode.DYNAMIC_ON_OFF -> {
                editorInfo.imeOptions.flagNoPersonalizedLearning || prefs.suggestion.forceIncognitoModeFromDynamic.get()
            }
        }
    }

    override fun handleSelectionUpdate(oldSelection: EditorRange, newSelection: EditorRange, composing: EditorRange) {
        autoSpace.setInactiveFromUpdate()
        phantomSpace.setInactiveFromUpdate()
        if (massSelection.isActive) {
            super.handleMassSelectionUpdate(newSelection, composing)
        } else {
            super.handleSelectionUpdate(oldSelection, newSelection, composing)
        }
    }

    override fun onUnexpectedSelectionUpdate(newSelection: EditorRange) {
        // Tiune fork: the caret moving away from the corrected word (a tap
        // in the text, the app itself) ends the chance to take the
        // correction back with backspace, and the highlight with it. The
        // highlight's own frames keep the caret where it is.
        val highlightFrame = highlightOwnsComposing && revertable?.caret == newSelection.start
        revertable?.let { r ->
            if (newSelection.start != r.caret || newSelection.isSelectionMode) dropAutocorrectState(finishHighlight = true)
        }
        // A caret that is neither where we last knew it nor where our
        // pending edit puts it was moved by the user or the app.
        if (!highlightFrame && newSelection.start != activeContent.selection.start &&
            newSelection.start != expectedContent()?.selection?.start) {
            caretMoved = true
            resumedStart = -1
        }
    }

    override fun handleFinishInputView() {
        dropAutocorrectState(finishHighlight = false)
        super.handleFinishInputView()
    }

    override fun determineComposingEnabled(): Boolean {
        return activeState.isComposingEnabled && nlpManager.isSuggestionOn()
    }

    override fun determineComposer(composerName: ExtensionComponentName): Composer {
        return keyboardManager.resources.composers.value[composerName] ?: Appender
    }

    override fun shouldDetermineComposingRegion(editorInfo: FlorisEditorInfo): Boolean {
        return super.shouldDetermineComposingRegion(editorInfo) &&
            (phantomSpace.isInactive || phantomSpace.showComposingRegion)
    }

    /**
     * Sets the selection of the input editor to the specified [start] and [end] values. This method does nothing if
     * the input connection is not valid or if the input editor is raw.
     *
     * @param start The start of the selection (inclusive). May be any value ranging from -1 to positive infinity.
     * @param end The end of the selection (exclusive). May be any value ranging from -1 to positive infinity.
     *
     * @return True on success or if the selection is already at specified position, false otherwise.
     */
    fun setSelection(start: Int, end: Int): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        dropAutocorrectState(finishHighlight = true)
        val selection = EditorRange.normalized(start, end)
        return super.setSelection(selection)
    }

    private fun shouldInsertAutoSpaceBefore(text: String): Boolean {
        if (!prefs.correction.autoSpacePunctuation.get() || text.isEmpty()) return false
        if (activeInfo.isRawInputEditor) return false
        if (activeState.keyVariation != KeyVariation.NORMAL) return false

        val punctuationRule = nlpManager.getActivePunctuationRule()
        val textBefore = activeContent.getTextBeforeCursor(1)
        return textBefore.isNotEmpty() && !textBefore.last().isWhitespace() &&
            punctuationRule.symbolsFollowingAutoSpace.contains(text.first())
    }

    private fun shouldInsertAutoSpaceAfter(text: String): Boolean {
        if (!prefs.correction.autoSpacePunctuation.get() || text.isEmpty()) return false
        if (activeInfo.isRawInputEditor) return false
        if (activeState.keyVariation != KeyVariation.NORMAL) return false

        val punctuationRule = nlpManager.getActivePunctuationRule()
        val content = activeContent
        val textBefore = content.getTextBeforeCursor(3).let { textBefore ->
            if (autoSpace.isActive && textBefore.isNotEmpty() && textBefore.last() == ' ') {
                textBefore.dropLast(1)
            } else {
                textBefore
            }
        }
        return textBefore.isNotEmpty() && !textBefore.last().isWhitespace() &&
            content.currentWordText.all { !it.isDigit() } &&
            punctuationRule.symbolsPrecedingAutoSpace.contains(text.first())
    }

    override fun commitChar(char: String): Boolean {
        val endsWord = char.isNotEmpty() && !char[0].isLetterOrDigit() && char[0] != '\''
        val ic = currentInputConnection()
        ic?.beginBatchEdit()
        val correctedBefore = justCorrected != null
        if (endsWord) cancelHighlight() else dropAutocorrectState(finishHighlight = true)
        if (endsWord) finishResumedWord() else settleResumed()
        if (endsWord && !correctedBefore) underlineTypoIfAny()
        val ok = commitCharInner(char)
        if (ok && endsWord) afterSeparator()
        ic?.endBatchEdit()
        return ok
    }

    /** A resumed word being finished is the user's: remembered, and never
     *  marked. */
    private fun finishResumedWord() {
        if (isComposingResumed()) AutocorrectMemory.insist(activeContent.composingText.trim())
        endResumed()
    }

    private fun commitCharInner(char: String): Boolean {
        val isInsertAutoSpaceBeforeChar = shouldInsertAutoSpaceBefore(char)
        val isInsertAutoSpaceAfterChar = shouldInsertAutoSpaceAfter(char)
        val isDeletePreviousSpace = isInsertAutoSpaceAfterChar && autoSpace.isActive
        if (isInsertAutoSpaceAfterChar) {
            autoSpace.setActive()
        } else {
            autoSpace.setInactive()
        }
        val isPhantomSpaceActive = phantomSpace.determine(char)
        phantomSpace.setInactive()
        return super.commitChar(
            char = char,
            deletePreviousSpace = isDeletePreviousSpace,
            insertSpaceBeforeChar = isInsertAutoSpaceBeforeChar || isPhantomSpaceActive,
            insertSpaceAfterChar = isInsertAutoSpaceAfterChar,
        )
    }

    /**
     * Commits the given [text] to this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * This method overwrites any selected text and replaces it with given [text]. If there is no
     * text selected (selection is in cursor mode), then this method will insert the [text] after
     * the cursor, then set the cursor position to the first character after the inserted text.
     *
     * @param text The text to commit.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    override fun commitText(text: String): Boolean {
        val endsWord = text.isNotEmpty() && !text[0].isLetterOrDigit() && text[0] != '\''
        val ic = currentInputConnection()
        ic?.beginBatchEdit()
        val correctedBefore = justCorrected != null
        if (endsWord) cancelHighlight() else dropAutocorrectState(finishHighlight = true)
        if (endsWord) finishResumedWord() else settleResumed()
        if (endsWord && !correctedBefore) underlineTypoIfAny()
        val isPhantomSpaceActive = phantomSpace.determine(text)
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val ok = if (isPhantomSpaceActive) {
            super.commitText("$SPACE$text")
        } else {
            super.commitText(text)
        }
        if (ok && endsWord) afterSeparator()
        ic?.endBatchEdit()
        return ok
    }

    /**
     * Tiune fork. The keyboard is about to replace the word being typed
     * with the correction it chose itself. Remembered so that the
     * separator that follows can show the change and offer it back.
     */
    fun noteAutocorrect(typed: String, corrected: String) {
        val content = activeContent
        justCorrected = typed to corrected
        justCorrectedStart = if (content.composing.isValid) content.composing.start else -1
        AutocorrectMemory.recordApplied(typed, corrected)
    }

    /** Forgets a correction in flight, the revert window and the highlight. */
    private fun dropAutocorrectState(finishHighlight: Boolean) {
        justCorrected = null
        revertable = null
        if (finishHighlight) cancelHighlight() else highlightGen++
        highlightOwnsComposing = false
    }

    /** After the separator that followed a correction: open the revert
     *  window and light the corrected word up. */
    private fun afterSeparator() {
        val (typed, corrected) = justCorrected ?: return
        justCorrected = null
        val start = justCorrectedStart
        val expected = expectedContent() ?: return
        val end = start + corrected.length
        val caret = expected.selection.start
        val sepLen = caret - end
        if (start < 0 || sepLen !in 1..3) return
        val before = expected.textBeforeSelection
        if (before.length < corrected.length + sepLen) return
        val tail = before.substring(before.length - corrected.length - sepLen)
        if (!tail.startsWith(corrected)) return
        revertable = Revertable(typed, corrected, tail.substring(corrected.length), caret)
        startHighlight(start, end, corrected, caret)
    }

    /**
     * Tiune fork. The backspace right after a correction puts the typed
     * word back — with its separator, so typing carries on — and tells
     * the memory the user meant it. Like Gboard.
     */
    fun revertAutocorrect(): Boolean {
        val r = revertable ?: return false
        revertable = null
        val content = activeContent
        val tail = r.corrected + r.separator
        if (activeInfo.isRawInputEditor || content.selection.isSelectionMode ||
            content.selection.start != r.caret || !content.textBeforeSelection.endsWith(tail)) {
            cancelHighlight()
            return false
        }
        cancelHighlight()
        val ok = replaceBeforeCursor(tail.length, r.typed + r.separator)
        if (ok) AutocorrectMemory.insist(r.typed)
        return ok
    }

    /**
     * The corrected word, washed in Tiune amber that comes in and fades
     * out (about two thirds of a second) — the field's own way of saying
     * "this was changed". Drawn as composing text with a background span,
     * which every plain text field and the WebView honour; a field that
     * ignores spans simply shows nothing. Any key or tap ends it early.
     */
    private fun startHighlight(start: Int, end: Int, word: String, caret: Int) {
        if (activeInfo.isRawInputEditor || currentInputConnection() == null) return
        val gen = ++highlightGen
        highlightOwnsComposing = true
        fun frame(alpha: Int, last: Boolean) = Runnable {
            if (gen != highlightGen) return@Runnable
            val ic = currentInputConnection() ?: return@Runnable
            ic.beginBatchEdit()
            if (last) {
                highlightOwnsComposing = false
                ic.finishComposingText()
            } else {
                ic.setComposingRegion(start, end)
                val s = SpannableString(word)
                s.setSpan(BackgroundColorSpan(Color.argb(alpha, 0xF5, 0xA6, 0x23)), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ic.setComposingText(s, 1)
                ic.setSelection(caret, caret)
            }
            ic.endBatchEdit()
        }
        for ((at, alpha) in HIGHLIGHT_FRAMES) mainHandler.postDelayed(frame(alpha, false), at)
        mainHandler.postDelayed(frame(0, true), HIGHLIGHT_END)
    }

    private fun cancelHighlight() {
        highlightGen++
        if (!highlightOwnsComposing) return
        highlightOwnsComposing = false
        currentInputConnection()?.finishComposingText()
    }

    /**
     * Tiune fork. The word being finished is a misspelling — English says
     * so, and it is not the user's, not Roman Urdu, not one they insisted
     * on — so it goes into the field with the red line every text field
     * draws for a misspelt word, and the fixes behind a tap on it. Done
     * by replacing the composing word with itself plus the span, which is
     * no visible change. Skipped while an edit of ours is still in flight,
     * because then the word here is a keystroke behind the field.
     */
    private fun underlineTypoIfAny() {
        // A keyboard told not to fix typos should not nag about them either.
        if (!FlorisImeService.autoCorrectEnabled()) return
        val content = activeContent
        if (activeInfo.isRawInputEditor || !content.composing.isValid || content.composingText.isBlank()) return
        if (activeState.keyVariation != KeyVariation.NORMAL || hasPendingEdit()) return
        val fixes = nlpManager.typoSuggestionsOrNull(content) ?: return
        val ic = currentInputConnection() ?: return
        val s = SpannableString(content.composingText)
        val span = SuggestionSpan(appContext, fixes.take(SuggestionSpan.SUGGESTIONS_MAX_SIZE).toTypedArray(),
            SuggestionSpan.FLAG_EASY_CORRECT or SuggestionSpan.FLAG_MISSPELLED)
        s.setSpan(span, 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ic.commitText(s, 1)
    }

    /**
     * Completes the given [candidate] in the current composing region. Does nothing if the current
     * input editor is not rich or if the input connection is invalid.
     *
     * Current phantom space state is respected and a space char will be inserted accordingly.
     * Phantom space will be activated if the text is committed.
     *
     * @param candidate The candidate to complete in this editor.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun commitCompletion(candidate: SuggestionCandidate): Boolean {
        val text = candidate.text.toString()
        if (text.isEmpty() || activeInfo.isRawInputEditor) return false
        revertable = null
        cancelHighlight()
        endResumed()
        val content = activeContent
        return if (content.composing.isValid) {
            phantomSpace.setActive(showComposingRegion = false, candidate = candidate)
            super.finalizeComposingText(text)
        } else {
            val isPhantomSpaceActive = phantomSpace.determine(text)
            phantomSpace.setActive(showComposingRegion = false, candidate = candidate)
            return if (isPhantomSpaceActive) {
                super.commitText("$SPACE$text")
            } else {
                super.commitText(text)
            }.also {
                // handled in finalizeComposingText if content.composing.isValid
                updateLastCommitPosition()
            }
        }
    }

    /**
     * Commit a word generated by a gesture.
     *
     * Ignores the current phantom space state and will insert a space depending on the character
     * before selection start. Phantom space will be activated if the text is committed.
     *
     * @param text The text to commit in this editor.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun commitGesture(text: String): Boolean {
        if (text.isEmpty() || activeInfo.isRawInputEditor) return false
        dropAutocorrectState(finishHighlight = true)
        val isPhantomSpaceActive = phantomSpace.determine(text, forceActive = true)
        phantomSpace.setActive(showComposingRegion = true)
        return if (isPhantomSpaceActive) {
            super.commitText("$SPACE$text")
        } else {
            super.commitText(text)
        }.also {
            updateLastCommitPosition()
        }
    }

    /**
     * Commits the given [ClipboardItem]. If the clip data is text (incl. HTML), it delegates to [commitText].
     * If the item has a content URI (and the EditText supports it), the item is committed as rich data.
     * This allows for committing (e.g) images.
     *
     * @param item The ClipboardItem to commit
     *
     * @return True on success, false if something went wrong.
     */
    fun commitClipboardItem(item: ClipboardItem?): Boolean {
        if (item == null) return false
        val mimeTypes = item.mimeTypes
        return when (item.type) {
            ItemType.TEXT -> {
                commitText(item.text.toString()).also {
                    updateLastCommitPosition()
                }
            }
            ItemType.IMAGE, ItemType.VIDEO -> {
                item.uri ?: return false
                val id = ContentUris.parseId(item.uri)
                val file = ClipboardFileStorage.getFileForId(appContext, id)
                if (!file.exists()) return false
                val inputContentInfo = InputContentInfoCompat(
                    item.uri,
                    ClipDescription("clipboard media file", mimeTypes.toTypedArray()),
                    null,
                )
                val ic = currentInputConnection() ?: return false
                ic.finishComposingText()
                val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                InputConnectionCompat.commitContent(ic, activeInfo.base, inputContentInfo, flags, null)
            }
        }.also {
            if (prefs.clipboard.historyHideOnPaste.get()) {
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
            }
        }
    }

    /**
     * Executes a backward delete on this editor's text. If a text selection is active, all
     * characters inside this selection will be removed, else only the left-most character from
     * the cursor's position.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun deleteBackwards(unit: OperationUnit): Boolean {
        dropAutocorrectState(finishHighlight = true)
        val content = activeContent
        if (unit == OperationUnit.CHARACTERS) {
            if (phantomSpace.isActive && content.currentWord.isValid && prefs.glide.immediateBackspaceDeletesWord.get()) {
                return deleteBackwards(OperationUnit.WORDS)
            }
        }
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return if (content.selection.isSelectionMode) {
            commitText("")
        } else runBlocking {
            deleteAroundCursor(unit, OperationScope.BEFORE_CURSOR, n = 1)
        }
    }

    /**
     * Executes a backward delete on this editor's text. If a text selection is active, all
     * characters inside this selection will be removed, else only the left-most character from
     * the cursor's position.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun deleteForwards(unit: OperationUnit): Boolean {
        dropAutocorrectState(finishHighlight = true)
        val content = activeContent
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return if (content.selection.isSelectionMode) {
            commitText("")
        } else runBlocking {
            deleteAroundCursor(unit, OperationScope.AFTER_CURSOR, n = 1)
        }
    }

    fun setSelectionSurrounding(n: Int, unit: OperationUnit, scope: OperationScope): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val content = activeContent
        val selection = content.selection
        val safeEditorBounds = content.safeEditorBounds
        if (selection.isNotValid) return false
        when (scope) {
            OperationScope.BEFORE_CURSOR -> {
                if (n <= 0) {
                    return setSelection(selection.end, selection.end)
                }
                val textToAnalyze = content.text.substring(0, content.localSelection.end)
                val length = runBlocking {
                    when (unit) {
                        OperationUnit.CHARACTERS -> breakIterators.measureLastUChars(textToAnalyze, n)
                        OperationUnit.WORDS -> breakIterators.measureLastUWords(textToAnalyze, n)
                    }
                }
                return setSelection((selection.end - length).coerceAtLeast(safeEditorBounds.start), selection.end)
            }
            OperationScope.AFTER_CURSOR -> {
                if (n <= 0) {
                    return setSelection(selection.start, selection.start)
                }
                val textToAnalyze = content.text.substring(content.localSelection.start)
                val length = runBlocking {
                    when (unit) {
                        OperationUnit.CHARACTERS -> breakIterators.measureUChars(textToAnalyze, n)
                        OperationUnit.WORDS -> breakIterators.measureUWords(textToAnalyze, n)
                    }
                }
                return setSelection(selection.start, (selection.start + length).coerceAtMost(safeEditorBounds.end))
            }
        }
    }

    /**
     * Performs a cut command on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardCut(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val text = activeContent.selectedText.ifBlank { currentInputConnection()?.getSelectedText(0) }
        if (text != null) {
            clipboardManager.addNewPlaintext(text.toString())
        } else {
            appContext.showShortToastSync("Failed to retrieve selected text requested to cut: Eiter selection state is invalid or an error occurred within the input connection.")
        }
        return deleteBackwards(OperationUnit.CHARACTERS)
    }

    /**
     * Performs a copy command on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardCopy(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val text = activeContent.selectedText.ifBlank { currentInputConnection()?.getSelectedText(0) }
        if (text != null) {
            clipboardManager.addNewPlaintext(text.toString())
        } else {
            appContext.showShortToastSync("Failed to retrieve selected text requested to copy: Eiter selection state is invalid or an error occurred within the input connection.")
        }
        val activeSelection = activeContent.selection
        return setSelection(activeSelection.end, activeSelection.end)
    }

    /**
     * Performs a paste command on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardPaste(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return commitClipboardItem(clipboardManager.primaryClip).also { result ->
            if (!result) {
                appContext.showShortToastSync("Failed to paste item.")
            }
        }
    }

    /**
     * Performs a select all on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardSelectAll(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val ic = currentInputConnection() ?: return false
        ic.finishComposingText()
        return if (activeInfo.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_A, meta(ctrl = true))
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
    }

    /**
     * Performs an enter key press on the current input editor.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performEnter(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return if (activeInfo.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
        } else {
            commitText("\n")
        }
    }

    fun tryPerformEnterCommitRaw(): Boolean {
        return if (subtypeManager.activeSubtype.primaryLocale.language.startsWith("zh") && activeContent.composing.length > 0) {
            finalizeComposingText(activeContent.composingText)
        } else {
            false
        }
    }

    /**
     * Performs a given [action] on the current input editor.
     *
     * @param action The action to be performed on this editor instance.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performEnterAction(action: ImeOptions.Action): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val ic = currentInputConnection() ?: return false
        return ic.performEditorAction(action.toInt())
    }

    /**
     * Undoes the last action.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performUndo(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true))
    }

    /**
     * Redoes the last Undo action.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performRedo(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true, shift = true))
    }

    override fun reset() {
        super.reset()
        autoSpace.setInactive()
        phantomSpace.setInactive()
        massSelection.reset()
    }

    private fun PhantomSpaceState.determine(text: String, forceActive: Boolean = false): Boolean {
         val content = activeContent
         val selection = content.selection
         if (!(isActive || forceActive) || selection.isNotValid || selection.start <= 0 || text.isEmpty()) return false
         val textBefore = content.getTextBeforeCursor(1)
         val punctuationRule = nlpManager.getActivePunctuationRule()
         if (!subtypeManager.activeSubtype.primaryLocale.supportsAutoSpace) return false;
         return textBefore.isNotEmpty() &&
             (punctuationRule.symbolsPrecedingPhantomSpace.contains(textBefore[textBefore.length - 1]) ||
                 textBefore[textBefore.length - 1].isLetterOrDigit()) &&
             (punctuationRule.symbolsFollowingPhantomSpace.contains(text[0]) || text[0].isLetterOrDigit())
    }

    class AutoSpaceState {
        companion object {
            private const val F_IS_ACTIVE = 0x1
            private const val F_STAY_ACTIVE_NEXT_UPDATE = 0x4
        }

        private val state = AtomicInteger(0)

        val isActive: Boolean
            get() = state.get() and F_IS_ACTIVE != 0

        val isInactive: Boolean
            get() = !isActive

        fun setActive(stayActiveNextUpdate: Boolean = true) {
            state.set(F_IS_ACTIVE or (if (stayActiveNextUpdate) F_STAY_ACTIVE_NEXT_UPDATE else 0))
        }

        fun setInactive() {
            state.set(0)
        }

        fun setInactiveFromUpdate() {
            state.updateAndGet { state ->
                if ((state and F_STAY_ACTIVE_NEXT_UPDATE) != 0) (state and F_STAY_ACTIVE_NEXT_UPDATE.inv()) else 0
            }
        }
    }

    class PhantomSpaceState {
        companion object {
            private const val F_IS_ACTIVE = 0x1
            private const val F_SHOW_COMPOSING_REGION = 0x2
            private const val F_STAY_ACTIVE_NEXT_UPDATE = 0x4
        }

        private val state = AtomicInteger(0)
        var candidateForRevert: SuggestionCandidate? = null
            private set

        val isActive: Boolean
            get() = state.get() and F_IS_ACTIVE != 0

        val isInactive: Boolean
            get() = !isActive

        val showComposingRegion: Boolean
            get() = state.get() and F_SHOW_COMPOSING_REGION != 0

        fun setActive(
            showComposingRegion: Boolean,
            stayActiveNextUpdate: Boolean = true,
            candidate: SuggestionCandidate? = null,
        ) {
            state.set(
                F_IS_ACTIVE
                    or (if (showComposingRegion) F_SHOW_COMPOSING_REGION else 0)
                    or (if (stayActiveNextUpdate) F_STAY_ACTIVE_NEXT_UPDATE else 0)
            )
            candidateForRevert = candidate
        }

        fun setInactive() {
            state.set(0)
            candidateForRevert = null
        }

        fun setInactiveFromUpdate() {
            val prevStateValue = state.getAndUpdate { state ->
                if ((state and F_STAY_ACTIVE_NEXT_UPDATE) != 0) (state and F_STAY_ACTIVE_NEXT_UPDATE.inv()) else 0
            }
            if ((prevStateValue and F_STAY_ACTIVE_NEXT_UPDATE) == 0) {
                candidateForRevert = null
            }
        }
    }

    inner class MassSelectionState {
        private val state = AtomicInteger(0)

        val isActive: Boolean
            get() = state.get() > 0

        val isInactive: Boolean
            get() = !isActive

        fun begin() {
            state.incrementAndGet()
        }

        fun end() {
            if (state.decrementAndGet() == 0) {
                // We need to emulate a selection update to update the content if mass selection has ended
                handleSelectionUpdate(EditorRange.Unspecified, activeContent.selection, EditorRange.Unspecified)
            }
        }

        fun reset() {
            state.set(0)
        }
    }
}

/** Tiune fork. The highlight's frames: (ms after the separator, alpha of
 *  the amber wash). In over 120 ms, a hold, out over half a second. */
private val HIGHLIGHT_FRAMES: List<Pair<Long, Int>> = listOf(
    0L to 40, 40L to 80, 90L to 110, 320L to 110, 380L to 88, 440L to 66, 500L to 44, 560L to 24, 620L to 8,
)
private const val HIGHLIGHT_END = 680L
