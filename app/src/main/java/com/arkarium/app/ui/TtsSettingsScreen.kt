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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
// auto-continue, keep-screen-on), and tts-voice-picker.md's Step 4 (below)
// adds the voice picker on top of that same wiring - so every control on
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
    // Step 4 of docs/arkarium/proposal/tts-voice-picker.md. `availableVoices` is
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
    onVoiceChange: (String?) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showVoicePicker by remember { mutableStateOf(false) }
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

            // Step 4 of docs/arkarium/proposal/tts-voice-picker.md. A single
            // tappable row, same surfaceVariant-card treatment as the two
            // groups above, rather than its own Slider/Switch - the control
            // itself is "open a picker", not a value dragged in place. Shows
            // the current voice's locale display name (or "System default"
            // when unset/non-resolving) plus its quality and network
            // badges, so the row is informative even closed.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showVoicePicker = true },
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

            if (showVoicePicker) {
                VoicePickerDialog(
                    availableVoices = availableVoices,
                    currentVoiceName = currentVoice?.name,
                    onVoiceSelected = { name ->
                        onVoiceChange(name)
                        showVoicePicker = false
                    },
                    onDismiss = { showVoicePicker = false }
                )
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
                Text("Change voice")
            }
        }
    }
}

// Closed-row summary text for the Voice card above: the selected voice's
// locale display name plus its quality tier, with a trailing "- offline
// only" note when it doesn't require a network connection to speak (the
// case most readers want, per tts-voice-picker.md's "Offline readers" risk)
// and no note at all when it does require one, since that's the risk, not
// the reassurance. `voice` is already the result of matching currentVoiceName
// against availableVoices, so a non-resolving stored name reaches here as
// null, same as never having picked one - see TtsSettingsScreen's doc
// comment on `currentVoice`.
private fun voiceSummary(voice: Voice?): String {
    if (voice == null) return "System default"
    val locale = voice.locale?.displayName ?: voice.name
    val quality = qualityLabel(voice.quality)
    return if (voice.isNetworkConnectionRequired) {
        "$locale - $quality"
    } else {
        "$locale - $quality - offline"
    }
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

// Picker dialog per tts-voice-picker.md's UI sketch: voices grouped by
// locale display name (language first - on a single-engine device most
// people care "which language" more than "which voice model"), each row
// showing a quality tier and a "requires network" badge. A leading "System
// default" row clears back to null, same as every other "unset means
// default" control in this screen. Groups and the voices within them are
// sorted alphabetically so the list is stable/scannable across
// recompositions rather than following whatever order the engine happened
// to return.
@Composable
private fun VoicePickerDialog(
    availableVoices: List<Voice>,
    currentVoiceName: String?,
    onVoiceSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val groupedVoices = remember(availableVoices) {
        availableVoices
            .sortedBy { it.name }
            .groupBy { it.locale?.displayName ?: "Other" }
            .toSortedMap()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a voice") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    VoicePickerRow(
                        title = "System default",
                        subtitle = null,
                        selected = currentVoiceName == null,
                        networkRequired = false,
                        onClick = { onVoiceSelected(null) }
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
                groupedVoices.forEach { (localeName, voicesInLocale) ->
                    item {
                        Text(
                            localeName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(voicesInLocale, key = { it.name }) { voice ->
                        VoicePickerRow(
                            title = voice.name,
                            subtitle = qualityLabel(voice.quality)
                                .replaceFirstChar { it.uppercase() },
                            selected = voice.name == currentVoiceName,
                            networkRequired = voice.isNetworkConnectionRequired,
                            onClick = { onVoiceSelected(voice.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun VoicePickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    networkRequired: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    // Flagged, not hidden, when a voice does require
                    // network - see tts-voice-picker.md's "Offline readers"
                    // risk: someone picking this on wifi shouldn't lose
                    // read-aloud on their commute without having been told.
                    if (networkRequired) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = "Requires a network connection",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(14.dp)
                        )
                    }
                }
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
