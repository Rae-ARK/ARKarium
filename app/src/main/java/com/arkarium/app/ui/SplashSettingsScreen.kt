package com.arkarium.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkarium.app.data.SplashAnimationMode
import com.arkarium.app.data.SplashMusicMode

// Stage 2 (see docs/foundational/design-notes.md, "mechanical extraction, no
// behavior change") - the Animation and Animation music switches move here
// verbatim from the old monolithic SettingsScreen.kt. MainActivity's
// "settings/splash" composable wires splashAnimationEnabled/
// splashMusicEnabled and the two toggle callbacks the same way its old
// "settings" composable did.
//
// Beautification pass: the header badge shared with settings/theme and
// settings/library opens this page too, and the two switch rows now live
// inside one surfaceVariant card (matching AuthorPageScreen's card idiom)
// with a divider between them instead of floating loose against the plain
// background.
//
// R8 fix (urgent-review.md, F8): both switches replaced with a three-option
// radio group each (ThemeSettingsScreen/TtsVoicePickerScreen's existing radio
// idiom, not a new pattern) - a plain on/off toggle couldn't express "play
// every launch" vs "play once per update", and conflated "skip the chord
// entirely" with "don't let it outlive the splash". See
// PreferencesManager.SplashAnimationMode/SplashMusicMode for what each option
// actually does.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplashSettingsScreen(
    splashAnimationMode: SplashAnimationMode = SplashAnimationMode.ONCE_PER_UPDATE,
    splashMusicMode: SplashMusicMode = SplashMusicMode.SHORT,
    onSplashAnimationModeChange: (SplashAnimationMode) -> Unit = {},
    onSplashMusicModeChange: (SplashMusicMode) -> Unit = {},
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("Splash Screen") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SettingsHeaderBadge(
                icon = Icons.Filled.Bolt,
                caption = "Control the animation and music ARKarium plays on launch."
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Animation", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Two lines whoosh in, cross, and trace the ARKarium mark.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    SplashOptionRow(
                        label = "Once per update",
                        description = "Full animation the first launch after install/update, a quick fade otherwise.",
                        selected = splashAnimationMode == SplashAnimationMode.ONCE_PER_UPDATE,
                        onClick = { onSplashAnimationModeChange(SplashAnimationMode.ONCE_PER_UPDATE) }
                    )
                    SplashOptionRow(
                        label = "Every launch",
                        description = "Play the full animation every time ARKarium opens.",
                        selected = splashAnimationMode == SplashAnimationMode.EVERY_LAUNCH,
                        onClick = { onSplashAnimationModeChange(SplashAnimationMode.EVERY_LAUNCH) }
                    )
                    SplashOptionRow(
                        label = "Off",
                        description = "Always use a plain, faster fade-in instead.",
                        selected = splashAnimationMode == SplashAnimationMode.OFF,
                        onClick = { onSplashAnimationModeChange(SplashAnimationMode.OFF) }
                    )

                    Divider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    Text("Animation music", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A short guitar chord scored to the launch animation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    SplashOptionRow(
                        label = "Full",
                        description = "Let the chord ring out fully, even after the splash finishes.",
                        selected = splashMusicMode == SplashMusicMode.FULL,
                        onClick = { onSplashMusicModeChange(SplashMusicMode.FULL) }
                    )
                    SplashOptionRow(
                        label = "Short",
                        description = "Fade it out as the splash ends, instead of cutting it off.",
                        selected = splashMusicMode == SplashMusicMode.SHORT,
                        onClick = { onSplashMusicModeChange(SplashMusicMode.SHORT) }
                    )
                    SplashOptionRow(
                        label = "Off",
                        description = "No music on launch.",
                        selected = splashMusicMode == SplashMusicMode.OFF,
                        onClick = { onSplashMusicModeChange(SplashMusicMode.OFF) }
                    )
                }
            }
        }
    }
}

// One radio-selectable row shared by both three-way groups above - label,
// a one-line description underneath, and the RadioButton itself, same
// clickable-row-plus-RadioButton idiom TtsVoicePickerScreen's language picker
// already uses elsewhere in the app.
@Composable
private fun SplashOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
