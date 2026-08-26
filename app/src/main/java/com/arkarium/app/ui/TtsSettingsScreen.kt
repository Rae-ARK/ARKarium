package com.arkarium.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Stage 3.2 (docs/arkarium/SETTINGS_REDESIGN.md) - the actual TTS default
// controls, replacing the "arrives in Stage 3" placeholder text Stage 1 left
// here. Each control reads/writes exactly one Stage 3.1 PreferencesManager
// key via a callback MainActivity's "settings/tts" composable hoists and
// passes down, same direct-PreferencesManager shape settings/splash already
// uses - no TtsSettingsViewModel, per the design doc's resolved
// ViewModel-ownership question. This stage is UI-and-persistence only:
// changing these controls updates the stored preference and this screen
// reflects it, but Tts.kt doesn't read any of these keys yet (that's Stages
// 3.3-3.5), so reader-facing TTS behavior is unchanged until then.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(
    // Defaults mirror PreferencesManager's own fallbacks (ttsDefaultRate,
    // ttsPitch default to 1.0f; ttsAutoContinue, ttsKeepScreenOn default to
    // false) so a fresh/never-collected state renders the same values the
    // preference flow would emit before its first real read completes.
    ttsDefaultRate: Float = 1.0f,
    ttsPitch: Float = 1.0f,
    ttsAutoContinue: Boolean = false,
    ttsKeepScreenOn: Boolean = false,
    onDefaultRateChange: (Float) -> Unit = {},
    onPitchChange: (Float) -> Unit = {},
    onAutoContinueToggle: (Boolean) -> Unit = {},
    onKeepScreenOnToggle: (Boolean) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Text-to-Speech") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            // Default speech rate. Same 0.5x-2.5x range/step as the reader
            // pill's live rate slider (ReaderScreen.kt) for a consistent feel
            // between the two controls - this one only changes what
            // rememberChapterTts() seeds ChapterTtsState.speechRate with
            // (Stage 3.3), not the live session value.
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text("Default speech rate")
                Text(
                    "Starting read-aloud speed for every chapter. The " +
                        "reader's Speed slider can still adjust it for that " +
                        "session without changing this default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = ttsDefaultRate,
                        onValueChange = onDefaultRateChange,
                        valueRange = 0.5f..2.5f,
                        steps = 7,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Text(
                        "${String.format("%.2f", ttsDefaultRate)}x",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Pitch. No pill exposure - unlike rate, there's no mid-chapter
            // case for changing this, per the design doc. Applied once at
            // engine init (Stage 3.3) via TextToSpeech.setPitch(), whose
            // documented range is the same 0.5-2.0 used here (1.0 = normal).
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text("Pitch")
                Text(
                    "Voice pitch for read-aloud. 1.0 is the engine's normal " +
                        "pitch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = ttsPitch,
                        onValueChange = onPitchChange,
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Text(
                        "${String.format("%.2f", ttsPitch)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text("Auto-continue to next chapter")
                    Text(
                        "Keep reading aloud into the next chapter instead of " +
                            "stopping at the end of this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = ttsAutoContinue, onCheckedChange = onAutoContinueToggle)
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
                    Text("Keep screen on while speaking")
                    Text(
                        "Prevents the screen from sleeping for as long as " +
                            "read-aloud is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = ttsKeepScreenOn, onCheckedChange = onKeepScreenOnToggle)
            }

            // Link-out engine row, per the design doc's resolved
            // engine-picker question: no in-app voice/engine chooser,
            // matching Tts.kt's doc comment about deliberately not shipping
            // or managing voices itself. Opens the system's own TTS output
            // settings (Settings > Accessibility > Text-to-speech output on
            // most devices); falls back to the general Accessibility
            // settings screen on the rare device where that intent doesn't
            // resolve, rather than crashing on ActivityNotFoundException.
            Button(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(TextToSpeech.Engine.ACTION_TTS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Change voice")
            }
        }
    }
}
