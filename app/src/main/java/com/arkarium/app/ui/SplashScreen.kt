package com.arkarium.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.R
import kotlinx.coroutines.delay

// How long the splash stays on screen before handing off to the real app. Long
// enough to register as an intentional brand moment, short enough that it never
// feels like it's blocking the user - this is purely decorative, unlike the
// system's actual cold-start window (process init, first frame, etc.) which has
// already happened by the time this composable is even reached.
private const val SPLASH_HOLD_MS = 1100L
private const val FADE_IN_MS = 350
private const val FADE_OUT_MS = 250

// Deliberately hardcoded black/white here rather than reading MaterialTheme's
// colorScheme: the splash is a fixed brand moment tied directly to the AR mark's
// own black canvas (see assets/app-icon-source.png), and should look identical
// regardless of which reader theme (Light/Dark/Warm Paper) the user has picked -
// the same way the launcher icon itself doesn't change with in-app theme.
private val SplashBackground = Color(0xFF000000)
private val SplashForeground = Color(0xFFFFFFFF)

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) FADE_IN_MS else FADE_OUT_MS),
        label = "splash_alpha"
    )

    LaunchedEffect(Unit) {
        visible = true
        delay(SPLASH_HOLD_MS)
        visible = false
        delay(FADE_OUT_MS.toLong())
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(32.dp)
                .alpha(alpha)
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
