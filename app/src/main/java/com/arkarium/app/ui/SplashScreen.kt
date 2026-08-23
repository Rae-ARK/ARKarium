package com.arkarium.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.R
import com.arkarium.app.audio.SplashChordPlayer
import kotlin.random.Random
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Timeline. Four phases, driven by a single 0f..1f Animatable (see `playtime`
// below) so nothing here can drift out of sync with itself:
//   1. RECONSTRUCT: lines sweep in from scattered points around the screen
//      and converge into the "A+R" mark, point by point (see LogoPoints).
//   2. CROSSFADE: the line drawing dissolves into the real ic_splash_logo.png
//      + wordmark, exactly like the previous static splash - so any
//      imperfection in the reconstructed silhouette is covered by the crisp
//      raster image snapping into place, the same trick streaming-service
//      intros use.
//   3. HOLD: the real logo sits still, fully opaque, long enough to register
//      as an intentional brand moment.
//   4. FADE_OUT: everything fades to black, then onFinished() hands off to
//      the real app content.
// ---------------------------------------------------------------------------
private const val RECONSTRUCT_MS = 1400
private const val CROSSFADE_MS = 380
private const val HOLD_MS = 650
private const val FADE_OUT_MS = 320

// Used in place of RECONSTRUCT_MS + CROSSFADE_MS when the line-reconstruction
// animation is turned off (see SettingsScreen's "Splash Screen" section) - the
// real logo just does a plain, quick fade-in instead of a multi-second
// sequence, so disabling the animation actually gets you a faster splash
// rather than the same total duration with the visual removed.
private const val SIMPLE_FADE_IN_MS = 280

// How much of the reconstruction phase a single point's own travel takes -
// 0.4 means each line takes 40% of the phase to arrive, so with staggered
// start times the sweep still reads as one continuous motion rather than
// everything snapping in during the final instant.
private const val POINT_TRAVEL_FRACTION = 0.4f

private val SplashBackground = Color(0xFF000000)
private val SplashForeground = Color(0xFFFFFFFF)

// Where a converging line starts from, expressed as a fraction of the full
// screen so it scales to any device size: which edge, and how far along it.
// Kept as plain data (not Offset) since the actual pixel position depends on
// canvas size, which isn't known until draw time.
private data class LineOrigin(val edge: Int, val fraction: Float)

// Per-point animation parameters, computed once (see `remember` below) rather
// than re-randomized every frame or every recomposition.
private data class PointAnim(
    val target: Offset,        // normalized 0f..1f within the mark's own bounding box
    val origin: LineOrigin,
    val startFraction: Float   // where in the RECONSTRUCT phase (0f..1f) this point starts moving
)

private fun buildPointAnimations(): List<PointAnim> {
    val points = LogoPoints.POINTS
    val rnd = Random(42) // fixed seed - deterministic across recompositions/launches
    val lastStart = (1f - POINT_TRAVEL_FRACTION).coerceAtLeast(0f)
    return points.mapIndexed { index, (x, y) ->
        val edge = rnd.nextInt(4)
        val fraction = rnd.nextFloat()
        // Points are already ordered as one continuous path (see LogoPoints'
        // doc comment) - starting them in that same order, spread evenly
        // across the phase, is what makes the converging lines read as a
        // single sweep tracing the mark instead of a random scatter.
        val startFraction = if (points.size <= 1) 0f else lastStart * (index.toFloat() / (points.size - 1))
        PointAnim(Offset(x, y), LineOrigin(edge, fraction), startFraction)
    }
}

// Resolves a LineOrigin to an actual off-mark screen position for the given
// canvas size. Slightly outside the 0..canvas bounds on the relevant edge
// so lines visibly enter from off-screen rather than starting flush at the
// very edge pixel.
private fun originPx(origin: LineOrigin, width: Float, height: Float): Offset =
    when (origin.edge) {
        0 -> Offset(origin.fraction * width, -0.06f * height)          // top
        1 -> Offset(origin.fraction * width, height + 0.06f * height)  // bottom
        2 -> Offset(-0.06f * width, origin.fraction * height)          // left
        else -> Offset(width + 0.06f * width, origin.fraction * height) // right
    }

@Composable
fun SplashScreen(
    // See SettingsScreen's "Splash Screen" section / PreferencesManager -
    // both default to true. animationEnabled=false skips straight to a plain
    // fade-in of the real logo (see SIMPLE_FADE_IN_MS); musicEnabled=false
    // just never starts the AudioTrack. The two are independent of each
    // other.
    animationEnabled: Boolean = true,
    musicEnabled: Boolean = true,
    onFinished: () -> Unit
) {
    val pointAnims = remember(animationEnabled) {
        if (animationEnabled) buildPointAnimations() else emptyList()
    }

    // Single linear timeline driver - every phase below derives its own
    // progress from this rather than running independent animations, so nothing
    // can end up out of step with anything else.
    val playtime = remember { Animatable(0f) }

    // Scores the reconstruction with a synthesized C major guitar strum (see
    // GuitarChordSynth) - generated in code, no bundled audio asset. Starts
    // the moment this composable enters composition (i.e. right alongside
    // the animation below) and is always released via onDispose, so the
    // AudioTrack can't leak or keep playing past the splash itself. Skipped
    // entirely (never even constructed) when musicEnabled is false.
    DisposableEffect(musicEnabled) {
        if (!musicEnabled) {
            return@DisposableEffect onDispose {}
        }
        val player = SplashChordPlayer()
        try {
            player.play()
        } catch (_: Exception) {
            // An unusual/missing audio output shouldn't be able to crash the
            // splash - the visual reconstruction still runs fine without sound.
        }
        onDispose { player.release() }
    }

    // Reconstruction + crossfade collapse into a single quick fade-in when
    // the animation is disabled - see SIMPLE_FADE_IN_MS.
    val reconstructDurationMs = if (animationEnabled) RECONSTRUCT_MS else 0
    val crossfadeDurationMs = if (animationEnabled) CROSSFADE_MS else SIMPLE_FADE_IN_MS
    val totalMs = reconstructDurationMs + crossfadeDurationMs + HOLD_MS + FADE_OUT_MS

    LaunchedEffect(totalMs) {
        playtime.snapTo(0f)
        playtime.animateTo(1f, animationSpec = tween(totalMs, easing = LinearEasing))
        delay(16) // let the final frame actually render before tearing the composable down
        onFinished()
    }

    val elapsedMs = playtime.value * totalMs

    // Phase 1: 0f..1f across reconstructDurationMs (0 when animation is off,
    // in which case this stays pinned at 1f and the Canvas below never draws).
    val reconstructProgress =
        if (reconstructDurationMs <= 0) 1f else (elapsedMs / reconstructDurationMs).coerceIn(0f, 1f)
    // Phase 2: 0f..1f across crossfadeDurationMs, starting right as phase 1 ends.
    val crossfadeProgress =
        ((elapsedMs - reconstructDurationMs) / crossfadeDurationMs).coerceIn(0f, 1f)
    // Phase 4: 0f..1f across FADE_OUT_MS, starting after phase1+phase2+HOLD.
    val fadeOutProgress =
        ((elapsedMs - reconstructDurationMs - crossfadeDurationMs - HOLD_MS) / FADE_OUT_MS).coerceIn(0f, 1f)

    // The real logo+text crossfades in starting in phase 2 and stays fully
    // visible through the hold, then fades with everything else in phase 4.
    val realContentAlpha = crossfadeProgress
    // The line drawing fades out over the same crossfade window.
    val lineCanvasAlpha = 1f - crossfadeProgress
    // Master alpha for the whole screen - only phase 4 touches this.
    val masterAlpha = 1f - fadeOutProgress

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground)
            .alpha(masterAlpha),
        contentAlignment = Alignment.Center
    ) {
        if (animationEnabled && lineCanvasAlpha > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLineReconstruction(pointAnims, reconstructProgress, lineCanvasAlpha)
            }
        }

        if (realContentAlpha > 0f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(32.dp)
                    .alpha(realContentAlpha)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_splash_logo),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "ARKarium",
                    color = SplashForeground,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "A better way to consume Rae ARK's Novels straight from him.",
                    color = SplashForeground.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// Draws every converging line + its arrival point for the current
// reconstruction progress. `canvasAlpha` is the phase-2 crossfade multiplier
// (see caller) applied on top of each point's own arrival-based alpha.
private fun DrawScope.drawLineReconstruction(
    points: List<PointAnim>,
    reconstructProgress: Float,
    canvasAlpha: Float
) {
    if (canvasAlpha <= 0f) return

    val w = size.width
    val h = size.height

    // The mark is reconstructed into a centered box sized to LogoPoints'
    // own aspect ratio, occupying a comfortable fraction of the shorter
    // screen dimension so it lands in roughly the same place the real
    // ic_splash_logo.png will crossfade in at (see SplashScreen's Image,
    // sized 112.dp within similar centered padding).
    val boxSize = minOf(w, h) * 0.42f
    val logoW: Float
    val logoH: Float
    if (LogoPoints.ASPECT_RATIO >= 1f) {
        logoW = boxSize
        logoH = boxSize / LogoPoints.ASPECT_RATIO
    } else {
        logoH = boxSize
        logoW = boxSize * LogoPoints.ASPECT_RATIO
    }
    val left = (w - logoW) / 2f
    val top = (h - logoH) / 2f

    for (p in points) {
        val local = ((reconstructProgress - p.startFraction) / POINT_TRAVEL_FRACTION).coerceIn(0f, 1f)
        if (local <= 0f) continue // hasn't started moving yet - nothing to draw

        val eased = EaseOutCubic.transform(local)
        val origin = originPx(p.origin, w, h)
        val target = Offset(left + p.target.x * logoW, top + p.target.y * logoH)
        val current = Offset(
            origin.x + (target.x - origin.x) * eased,
            origin.y + (target.y - origin.y) * eased
        )

        // The traveling line itself: brightest while in flight, fading as it
        // settles so the final silhouette reads as a field of soft points
        // rather than a tangle of leftover streaks.
        val lineAlpha = (1f - eased) * 0.55f * canvasAlpha
        if (lineAlpha > 0.01f) {
            drawLine(
                color = SplashForeground.copy(alpha = lineAlpha),
                start = origin,
                end = current,
                strokeWidth = 1.6f,
                cap = StrokeCap.Round
            )
        }

        // The arrival point: fades in as the line completes its travel, so
        // the mark's silhouette gradually accumulates out of settled dots.
        val dotAlpha = eased * canvasAlpha
        if (dotAlpha > 0.01f) {
            drawCircle(
                color = SplashForeground.copy(alpha = dotAlpha),
                radius = 2.4f,
                center = current
            )
        }
    }
}
