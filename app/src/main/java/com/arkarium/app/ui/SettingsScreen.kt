package com.arkarium.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.BuildConfig

// Small uppercase, letter-spaced label used above each grouped card
// (PREFERENCES / LEGAL / ABOUT) so the index page reads as sections instead
// of one long undifferentiated list - the same "group related settings"
// idea most platform settings apps use, just without pulling in a whole new
// library for it.
@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

// A round, tinted "badge" behind each row's leading icon. Circular icon
// badges like this are what turn a settings row from "a line of text with a
// glyph in front of it" into something that actually looks designed - the
// tinted circle gives the icon room to breathe and a splash of the app's
// primary color instead of a flat monochrome glyph sitting directly on the
// row background.
@Composable
private fun SettingsIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp)
        )
    }
}

// A settings row that lives inside one of the grouped cards below: icon
// badge, title, optional one-line subtitle, chevron. `showDivider` draws the
// hairline that separates it from the next row in the same card - omitted
// after the last row in a card so the card's own rounded bottom edge is the
// visual stop instead of a divider running right up against it.
@Composable
private fun SettingsCardRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    showDivider: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconBadge(icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (showDivider) {
            Divider(
                modifier = Modifier.padding(start = 62.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
        }
    }
}

// One rounded, tinted card wrapping a whole section's rows - the same
// "surfaceVariant Card" idiom AuthorPageScreen's AuthorInfoCard already uses
// elsewhere in the app, reused here so grouped settings feel consistent with
// the rest of ARKarium's visual language rather than inventing a new style.
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            content()
        }
    }
}

// Stage 1 of docs/arkarium/SETTINGS_REDESIGN.md: this screen stops rendering
// any control itself (Theme radio group, Library switch/buttons, Splash
// switches all used to live here inline) and becomes a pure index of rows,
// each navigating to its own destination - the same tappable-row pattern
// Privacy Policy/Terms/About Me already used, just applied to all seven
// entries instead of three. The actual Theme/Library/Splash controls still
// exist verbatim in MainActivity's "settings" call site for one more stage;
// they move into settings/theme, settings/library, settings/splash's own
// screens in Stage 2, which is also when this screen's callback params
// below get threaded one level deeper instead of terminating here.
//
// "Extra stage" beautification pass: every row on this index, including
// About Me, now renders inside an icon-badged, section-labeled card
// (SettingsCard/SettingsCardRow above) instead of as bare text - purely
// visual, no navigation or callback wiring changed. The "ignore About Me"
// direction from the original pass was about not reskinning the WebView
// destination page that opens the author's portfolio site (that page really
// is just a dumb WebView wrapper and doesn't need styling) - it was never
// about leaving this index row looking inconsistent with the rows around
// it, so About Me now matches Theme/Library/Splash/TTS/Privacy/Terms here.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onThemeClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onSplashClick: () -> Unit,
    onTtsClick: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onTermsAndConditions: () -> Unit,
    onAboutMe: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SettingsSectionLabel("Preferences")
            SettingsCard {
                SettingsCardRow(
                    icon = Icons.Filled.Palette,
                    title = "Theme",
                    subtitle = "Light, dark, warm paper, or match the system",
                    onClick = onThemeClick,
                    showDivider = true
                )
                SettingsCardRow(
                    icon = Icons.Filled.LibraryBooks,
                    title = "Library",
                    subtitle = "Where ARKarium reads your novels from",
                    onClick = onLibraryClick,
                    showDivider = true
                )
                SettingsCardRow(
                    icon = Icons.Filled.Bolt,
                    title = "Splash Screen",
                    subtitle = "Launch animation and its music",
                    onClick = onSplashClick,
                    showDivider = true
                )
                SettingsCardRow(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "Text-to-Speech",
                    subtitle = "Default rate, pitch, and read-aloud behavior",
                    onClick = onTtsClick,
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsSectionLabel("Legal")
            SettingsCard {
                SettingsCardRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = "Privacy Policy",
                    onClick = onPrivacyPolicy,
                    showDivider = true
                )
                SettingsCardRow(
                    icon = Icons.Filled.Gavel,
                    title = "Terms & Conditions",
                    onClick = onTermsAndConditions,
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsSectionLabel("About")
            SettingsCard {
                // Opens the author's site in-app via WebViewScreen (see MainActivity's
                // Screen.AboutMe case) rather than sending the reader out to a browser.
                // That destination page is left as a plain WebView wrapper - no reason
                // to style it, it just displays the portfolio site - but the row here
                // on the index now matches every other row's card/icon-badge look.
                SettingsCardRow(
                    icon = Icons.Filled.Person,
                    title = "About Me",
                    onClick = onAboutMe,
                    showDivider = false
                )
            }

            Divider(modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))

            // BuildConfig.VERSION_NAME is generated from app/build.gradle.kts'
            // defaultConfig.versionName - the single source of truth for the app's
            // version. This is the only place in the running app's normal UI that
            // shows it; the crash screens in MainActivity read the same field. Stays
            // on the index page per SETTINGS_REDESIGN.md §1 - it's not really "a
            // setting," so it doesn't get promoted to its own sub-page.
            Text(
                "ARKarium v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
