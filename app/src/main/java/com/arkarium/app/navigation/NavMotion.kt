package com.arkarium.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

// Screen-to-screen transitions for MainActivity's NavHost.
//
// Before this, NavHost ran with no transitions configured, which in navigation-compose
// 2.7.x means its built-in default: every screen change is a 700ms crossfade of the whole
// screen. That is long, and a plain crossfade of two full screens shows both at half
// opacity at once, which reads as a muddy smear rather than movement.
//
// This follows Material's motion patterns (m3.material.io, "Transitions"):
//  - Shared axis X for moving deeper into the app and back out (Home -> fiction ->
//    chapter, any settings page): the screen slides a short distance (about 30dp) along
//    the horizontal axis while fading. Going forward the new screen arrives from the
//    right and the old one leaves to the left; going back is the mirror image. The
//    short travel and the direction are what tell the reader which way they moved.
//  - Fade through for the chapter -> chapter hop (Previous/Next): same destination, no
//    depth change, so no slide - the old chapter fades out, the new one fades in with a
//    slight scale-up.
// Timing for both: 300ms total. The outgoing screen fades out over the first 90ms and the
// incoming one fades in over the remaining 210ms, so the two are never both clearly
// visible at once. Standard easing in/out.
object NavMotion {
    // Route pattern of the reader destination, as registered in MainActivity's NavHost.
    // NavDestination.route is the pattern, not the filled-in route, so every chapter
    // reports this same string.
    const val READER_ROUTE = "reader/{novelId}/{chapterId}"

    private const val DURATION_MS = 300
    private const val FADE_OUT_MS = 90
    private const val FADE_IN_MS = DURATION_MS - FADE_OUT_MS

    // Slide distance as a fraction of the screen width. A fraction (the lambda only
    // receives the width in px, with no density in scope) rather than a fixed 30dp; 1/12
    // of a 360dp-wide phone is 30dp, and it grows gently on wider screens.
    private const val SLIDE_DIVISOR = 12

    private const val CHAPTER_SWAP_MS = 220
    private const val CHAPTER_SWAP_SCALE = 0.96f

    // Previous/Next navigates reader -> reader (replacing the entry), so the generic
    // forward/back rule would slide between two chapters of the same book.
    private fun isChapterSwap(initial: NavBackStackEntry, target: NavBackStackEntry): Boolean =
        initial.destination.route == READER_ROUTE && target.destination.route == READER_ROUTE

    // --- forward: new screen arrives from the right ----------------------------

    fun enter(initial: NavBackStackEntry, target: NavBackStackEntry): EnterTransition =
        if (isChapterSwap(initial, target)) chapterSwapIn() else slideFadeIn(fromEnd = true)

    fun exit(initial: NavBackStackEntry, target: NavBackStackEntry): ExitTransition =
        if (isChapterSwap(initial, target)) chapterSwapOut() else slideFadeOut(towardsStart = true)

    // --- back: previous screen returns from the left ---------------------------

    fun popEnter(initial: NavBackStackEntry, target: NavBackStackEntry): EnterTransition =
        if (isChapterSwap(initial, target)) chapterSwapIn() else slideFadeIn(fromEnd = false)

    fun popExit(initial: NavBackStackEntry, target: NavBackStackEntry): ExitTransition =
        if (isChapterSwap(initial, target)) chapterSwapOut() else slideFadeOut(towardsStart = false)

    // --- building blocks --------------------------------------------------------

    private fun slideFadeIn(fromEnd: Boolean): EnterTransition =
        slideInHorizontally(
            animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
        ) { fullWidth ->
            if (fromEnd) fullWidth / SLIDE_DIVISOR else -fullWidth / SLIDE_DIVISOR
        } + fadeIn(
            animationSpec = tween(
                durationMillis = FADE_IN_MS,
                delayMillis = FADE_OUT_MS,
                easing = LinearOutSlowInEasing
            )
        )

    private fun slideFadeOut(towardsStart: Boolean): ExitTransition =
        slideOutHorizontally(
            animationSpec = tween(DURATION_MS, easing = FastOutSlowInEasing)
        ) { fullWidth ->
            if (towardsStart) -fullWidth / SLIDE_DIVISOR else fullWidth / SLIDE_DIVISOR
        } + fadeOut(
            animationSpec = tween(durationMillis = FADE_OUT_MS, easing = FastOutLinearInEasing)
        )

    private fun chapterSwapIn(): EnterTransition =
        fadeIn(
            animationSpec = tween(
                durationMillis = CHAPTER_SWAP_MS - FADE_OUT_MS,
                delayMillis = FADE_OUT_MS,
                easing = LinearOutSlowInEasing
            )
        ) + scaleIn(
            animationSpec = tween(
                durationMillis = CHAPTER_SWAP_MS - FADE_OUT_MS,
                delayMillis = FADE_OUT_MS,
                easing = LinearOutSlowInEasing
            ),
            initialScale = CHAPTER_SWAP_SCALE
        )

    private fun chapterSwapOut(): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = FADE_OUT_MS, easing = FastOutLinearInEasing))
}
