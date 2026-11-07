package com.arkarium.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

// The "water ripple" the reader's bottom pill swaps with (Reader Preferences <-> read-
// aloud controls, see ReaderScreen). Two cooperating pieces:
//  - RippleReveal: the pill itself appears as a circle growing out from its centre, like
//    a drop landing on water, and leaves by the same circle shrinking away.
//  - WaterRippleWave: a few thin rings spreading outward from the same spot, fading as
//    they go. Purely decorative; it never takes touches.
// Both are short (about half a second) so the swap reads as one gesture rather than
// something the reader waits on.

private const val REVEAL_MS = 420
private const val WAVE_MS = 900

// A circle centred in the shape's bounds whose radius is `fraction` of the way to the
// farthest corner, so at 1f the whole rectangle is revealed.
private class CircleRevealShape(private val fraction: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = hypot(cx, cy) * fraction
        return Outline.Generic(
            Path().apply { addOval(Rect(Offset(cx, cy), radius)) }
        )
    }
}

// AnimatedVisibility whose content is revealed (and hidden) by a circle expanding from
// its centre. Layout space grows/shrinks vertically alongside, so a pill sitting in a
// Column footer doesn't leave a hole - or snap the space away - when it goes.
@Composable
fun RippleReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(REVEAL_MS / 2)) +
            expandVertically(tween(REVEAL_MS, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(REVEAL_MS / 2)) +
            shrinkVertically(tween(REVEAL_MS, easing = FastOutSlowInEasing))
    ) {
        val reveal by transition.animateFloat(
            transitionSpec = { tween(REVEAL_MS, easing = FastOutSlowInEasing) },
            label = "rippleReveal"
        ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

        Box(
            modifier = Modifier.graphicsLayer {
                // Clip only while the circle is still growing/shrinking. Once fully
                // revealed, no clip at all, so the pill's drop shadow isn't cut off.
                if (reveal < 1f) {
                    shape = CircleRevealShape(reveal)
                    clip = true
                } else {
                    shape = RectangleShape
                    clip = false
                }
            }
        ) {
            content()
        }
    }
}

// Rings spreading from a point near the bottom-centre, where the reader's pill sits,
// replayed every time `trigger` changes (never on first composition) and only while
// `enabled`. Overlay it on the reader; it draws nothing at rest and has no pointer
// handling, so touches fall straight through to what's underneath.
@Composable
fun WaterRippleWave(
    trigger: Any,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(1f) }
    var firstRun by remember { mutableStateOf(true) }

    LaunchedEffect(trigger) {
        if (firstRun) {
            // Opening the reader (or re-attaching after a rotation) isn't a swap.
            firstRun = false
            return@LaunchedEffect
        }
        if (!enabled) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(WAVE_MS, easing = LinearOutSlowInEasing))
    }

    Canvas(modifier = modifier) {
        // Read here, in the draw phase, so each animation frame only redraws instead of
        // recomposing the whole reader.
        val p = progress.value
        if (p >= 1f) return@Canvas

        val center = Offset(size.width / 2f, size.height - 56.dp.toPx())
        val maxRadius = size.width * 0.45f
        for (ring in 0 until 3) {
            // Each ring starts a little after the last, so they read as successive
            // ripples rather than one fat circle.
            val t = ((p - ring * 0.14f) / 0.72f).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) continue
            drawCircle(
                color = color.copy(alpha = 0.38f * (1f - t)),
                radius = maxRadius * t,
                center = center,
                style = Stroke(width = (1.dp + 3.dp * (1f - t)).toPx())
            )
        }
    }
}
