package com.arkarium.app

import android.app.Application

// Process-level owner of things that must exist exactly once per process, not once per
// MainActivity instance. MainActivity used to install the uncaught-exception handler
// itself, in onCreate - which runs again on every recreation (rotation, multi-window
// resize, memory reclaim). Each run wrapped the previous handler in a new one that
// captured that Activity instance, so every destroyed MainActivity stayed reachable
// from Thread's static default handler (a leak), and one crash wrote the crash record
// once per wrapper. The handler is process-wide state, so it lives here.
//
// Ownership map (one authority per thing - see docs/arkarium/foundational/architecture.md,
// "Lifecycle ownership"):
//   process (this class)    crash recorder, AppScope (data/AppScope.kt), AppDatabase singleton
//   Activity (MainActivity) UI + one-shot intent routing only
//   ViewModels              in-memory library/sync/settings state
//   TtsPlaybackService      the TextToSpeech engine and the read-aloud session
class ArkariumApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
    }

    private fun installCrashRecorder() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        // Application context only: this closure lives as long as the process does.
        val appContext = applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appContext.getSharedPreferences(MainActivity.CRASH_PREFS, MODE_PRIVATE).edit()
                    .putString(
                        MainActivity.CRASH_KEY,
                        "ARKarium v${BuildConfig.VERSION_NAME}\n\n" +
                            (throwable::class.java.name + ": " + throwable.message) + "\n\n" +
                            throwable.stackTrace.take(30).joinToString("\n") { "  at $it" }
                    )
                    .commit() // commit(), not apply() - must be on disk before the process dies
            } catch (_: Throwable) {
                // Never let the crash handler itself throw and mask the original crash.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
