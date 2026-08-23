package com.arkarium.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

// Plays the synthesized C major strum (see GuitarChordSynth) once, backing
// SplashScreen's line-reconstruction animation. The whole clip is a few
// hundred KB of PCM, generated fresh in memory each launch - MODE_STATIC
// just means "write it all up front," not that it's read from disk.
class SplashChordPlayer {
    private var audioTrack: AudioTrack? = null

    fun play() {
        // Guard against a second play() on an already-playing instance
        // (shouldn't happen given how SplashScreen uses this, but cheap to
        // make safe).
        release()

        val pcm = GuitarChordSynth.buildCMajorStrumPcm16()
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
