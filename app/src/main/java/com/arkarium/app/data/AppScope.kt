package com.arkarium.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Continue-Reading-loses-progress fix, the actual remaining cause (see
// MainActivity's onPositionChanged/onSystemBack - the "last safe checkpoint
// before possible process death" and the system-Back save). Both used to launch
// their Room write on `lifecycleScope`, which androidx.lifecycle cancels the
// moment that particular MainActivity *instance* reaches Lifecycle.Event.ON_DESTROY.
// That's harmless for an ordinary background (press Home): onDestroy doesn't run
// while the instance is merely stopped, so the already-launched coroutine gets all
// the time it needs. It is NOT harmless for MainActivity being recreated - a
// rotation, a multi-window resize, or the system recreating the Activity after
// reclaiming it for memory - which is exactly "the continue reading gets lost
// whenever MainActivity is redone": during a recreation, the OLD instance's
// onPause -> onStop -> onDestroy run back-to-back in the same handful of frames,
// almost guaranteeing the in-flight Room upsert gets cancelled mid-write, before it
// commits, long before the new instance is even created - restarting the app looks
// identical to this from the data layer's point of view, since a cold start is just
// a recreation with no prior instance to race against.
//
// A scope tied to the process instead of any one Activity instance fixes this: it
// is only ever cancelled if the process itself is dying, at which point there is
// nothing left to lose by not finishing the write anyway. Every caller of this is
// expected to be a short, already-essentially-fire-and-forget Room write (see
// MainActivity.saveReadingProgress) - nothing here is meant to outlive the app
// being force-stopped or uninstalled, only to outlive the *Activity instance* that
// happened to kick it off.
object AppScope {
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
