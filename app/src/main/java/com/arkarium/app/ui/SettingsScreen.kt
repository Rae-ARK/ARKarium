package com.arkarium.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkarium.app.BuildConfig

// A single tappable "goes to its own page" settings row - used for every entry
// on this screen now (Theme/Library/Splash/TTS as of Stage 1, see
// docs/arkarium/SETTINGS_REDESIGN.md; Privacy Policy/Terms/About Me already
// worked this way before that doc existed).
@Composable
private fun LegalRow(label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Text(label)
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// Stage 1 of docs/arkarium/SETTINGS_REDESIGN.md: this screen stops rendering
// any control itself (Theme radio group, Library switch/buttons, Splash
// switches all used to live here inline) and becomes a pure index of rows,
// each navigating to its own destination - the same LegalRow pattern Privacy
// Policy/Terms/About Me already used, just applied to all seven entries
// instead of three. The actual Theme/Library/Splash controls still exist
// verbatim in MainActivity's "settings" call site for one more stage; they
// move into settings/theme, settings/library, settings/splash's own screens
// in Stage 2, which is also when this screen's callback params below get
// threaded one level deeper instead of terminating here.
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
            LegalRow(label = "Theme", onClick = onThemeClick)
            LegalRow(label = "Library", onClick = onLibraryClick)
            LegalRow(label = "Splash Screen", onClick = onSplashClick)
            LegalRow(label = "Text-to-Speech", onClick = onTtsClick)

            Divider(modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))

            LegalRow(label = "Privacy Policy", onClick = onPrivacyPolicy)
            LegalRow(label = "Terms & Conditions", onClick = onTermsAndConditions)

            Divider(modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))

            // Opens the author's site in-app via WebViewScreen (see MainActivity's
            // Screen.AboutMe case) rather than sending the reader out to a browser.
            LegalRow(label = "About Me", onClick = onAboutMe)

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
