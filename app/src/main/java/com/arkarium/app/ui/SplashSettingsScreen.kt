package com.arkarium.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Stage 1 (docs/arkarium/SETTINGS_REDESIGN.md, "navigation scaffolding") -
// destination for the new "settings/splash" route, wired up in
// MainActivity's NavHost alongside this file. Deliberately a thin wrapper
// for now: the Animation and Animation music switches still render inline
// on MainActivity's "settings" composable this stage. Stage 2 moves that
// control code into this file verbatim and wires the
// splashAnimationEnabled/splashMusicEnabled/onSplashAnimationToggle/
// onSplashMusicToggle params this screen will need then - not yet, to keep
// this patch to scaffolding only.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplashSettingsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Splash Screen") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Splash screen controls move here in Stage 2.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
