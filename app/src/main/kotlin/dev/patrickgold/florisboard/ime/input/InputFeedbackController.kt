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

package dev.patrickgold.florisboard.ime.input

import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.staticCompositionLocalOf
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import org.florisboard.lib.android.systemServiceOrNull
import org.florisboard.lib.android.systemVibratorOrNull
import org.florisboard.lib.android.vibrate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

val LocalInputFeedbackController = staticCompositionLocalOf<InputFeedbackController> { error("not init") }

/**
 * Input feedback controller is responsible to process and perform audio and haptic
 * feedback for user interactions based on the system and floris preferences.
 */
class InputFeedbackController private constructor(private val ims: InputMethodService) {
    companion object {
        fun new(ims: InputMethodService) = InputFeedbackController(ims)
    }

    private val prefs by FlorisPreferenceStore

    private val audioManager = ims.systemServiceOrNull(AudioManager::class)
    private val vibrator = ims.systemVibratorOrNull()
    private val contentResolver = ims.contentResolver
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var systemAudioEnabled: Boolean = false
    private var systemHapticEnabled: Boolean = false

    fun updateSystemPrefsState() {
        systemAudioEnabled = systemPref(Settings.System.SOUND_EFFECTS_ENABLED)
        systemHapticEnabled = systemPref(Settings.System.HAPTIC_FEEDBACK_ENABLED)
    }

    fun keyPress(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyPress.get()) performAudioFeedback(data, 1.0)
        if (prefs.inputFeedback.hapticFeatKeyPress.get()) performHapticFeedback(data, 1.0, Touch.TAP)
    }

    fun keyLongPress(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyLongPress.get()) performAudioFeedback(data, 0.7)
        if (prefs.inputFeedback.hapticFeatKeyLongPress.get()) performHapticFeedback(data, 0.4, Touch.LONG_PRESS)
    }

    fun keyRepeatedAction(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyRepeatedAction.get()) performAudioFeedback(data, 0.4)
        if (prefs.inputFeedback.hapticFeatKeyRepeatedAction.get()) performHapticFeedback(data, 0.05, Touch.TICK)
    }

    fun gestureSwipe(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatGestureSwipe.get()) performAudioFeedback(data, 0.7)
        if (prefs.inputFeedback.hapticFeatGestureSwipe.get()) performHapticFeedback(data, 0.4, Touch.TAP)
    }

    fun gestureMovingSwipe(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatGestureMovingSwipe.get()) performAudioFeedback(data, 0.4)
        if (prefs.inputFeedback.hapticFeatGestureMovingSwipe.get()) performHapticFeedback(data, 0.05, Touch.TICK)
    }

    /**
     * Tiune fork: what kind of touch this was, so the right platform effect
     * can be asked for by name.
     *
     * The amplitude factor used to carry this too — anything under 1.0 became
     * the same faint tick — which made a long press and a key repeat
     * indistinguishable, and neither of them like the phone's own keyboard.
     * These three constants are the ones every stock keyboard uses, and the
     * device's motor is tuned for them.
     */
    private enum class Touch(val constant: Int) {
        TAP(HapticFeedbackConstants.KEYBOARD_TAP),
        LONG_PRESS(HapticFeedbackConstants.LONG_PRESS),
        TICK(HapticFeedbackConstants.CLOCK_TICK),
    }

    /**
     * The platform haptic, on the view's thread. Falls back to the vibrator
     * when the platform declines — a device with the effect unimplemented, or
     * a window that went away in the moment between the two.
     */
    private fun performOnViewThread(view: android.view.View, touch: Touch) {
        val didPerform = try {
            view.performHapticFeedback(
                touch.constant,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        } catch (e: Exception) {
            flogDebug { "haptic feedback refused by the view: $e" }
            false
        }
        if (didPerform) return
        vibrator?.vibrate(
            duration = prefs.inputFeedback.hapticVibrationDuration.get(),
            strength = prefs.inputFeedback.hapticVibrationStrength.get(),
        )
    }

    private fun systemPref(id: String): Boolean {
        if (contentResolver == null) return false
        return Settings.System.getInt(contentResolver, id, 0) != 0
    }

    private fun performAudioFeedback(data: KeyData, factor: Double) {
        if (audioManager == null) return
        if (!prefs.inputFeedback.audioEnabled.get()) return
        if (prefs.inputFeedback.audioActivationMode.get() ==
            InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS && !systemAudioEnabled) return

        scope.launch {
            val volume = (prefs.inputFeedback.audioVolume.get() * factor) / 100.0
            val effect = when (data.code) {
                KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyCode.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                KeyCode.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            if (volume in 0.01..1.00) {
                flogDebug { "Perform audio with volume=$volume and effect=$effect" }
                audioManager.playSoundEffect(effect, volume.toFloat())
            }
        }
    }

    private fun performHapticFeedback(data: KeyData, factor: Double, touch: Touch) {
        if (vibrator == null) return
        if (!prefs.inputFeedback.hapticEnabled.get()) return
        if (prefs.inputFeedback.hapticActivationMode.get() ==
            InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS && !systemHapticEnabled) return

        scope.launch {
            if (prefs.inputFeedback.hapticVibrationMode.get() == HapticVibrationMode.USE_HAPTIC_FEEDBACK_INTERFACE) {
                // Tiune fork: on the view's own thread, and never on this one.
                //
                // `performHapticFeedback` reaches through the view into its
                // window session, which is only valid on the thread that owns
                // the view — and this coroutine runs on Dispatchers.Default.
                // A view whose window has gone away between the keypress and
                // this line throws, on a background thread, with no handler
                // above it: the process dies. That was survivable while the
                // default was USE_VIBRATOR_DIRECTLY, which never touched a
                // view; it is not now that this is the path every keypress
                // takes.
                //
                // `post` returns false when the view has no handler to post
                // to, which is the same "there is no window" case — so both
                // ways of failing fall through to the vibrator below.
                val view = ims.window?.window?.decorView
                if (view != null && view.post { performOnViewThread(view, touch) }) {
                    return@launch
                }
            }

            vibrator.vibrate(
                duration = prefs.inputFeedback.hapticVibrationDuration.get(),
                strength = prefs.inputFeedback.hapticVibrationStrength.get(),
                factor = factor,
            )
        }
    }
}
