package com.arkarium.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

// Micro-haptics on state changes: chapter-complete, sync-done, and TTS session
// start/stop (see docs/arkarium/foundational/architecture.md, Micro-haptics). Each is a single
// Vibrator.vibrate(VibrationEffect) call - no pattern, no loop, nothing that needs
// cancelling - fired once at the moment the state actually changes, from whichever
// call site owns that transition (saveReadingProgress's COMPLETION_THRESHOLD branch,
// SyncViewModel's syncAllState.value = Done, TtsPlaybackService's start()/stop()).
// Deliberately not a HapticFeedbackConstants call on a View/composable: two of the
// three sites (SyncViewModel, TtsPlaybackService) aren't composables and don't have a
// View in hand, and the point is one shared helper every site can call the same way.
//
// Effect is a double-click style for completion/done, a single tick for TTS
// start/stop - just enough difference to feel distinct without needing a pattern.
// Pre-O devices (minSdk 24) fall back to the deprecated single-long-press vibrate();
// there is no amplitude/double-click equivalent on that API, so they get one longer
// buzz instead of two short ones - still a single call, just the only shape available.
object Haptics {

    /** Chapter finished (crossed ReadingStats.COMPLETION_THRESHOLD) or a sync batch completed. */
    fun confirm(context: Context) {
        val vibrator = vibratorOf(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    }

    /** A TTS read-aloud session starting or stopping. */
    fun tick(context: Context) {
        val vibrator = vibratorOf(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(25)
        }
    }

    // VibratorManager (API 31+) is the non-deprecated way to reach the default
    // vibrator; getSystemService(Vibrator::class.java) still works but is deprecated
    // as of 31 in favor of this. Either path returns null (not an exception) on a
    // device with no vibrator at all, e.g. some tablets - callers just no-op then,
    // same as if VIBRATE were denied, since it's a normal permission and effectively
    // always granted once declared.
    private fun vibratorOf(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
