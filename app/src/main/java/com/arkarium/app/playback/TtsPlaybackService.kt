package com.arkarium.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arkarium.app.MainActivity
import com.arkarium.app.R

// Android's TextToSpeech.speak() silently truncates (or on some engines rejects
// outright) input longer than TextToSpeech.getMaxSpeechInputLength() - around 4000
// characters on the system engine, but chapter bodies routinely run well past that.
// Long text is split into utterance-sized pieces and queued back-to-back rather than
// handed to speak() whole, which would just cut off mid-chapter with no warning.
// Moved here unchanged from the old ChapterTtsState (ui/Tts.kt) along with the rest of
// the chunking logic - see docs/arkarium/proposal/reader-playback.md F10.
private const val TTS_MAX_CHUNK_CHARS = 3800

// Splits `text` into pieces no longer than TTS_MAX_CHUNK_CHARS, preferring to break at
// the last paragraph/sentence boundary inside each window so a split lands between
// sentences rather than mid-word. Falls back to a hard cutoff only when a chunk has no
// such boundary at all (e.g. one very long unbroken line).
private fun splitForTts(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.length <= TTS_MAX_CHUNK_CHARS) return listOf(trimmed)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < trimmed.length) {
        val hardEnd = (start + TTS_MAX_CHUNK_CHARS).coerceAtMost(trimmed.length)
        if (hardEnd == trimmed.length) {
            chunks.add(trimmed.substring(start, hardEnd))
            break
        }
        val window = trimmed.substring(start, hardEnd)
        val breakIndex = window.lastIndexOfAny(charArrayOf('\n', '.', '!', '?'))
        val splitAt = if (breakIndex >= 0) breakIndex + 1 else window.length
        chunks.add(trimmed.substring(start, start + splitAt))
        start += splitAt
    }
    return chunks.map { it.trim() }.filter { it.isNotBlank() }
}

/**
 * Owns the on-device TextToSpeech engine and the chunk queue that used to live inside
 * ChapterTtsState, `remember`-scoped to ReaderScreen's composable - see
 * docs/arkarium/proposal/reader-playback.md F10, which is also the record of the
 * now-fixed "Rotating the device restarts the app" bug this split addressed.
 *
 * A Service's lifecycle is independent of any Activity or composable, and this one is
 * built with applicationContext, not an Activity context - so none of the events that
 * used to kill read-aloud (rotating the device, Previous/Next chapter navigation
 * replacing the backStackEntry, leaving the reader) tear this down anymore. ui/Tts.kt's
 * rememberChapterTts() now just binds a thin controller to whichever instance of this
 * service is already running (or starts one) instead of constructing a TextToSpeech
 * engine itself.
 *
 * Started, not just bound, for the duration of a session: start() below calls
 * ContextCompat.startForegroundService before binding. A *bound-only* service is
 * destroyed the moment its last client unbinds, and rotation briefly drops to zero
 * bound clients between the outgoing Activity's onStop (old composable disposed, old
 * ServiceConnection unbound) and the recreated Activity's onStart (new composable,
 * new bind) - exactly the gap that would silently kill mid-chapter audio again if this
 * were bound-only. Being started keeps it alive across that gap; stop() clears that
 * started state (stopSelf) on an explicit stop rather than a transient unbind - see
 * stop()'s own doc comment for when that actually tears the service down versus leaves
 * it running for a still-bound UI.
 */
class TtsPlaybackService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): TtsPlaybackService = this@TtsPlaybackService
    }

    // Mirrors ChapterTtsState's old UI-facing callback shape (ui/Tts.kt) so the
    // Compose-side controller that replaces it can stay a thin pass-through rather
    // than re-deriving these signals some other way.
    interface Listener {
        fun onSpeakingChanged(isSpeaking: Boolean)
        fun onAvailabilityChanged(isAvailable: Boolean)
        fun onPausedChanged(isPaused: Boolean)
        fun onChapterFinished()
    }

    private val binder = LocalBinder()

    // Set/cleared by whichever ChapterTtsState is currently attached (ui/Tts.kt). Null
    // while no UI is bound - e.g. briefly during rotation, or if the app process is
    // merely backgrounded while a session plays on - in which case callbacks are simply
    // not delivered until the next attach() re-syncs state from the getters below.
    var listener: Listener? = null

    var isSpeaking = false
        private set
    var isAvailable = false
        private set
    var isPaused = false
        private set
    var speechRate = 1.0f
        private set

    // Opaque id of what the current session is reading (ReaderScreen passes the chapter
    // id), set by start() and cleared by stop(). Lets a reader that re-attaches - back
    // from the TTS settings page, after a rotation, or reopened while a session played
    // on - tell "this session is reading the chapter on screen" from "this is a stale
    // session for another chapter". Null when no session was started with a key.
    var sessionKey: String? = null
        private set

    // Stage 3.4 of docs/arkarium/implementation/settings-tts.md, moved here unchanged:
    // kept live (the Compose side pushes every DataStore change straight through) so
    // toggling auto-continue in settings/tts takes effect on the next chapter boundary
    // rather than needing the reader to leave and reopen the reader.
    var autoContinueEnabled = false

    // Seeded exactly once per service instance - see setInitialRateAndPitch() below -
    // so a rotation (which re-runs rememberChapterTts's attach logic) doesn't stomp a
    // rate the reader already changed mid-chapter back to the stored default.
    private var defaultsApplied = false

    private var engine: TextToSpeech? = null
    private var pendingChunks: List<String> = emptyList()
    private var pendingIndex: Int = 0
    private var lastQueuedUtteranceId: String? = null
    private var currentChapterTitle: String = ""

    // Set just before pause() stops the engine mid-utterance. TextToSpeech can still
    // deliver a late onDone/onError for the utterance that was just interrupted, with
    // the same utteranceId onUtteranceFinished already checks against - but pause()
    // itself never calls speak() again afterwards, so (unlike skipNext/skipPrevious
    // below, which always re-queue immediately whenever something was actually
    // playing) lastQueuedUtteranceId never advances to make that stale callback fail
    // the ID check on its own. This flag is pause()'s equivalent of that ID bump:
    // consumed by whichever onUtteranceFinished call sees it first, however long
    // after pause() that turns out to be - even across an intervening skip while
    // still paused.
    private var ignorePendingFinish = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        engine = TextToSpeech(applicationContext) { status ->
            isAvailable = status == TextToSpeech.SUCCESS
            listener?.onAvailabilityChanged(isAvailable)
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                onUtteranceFinished(utteranceId)
            }

            @Deprecated("Deprecated in Java, but still the callback older API levels invoke")
            override fun onError(utteranceId: String?) {
                onUtteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onUtteranceFinished(utteranceId)
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // START_NOT_STICKY: if the whole process is killed (low memory) while a session is
    // started, there's no in-flight utterance left to resume when the system might
    // otherwise restart this service with a null Intent - restarting it would just spin
    // up a silent, do-nothing instance. ui/Tts.kt's rememberChapterTts re-starts it from
    // scratch the next time a reader presses play, same as before this change.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    // Applies the settings/tts defaults exactly once per service instance - see
    // defaultsApplied's doc comment above. Harmless to call again (it's a no-op after
    // the first time), so ui/Tts.kt calls it unconditionally on every attach rather
    // than tracking "did I already do this" on the Compose side too.
    fun setInitialRateAndPitch(rate: Float, pitch: Float) {
        if (defaultsApplied) return
        defaultsApplied = true
        speechRate = rate
        engine?.setSpeechRate(rate)
        engine?.setPitch(pitch)
    }

    // Only sets the rate on the engine; does NOT touch anything already queued -
    // Android's TextToSpeech.setSpeechRate() only affects speak() calls made *after* it
    // returns, so a rate change here reaches the *next* chunk (queued one at a time in
    // speakNextChunk below), same behavior as the old ChapterTtsState.setRate().
    fun setRate(rate: Float) {
        speechRate = rate
        engine?.setSpeechRate(rate)
    }

    // Starts (or restarts) the service as a foreground service and begins reading
    // `text` from the top. Called from ui/Tts.kt's ChapterTtsState.speak(), which has
    // the Context this needs for ContextCompat.startForegroundService - the service
    // itself only takes it as a parameter here rather than holding a Context of its own
    // outside applicationContext (used internally for the engine and notification).
    fun start(context: Context, chapterTitle: String, text: String, sessionKey: String? = null) {
        val tts = engine ?: return
        val chunks = splitForTts(text)
        if (chunks.isEmpty()) return
        this.sessionKey = sessionKey
        // Set before starting the service so that whenever onStartCommand actually
        // runs - always, on every startForegroundService call, whether this service
        // instance already exists or is only now being created - buildNotification()
        // already has the right title instead of momentarily showing the previous
        // session's (or the "ARKarium" fallback).
        currentChapterTitle = chapterTitle
        ContextCompat.startForegroundService(context, Intent(context, TtsPlaybackService::class.java))
        pendingChunks = chunks
        pendingIndex = 0
        isPaused = false
        ignorePendingFinish = false
        isSpeaking = true
        listener?.onSpeakingChanged(true)
        speakNextChunk(TextToSpeech.QUEUE_FLUSH)
    }

    // Stops whatever's currently queued, drops the foreground notification, and clears
    // the service's "started" state (stopSelf) - an explicit stop, not the transient
    // rebinding a rotation causes; see this class's doc comment on why those two are
    // kept distinct. If a UI is still bound when this runs (the reader's own Stop
    // button, not leaving the reader) the service and its engine stay alive, same as
    // the old engine-reuse-across-chapters behavior - stopSelf only actually tears the
    // service down once nothing is bound to it either, e.g. after the reader has also
    // been left.
    fun stop() {
        engine?.stop()
        isSpeaking = false
        isPaused = false
        ignorePendingFinish = false
        pendingChunks = emptyList()
        pendingIndex = 0
        lastQueuedUtteranceId = null
        sessionKey = null
        listener?.onSpeakingChanged(false)
        listener?.onPausedChanged(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Pause is not a TextToSpeech feature (see uiux.md's playback-controls entry);
    // modeled as "stop, remember pendingIndex, resume from that chunk" - the chunk
    // the engine was partway through restarts from its own beginning rather than
    // mid-word, which is the chunk-level granularity that entry describes as the easy
    // first step (word-level position would need onRangeStart, API 26+).
    fun pause() {
        if (!isSpeaking) return
        ignorePendingFinish = true
        engine?.stop()
        isSpeaking = false
        isPaused = true
        listener?.onSpeakingChanged(false)
        listener?.onPausedChanged(true)
    }

    // Resumes from pendingIndex - the chunk pause() left off on - by re-queuing it
    // from the top, same as speakNextChunk always has; there is no finer-grained
    // position to resume from at chunk granularity. No-ops if pendingIndex somehow
    // ran off the end while paused (shouldn't happen - nothing advances it except
    // onUtteranceFinished, which only runs while actively speaking).
    fun resume() {
        if (!isPaused) return
        isPaused = false
        if (pendingIndex !in pendingChunks.indices) {
            listener?.onPausedChanged(false)
            return
        }
        isSpeaking = true
        listener?.onPausedChanged(false)
        listener?.onSpeakingChanged(true)
        speakNextChunk(TextToSpeech.QUEUE_FLUSH)
    }

    // Moves directly to the next/previous chunk, rather than waiting for the current
    // one to finish (playing) or for a resume() call (paused). No-ops at either end of
    // the chapter instead of wrapping - same "disabled, not hidden" shape the
    // Previous/Next Chapter buttons elsewhere in the reader already use.
    fun skipNext() {
        if (pendingChunks.isEmpty()) return
        jumpTo((pendingIndex + 1).coerceAtMost(pendingChunks.size - 1))
    }

    fun skipPrevious() {
        if (pendingChunks.isEmpty()) return
        jumpTo((pendingIndex - 1).coerceAtLeast(0))
    }

    // Shared by skipNext/skipPrevious. Only re-queues speech immediately if something
    // was actually playing; while paused, the new index just takes effect the next
    // time resume() is called, the same "stop and remember where we are" model
    // pause() itself uses. Safe without touching ignorePendingFinish above:
    // speakNextChunk sets lastQueuedUtteranceId to the new chunk's id before
    // tts.speak() returns, so a late callback for the chunk being interrupted here
    // fails the existing utteranceId check in onUtteranceFinished on its own, the same
    // way a normal chunk-to-chunk advance already relies on.
    private fun jumpTo(index: Int) {
        if (index == pendingIndex) return
        pendingIndex = index
        if (isSpeaking) {
            engine?.stop()
            speakNextChunk(TextToSpeech.QUEUE_FLUSH)
        }
    }

    // Queues exactly one chunk - the one at pendingIndex - rather than the whole
    // chapter at once, so a setRate() call made mid-chunk takes effect on the next
    // chunk instead of having already been locked in. The next chunk is only queued
    // once this one finishes, from onUtteranceFinished() below.
    private fun speakNextChunk(queueMode: Int) {
        val tts = engine ?: return
        if (pendingIndex !in pendingChunks.indices) return
        val chunk = pendingChunks[pendingIndex]
        val utteranceId = "arkarium_chunk_${pendingIndex}_${chunk.hashCode()}"
        lastQueuedUtteranceId = utteranceId
        tts.setSpeechRate(speechRate)
        tts.speak(chunk, queueMode, null, utteranceId)
    }

    // Ignores callbacks that don't match the chunk most recently queued - a late
    // onDone/onError arriving after stop() (or after a fresh start() replaced the
    // queue) would otherwise advance a read that's no longer current. Otherwise
    // advances to the next chunk, or - once the last chunk in the chapter finishes -
    // either auto-continues (if enabled) or stops the service the same way stop() does.
    private fun onUtteranceFinished(utteranceId: String?) {
        if (ignorePendingFinish) {
            ignorePendingFinish = false
            return
        }
        if (utteranceId != null && utteranceId != lastQueuedUtteranceId) return
        pendingIndex++
        if (pendingIndex in pendingChunks.indices) {
            speakNextChunk(TextToSpeech.QUEUE_ADD)
        } else {
            isSpeaking = false
            listener?.onSpeakingChanged(false)
            if (autoContinueEnabled) {
                listener?.onChapterFinished()
                // The reader's onChapterFinished handler (ReaderScreen's onNext) loads
                // the next chapter and calls stop() itself as part of its existing
                // chapter.id-keyed reset (see ui/ReaderScreen.kt) before most likely
                // calling start() again for the new chapter - so this service is left
                // running, foreground notification and all, rather than torn down here
                // only to be immediately started again.
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_LOW: visible and persistent while reading aloud is genuinely
        // playing, same as any other media-playback notification, but silent - no
        // sound/heads-up for a notification that exists purely to keep the foreground
        // service alive and tell the reader something's still reading, not to alert
        // them to anything new.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Read aloud",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while a chapter is being read aloud"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Reading aloud")
        .setContentText(currentChapterTitle.ifBlank { "ARKarium" })
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 42
    }
}
