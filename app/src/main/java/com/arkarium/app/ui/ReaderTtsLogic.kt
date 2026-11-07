package com.arkarium.app.ui

// Plain-Kotlin decisions behind ReaderScreen's bottom pill and its read-aloud
// re-entry handling, kept free of Compose and Android types so they can be unit-tested
// on the JVM (ReaderTtsLogicTest). ReaderScreen only feeds these its state and acts on
// the answer.

// Which pill occupies the reader's bottom edge. Exactly one at a time:
//  - PREFERENCES: the "Reader Preferences" pill. Non-immersive mode only.
//  - TTS: the read-aloud controls pill. Immersive mode only, and only while a
//    read-aloud session exists (playing or paused).
//  - NONE: immersive with nothing playing, or no usable speech engine.
enum class ReaderBottomPill { PREFERENCES, TTS, NONE }

fun readerBottomPill(
    immersive: Boolean,
    ttsSessionActive: Boolean,
    ttsAvailable: Boolean
): ReaderBottomPill = when {
    !immersive -> ReaderBottomPill.PREFERENCES
    ttsSessionActive && ttsAvailable -> ReaderBottomPill.TTS
    else -> ReaderBottomPill.NONE
}

// What ReaderScreen does about a read-aloud session it finds already running when it
// (re)attaches to TtsPlaybackService - after returning from the TTS settings page or
// any other screen, after a rotation, or after reopening the reader while a session
// kept playing in the background.
enum class TtsSessionAction {
    // No session, nothing to do.
    NONE,

    // The session is reading this very chapter: go (back) to immersive mode so its
    // controls pill is showing, and leave playback alone.
    ENTER_IMMERSIVE,

    // The session is reading some other chapter (Previous/Next, or a different
    // chapter opened while one played on). The reader is on a fresh chapter, so the
    // stale session is stopped rather than left controlling audio for text that is
    // no longer on screen.
    STOP_STALE
}

fun reconcileTtsSession(
    sessionActive: Boolean,
    sessionKey: String?,
    chapterId: String
): TtsSessionAction = when {
    !sessionActive -> TtsSessionAction.NONE
    sessionKey == chapterId -> TtsSessionAction.ENTER_IMMERSIVE
    else -> TtsSessionAction.STOP_STALE
}
