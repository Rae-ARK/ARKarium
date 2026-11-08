package com.arkarium.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Stage 3.2 (see docs/arkarium/foundational/design-notes.md) - the actual TTS default
// controls, replacing the "arrives in Stage 3" placeholder text Stage 1 left
// here. Each control reads/writes exactly one Stage 3.1 PreferencesManager
// key via a callback MainActivity's "settings/tts" composable hoists and
// passes down, same direct-PreferencesManager shape settings/splash already
// uses - no TtsSettingsViewModel, per the design doc's resolved
// ViewModel-ownership question. Stages 3.3-3.5 wired Tts.kt/TtsPlaybackService
// to actually read these keys (seeding the engine's rate/pitch, live
// auto-continue, keep-screen-on), and the Voice card (below) adds the voice
// picker on top of that same wiring - so every control on
// this screen now has reader-facing effect, not just stored-and-reflected.
//
// Beautification pass: the shared header badge opens this page too; the two
// sliders (rate/pitch) and the two switches (auto-continue/keep screen on)
// each move into their own surfaceVariant card - two small grouped cards
// instead of one long undivided column - with a small GraphicEq accent icon
// next to each slider's label. "Change voice" keeps its own outlined,
// icon-fronted button below the cards, unchanged in behavior. No callback,
// param, range, step, or persisted-value logic changed.
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
    // Dropped during the beautification pass (cards split) and restored here:
    // each control above still reads exactly one of these as its
    // onValueChange/onCheckedChange, so the signature has to carry them even
    // though that pass's comment focused on layout, not these four.
    onDefaultRateChange: (Float) -> Unit = {},
    onPitchChange: (Float) -> Unit = {},
    onAutoContinueToggle: (Boolean) -> Unit = {},
    onKeepScreenOnToggle: (Boolean) -> Unit = {},
    // The Voice card. `availableVoices` is
    // empty until the engine finishes initializing (same timing as everything
    // else TtsPlaybackService exposes) - the voice row below renders fine in
    // that state, it just has nothing to list yet if tapped. `currentVoiceName`
    // mirrors PreferencesManager.ttsVoiceName (null = "System default"); it is
    // matched against `availableVoices` below rather than trusted on its own,
    // since a stored name that no longer resolves on this device must still
    // show as "System default" here, not as a stale string (see
    // TtsPlaybackService.setVoice's doc comment).
    availableVoices: List<Voice> = emptyList(),
    currentVoiceName: String? = null,
    // The Voice
    // card below navigates to the full `settings/tts/voice` page
    // (TtsVoicePickerScreen) instead of opening an in-place AlertDialog - this
    // screen no longer owns any picker state of its own, just the summary row
    // that opens it.
    onOpenVoicePicker: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentVoice = availableVoices.firstOrNull { it.name == currentVoiceName }

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
            SettingsHeaderBadge(
                icon = Icons.Filled.RecordVoiceOver,
                caption = "Defaults for read-aloud - rate, pitch, and how it " +
                    "behaves between chapters."
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Default speech rate. Same 0.5x-2.5x range/step as the reader
                    // pill's live rate slider (ReaderScreen.kt) for a consistent feel
                    // between the two controls - this one only changes what
                    // rememberChapterTts() seeds ChapterTtsState.speechRate with
                    // (Stage 3.3), not the live session value.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Default speech rate", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "Starting read-aloud speed for every chapter. The " +
                            "reader's Speed slider can still adjust it for that " +
                            "session without changing this default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp)
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

                    Divider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    // Pitch. No pill exposure - unlike rate, there's no mid-chapter
                    // case for changing this, per the design doc. Applied once at
                    // engine init (Stage 3.3) via TextToSpeech.setPitch(), whose
                    // documented range is the same 0.5-2.0 used here (1.0 = normal).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Pitch", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "Voice pitch for read-aloud. 1.0 is the engine's normal " +
                            "pitch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp)
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                            Text("Auto-continue to next chapter", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Keep reading aloud into the next chapter instead of " +
                                    "stopping at the end of this one.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = ttsAutoContinue, onCheckedChange = onAutoContinueToggle)
                    }

                    Divider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

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
                            Text("Keep screen on while speaking", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Prevents the screen from sleeping for as long as " +
                                    "read-aloud is active.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = ttsKeepScreenOn, onCheckedChange = onKeepScreenOnToggle)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // A single
            // tappable row, same surfaceVariant-card treatment as the two
            // groups above, rather than its own Slider/Switch - the control
            // itself is "open a picker", not a value dragged in place. Shows
            // the current voice's locale display name (or "System default"
            // when unset/non-resolving) plus its quality and network
            // badges, so the row is informative even closed.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenVoicePicker),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            voiceSummary(currentVoice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Link-out engine row, per the design doc's resolved
            // engine-picker question: still no in-app *engine* chooser
            // (which TTS app provides the voices above) - that's narrower
            // than it used to be, now that the "Voice" row above is an
            // in-app picker among whatever voices the current engine
            // exposes, so this button is for installing additional
            // language packs or tuning the engine's own settings, not a
            // dead end. Opens the system's own TTS output settings
            // (Settings > Accessibility > Text-to-speech output on most
            // devices); falls back to the general Accessibility settings
            // screen on the rare device where that intent doesn't resolve,
            // rather than crashing on ActivityNotFoundException.
            //
            // Rung 4 (U3): relabeled from "Change voice", which stopped being
            // true the moment the Voice card above became an in-app picker -
            // this button only ever reached the system's TTS settings, never
            // ARKarium's own choice of voice.
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent("com.android.settings.TTS_SETTINGS")
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
                    .padding(top = 16.dp)
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("System speech settings")
            }
        }
    }
}

// Closed-row summary text for the Voice card above: the selected voice's
// locale display name, its quality tier, and whether it needs a network
// connection to speak. `voice` is already the result of matching
// currentVoiceName against availableVoices, so a non-resolving stored name
// reaches here as null, same as never having picked one - see
// TtsSettingsScreen's doc comment on `currentVoice`.
//
// Rung 4 (U1): used to say "- offline" for a local voice and nothing at all
// for an online one - true but asymmetric, the "requires a network
// connection" risk this flags was never stated on *this* row,
// only inferred from its absence. Online and offline are now named the same
// way, matching TtsVoiceModel.kt's descriptionOf (the full voice page's
// equivalent text).
private fun voiceSummary(voice: Voice?): String {
    if (voice == null) return "System default"
    val locale = voice.locale?.displayName ?: voice.name
    val quality = qualityLabel(voice.quality)
    val network = if (voice.isNetworkConnectionRequired) "online" else "offline"
    return "$locale - $quality - $network"
}

// Voice.quality is one of five named int constants (QUALITY_VERY_LOW=100 ..
// QUALITY_VERY_HIGH=500, in steps of 100), not a bounded enum - a >= ladder
// rather than a `when` over exact values keeps this correct even if an
// engine returns an in-between value Android's own constants don't name.
private fun qualityLabel(quality: Int): String = when {
    quality >= Voice.QUALITY_VERY_HIGH -> "very high quality"
    quality >= Voice.QUALITY_HIGH -> "high quality"
    quality >= Voice.QUALITY_NORMAL -> "standard quality"
    quality >= Voice.QUALITY_LOW -> "low quality"
    else -> "very low quality"
}
