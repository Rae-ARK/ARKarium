package com.arkarium.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.delay

// Plays the synthesized C major strum (see GuitarChordSynth) once, backing
// SplashScreen's line-reconstruction animation. The whole clip is a few
// hundred KB of PCM, generated fresh in memory each launch - MODE_STATIC
// just means "write it all up front," not that it's read from disk.
class SplashChordPlayer {
    private var audioTrack: AudioTrack? = null

    // Synthesizes the chord and plays it. Kept for callers that don't care
    // where the ~507,000-sample Karplus-Strong synthesis runs - but see
    // playPcm below, which SplashScreen now uses so that synthesis (the
    // actually expensive part) can happen on Dispatchers.Default instead of
    // blocking the composition/apply pass on the main thread.
    fun play() = playPcm(GuitarChordSynth.buildCMajorStrumPcm16())

    // Plays an already-synthesized PCM16 buffer. Splitting this out from
    // play() (F13, docs/proposal/performance.md) lets the caller synthesize
    // off the main thread and only touch AudioTrack - construct, write,
    // play, all cheap - here.
    fun playPcm(pcm: ShortArray) {
        // Guard against a second play() on an already-playing instance
        // (shouldn't happen given how SplashScreen uses this, but cheap to
        // make safe).
        release()

        val bytesNeeded = pcm.size * 2 // 16-bit samples = 2 bytes each
        val minBufferSize = AudioTrack.getMinBufferSize(
            GuitarChordSynth.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSizeBytes = maxOf(minBufferSize, bytesNeeded)

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(GuitarChordSynth.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSizeBytes,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.write(pcm, 0, pcm.size)
        track.play()
        audioTrack = track
    }

    // A plain release() stops the chord hard when the splash ends - an audible
    // click/cutoff mid-note. Used for SplashMusicMode.SHORT instead: ramps the track's
    // volume down to 0 over `durationMs` in a handful of steps before actually
    // stopping it, so the note dies away instead of cutting off. Safe to call
    // from any coroutine (SplashScreen calls it from a DisposableEffect's
    // onDispose via AppScope.io, since onDispose itself can't suspend).
    // setVolume() can throw IllegalStateException on an already-stopped/
    // uninitialized track (e.g. a second caller racing this one) - caught the
    // same way release() below guards its own stop() call, and just falls
    // through to release() either way.
    suspend fun fadeOutAndRelease(durationMs: Long) {
        val track = audioTrack
        if (track == null || durationMs <= 0L) {
            release()
            return
        }
        val steps = 12
        val stepMs = durationMs / steps
        for (i in steps downTo 0) {
            try {
                track.setVolume(i.toFloat() / steps)
            } catch (_: IllegalStateException) {
                break
            }
            if (stepMs > 0L) delay(stepMs)
        }
        release()
    }

    // Stops and releases the underlying AudioTrack. Safe to call more than
    // once, and safe to call even if play() was never called - SplashScreen
    // calls this from a DisposableEffect's onDispose, which must never throw.
    fun release() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        } catch (_: IllegalStateException) {
            // Already stopped/uninitialized - nothing left to clean up.
        }
        track.release()
    }
}
