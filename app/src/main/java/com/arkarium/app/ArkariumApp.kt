package com.arkarium.app

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

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
        publishContinueReadingShortcut()
    }

    // "Continue Reading" used to be a *static* (manifest/XML-declared) shortcut. Static
    // shortcuts can't carry custom Intent flags - per Android's own docs, their launch
    // intent always gets FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK, which tears
    // down every Activity in the running task (i.e. our one-and-only MainActivity, and
    // its ViewModelStore/NavController with it) before delivering the intent. That made
    // onNewIntent() unreachable - MainActivity was always destroyed and recreated from
    // scratch first, indistinguishable from a cold start, no matter that it declared
    // launchMode="singleTop".
    //
    // Dynamic shortcuts don't have that restriction: we choose the Intent flags. Using
    // SINGLE_TOP | CLEAR_TOP here is what actually lets launchMode="singleTop" do its
    // job - MainActivity, if already running, is brought forward and reused via
    // onNewIntent() instead of being destroyed. See handleShortcutIntent()/onNewIntent()
    // in MainActivity, which already handled this correctly and just never used to get
    // the chance to run.
    //
    // Published fresh on every process start (cheap, idempotent - same shortcutId just
    // replaces the previous registration) rather than once at install time, which also
    // means a fresh install has no "Continue Reading" shortcut until the app has been
    // opened once - appropriate, since there's nothing to continue yet at that point.
    private fun publishContinueReadingShortcut() {
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_CONTINUE_READING_SHORTCUT, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val shortcut = ShortcutInfoCompat.Builder(this, "continue_reading")
            .setShortLabel(getString(R.string.shortcut_continue_reading_short_label))
            .setLongLabel(getString(R.string.shortcut_continue_reading_long_label))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_continue_reading))
            .setIntent(shortcutIntent)
            .build()
        // No-op below API 25 and silently ignored if ShortcutManager is unavailable -
        // ShortcutManagerCompat handles both; nothing here needs a version guard.
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
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
