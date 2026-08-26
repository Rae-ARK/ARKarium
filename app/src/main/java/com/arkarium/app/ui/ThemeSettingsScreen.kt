package com.arkarium.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkarium.app.data.Theme
import com.arkarium.app.ui.theme.colorSchemeFor

// Stage 2 (docs/arkarium/SETTINGS_REDESIGN.md, "mechanical extraction, no
// behavior change") - the Theme radio group (plus its System Default
// "daytime look" sub-choice) moves here verbatim from the old monolithic
// SettingsScreen.kt, unchanged apart from the params now terminating on
// this screen instead of the index. MainActivity's "settings/theme"
// composable wires currentTheme/systemDefaultLightVariant and the two
// setter callbacks the same way its old "settings" composable did.
//
// Beautification pass: the plain RadioButton + Text rows are replaced with
// selectable swatch cards that show each theme's *actual* primary/background
// colors (pulled straight from AppTheme.colorSchemeFor, so a palette tweak
// there automatically shows up here too) instead of asking the reader to
// picture "Warm Paper" from its name alone. Selection is still a single tap
// on the whole card; a checkmark badge replaces the RadioButton dot. No
// callback or parameter changed - onThemeSelected/onSystemDefaultLightVariantSelected
// fire from exactly the same places they always did.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    currentTheme: Theme,
    // Only consulted while currentTheme == Theme.SYSTEM_DEFAULT - which of the two
    // non-dark themes System Default should behave as during the day (it always
    // behaves as Dark at night regardless of this choice). See PreferencesManager's
    // systemDefaultLightVariant doc comment.
    systemDefaultLightVariant: Theme = Theme.LIGHT,
    onThemeSelected: (Theme) -> Unit,
    onSystemDefaultLightVariantSelected: (Theme) -> Unit = {},
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Theme") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SettingsHeaderBadge(
                icon = Icons.Filled.Palette,
                caption = "Pick a look for the whole app. System Default always " +
                    "switches to Dark at night."
            )

            listOf(Theme.LIGHT, Theme.DARK, Theme.WARM_PAPER, Theme.SYSTEM_DEFAULT).forEach { theme ->
                ThemeSwatchCard(
                    theme = theme,
                    selected = currentTheme == theme,
                    onClick = { onThemeSelected(theme) }
                )
                Spacer(modifier = Modifier.height(10.dp))

                // System Default's own daytime-look sub-choice - indented under its
                // parent option, same "reveal on select" pattern useCustomFolder's
                // Select/Change Folder button (now on settings/library) uses. Only
                // shown right under SYSTEM_DEFAULT's own row (not e.g. after every
                // row) so it reads as belonging to that one option rather than
                // floating loose.
                if (theme == Theme.SYSTEM_DEFAULT && currentTheme == Theme.SYSTEM_DEFAULT) {
                    Column(modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)) {
                        Text(
                            "Daytime look",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        listOf(Theme.LIGHT, Theme.WARM_PAPER).forEach { variant ->
                            ThemeSwatchCard(
                                theme = variant,
                                selected = systemDefaultLightVariant == variant,
                                compact = true,
                                onClick = { onSystemDefaultLightVariantSelected(variant) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

// Shared "icon badge + one line of context" header used at the top of each
// settings sub-page (Theme/Library/Splash/TTS) in this beautification pass -
// gives every page the same large tinted-circle-plus-caption opener instead
// of dropping straight into controls with no visual anchor.
@Composable
internal fun SettingsHeaderBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    caption: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}

// One theme option: a swatch pair (background + primary, taken from that
// theme's real colorSchemeFor()) so the reader can see the palette before
// committing to it, the theme's display name, and a checkmark when selected.
// `compact` shrinks the swatch/padding a touch for the nested "Daytime look"
// sub-choice so it doesn't compete visually with the four top-level options.
@Composable
private fun ThemeSwatchCard(
    theme: Theme,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    // SYSTEM_DEFAULT has no single palette of its own (it borrows Dark at night
    // and the chosen daytime variant otherwise - see AppTheme.resolveTheme), so
    // scheme stays null for it; the disc+badge swatch below falls back to a
    // light/dark stand-in pair in that case instead of a real palette.
    val scheme = if (theme == Theme.SYSTEM_DEFAULT) null else colorSchemeFor(theme)
    val swatchSize = if (compact) 30.dp else 36.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Two-circle "swatch chip": the full background color as the base
            // disc, with a smaller primary-color disc badged onto its bottom-right
            // corner (a thin surfaceVariant ring keeps the badge readable against
            // backgrounds close in tone to primary, e.g. Dark). SYSTEM_DEFAULT has
            // no single palette of its own (see AppTheme.resolveTheme), so it gets
            // a light-disc-with-dark-badge stand-in instead, hinting at "switches
            // between a light and a dark look" rather than implying one fixed
            // palette.
            val badgeSize = swatchSize * 0.52f
            Box(modifier = Modifier.size(swatchSize)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme?.background ?: Color(0xFFF5F5F5), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(badgeSize)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(scheme?.primary ?: Color(0xFF1B1B1B), CircleShape)
                    )
                }
            }

            Text(
                // Title-cased ("Warm Paper") rather than the enum's own ALL CAPS
                // ("WARM_PAPER") - a small readability touch now that the name is
                // sitting next to a swatch instead of a bare RadioButton.
                theme.name.split("_").joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { it.uppercase() }
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            )

            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
