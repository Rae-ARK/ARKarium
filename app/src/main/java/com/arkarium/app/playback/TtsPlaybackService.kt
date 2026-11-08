package com.arkarium.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.arkarium.app.MainActivity
import com.arkarium.app.R
import com.arkarium.app.util.Haptics

// Android's TextToSpeech.speak() silently truncates (or on some engines rejects
// outright) input longer than TextToSpeech.getMaxSpeechInputLength() - around 4000
// characters on the system engine, but chapter bodies routinely run well past that.
// Long text is split into utterance-sized pieces and queued back-to-back rather than
// handed to speak() whole, which would just cut off mid-chapter with no warning.
// Moved here unchanged from the old ChapterTtsState (ui/Tts.kt) along with the rest of
// the chunking logic - see docs/arkarium/proposal/reader-playback.md F10.
private const val TTS_MAX_CHUNK_CHARS = 3800

// Splits `text` into pieces no longer than `maxChunk`, breaking between sentences.
// Sentence boundaries come from java.text.BreakIterator (ICU-backed on Android, no new
// dependency), which handles CJK terminators (。！？), doesn't treat "3.14" / "Mr." as
// sentence ends the way a bare '.' search did, and never reports a boundary inside a
// surrogate pair. Whole sentences are packed greedily into each chunk. Only a single
// sentence longer than `maxChunk` is hard-cut, and even then at the last whitespace /
// CJK comma inside the window if there is one, and never between the two halves of a
// surrogate pair. No text is dropped or duplicated; only whitespace at chunk edges is
// trimmed. See docs/arkarium/proposal/engineering-review.md F11.
internal fun splitForTts(
    text: String,
    maxChunk: Int = TTS_MAX_CHUNK_CHARS,
    locale: java.util.Locale = java.util.Locale.getDefault()
): List<String> = splitForTtsSpans(text, maxChunk, locale).map { text.substring(it.start, it.end) }

// Rung 5 (docs/arkarium/implementation/tts-playback-controls.md): where a chunk sits in
// the *original* `text` handed to splitForTts - [start, end), whitespace at both edges
// already excluded, so text.substring(start, end) is exactly the chunk splitForTts
// returns. This is the chunk-index -> text-range mapping the "currently reading"
// highlight and auto-scroll are built on; splitForTts above is now just this with the
// ranges turned back into strings, so the two can't drift apart.
internal data class TtsChunkSpan(val start: Int, val end: Int)

internal fun splitForTtsSpans(
    text: String,
    maxChunk: Int = TTS_MAX_CHUNK_CHARS,
    locale: java.util.Locale = java.util.Locale.getDefault()
): List<TtsChunkSpan> {
    require(maxChunk >= 2) { "maxChunk must be at least 2 (one surrogate pair)" }
    val lead = text.indexOfFirst { !it.isWhitespace() }
    if (lead < 0) return emptyList()
    val trimmed = text.trim()

    // Raw [start, end) pieces in `trimmed` coordinates, before per-piece whitespace
    // trimming - the same cuts splitForTts always made.
    val raw = mutableListOf<TtsChunkSpan>()
    if (trimmed.length <= maxChunk) {
        raw.add(TtsChunkSpan(0, trimmed.length))
    } else {
        var chunkStart = 0
        val iterator = java.text.BreakIterator.getSentenceInstance(locale)
        iterator.setText(trimmed)

        var sentenceStart = iterator.first()
        var sentenceEnd = iterator.next()
        while (sentenceEnd != java.text.BreakIterator.DONE) {
            if (sentenceEnd - chunkStart > maxChunk) {
                // This sentence doesn't fit on top of what's already in the chunk.
                if (sentenceStart > chunkStart) {
                    raw.add(TtsChunkSpan(chunkStart, sentenceStart))
                    chunkStart = sentenceStart
                }
                // Still too long on its own: hard-cut it into pieces.
                while (sentenceEnd - chunkStart > maxChunk) {
                    val cut = hardCutIndex(trimmed, chunkStart, maxChunk)
                    raw.add(TtsChunkSpan(chunkStart, cut))
                    chunkStart = cut
                }
            }
            sentenceStart = sentenceEnd
            sentenceEnd = iterator.next()
        }
        if (chunkStart < trimmed.length) raw.add(TtsChunkSpan(chunkStart, trimmed.length))
    }

    return raw.mapNotNull { piece ->
        var a = piece.start
        var b = piece.end
        while (a < b && trimmed[a].isWhitespace()) a++
        while (b > a && trimmed[b - 1].isWhitespace()) b--
        if (b > a) TtsChunkSpan(lead + a, lead + b) else null
    }
}

// Where to cut `text` when a single sentence starting at `start` is longer than
// `maxChunk`: the last whitespace or CJK comma within the window if there is one,
// otherwise the window's end - pulled back by one if that would land between a high
// and low surrogate.
private fun hardCutIndex(text: String, start: Int, maxChunk: Int): Int {
    val windowEnd = start + maxChunk
    var i = windowEnd
    while (i > start + 1) {
        val c = text[i - 1]
        if (c.isWhitespace() || c == '\u3001' || c == '\uff0c') return i
        i--
    }
    return if (Character.isHighSurrogate(text[windowEnd - 1])) windowEnd - 1 else windowEnd
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

        // Pushed whenever availableVoices changes
        // (today, only once - from the TextToSpeech init callback), so a picker that's
        // already attached and open while the engine is still initializing fills in on
        // its own instead of staying stuck on "System default" until the page reopens.
        fun onVoicesChanged(voices: List<Voice>)

        // Rung 2 (D4): reported instead of silently advancing when a chunk fails - see
        // onChunkError below. errorCode is one of TextToSpeech.ERROR_*.
        fun onError(errorCode: Int)

        // Rung 3 (docs/arkarium/implementation/tts-playback-controls.md): pushed
        // whenever pendingIndex or the chunk count changes - a fresh start(), a
        // skip, a chunk finishing on its own, or the session ending (index/total
        // both 0, same "nothing to show" signal as isSpeaking/isPaused both false).
        // Chunk granularity, not a page number - see that doc's "Rung 3" section for
        // why a chunk isn't a stable reader-facing unit yet.
        fun onChunkProgressChanged(index: Int, total: Int)
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

    // Rung 3: read-only views onto pendingChunks/pendingIndex below, for whatever
    // attaches after the fact (ChapterTtsState.attach()) rather than only via the
    // push-based Listener callback - same "getter for the late-attacher, callback for
    // everyone already attached" split isSpeaking/isPaused/etc. above already use.
    val currentChunkIndex: Int
        get() = pendingIndex
    val totalChunks: Int
        get() = pendingChunks.size

    // Rung 5: the [start, end) range of chunk `index` in the text the session was started
    // with (ReaderScreen passes the chapter body), or null for an index outside the
    // session - which includes "no session", so a stopped service reports no highlight.
    internal fun chunkSpan(index: Int): TtsChunkSpan? = pendingSpans.getOrNull(index)

    // The engine's voice list (design-notes.md's "TTS settings split"). Populated once from
    // engine?.voices inside onInit below, alongside isAvailable - empty until the
    // engine finishes initializing, same timing as isAvailable itself. Exposed as a
    // plain List (not a Flow) since, like isSpeaking/isAvailable above, it's state
    // this service owns outright and pushes through Listener/attach(), not something
    // DataStore-backed.
    var availableVoices: List<Voice> = emptyList()
        private set

    // Opaque id of what the current session is reading (ReaderScreen passes the chapter
    // id), set by start() and cleared by stop(). Lets a reader that re-attaches - back
    // from the TTS settings page, after a rotation, or reopened while a session played
    // on - tell "this session is reading the chapter on screen" from "this is a stale
    // session for another chapter". Null when no session was started with a key.
    var sessionKey: String? = null
        private set

    // Mirrors whatever voice is actually active on the engine right now (null = still
    // on the engine's own default). Updated by setInitialRateAndPitch's seed-once call
    // and by setVoice() below - TtsSettingsScreen's "current selection" row reads this
    // (via ChapterTtsState, Step 3) rather than re-deriving it from the raw preference,
    // since a stored name that no longer resolves (see setVoice's doc comment) must
    // show as "System default" here too, not as a stale string.
    var currentVoiceName: String? = null
        private set

    // Stage 3.4 (see docs/arkarium/foundational/design-notes.md), moved here unchanged:
    // kept live (the Compose side pushes every DataStore change straight through) so
    // toggling auto-continue in settings/tts takes effect on the next chapter boundary
    // rather than needing the reader to leave and reopen the reader.
    var autoContinueEnabled = false

    // Seeded exactly once per service instance - see setInitialRateAndPitch() below -
    // so a rotation (which re-runs rememberChapterTts's attach logic) doesn't stomp a
    // rate the reader already changed mid-chapter back to the stored default. Rung 1
    // (D2): this only flips to true once the seed has actually been applied to the
    // engine, not as soon as setInitialRateAndPitch is called - see applyPendingSeed().
    private var defaultsApplied = false

    // Rung 1 (D2): the rate/pitch/voice setInitialRateAndPitch was given, held here
    // until the engine is ready to actually accept them. Previously the setters were
    // called immediately regardless of init state; if the engine's TextToSpeech
    // constructor callback hadn't run yet, setSpeechRate/setPitch/setVoice silently
    // failed (they return an error code rather than queueing) and - because
    // defaultsApplied was already set - never got a second chance. pendingSeedRate
    // doubles as "is there a seed waiting at all": null once applied or never seeded.
    private var pendingSeedRate: Float? = null
    private var pendingSeedPitch: Float = 1.0f
    private var pendingSeedVoiceName: String? = null

    private var engine: TextToSpeech? = null
    private var pendingChunks: List<String> = emptyList()

    // Rung 5: where each of pendingChunks sits in the text start() was given, index for
    // index. Always the same size as pendingChunks (both set together, both cleared
    // together).
    private var pendingSpans: List<TtsChunkSpan> = emptyList()
    private var pendingIndex: Int = 0
        set(value) {
            field = value
            listener?.onChunkProgressChanged(value, pendingChunks.size)
        }
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

    // --- F22 (docs/arkarium/proposal/reader-playback.md): audio focus, becoming-noisy,
    // MediaSession ---

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Speech, not music: tells the platform (and the engine, see onCreate) what this
    // audio is, so it routes/ducks others correctly and Bluetooth shows the right icon.
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // API 26+ keeps the request object so it can be abandoned later; below that the
    // deprecated listener-based overloads are used and this stays null.
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // True only while paused because another app took focus *temporarily*, i.e. when a
    // later AUDIOFOCUS_GAIN should resume us. Cleared by any pause/stop the reader
    // initiated themselves, so a deliberate pause is never undone by a stray GAIN.
    private var pausedForTransientFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (focusActionFor(change, pausedForTransientFocusLoss)) {
            FocusAction.PAUSE_UNTIL_GAIN -> if (isSpeaking) {
                pausedForTransientFocusLoss = true
                pauseInternal(abandonFocus = false)
            }
            FocusAction.PAUSE_AND_RELEASE -> {
                pausedForTransientFocusLoss = false
                if (isSpeaking) pauseInternal(abandonFocus = true) else abandonAudioFocus()
            }
            FocusAction.RESUME -> {
                pausedForTransientFocusLoss = false
                resume()
            }
            FocusAction.NONE -> Unit
        }
    }

    // Unplugging wired headphones / dropping Bluetooth audio flips output to the
    // speaker; the platform broadcasts this precisely so a spoken-word app pauses
    // instead of suddenly reading aloud to the room. Registered only while speaking.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private var noisyReceiverRegistered = false

    private var mediaSession: MediaSession? = null

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        noisyReceiverRegistered = false
        runCatching { unregisterReceiver(noisyReceiver) }
    }

    // Lock-screen controls, Bluetooth/headset buttons and Wear/Auto all drive playback
    // through the session's callback, which just forwards to the same public methods the
    // in-app controls use. Framework android.media.session.MediaSession (API 21+) rather
    // than rewriting it on MediaSessionCompat; the notification's MediaStyle (see
    // buildNotification) is attached to it via MediaSessionCompat.Token.fromToken, which
    // wraps this same session's token, so there is still exactly one session.
    private fun createMediaSession() {
        val session = MediaSession(this, "ARKariumTts")
        @Suppress("DEPRECATION")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { resume() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { skipNext() }
            override fun onSkipToPrevious() { skipPrevious() }
            override fun onStop() { stop() }
        })
        mediaSession = session
    }

    // Pushes isSpeaking/isPaused/chapter title to the session. Called wherever those
    // change; cheap, and a no-op before the session exists or after it's released.
    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val state = when {
            isSpeaking -> PlaybackState.STATE_PLAYING
            isPaused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (isSpeaking) 1f else 0f)
                .build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentChapterTitle.ifBlank { "ARKarium" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Reading aloud")
                .build()
        )
        session.isActive = state != PlaybackState.STATE_STOPPED
        refreshNotification()
    }

    // Re-posts the foreground notification so its play/pause button follows
    // isSpeaking/isPaused. Only while a session is live: once stop()/onChunkError()/the
    // last chunk have called stopForeground(REMOVE), re-posting here would resurrect a
    // notification nobody owns.
    private fun refreshNotification() {
        if (!isSpeaking && !isPaused) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    // Everything a session holds that must not outlive it: focus, the noisy receiver,
    // the pending auto-resume, and the session's active flag.
    private fun releaseSessionResources() {
        pausedForTransientFocusLoss = false
        unregisterNoisyReceiver()
        abandonAudioFocus()
        updateMediaSession()
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        createMediaSession()
        engine = TextToSpeech(applicationContext) { status ->
            isAvailable = status == TextToSpeech.SUCCESS
            if (isAvailable) engine?.setAudioAttributes(audioAttributes)
            // engine?.voices can itself be null on some engines/OEM builds even after a
            // SUCCESS init - orEmpty() keeps availableVoices a plain empty list rather
            // than forcing every caller (TtsSettingsScreen's grouping, Step 4) to
            // null-check it too.
            availableVoices = engine?.voices?.toList().orEmpty()
            listener?.onAvailabilityChanged(isAvailable)
            // Rung 1 (D1): pushed every time this callback runs (today, that's once),
            // so a picker already open and bound before init finished sees the real
            // list land instead of staying stuck on an empty snapshot.
            listener?.onVoicesChanged(availableVoices)
            // Rung 1 (D2): apply whatever setInitialRateAndPitch was given before the
            // engine was ready, now that it is. No-op if nothing was ever seeded, or
            // if a seed already got applied via the "engine was already ready" path in
            // setInitialRateAndPitch itself.
            if (isAvailable) applyPendingSeed()
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                onUtteranceFinished(utteranceId)
            }

            @Deprecated("Deprecated in Java, but still the callback older API levels invoke")
            override fun onError(utteranceId: String?) {
                // No errorCode available on this overload - TextToSpeech.ERROR is the
                // generic code, same fallback rung 2's onChunkError uses it for.
                onChunkError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onChunkError(utteranceId, errorCode)
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // START_NOT_STICKY: if the whole process is killed (low memory) while a session is
    // started, there's no in-flight utterance left to resume when the system might
    // otherwise restart this service with a null Intent - restarting it would just spin
    // up a silent, do-nothing instance. ui/Tts.kt's rememberChapterTts re-starts it from
    // scratch the next time a reader presses play, same as before this change.
    //
    // Rung 4: this is also where the notification's transport buttons land - each is a
    // PendingIntent.getService carrying one of TtsTransportAction's intent actions. A tap
    // calls the same public methods the in-app controls and the MediaSession callback
    // use. It never calls startForeground: the session's own start() already did, and a
    // tap that arrives with no live session (a stale notification, the gap between
    // auto-continued chapters) just lets the service go.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transport = ttsTransportActionFor(intent?.action)
        if (transport != null) {
            if (!isSpeaking && !isPaused) {
                stopSelf()
                return START_NOT_STICKY
            }
            when (transport) {
                TtsTransportAction.PREVIOUS -> skipPrevious()
                TtsTransportAction.NEXT -> skipNext()
                TtsTransportAction.PAUSE -> pause()
                TtsTransportAction.PLAY -> resume()
                TtsTransportAction.STOP -> stop()
            }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterNoisyReceiver()
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        super.onDestroy()
    }

    // Applies the settings/tts defaults exactly once per service instance - see
    // defaultsApplied's doc comment above. `voiceName` defaults to null (engine/system
    // default) for anyone who hasn't visited the voice picker, same "no-op until
    // opted into" shape rate/pitch already had before Stage 3.3.
    //
    // Rung 1 (D2): no longer applies anything itself or flips defaultsApplied up
    // front - it only records the seed and, if the engine is already ready, applies
    // it now; otherwise onCreate's init callback applies it the moment isAvailable
    // goes true. Still harmless to call again (a no-op after the first successful
    // apply), so ui/Tts.kt still calls it unconditionally on every attach rather than
    // tracking "did I already do this" on the Compose side too.
    fun setInitialRateAndPitch(rate: Float, pitch: Float, voiceName: String? = null) {
        if (defaultsApplied) return
        pendingSeedRate = rate
        pendingSeedPitch = pitch
        pendingSeedVoiceName = voiceName
        if (isAvailable) applyPendingSeed()
    }

    // Does the actual applying for setInitialRateAndPitch, from whichever of "engine
    // ready" and "seed provided" happens second. pendingSeedRate is cleared (set back
    // to null) once applied so a later onInit callback - there shouldn't be one, but a
    // defensive re-check costs nothing - doesn't re-apply a stale seed.
    private fun applyPendingSeed() {
        val rate = pendingSeedRate ?: return
        if (defaultsApplied) return
        defaultsApplied = true
        pendingSeedRate = null
        speechRate = rate
        engine?.setSpeechRate(rate)
        engine?.setPitch(pendingSeedPitch)
        val toApply = voiceNameToApply(pendingSeedVoiceName, availableVoices.map { it.name })
        if (toApply != null) setVoice(toApply)
    }

    // Resolves `name` against availableVoices and applies it, live. A name that
    // doesn't resolve (uninstalled language pack, engine switched since it was stored)
    // is a silent no-op that leaves the engine on whatever voice it already had - the
    // same "unknown ⇒ treat as default, never crash" posture tts-multivoice.md uses for
    // its own segment format - rather than throwing or falling back to some other
    // voice the reader didn't ask for. currentVoiceName only advances when the resolve
    // actually succeeds, so a failed call doesn't make the settings UI claim a voice is
    // active when the engine is still on its old one.
    //
    // Rung 1 (D3): a null name is no longer a no-op. It now means "go back to the
    // engine's own default voice" - applied live via TextToSpeech.getDefaultVoice()
    // (Kotlin: engine.defaultVoice) - and currentVoiceName is set to null either way,
    // so the Voice card and the picker's "System default" row both update immediately
    // instead of only after the page is reopened with a fresh service.
    fun setVoice(name: String?) {
        if (name == null) {
            val default = engine?.defaultVoice
            if (default != null) engine?.setVoice(default)
            currentVoiceName = null
            return
        }
        val voice = availableVoices.firstOrNull { it.name == name } ?: return
        engine?.setVoice(voice)
        currentVoiceName = name
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
        val spans = splitForTtsSpans(text)
        if (spans.isEmpty()) return
        val chunks = spans.map { text.substring(it.start, it.end) }
        // F22: ask for audio focus before anything else. Granted means other audio
        // (music, a podcast) has been paused or ducked by the system for us. Refused
        // (a call is active, say) means starting would just talk over it, so report
        // that and don't start - no foreground service, no session.
        if (!requestAudioFocus()) {
            listener?.onError(ERROR_AUDIO_FOCUS_DENIED)
            return
        }
        pausedForTransientFocusLoss = false
        this.sessionKey = sessionKey
        // Set before starting the service so that whenever onStartCommand actually
        // runs - always, on every startForegroundService call, whether this service
        // instance already exists or is only now being created - buildNotification()
        // already has the right title instead of momentarily showing the previous
        // session's (or the "ARKarium" fallback).
        currentChapterTitle = chapterTitle
        ContextCompat.startForegroundService(context, Intent(context, TtsPlaybackService::class.java))
        pendingChunks = chunks
        pendingSpans = spans
        pendingIndex = 0
        isPaused = false
        ignorePendingFinish = false
        isSpeaking = true
        registerNoisyReceiver()
        updateMediaSession()
        listener?.onSpeakingChanged(true)
        // Micro-haptics (util/Haptics.kt) - fires only once audio focus was actually
        // granted and the session is really starting, not on the early return above.
        Haptics.tick(this)
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
        pendingSpans = emptyList()
        pendingIndex = 0
        lastQueuedUtteranceId = null
        sessionKey = null
        releaseSessionResources()
        listener?.onSpeakingChanged(false)
        listener?.onPausedChanged(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Micro-haptics (util/Haptics.kt) - the explicit stop this function is (see
        // its own doc comment above); pause doesn't call stop() so this doesn't also
        // fire every time the user pauses.
        Haptics.tick(this)
    }

    // Pause is not a TextToSpeech feature (see uiux.md's playback-controls entry);
    // modeled as "stop, remember pendingIndex, resume from that chunk" - the chunk
    // the engine was partway through restarts from its own beginning rather than
    // mid-word, which is the chunk-level granularity that entry describes as the easy
    // first step (word-level position would need onRangeStart, API 26+).
    //
    // F22: this is the reader-initiated pause (the in-app button, a headset button via
    // the MediaSession, becoming-noisy). It abandons audio focus so whatever was playing
    // before can pick back up, and cancels any pending auto-resume - a deliberate pause
    // must not be undone by a later AUDIOFOCUS_GAIN. The focus-loss path calls
    // pauseInternal directly instead.
    fun pause() {
        pausedForTransientFocusLoss = false
        pauseInternal(abandonFocus = true)
    }

    private fun pauseInternal(abandonFocus: Boolean) {
        if (!isSpeaking) return
        ignorePendingFinish = true
        engine?.stop()
        isSpeaking = false
        isPaused = true
        unregisterNoisyReceiver()
        if (abandonFocus) abandonAudioFocus()
        updateMediaSession()
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
        // F22: pausing may have given focus up (or lost it for good), so take it again;
        // if another app still holds it (a call), stay paused rather than talk over it.
        if (!requestAudioFocus()) return
        pausedForTransientFocusLoss = false
        isPaused = false
        if (pendingIndex !in pendingChunks.indices) {
            listener?.onPausedChanged(false)
            return
        }
        isSpeaking = true
        registerNoisyReceiver()
        updateMediaSession()
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
    // queue) would otherwise advance (or abort) a read that's no longer current.
    // Otherwise advances to the next chunk, or - once the last chunk in the chapter
    // finishes - either auto-continues (if enabled) or stops the service the same way
    // stop() does. Only ever called from onDone now - see onChunkError below for the
    // rung-2 (D4) failure path, which used to share this method and be silently
    // treated as "chunk finished".
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
                releaseSessionResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // Rung 2 (D4): a chunk that failed to synthesize/play used to be routed into
    // onUtteranceFinished, which advanced as if it had succeeded - so a lost
    // connection on an online voice, or any other synthesis failure, made the chapter
    // "complete" silently, auto-continue included. This stops the session instead
    // (same cleanup stop() does, minus engine.stop() - the engine already isn't
    // speaking after an error) and reports errorCode so the UI can say why. The same
    // stale-callback guard onUtteranceFinished uses applies here for the same reason:
    // a late error for an already-interrupted chunk (pause(), skipNext/Previous())
    // must not abort a session that's already moved on.
    private fun onChunkError(utteranceId: String?, errorCode: Int) {
        if (ignorePendingFinish) {
            ignorePendingFinish = false
            return
        }
        if (utteranceId != null && utteranceId != lastQueuedUtteranceId) return
        isSpeaking = false
        isPaused = false
        pendingChunks = emptyList()
        pendingSpans = emptyList()
        pendingIndex = 0
        lastQueuedUtteranceId = null
        sessionKey = null
        releaseSessionResources()
        listener?.onSpeakingChanged(false)
        listener?.onError(errorCode)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun transportPendingIntent(action: TtsTransportAction): PendingIntent =
        PendingIntent.getService(
            this,
            action.ordinal,
            Intent(this, TtsPlaybackService::class.java).setAction(action.intentAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // Framework drawables, so there is no icon asset to ship or keep in sync.
    private fun transportIcon(action: TtsTransportAction): Int = when (action) {
        TtsTransportAction.PREVIOUS -> android.R.drawable.ic_media_previous
        TtsTransportAction.PAUSE -> android.R.drawable.ic_media_pause
        TtsTransportAction.PLAY -> android.R.drawable.ic_media_play
        TtsTransportAction.NEXT -> android.R.drawable.ic_media_next
        TtsTransportAction.STOP -> android.R.drawable.ic_menu_close_clear_cancel
    }

    private fun transportLabel(action: TtsTransportAction): String = when (action) {
        TtsTransportAction.PREVIOUS -> "Previous"
        TtsTransportAction.PAUSE -> "Pause"
        TtsTransportAction.PLAY -> "Play"
        TtsTransportAction.NEXT -> "Next"
        TtsTransportAction.STOP -> "Stop"
    }

    private fun buildNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Reading aloud")
            .setContentText(currentChapterTitle.ifBlank { "ARKarium" })
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
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
        ttsTransportActions(isSpeaking).forEach { action ->
            builder.addAction(transportIcon(action), transportLabel(action), transportPendingIntent(action))
        }
        val style = MediaStyle().setShowActionsInCompactView(*TTS_COMPACT_ACTION_INDICES)
        mediaSession?.let { style.setMediaSession(MediaSessionCompat.Token.fromToken(it.sessionToken)) }
        return builder.setStyle(style).build()
    }

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 42
    }
}

// Converts a live android.speech.tts.Voice into the Voice-free TtsVoiceInfo model
// (TtsVoiceModel.kt, rung 3) that TtsSettingsScreen's picker consumes - this file is
// the one place that already needs both android.speech.tts.Voice and TtsVoiceInfo in
// scope, so the conversion lives here rather than duplicating the Voice import there.
internal fun Voice.toTtsVoiceInfo(): TtsVoiceInfo = TtsVoiceInfo(
    name = name,
    language = locale?.language ?: "",
    region = locale?.displayCountry ?: "",
    quality = quality,
    online = isNetworkConnectionRequired,
    installed = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
)

// Human-readable text for a TextToSpeech.ERROR_* code (rung 2, D4), shown once in the
// reader when a chunk fails instead of silently advancing. The two network codes get a
// specific nudge toward a local voice, since that's the one concrete thing a reader
// hitting ERROR_NETWORK/ERROR_NETWORK_TIMEOUT can do about it; every other code gets a
// flat message instead of guessing at a cause this function has no evidence for.
internal fun ttsErrorMessage(errorCode: Int): String = when (errorCode) {
    ERROR_AUDIO_FOCUS_DENIED ->
        "Read-aloud didn't start: another app (a call, probably) is using the audio."
    TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
        "Read-aloud stopped: no connection for this voice. Try a local voice in TTS settings."
    TextToSpeech.ERROR_NOT_INSTALLED_YET ->
        "Read-aloud stopped: this voice isn't installed yet."
    TextToSpeech.ERROR_OUTPUT ->
        "Read-aloud stopped: the audio output failed."
    TextToSpeech.ERROR_SERVICE ->
        "Read-aloud stopped: the speech service failed."
    TextToSpeech.ERROR_SYNTHESIS ->
        "Read-aloud stopped: couldn't synthesize this voice."
    TextToSpeech.ERROR_INVALID_REQUEST ->
        "Read-aloud stopped: invalid request to the speech engine."
    else ->
        "Read-aloud stopped: a speech engine error occurred."
}
