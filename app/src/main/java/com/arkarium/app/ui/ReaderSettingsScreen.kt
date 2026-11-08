package com.arkarium.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkarium.app.data.ReadingModeDefault

// docs/arkarium/proposal/reader-settings.md, "Page shape" + suggested-order
// items 1-5 (the "cheap" batch the owner approved; multi-document, the
// day/night button, the volume slider and column count stay
// documented-only - see that doc's own order list). Same flat-
// PreferencesManager-keys, no-ViewModel shape as TtsSettingsScreen - see
// that screen's own doc comment and design-notes.md's "Settings is an
// index; each option is its own page".
//
// This page is the one and only place the "set and forget" reader settings are
// changed - font family, brightness (opt-in + level), keep-screen-on and
// fullscreen. Size/spacing/mode and read-aloud speed are deliberately NOT here:
// those are adjusted live, in-session, from the in-reader "Reader Preferences"
// pill's own panel (see ReaderScreen's showPreferences), which never writes back
// to this page - this page only sets the *default* each new reading session
// seeds those from. So pill and page stay two separate surfaces with no option
// ever shown on both. Font family is a tappable row that opens a card of font
// options.
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
    // On by default, like PreferencesManager's own fallback (readerBreakLinesOf).
    breakLines: Boolean = true,
    brightnessSliderEnabled: Boolean = false,
    // -1f = follow the system; only applied while brightnessSliderEnabled is on.
    brightness: Float = -1f,
    onFontSizeChange: (Float) -> Unit = {},
    onLineHeightChange: (Float) -> Unit = {},
    onReadingModeChange: (ReadingModeDefault?) -> Unit = {},
    onFontFamilyChange: (ReaderFontFamily) -> Unit = {},
    onKeepScreenOnToggle: (Boolean) -> Unit = {},
    onFullscreenToggle: (Boolean) -> Unit = {},
    onBreakLinesToggle: (Boolean) -> Unit = {},
    onBrightnessSliderToggle: (Boolean) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
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

        var fontPickerOpen by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SettingsHeaderBadge(
                icon = Icons.Filled.AutoStories,
                caption = "Defaults for the chapter reader - text size, " +
                    "spacing, mode, and how the screen behaves while " +
                    "you're reading."
            )

            // Font family - opens a card of options, see FontPickerDialog.
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
                        "Used everywhere you read. Tap to see every option.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    // One tappable row showing the current choice in its own face;
                    // tapping it opens the card of every font (FontPickerDialog below).
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fontPickerOpen = true },
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                fontFamily.displayName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = resolveFontFamily(fontFamily),
                                    letterSpacing = extraLetterSpacing(fontFamily)
                                )
                            )
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = "Choose font",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (fontPickerOpen) {
                FontPickerDialog(
                    selected = fontFamily,
                    onSelect = {
                        onFontFamilyChange(it)
                        fontPickerOpen = false
                    },
                    onDismiss = { fontPickerOpen = false }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Defaults the reader seeds its text size, spacing and mode from when a
            // chapter opens.
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

            // How chapter text is read, not how the screen behaves: whether a bare
            // "***" / "---" / "###" line becomes a chapter-break divider.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text("Detect chapter breaks", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "A line of one character three times - ***, ---, ### - " +
                                "shows as a divider. Explicit <chapter break> tags " +
                                "always work, whatever this says.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(checked = breakLines, onCheckedChange = onBreakLinesToggle)
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
                                Text("Set brightness while reading", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Overrides screen brightness only while a " +
                                        "chapter is open - nothing outside the " +
                                        "reader changes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Switch(checked = brightnessSliderEnabled, onCheckedChange = onBrightnessSliderToggle)
                    }

                    if (brightnessSliderEnabled) {
                        // -1f (never set) shows mid-way; the first drag stores a real level.
                        Slider(
                            value = if (brightness < 0f) 0.5f else brightness,
                            onValueChange = onBrightnessChange,
                            valueRange = 0.02f..1f,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

// The card of font options the Font family row opens: every ReaderFontFamily, each
// named in its own face with a line of sample text, the current one ticked. Picking one
// applies and closes it. Scrolls, so adding more families later needs no layout change.
@Composable
private fun FontPickerDialog(
    selected: ReaderFontFamily,
    onSelect: (ReaderFontFamily) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    "Font",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    ReaderFontFamily.values().forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(choice) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    choice.displayName,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = resolveFontFamily(choice),
                                        letterSpacing = extraLetterSpacing(choice)
                                    ),
                                    color = if (choice == selected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "The quick brown fox jumps over the lazy dog",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = resolveFontFamily(choice),
                                        letterSpacing = extraLetterSpacing(choice)
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                            }
                            if (choice == selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, end = 12.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
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
