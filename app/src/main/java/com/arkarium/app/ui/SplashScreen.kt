package com.arkarium.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.R
import com.arkarium.app.audio.GuitarChordSynth
import com.arkarium.app.audio.SplashChordPlayer
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Timeline. Four phases, driven by a single 0f..1f Animatable (see `playtime`
// below) so nothing here can drift out of sync with itself:
//   1. WHOOSH: the EA-2005-ident beat - two thick lines rush in from opposite
//      sides of the screen at offset heights (one upper-left, one
//      lower-right - see drawWhoosh), race toward the centre, and *cross
//      paths* there: the upper one curves down to become the lower line, the
//      lower one curves up to become the upper line (the "U-turn"). The
//      instant they've swapped they're already sweeping onward, but now each
//      one is physically on the OTHER side of the screen from where it
//      started - so it locks onto and traces the letter that lives on that
//      side: the line that started on the left ends up on the right after
//      the swap and traces the "R" (R_STROKE); the one that started on the
//      right ends up on the left and traces the "A" (A_STROKE). The logo
//      isn't revealed after the cross - the cross *is* the logo happening,
//      same trick the real ident uses ("two white lines... go down and
//      right, forming the EA logo").
//   2. CROSSFADE: the two traced strokes dissolve into the real
//      ic_splash_logo.png + wordmark, exactly like the previous static splash
//      - so any imperfection in the hand-traced strokes is covered by the
//      crisp raster image snapping into place, the same trick streaming-
//      service intros use.
//   3. HOLD: the real logo sits still, fully opaque, long enough to register
//      as an intentional brand moment.
//   4. FADE_OUT: everything fades to black, then onFinished() hands off to
//      the real app content.
// ---------------------------------------------------------------------------

// WHOOSH itself breaks into three sub-beats - see drawWhooshLine:
private const val APPROACH_MS = 330f // straight whoosh in from off-screen, constant lane
private const val CROSS_MS = 230f // the swap: upper<->lower, left<->right
private const val TRACE_MS = 520f // each line draws the letter it's landed in front of
// Not `const` - a Float->Int conversion isn't a valid constant-expression in
// Kotlin, so this has to be a regular (if still immutable, file-scope) val.
private val WHOOSH_MS = (APPROACH_MS + CROSS_MS + TRACE_MS).toInt()

private const val CROSSFADE_MS = 380
private const val HOLD_MS = 650
private const val FADE_OUT_MS = 320

// Used in place of WHOOSH_MS + CROSSFADE_MS whenever the full sequence
// shouldn't play (see SettingsScreen's "Splash Screen" section's animation
// toggle, and `fullSequence` below) - the real logo just does a plain, quick
// fade-in instead of the whoosh-and-cross sequence. ~300ms per D6
// (docs/proposal/performance.md, F13): the policy for every launch that
// isn't the first one after an install/update.
private const val SIMPLE_FADE_IN_MS = 300

// A_STROKE/R_STROKE both start near the shared crossbar (see LogoPoints), not
// exactly where the cross sub-beat leaves off - this fraction of TRACE_MS is
// spent easing from the cross's end position into that first point, so the
// hand-off reads as one continuous line rather than a snap.
private const val BRIDGE_FRACTION = 0.18f

private val SplashBackground = Color(0xFF000000)
private val SplashForeground = Color(0xFFFFFFFF)

@Composable
fun SplashScreen(
    // See SettingsScreen's "Splash Screen" section / PreferencesManager -
    // both default to true (D6, revised: the chord is scored to the
    // animation, so it stays on by default rather than being muted
    // out-of-the-box). animationEnabled=false skips straight to a plain
    // fade-in of the real logo (see SIMPLE_FADE_IN_MS); musicEnabled=false
    // just never starts the AudioTrack. The two are independent of each
    // other.
    animationEnabled: Boolean = true,
    musicEnabled: Boolean = true,
    // D6 (docs/proposal/performance.md, F13): true exactly once per
    // install/update (see PreferencesManager.shouldShowFullSplash) - plays the
    // full whoosh-and-cross sequence. false on every other cold start, which
    // collapses to the same short SIMPLE_FADE_IN_MS fade animationEnabled=false
    // already uses, regardless of animationEnabled's own value - a user who
    // wants the animation still gets it on the one launch it's meant for.
    fullSequence: Boolean = true,
    onFinished: () -> Unit
) {
    // Single linear timeline driver - every phase below derives its own
    // progress from this rather than running independent animations, so nothing
    // can end up out of step with anything else. Only read inside a
    // graphicsLayer{}/draw-phase lambda below (never in this composable's own
    // body) - see F13 in docs/proposal/performance.md: reading an Animatable's
    // .value directly in a @Composable body makes every observer of that
    // function recompose on every animation frame, when only layout/draw
    // actually need the new value each frame.
    val playtime = remember { Animatable(0f) }

    // Lets a tap jump straight to the end instead of waiting out the full
    // timeline - D6's "tap-to-skip always available", independent of
    // animationEnabled/fullSequence.
    val skipRequested = remember { mutableStateOf(false) }

    // Scores the whoosh-and-cross with a synthesized C major guitar strum
    // (see GuitarChordSynth) - generated in code, no bundled audio asset.
    // F13 (performance.md): synthesis (buildCMajorStrumPcm16, ~507,000
    // samples) used to run inline inside a DisposableEffect - i.e. on the main
    // thread, before the first animated frame could show. Moved to
    // Dispatchers.Default here; only the AudioTrack construct/write/play in
    // SplashChordPlayer.playPcm (comparatively cheap) still touches the main
    // thread, via the LaunchedEffect's resumption after withContext returns.
    // Skipped entirely when musicEnabled is false, and released via
    // DisposableEffect regardless of whether synthesis had finished yet by the
    // time the splash tears down, so the AudioTrack can never leak or keep
    // playing past the splash itself.
    val chordPlayer = remember { mutableStateOf<SplashChordPlayer?>(null) }
    DisposableEffect(musicEnabled) {
        onDispose {
            chordPlayer.value?.release()
            chordPlayer.value = null
        }
    }
    LaunchedEffect(musicEnabled) {
        if (!musicEnabled) return@LaunchedEffect
        try {
            val pcm = withContext(Dispatchers.Default) {
                GuitarChordSynth.buildCMajorStrumPcm16()
            }
            val player = SplashChordPlayer()
            player.playPcm(pcm)
            chordPlayer.value = player
        } catch (_: Exception) {
            // An unusual/missing audio output shouldn't be able to crash the
            // splash - the visual whoosh still runs fine without sound.
        }
    }

    // Whoosh + crossfade collapse into a single quick fade-in whenever the
    // full sequence shouldn't play this launch - either the user turned the
    // animation off, or (D6) this isn't the first launch since an
    // install/update. See SIMPLE_FADE_IN_MS.
    val playFullSequence = animationEnabled && fullSequence
    val whooshDurationMs = if (playFullSequence) WHOOSH_MS else 0
    val crossfadeDurationMs = if (playFullSequence) CROSSFADE_MS else SIMPLE_FADE_IN_MS
    val totalMs = whooshDurationMs + crossfadeDurationMs + HOLD_MS + FADE_OUT_MS

    LaunchedEffect(totalMs) {
        playtime.snapTo(0f)
        // Races the timed animation against a tap: whichever finishes first
        // (the animation reaching 1f on its own, or skipRequested flipping
        // true) cancels the other and the result either way is playtime
        // sitting at 1f.
        coroutineScope {
            val animJob = launch {
                playtime.animateTo(1f, animationSpec = tween(totalMs, easing = LinearEasing))
            }
            val skipJob = launch {
                snapshotFlow { skipRequested.value }.first { it }
                playtime.snapTo(1f)
            }
            select<Unit> {
                animJob.onJoin { skipJob.cancel() }
                skipJob.onJoin { animJob.cancel() }
            }
        }
        delay(16) // let the final frame actually render before tearing the composable down
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground)
            // Phase 4 (fade-out) is the only thing that touches the whole
            // screen's alpha - computed here, inside the graphicsLayer block,
            // so this read of playtime.value drives redraw only, not
            // recomposition of SplashScreen itself.
            .graphicsLayer {
                val elapsedMs = playtime.value * totalMs
                val fadeOutProgress =
                    ((elapsedMs - whooshDurationMs - crossfadeDurationMs - HOLD_MS) / FADE_OUT_MS)
                        .coerceIn(0f, 1f)
                alpha = 1f - fadeOutProgress
            }
            // D6: tap anywhere on the splash to skip straight to onFinished.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { skipRequested.value = true })
            },
        contentAlignment = Alignment.Center
    ) {
        if (playFullSequence) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Phase 1/2 progress, read here (draw phase) rather than in
                // the composable body - see the playtime doc comment above.
                val elapsedMs = playtime.value * totalMs
                val crossfadeProgress =
                    ((elapsedMs - whooshDurationMs) / crossfadeDurationMs).coerceIn(0f, 1f)
                val lineCanvasAlpha = 1f - crossfadeProgress
                if (lineCanvasAlpha > 0f) {
                    val whooshProgress = (elapsedMs / whooshDurationMs).coerceIn(0f, 1f)
                    drawWhoosh(whooshProgress, lineCanvasAlpha)
                }
            }
        }

        // Always present once composed (rather than conditionally added/
        // removed based on a per-frame alpha, as this used to be) - only its
        // graphicsLayer alpha changes per frame, which is redraw, not
        // composition churn.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(32.dp)
                .graphicsLayer {
                    // The real logo+text crossfades in starting once the
                    // whoosh phase ends and stays fully visible through the
                    // hold; phase 4's fade-out is handled by the Box above.
                    val elapsedMs = playtime.value * totalMs
                    alpha = ((elapsedMs - whooshDurationMs) / crossfadeDurationMs).coerceIn(0f, 1f)
                }
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

private fun smoothstep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

// Centered box the mark is reconstructed into, sized to LogoPoints'
// ASPECT_RATIO and occupying a comfortable fraction of the shorter screen
// dimension - lines up with where the real ic_splash_logo.png crossfades in
// at (see SplashScreen's Image, sized 112.dp within similar centered
// padding).
private data class LogoBox(val left: Float, val top: Float, val width: Float, val height: Float)

private fun computeLogoBox(w: Float, h: Float): LogoBox {
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
    return LogoBox((w - logoW) / 2f, (h - logoH) / 2f, logoW, logoH)
}

// Maps one of LogoPoints' 0f..1f-normalized points into this box's screen
// pixels.
private fun LogoBox.toScreen(normalized: Pair<Float, Float>): Offset =
    Offset(left + normalized.first * width, top + normalized.second * height)

// Draws both whooshing/crossing/tracing lines for the current WHOOSH
// progress - see the phase doc comment above SplashScreen for the full
// choreography. `canvasAlpha` is the phase-2 crossfade multiplier applied on
// top of everything drawn here.
private fun DrawScope.drawWhoosh(progress: Float, canvasAlpha: Float) {
    if (canvasAlpha <= 0f) return

    val w = size.width
    val h = size.height
    val centerY = h / 2f
    val box = computeLogoBox(w, h)

    // Two lanes straddling vertical centre - "upper" and "lower" from the
    // ASCII sketch this animation is based on.
    val laneTop = centerY - h * 0.09f
    val laneBottom = centerY + h * 0.09f
    val crossGap = w * 0.05f
    val barLen = minOf(w, h) * 0.22f
    val strokeWidthPx = 7.dp.toPx()
    val elapsedMs = progress * WHOOSH_MS

    // Starts on the LEFT, swaps to the right mid-screen, traces the "R".
    drawWhooshLine(
        elapsedMs = elapsedMs,
        startsOnRight = false,
        laneStart = laneTop,
        laneEnd = laneBottom,
        crossGap = crossGap,
        barLen = barLen,
        strokeWidthPx = strokeWidthPx,
        stroke = LogoPoints.R_STROKE,
        box = box,
        w = w,
        canvasAlpha = canvasAlpha
    )

    // Mirror image: starts on the RIGHT, swaps to the left, traces the "A".
    drawWhooshLine(
        elapsedMs = elapsedMs,
        startsOnRight = true,
        laneStart = laneBottom,
        laneEnd = laneTop,
        crossGap = crossGap,
        barLen = barLen,
        strokeWidthPx = strokeWidthPx,
        stroke = LogoPoints.A_STROKE,
        box = box,
        w = w,
        canvasAlpha = canvasAlpha
    )
}

// One of the two lines described above. `laneStart`/`laneEnd` are this
// line's lane before/after the cross; `stroke` is the letter it ends up
// tracing (the one that lives on the side it swaps INTO, not the side it
// started on).
private fun DrawScope.drawWhooshLine(
    elapsedMs: Float,
    startsOnRight: Boolean,
    laneStart: Float,
    laneEnd: Float,
    crossGap: Float,
    barLen: Float,
    strokeWidthPx: Float,
    stroke: List<Pair<Float, Float>>,
    box: LogoBox,
    w: Float,
    canvasAlpha: Float
) {
    val centerX = w / 2f
    // Which side of centre this line starts from: +1 = right, -1 = left.
    val sign = if (startsOnRight) 1f else -1f

    when {
        elapsedMs <= APPROACH_MS -> {
            // Sub-beat 1 (APPROACH): straight whoosh in from off-screen,
            // constant lane - the two flat dashes in the original sketch.
            val ap = EaseOutCubic.transform((elapsedMs / APPROACH_MS).coerceIn(0f, 1f))
            val startX = centerX + sign * (w / 2f + barLen)
            val endX = centerX + sign * crossGap
            val x = lerp(startX, endX, ap)
            drawBar(Offset(x, laneStart), Offset(1f, 0f), barLen, strokeWidthPx, canvasAlpha)
        }

        elapsedMs <= APPROACH_MS + CROSS_MS -> {
            // Sub-beat 2 (CROSS): the U-turn. Sweeps through centre while its
            // lane eases from laneStart to laneEnd - upper becomes lower (or
            // vice versa) by curving through the middle, not by teleporting.
            val cp = ((elapsedMs - APPROACH_MS) / CROSS_MS).coerceIn(0f, 1f)
            val x = centerX + sign * crossGap * (1f - 2f * cp)
            val y = lerp(laneStart, laneEnd, smoothstep(cp))
            // Direction of travel, so the bar visibly rotates through the
            // curve instead of staying rigidly horizontal through the swap.
            val dir = Offset(-sign * crossGap, laneEnd - laneStart)
            drawBar(Offset(x, y), dir, barLen, strokeWidthPx, canvasAlpha)
        }

        else -> {
            // Sub-beat 3 (TRACE): bridge from where the cross left off into
            // the stroke's own first point (BRIDGE_FRACTION), then trace it
            // start-to-finish, leaving every segment already drawn in place -
            // the line IS the result, nothing fades or shrinks to a dot.
            val tp = ((elapsedMs - APPROACH_MS - CROSS_MS) / TRACE_MS).coerceIn(0f, 1f)
            val crossEnd = Offset(centerX - sign * crossGap, laneEnd)
            val strokeStart = box.toScreen(stroke.first())

            val path = Path()
            path.moveTo(crossEnd.x, crossEnd.y)

            if (tp <= BRIDGE_FRACTION) {
                val bp = EaseOutCubic.transform((tp / BRIDGE_FRACTION).coerceIn(0f, 1f))
                path.lineTo(
                    lerp(crossEnd.x, strokeStart.x, bp),
                    lerp(crossEnd.y, strokeStart.y, bp)
                )
            } else {
                path.lineTo(strokeStart.x, strokeStart.y)

                val traceP = ((tp - BRIDGE_FRACTION) / (1f - BRIDGE_FRACTION)).coerceIn(0f, 1f)
                val exact = traceP * (stroke.size - 1)
                val idx = exact.toInt().coerceIn(0, stroke.size - 2)
                val localT = exact - idx

                for (i in 1..idx) {
                    val p = box.toScreen(stroke[i])
                    path.lineTo(p.x, p.y)
                }
                val a = box.toScreen(stroke[idx])
                val b = box.toScreen(stroke[idx + 1])
                path.lineTo(lerp(a.x, b.x, localT), lerp(a.y, b.y, localT))
            }

            drawPath(
                path = path,
                color = SplashForeground.copy(alpha = canvasAlpha),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

// Draws the moving whoosh bar itself (APPROACH and CROSS sub-beats, before a
// line locks onto its stroke) as a short thick rounded segment centred on
// `center`, oriented along `dir`.
private fun DrawScope.drawBar(
    center: Offset,
    dir: Offset,
    barLen: Float,
    strokeWidthPx: Float,
    alpha: Float
) {
    val len = sqrt(dir.x * dir.x + dir.y * dir.y).let { if (it == 0f) 1f else it }
    val ux = dir.x / len
    val uy = dir.y / len
    val half = barLen / 2f
    drawLine(
        color = SplashForeground.copy(alpha = alpha),
        start = Offset(center.x - ux * half, center.y - uy * half),
        end = Offset(center.x + ux * half, center.y + uy * half),
        strokeWidth = strokeWidthPx,
        cap = StrokeCap.Round
    )
}
