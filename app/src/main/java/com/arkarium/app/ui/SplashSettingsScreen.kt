package com.arkarium.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Stage 2 (docs/arkarium/SETTINGS_REDESIGN.md, "mechanical extraction, no
// behavior change") - the Animation and Animation music switches move here
// verbatim from the old monolithic SettingsScreen.kt. MainActivity's
// "settings/splash" composable wires splashAnimationEnabled/
// splashMusicEnabled and the two toggle callbacks the same way its old
// "settings" composable did.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplashSettingsScreen(
    // Splash-screen behavior (see PreferencesManager.splashAnimationEnabled /
    // splashMusicEnabled) - both default to true, independent of each other.
    splashAnimationEnabled: Boolean = true,
    splashMusicEnabled: Boolean = true,
    onSplashAnimationToggle: (Boolean) -> Unit = {},
    onSplashMusicToggle: (Boolean) -> Unit = {},
    onBack: () -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // weight(1f) here for the same reason as the "Use custom folder"
                // row on settings/library - forces the description to wrap within
                // its share of the Row instead of pushing the Switch off-screen.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text("Animation")
                    Text(
                        "Lines converge into the ARKarium mark on launch. Turn " +
                            "off for a plain, faster fade-in instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = splashAnimationEnabled, onCheckedChange = onSplashAnimationToggle)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text("Animation music")
                    Text(
                        "Plays a short guitar chord alongside the launch animation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = splashMusicEnabled, onCheckedChange = onSplashMusicToggle)
            }
        }
    }
}
