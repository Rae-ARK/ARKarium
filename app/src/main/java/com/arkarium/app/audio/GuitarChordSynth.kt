package com.arkarium.app.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

// Synthesizes a strummed C major chord with a plucked-guitar-string timbre,
// entirely in code - no bundled audio asset. Used by SplashChordPlayer to
// score SplashScreen's line-reconstruction animation.
//
// The plucked-string sound comes from the Karplus-Strong algorithm: seed a
// short ring buffer with white noise (the "pluck"), then repeatedly average
// each sample with its neighbor and feed it back with a slight decay. That
// simple feedback loop is a surprisingly good model of a vibrating string -
// it's the same technique behind a lot of classic synthesized plucked-string
// sounds.
object GuitarChordSynth {
    const val SAMPLE_RATE = 44100

    // Open-position C major chord on guitar, low string to high (5th string
    // through 1st; the 6th/low-E string is muted in this voicing, same as a
    // standard open C fingering): C3-E3-G3-C4-E4.
    private val CHORD_FREQUENCIES_HZ = listOf(130.81, 164.81, 196.00, 261.63, 329.63)

    // How far apart each successive string is plucked, low to high - a real
    // downward strum isn't a single simultaneous attack, it's a fast cascade.
    private const val STRUM_OFFSET_MS = 14.0

    // How long each individual string rings out before its Karplus-Strong
    // buffer is truncated. The natural decay usually falls well below
    // audible before this, so the exact value mostly just bounds the array
    // size.
    private const val NOTE_DURATION_SEC = 2.3

    // Per-sample decay factor in the Karplus-Strong feedback loop. Closer to
    // 1.0 = longer sustain, further below = a shorter, more damped pluck.
    private const val STRING_DECAY = 0.9965

    // Generates one plucked string's waveform via Karplus-Strong.
    private fun karplusStrongPluck(frequencyHz: Double, durationSec: Double, seed: Long): FloatArray {
        // The ring buffer's length sets the fundamental pitch: sampleRate/frequency
        // samples per cycle.
        val bufferLength = max(2, (SAMPLE_RATE / frequencyHz).toInt())
        val ring = FloatArray(bufferLength)
        val rnd = Random(seed)
        for (i in ring.indices) {
            ring[i] = rnd.nextFloat() * 2f - 1f // white noise burst = the pluck
        }

        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val output = FloatArray(totalSamples)
        var index = 0
        for (i in 0 until totalSamples) {
            val current = ring[index]
            output[i] = current
            val next = ring[(index + 1) % bufferLength]
            // Low-pass-and-decay feedback: averaging two neighboring samples
            // damps high frequencies faster than low ones, the same way a
            // real string's harmonics decay unevenly.
            ring[index] = (STRING_DECAY * 0.5 * (current + next)).toFloat()
            index = (index + 1) % bufferLength
        }
        return output
    }

    // Builds the full strummed chord as 16-bit PCM mono samples, ready to
    // hand straight to an AudioTrack.
    fun buildCMajorStrumPcm16(): ShortArray {
        val strumOffsetSamples = ((STRUM_OFFSET_MS / 1000.0) * SAMPLE_RATE).toInt()
        val noteSamples = CHORD_FREQUENCIES_HZ.mapIndexed { stringIndex, freq ->
            // Fixed per-string seeds so the chord's exact texture is
            // deterministic and repeatable across launches, not re-rolled
            // every time.
            karplusStrongPluck(freq, NOTE_DURATION_SEC, seed = 1000L + stringIndex)
        }

        val totalLength = noteSamples.indices.maxOf { i ->
            i * strumOffsetSamples + noteSamples[i].size
        }
        val mixed = FloatArray(totalLength)
        noteSamples.forEachIndexed { stringIndex, samples ->
            val startAt = stringIndex * strumOffsetSamples
            for (i in samples.indices) {
                mixed[startAt + i] += samples[i]
            }
        }

        // Short linear fade-out on the tail so truncating the buffer never
        // produces an audible click.
        val fadeSamples = (0.05 * SAMPLE_RATE).toInt().coerceAtMost(mixed.size)
        for (i in 0 until fadeSamples) {
            val gain = i.toFloat() / fadeSamples
            val idx = mixed.size - fadeSamples + i
            mixed[idx] *= gain
        }

        // Normalize so five summed strings can never clip, leaving a little
        // headroom (0.85) rather than driving all the way to full scale.
        var peak = 0f
        for (v in mixed) peak = max(peak, abs(v))
        val scale = if (peak > 0f) (0.85f * Short.MAX_VALUE) / peak else 0f

        return ShortArray(mixed.size) { i -> (mixed[i] * scale).toInt().toShort() }
    }
}
