package com.arkarium.app.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.arkarium.app.data.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Android's TextToSpeech.speak() silently truncates (or on some engines rejects
// outright) input longer than TextToSpeech.getMaxSpeechInputLength() - around 4000
// characters on the system engine, but chapter bodies routinely run well past that.
// Long text is split into utterance-sized pieces and queued back-to-back (see speak()
// below) rather than handed to speak() whole, which would just cut off mid-chapter
// with no warning to the reader.
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

// Thin Compose-facing wrapper around a single android.speech.tts.TextToSpeech engine,
// scoped to one ReaderScreen session via rememberChapterTts() below. Purely on-device -
// no bundled voice data and no network calls, matching the rest of the app's offline
// design - and uses whichever TTS engine/voice the user already has configured
// system-wide (Settings > Accessibility > Text-to-speech on most devices), so ARKarium
// doesn't need to ship or manage voices itself.
class ChapterTtsState internal constructor() {
    var isSpeaking by mutableStateOf(false)
        internal set

    // False until engine init finishes, and stays false permanently if init fails (no
    // TTS engine installed - rare, but possible on stripped-down ROMs/emulators). The
    // play control checks this and hides itself rather than doing nothing when tapped.
    var isAvailable by mutableStateOf(false)
        internal set

    var speechRate by mutableFloatStateOf(1.0f)
        internal set

    // Stage 3.4 of docs/arkarium/SETTINGS_REDESIGN.md. Unlike speechRate/pitch (seeded
    // once at engine init, see rememberChapterTts below), this is kept live for the
    // whole session - see the collectAsState() wiring below - so toggling auto-continue
    // in settings/tts takes effect on the very next chapter boundary rather than
    // requiring the reader to leave and reopen the reader. Off by default, matching
    // TTS_AUTO_CONTINUE_KEY's own default in PreferencesManager.
    var autoContinueEnabled by mutableStateOf(false)
        internal set

    // Invoked from onUtteranceFinished below in place of merely dropping isSpeaking back
    // to false, once the last chunk in a chapter finishes and autoContinueEnabled is
    // true. Set by ReaderScreen to its existing onNext callback - ChapterTtsState has no
    // access to chapter navigation itself (that lives with libraryViewModel.chapters in
    // MainActivity), so this is the seam between "TTS finished" and "advance to the next
    // chapter," same division of responsibility Tts.kt already keeps from ReaderScreen
    // elsewhere in this file. Null (ReaderScreen leaves it unset, or onNext itself is
    // null because there's no next chapter) means "do nothing beyond stopping," same
    // "null means disabled" contract onNext already uses.
    var onChapterFinished: (() -> Unit)? = null

    internal var engine: TextToSpeech? = null
    private var pendingChunks: List<String> = emptyList()
    private var pendingIndex: Int = 0
    private var lastQueuedUtteranceId: String? = null

    // Only sets the rate on the engine; does NOT touch anything already queued. Android's
    // TextToSpeech.setSpeechRate() only affects speak() calls made *after* it returns - it
    // has no effect on utterances that were already submitted to the engine's synthesis
    // queue, even if they haven't started playing yet. Chunks are queued one at a time (see
    // speakNextChunk() below) precisely so that a rate change here reaches the *next* chunk
    // rather than being silently dropped because every chunk for the chapter was already
    // queued up front.
    fun setRate(rate: Float) {
        speechRate = rate
        engine?.setSpeechRate(rate)
    }

    // Stops whatever's currently queued and starts reading `text` from the top.
    fun speak(text: String) {
        val tts = engine ?: return
        val chunks = splitForTts(text)
        if (chunks.isEmpty()) return
        pendingChunks = chunks
        pendingIndex = 0
        isSpeaking = true
        speakNextChunk(TextToSpeech.QUEUE_FLUSH)
    }

    // Queues exactly one chunk - the one at pendingIndex - rather than the whole chapter at
    // once. The engine's current speechRate is applied immediately beforehand so that a
    // setRate() call made while a previous chunk is still playing takes effect on this
    // chunk, instead of this chunk having already been queued (and its rate locked in)
    // before the user touched the slider. The next chunk is only queued once this one
    // finishes, from onUtteranceFinished() below, keeping the same "one queued rate change
    // ahead" behavior for every chunk boundary in a long chapter.
    private fun speakNextChunk(queueMode: Int) {
        val tts = engine ?: return
        if (pendingIndex !in pendingChunks.indices) return
        val chunk = pendingChunks[pendingIndex]
        val utteranceId = "arkarium_chunk_${pendingIndex}_${chunk.hashCode()}"
        lastQueuedUtteranceId = utteranceId
        tts.setSpeechRate(speechRate)
        tts.speak(chunk, queueMode, null, utteranceId)
    }

    fun stop() {
        engine?.stop()
        isSpeaking = false
        pendingChunks = emptyList()
        pendingIndex = 0
        lastQueuedUtteranceId = null
    }

    // Called from the engine's UtteranceProgressListener (see rememberChapterTts) on
    // both successful completion and error, for any queued chunk. Ignores callbacks that
    // don't match the chunk we most recently queued - a late onDone/onError arriving after
    // stop() (or after a fresh speak() call replaced the queue) would otherwise advance a
    // read that's no longer current. Otherwise, advances to the next chunk if there is one,
    // or drops isSpeaking back to false once the last chunk in the chapter finishes.
    internal fun onUtteranceFinished(utteranceId: String?) {
        if (utteranceId != null && utteranceId != lastQueuedUtteranceId) return
        pendingIndex++
        if (pendingIndex in pendingChunks.indices) {
            speakNextChunk(TextToSpeech.QUEUE_ADD)
        } else {
            isSpeaking = false
            // Stage 3.4: off by default, so this is a no-op until a reader opts in on
            // settings/tts - see autoContinueEnabled's doc comment above.
            if (autoContinueEnabled) {
                onChapterFinished?.invoke()
            }
        }
    }
}

// Creates and owns a TextToSpeech engine for as long as the calling composable stays in
// composition. ReaderScreen's call site in MainActivity is reused across
// Previous/Next chapter navigation rather than torn down and recreated (see the
// chapter.id-keyed LaunchedEffect elsewhere in this file for the same reuse behavior
// with scroll state), so in practice one engine instance lives for the whole reading
// session instead of being reinitialized every chapter. It's shut down via
// TextToSpeech.shutdown() once ReaderScreen itself leaves composition (navigating back
// to Home/the fiction page).
@Composable
fun rememberChapterTts(): ChapterTtsState {
    val context = LocalContext.current
    val state = remember { ChapterTtsState() }
    // Stage 3.3 of docs/arkarium/SETTINGS_REDESIGN.md: seeds this session's starting
    // rate/pitch from the settings/tts defaults (Stage 3.1's keys) instead of the
    // hardcoded 1.0f/implicit-default this composable used before. Direct
    // PreferencesManager access, same as everywhere else those four keys are read -
    // see SETTINGS_REDESIGN.md's "Open questions" for why TTS defaults don't get a
    // ViewModel of their own.
    val prefsManager = remember { PreferencesManager(context) }
    val coroutineScope = rememberCoroutineScope()

    // Stage 3.4 of docs/arkarium/SETTINGS_REDESIGN.md. Deliberately not a one-shot
    // .first() read like defaultRate/defaultPitch below - auto-continue is checked
    // every time a chapter finishes, not just once at engine construction, so it's kept
    // live via collectAsState() instead and pushed onto ChapterTtsState whenever it
    // changes.
    val autoContinue by prefsManager.ttsAutoContinue.collectAsState(initial = false)
    LaunchedEffect(autoContinue) {
        state.autoContinueEnabled = autoContinue
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null

        // Reading the defaults is a suspend call (DataStore's Flow.first()), so engine
        // construction moves inside this coroutine rather than happening synchronously
        // in the DisposableEffect body as before. TextToSpeech(context) {...}'s own
        // init callback was already asynchronous, so this just adds one more (already
        // in-memory/cached, effectively instant) suspension ahead of it - a chapter
        // opened in that brief window sees isAvailable == false, same as it already
        // could while waiting on the engine's own init callback.
        val job = coroutineScope.launch {
            val defaultRate = prefsManager.ttsDefaultRate.first()
            val defaultPitch = prefsManager.ttsPitch.first()

            // Seeds the session's starting rate. The pill's live setRate() during
            // reading still only touches this in-memory value, same as before this
            // stage - see SETTINGS_REDESIGN.md's last "Open question".
            state.speechRate = defaultRate

            engine = TextToSpeech(context) { status ->
                state.isAvailable = status == TextToSpeech.SUCCESS
                // Pitch has no pill/mid-session control (unlike rate), so it's only
                // ever applied here, once, at engine init - see SETTINGS_REDESIGN.md
                // §2 for why pitch doesn't get the same live-adjustment treatment.
                if (status == TextToSpeech.SUCCESS) {
                    engine?.setPitch(defaultPitch)
                }
            }
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    state.onUtteranceFinished(utteranceId)
                }

                @Deprecated("Deprecated in Java, but still the callback older API levels invoke")
                override fun onError(utteranceId: String?) {
                    state.onUtteranceFinished(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    state.onUtteranceFinished(utteranceId)
                }
            })
            state.engine = engine
        }

        onDispose {
            job.cancel()
            engine?.stop()
            engine?.shutdown()
            state.engine = null
        }
    }

    return state
}
