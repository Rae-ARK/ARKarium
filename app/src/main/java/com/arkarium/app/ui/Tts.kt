package com.arkarium.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.playback.TtsPlaybackService
import com.arkarium.app.playback.ttsErrorMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Thin Compose-facing controller for read-aloud, scoped to one ReaderScreen call site
// via rememberChapterTts() below. The TextToSpeech engine and chunk queue themselves no
// longer live here - they moved to TtsPlaybackService (see docs/proposal/
// reader-playback.md F10) precisely because this class used to own them directly and
// die with the composable (rotation, leaving the reader) along with them. This class is
// now just a live mirror of the service's state plus a couple of pass-through calls,
// re-attached to whichever service instance is actually running by rememberChapterTts's
// DisposableEffect below.
class ChapterTtsState internal constructor() {
    var isSpeaking by mutableStateOf(false)
        internal set

    // False until the engine (owned by the service now) finishes initializing, and
    // stays false permanently if init fails (no TTS engine installed - rare, but
    // possible on stripped-down ROMs/emulators) or while no service is attached yet.
    // The play control checks this and hides itself rather than doing nothing when
    // tapped.
    var isAvailable by mutableStateOf(false)
        internal set

    // Mirrors TtsPlaybackService.isPaused. "Stopped" (isSpeaking false, isPaused
    // false) and "paused" (isSpeaking false, isPaused true) both have the engine
    // silent, so UI that only checked isSpeaking couldn't tell them apart - the
    // play/pause control in TtsControlsBar (ui/ReaderScreen.kt) needs to, since it
    // shows a different icon and calls a different service method for each.
    var isPaused by mutableStateOf(false)
        internal set

    var speechRate by mutableFloatStateOf(1.0f)
        internal set

    // Rung 3 (docs/implementation/tts-playback-controls.md): mirrors
    // TtsPlaybackService.currentChunkIndex/totalChunks the same way isSpeaking/
    // isPaused above mirror their service counterparts. totalChunks is 0 with no
    // session (stopped, or not yet started) - that's the "nothing to show" case
    // TtsProgressIndicator (ui/ReaderScreen.kt) checks for.
    var currentChunkIndex by mutableIntStateOf(0)
        internal set

    var totalChunks by mutableIntStateOf(0)
        internal set

    // Rung 5: the [start, end) character range, in the text speak() was given (the
    // chapter body), of the chunk being read right now; both -1 with no session. Pushed
    // along with currentChunkIndex/totalChunks above - ReaderScreen turns it into the
    // "currently reading" highlight and the auto-scroll target.
    var currentChunkStart by mutableIntStateOf(-1)
        internal set

    var currentChunkEnd by mutableIntStateOf(-1)
        internal set

    // Rung 6 (paragraph-highlight variant, docs/implementation/
    // tts-playback-controls.md): mirrors TtsPlaybackService.currentParagraphStart/End
    // the same way currentChunkStart/End above mirror chunkSpan() - the [start, end)
    // range, in the same chapter-body coordinates, of the prose paragraph being read
    // right now. -1/-1 with no session, or whenever the current position isn't inside
    // any paragraph (see the service property's own doc comment); ReaderScreen treats
    // that the same way it already treats "no chunk" - falling back to
    // currentChunkStart/End's coarser highlight instead of drawing nothing.
    var currentParagraphStart by mutableIntStateOf(-1)
        internal set

    var currentParagraphEnd by mutableIntStateOf(-1)
        internal set

    // The voice picker's state: mirrors
    // TtsPlaybackService.availableVoices/currentVoiceName the same way the properties
    // above mirror its speaking/available/paused state - pulled fresh in attach()
    // below, not pushed live mid-session (the engine's voice list doesn't change
    // during a session the way isSpeaking does).
    var availableVoices by mutableStateOf<List<Voice>>(emptyList())
        internal set

    var currentVoiceName by mutableStateOf<String?>(null)
        internal set

    // The message for the most recent chunk
    // failure, or null once consumed. ReaderScreen shows this once (Toast - no
    // Snackbar host exists today) and calls clearError() right after, the same
    // one-shot-event shape as a SnackbarHostState's showSnackbar, just without a
    // dedicated host.
    var lastErrorMessage by mutableStateOf<String?>(null)
        internal set

    fun clearError() {
        lastErrorMessage = null
    }

    // Stage 3.4 (see docs/foundational/design-notes.md). Pushed straight through
    // to the attached service (which is where the live flag now actually lives, so it
    // survives rotation same as everything else about an in-progress session) whenever
    // it changes - see rememberChapterTts's collectAsState wiring below.
    var autoContinueEnabled: Boolean = false
        internal set(value) {
            field = value
            service?.autoContinueEnabled = value
        }

    // Invoked once the last chunk in a chapter finishes and autoContinueEnabled is
    // true. Set by ReaderScreen to its existing onNext callback - ChapterTtsState has
    // no access to chapter navigation itself, so this is the seam between "TTS
    // finished" and "advance to the next chapter." Null (ReaderScreen leaves it unset,
    // or onNext itself is null because there's no next chapter) means "do nothing
    // beyond stopping," same "null means disabled" contract onNext already uses.
    var onChapterFinished: (() -> Unit)? = null

    private var service: TtsPlaybackService? = null

    private val listener = object : TtsPlaybackService.Listener {
        override fun onSpeakingChanged(isSpeaking: Boolean) {
            this@ChapterTtsState.isSpeaking = isSpeaking
        }

        override fun onAvailabilityChanged(isAvailable: Boolean) {
            this@ChapterTtsState.isAvailable = isAvailable
        }

        override fun onPausedChanged(isPaused: Boolean) {
            this@ChapterTtsState.isPaused = isPaused
        }

        override fun onChapterFinished() {
            // Qualified explicitly: the method being overridden here and the
            // ChapterTtsState property it forwards to share a name, and an
            // unqualified reference inside this override would shadow the property
            // with the method itself rather than reading it.
            this@ChapterTtsState.onChapterFinished?.invoke()
        }

        // Rung 1 (D1): mirrors a voice list that arrives (or changes) after attach(),
        // not just the one-time snapshot attach() itself copies - see attach() below.
        override fun onVoicesChanged(voices: List<Voice>) {
            this@ChapterTtsState.availableVoices = voices
        }

        // Rung 2 (D4): surfaced as lastErrorMessage for whatever UI is attached to
        // show once and clear.
        override fun onError(errorCode: Int) {
            this@ChapterTtsState.lastErrorMessage = ttsErrorMessage(errorCode)
        }

        override fun onChunkProgressChanged(index: Int, total: Int) {
            this@ChapterTtsState.currentChunkIndex = index
            this@ChapterTtsState.totalChunks = total
            val span = service?.chunkSpan(index)
            this@ChapterTtsState.currentChunkStart = span?.start ?: -1
            this@ChapterTtsState.currentChunkEnd = span?.end ?: -1
        }

        // Rung 6 (paragraph-highlight variant): straight mirror, same shape as
        // onChunkProgressChanged above - the service already resolved start/end (or
        // -1/-1), nothing left to look up here.
        override fun onParagraphProgressChanged(start: Int, end: Int) {
            this@ChapterTtsState.currentParagraphStart = start
            this@ChapterTtsState.currentParagraphEnd = end
        }
    }

    // Called from rememberChapterTts's ServiceConnection once bound (including after a
    // rotation re-binds to the still-running service) - pulls this controller's visible
    // state up to date with whatever the service is already doing, rather than
    // resetting it to defaults the way a freshly-constructed TextToSpeech used to.
    internal fun attach(svc: TtsPlaybackService) {
        service = svc
        svc.listener = listener
        isSpeaking = svc.isSpeaking
        isAvailable = svc.isAvailable
        isPaused = svc.isPaused
        speechRate = svc.speechRate
        availableVoices = svc.availableVoices
        currentVoiceName = svc.currentVoiceName
        currentChunkIndex = svc.currentChunkIndex
        totalChunks = svc.totalChunks
        val span = svc.chunkSpan(svc.currentChunkIndex)
        currentChunkStart = span?.start ?: -1
        currentChunkEnd = span?.end ?: -1
        // Rung 6 (paragraph-highlight variant): same re-sync as currentChunkStart/End
        // above, for whatever the service had already narrowed the highlight to before
        // this attach (rotation, returning from settings/tts, reopening the chapter).
        currentParagraphStart = svc.currentParagraphStart
        currentParagraphEnd = svc.currentParagraphEnd
        svc.autoContinueEnabled = autoContinueEnabled
    }

    // Called on unbind (including the transient unbind a rotation causes). Deliberately
    // does *not* stop or shut down anything on the service side - that's the whole
    // point of this split; see TtsPlaybackService's doc comment on started-vs-bound.
    internal fun detach() {
        if (service?.listener === listener) {
            service?.listener = null
        }
        service = null
    }

    // Only sets the rate on the engine; does NOT touch anything already queued -
    // see TtsPlaybackService.setRate's doc comment.
    fun setRate(rate: Float) {
        speechRate = rate
        service?.setRate(rate)
    }

    // Pass-through to TtsPlaybackService.setVoice() - "push live" the same way
    // setRate() does: selecting a row calls ChapterTtsState.setVoice() immediately
    // if a service is attached. Only
    // advances currentVoiceName when the service actually resolved the name (its
    // own silent-no-op rule), so picking a voice that fails to resolve doesn't make
    // this controller claim a voice is active that the engine never actually set.
    fun setVoice(name: String?) {
        service?.setVoice(name)
        currentVoiceName = service?.currentVoiceName
    }

    // Starts (or restarts) the attached service and begins reading `text` aloud under
    // `chapterTitle`, which only ever reaches the read-aloud notification - nothing
    // else reads it back. No-ops if no service is attached yet (shouldn't happen in
    // practice - isAvailable, which gates the play control in ReaderScreen, only ever
    // goes true once a service has attached and its engine has finished initializing).
    fun speak(
        context: Context,
        chapterTitle: String,
        text: String,
        sessionKey: String? = null,
        // Rung 7: passed straight through to TtsPlaybackService.start() - see its own
        // doc comment on this same parameter for where it ends up.
        coverUri: String? = null
    ) {
        service?.start(context, chapterTitle, text, sessionKey, coverUri)
    }

    // Which chapter the attached service's current session is reading (the key passed
    // to speak()), or null with no session or no service attached yet. A plain getter,
    // not Compose state: read it once, after isAvailable has gone true, to reconcile
    // with a session that outlived this composition (see reconcileTtsSession).
    val sessionKey: String?
        get() = service?.sessionKey

    fun stop() {
        service?.stop()
    }

    // Pass-throughs for TtsPlaybackService's own pause/resume/skip - see that class
    // for how each is modeled at chunk granularity. No-ops (same as speak()/stop()
    // above) if no service is attached yet.
    fun pause() {
        service?.pause()
    }

    fun resume() {
        service?.resume()
    }

    fun skipNext() {
        service?.skipNext()
    }

    fun skipPrevious() {
        service?.skipPrevious()
    }
}

// Binds a ChapterTtsState to TtsPlaybackService for as long as the calling composable
// stays in composition. ReaderScreen's call site in MainActivity is reused across
// Previous/Next chapter navigation (see the chapter.id-keyed LaunchedEffect elsewhere
// in this file for the same reuse behavior with scroll state), so in practice one
// ChapterTtsState persists for the whole reading session, same as before this change -
// what's different is that rotating the device (which recreates MainActivity, and with
// it this composable) now just unbinds and rebinds to the *same still-running* service
// rather than tearing down and recreating the TextToSpeech engine itself. See
// docs/proposal/reader-playback.md F10 and bugs.md's rotation entry.
@Composable
fun rememberChapterTts(): ChapterTtsState {
    val context = LocalContext.current
    val state = remember { ChapterTtsState() }
    val prefsManager = remember { PreferencesManager(context) }
    val coroutineScope = rememberCoroutineScope()

    // Stage 3.4 (see docs/foundational/design-notes.md). Deliberately not a
    // one-shot .first() read - auto-continue is checked every time a chapter finishes,
    // not just once, so it's kept live via collectAsStateWithLifecycle() and pushed onto
    // the attached service (via ChapterTtsState.autoContinueEnabled's setter above)
    // whenever it changes. Lifecycle-aware so this stops collecting (and this
    // composable's LaunchedEffect stops firing) while the screen is backgrounded,
    // rather than collecting from DataStore for the lifetime of the composition
    // regardless of visibility (engineering-review.md F14).
    val autoContinue by prefsManager.ttsAutoContinue.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(autoContinue) {
        state.autoContinueEnabled = autoContinue
    }

    DisposableEffect(Unit) {
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? TtsPlaybackService.LocalBinder)?.service() ?: return
                state.attach(svc)
                // Stage 3.3 (see docs/foundational/design-notes.md): seeds this
                // service instance's starting rate/pitch from the settings/tts defaults
                // the first time it's ever attached to - see
                // TtsPlaybackService.setInitialRateAndPitch's doc comment for why it's
                // a no-op on every attach after the first, so a rotation mid-chapter
                // doesn't stomp a rate the reader already changed. Reading the defaults
                // is a suspend call (DataStore's Flow.first()), same as it was when
                // this lived inline in the old DisposableEffect before this change.
                coroutineScope.launch {
                    val defaultRate = prefsManager.ttsDefaultRate.first()
                    val defaultPitch = prefsManager.ttsPitch.first()
                    // The voice list isn't populated until
                    // the engine's onInit callback runs, which can land after or before
                    // this attach - svc.availableVoices is read fresh right after the
                    // seed call below rather than cached from the connection callback's
                    // own earlier read, so a voice list that only became non-empty in
                    // between isn't missed here.
                    val defaultVoiceName = prefsManager.ttsVoiceName.first()
                    svc.setInitialRateAndPitch(defaultRate, defaultPitch, defaultVoiceName)
                    state.speechRate = svc.speechRate
                    state.availableVoices = svc.availableVoices
                    state.currentVoiceName = svc.currentVoiceName
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Only the system calls this (the service process crashing), not a
                // normal unbindService() - detach() below already covers the ordinary
                // unbind-on-dispose path. Covered here too so a crash doesn't leave
                // this controller pointing at a dead binder.
                state.detach()
            }
        }

        // BIND_AUTO_CREATE: creates the service on first bind (e.g. the very first time
        // a reader opens a chapter) if it isn't already running. Does not by itself
        // start() the service in the Service.startService sense - see
        // TtsPlaybackService's doc comment on why ChapterTtsState.speak() separately
        // calls ContextCompat.startForegroundService before binding would matter, i.e.
        // the gap this bind alone would not survive across a rotation.
        bound = context.bindService(
            Intent(context, TtsPlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )

        onDispose {
            if (bound) {
                context.unbindService(connection)
            }
            state.detach()
        }
    }

    return state
}
