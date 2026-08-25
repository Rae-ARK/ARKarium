package com.arkarium.app.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

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

    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            state.isAvailable = status == TextToSpeech.SUCCESS
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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

        onDispose {
            engine.stop()
            engine.shutdown()
            state.engine = null
        }
    }

    return state
}
