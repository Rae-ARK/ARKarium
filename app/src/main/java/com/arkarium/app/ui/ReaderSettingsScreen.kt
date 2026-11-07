package com.arkarium.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkarium.app.data.ReadingModeDefault

// docs/arkarium/proposal/reader-settings.md, "Page shape" + suggested-order
// items 1-5 (the "cheap" batch the owner approved; multi-document, the
// day/night button, the volume slider and column count stay
// documented-only - see that doc's own order list). Same flat-
// PreferencesManager-keys, no-ViewModel shape as TtsSettingsScreen - see
// that screen's own doc comment and design-notes.md's "Settings is an
// index; each option is its own page".
//
// Font family lives on this page as its own card rather than in the Reader
// Preferences pill, per the doc's Font family section: it's "set and
// forget", unlike Size/Spacing/Mode, which the pill still adjusts live and
// never writes back here.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(
    // Defaults mirror PreferencesManager's own fallbacks, same reasoning as
    // TtsSettingsScreen's defaults.
    fontSize: Float = 18f,
    lineHeight: Float = 1.8f,
    // Null = "follow the app theme" (the owner's answer to the doc's first
    // open question) - not a fourth ReadingModeDefault entry, since that
    // would collapse "never chose" and "chose to pin Light" into one state.
    readingMode: ReadingModeDefault? = null,
    fontFamily: ReaderFontFamily = ReaderFontFamily.SERIF,
    keepScreenOn: Boolean = false,
    fullscreen: Boolean = false,
    brightnessSliderEnabled: Boolean = false,
    onFontSizeChange: (Float) -> Unit = {},
    onLineHeightChange: (Float) -> Unit = {},
    onReadingModeChange: (ReadingModeDefault?) -> Unit = {},
    onFontFamilyChange: (ReaderFontFamily) -> Unit = {},
    onKeepScreenOnToggle: (Boolean) -> Unit = {},
    onFullscreenToggle: (Boolean) -> Unit = {},
    onBrightnessSliderToggle: (Boolean) -> Unit = {},
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Reader") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SettingsHeaderBadge(
                icon = Icons.Filled.AutoStories,
                caption = "Defaults for the chapter reader - text size, " +
                    "spacing, mode, and how the screen behaves while " +
                    "you're reading."
            )

            // Font family. Still a reader setting, just not a per-session
            // one - see this file's doc comment.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Font family", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "Used everywhere you read - not adjustable per " +
                            "session from the Reader Preferences pill.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReaderFontFamily.values().forEach { choice ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onFontFamilyChange(choice) },
                                color = if (fontFamily == choice)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    when (choice) {
                                        ReaderFontFamily.SERIF -> "Serif"
                                        ReaderFontFamily.SANS -> "Sans"
                                        ReaderFontFamily.DYSLEXIC -> "Dyslexia-friendly"
                                    },
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (fontFamily == choice)
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Defaults the pill seeds from once per reader session and
            // never writes back to - see this file's doc comment.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Default text size", style = MaterialTheme.typography.bodyLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = fontSize,
                            onValueChange = onFontSizeChange,
                            valueRange = 12f..28f,
                            steps = 7,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Text("${fontSize.toInt()}", style = MaterialTheme.typography.bodySmall)
                    }

                    Divider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    Text("Default spacing", style = MaterialTheme.typography.bodyLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = lineHeight,
                            onValueChange = onLineHeightChange,
                            valueRange = 1.0f..2.5f,
                            steps = 10,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            String.format("%.1f", lineHeight),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Divider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    Text("Default reading mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "\"Follow app theme\" tracks whatever you've set in " +
                            "Settings > Theme; the reader still won't start " +
                            "reflecting manual Light/Sepia/Dark changes made " +
                            "mid-session here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReadingModeOption(
                            label = "Follow app theme",
                            selected = readingMode == null,
                            onClick = { onReadingModeChange(null) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReadingModeDefault.values().forEach { mode ->
                            ReadingModeOption(
                                label = when (mode) {
                                    ReadingModeDefault.LIGHT -> "Light"
                                    ReadingModeDefault.SEPIA -> "Sepia"
                                    ReadingModeDefault.DARK -> "Dark"
                                },
                                selected = readingMode == mode,
                                onClick = { onReadingModeChange(mode) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The two reader-level switches plus the brightness-slider
            // opt-in, grouped together as "how the reader behaves while
            // open" - same surfaceVariant-card treatment as everything
            // above.
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
                            Text("Keep screen on while reading", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Separate from the read-aloud one in " +
                                    "Text-to-Speech settings - this covers " +
                                    "plain reading too.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = keepScreenOn, onCheckedChange = onKeepScreenOnToggle)
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
                            Text("Full-screen mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Hides the status and navigation bars too, " +
                                    "not just ARKarium's own top bar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = fullscreen, onCheckedChange = onFullscreenToggle)
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
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.BrightnessMedium,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column {
                                Text("Brightness slider in Reader Preferences", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Adds a brightness slider to the pill, " +
                                        "for this reading session only - it " +
                                        "doesn't change anything outside the " +
                                        "reader.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Switch(checked = brightnessSliderEnabled, onCheckedChange = onBrightnessSliderToggle)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
