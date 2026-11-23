package com.arkarium.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkarium.app.BuildConfig

// Replaces Settings' old flat "Legal" + "About" section cards (Privacy
// Policy, Terms & Conditions, About Me each as their own index row) and the
// bare "ARKarium vX" text line that used to sit at the very bottom of that
// index. All of it - plus Acknowledgements, which didn't exist before - now
// lives on this one dedicated screen instead, in the same "big logo + name up
// top, then grouped info rows below" shape Android's own About Phone screen
// uses: a centered identity header first, then a plain label:value info
// card (nothing to tap), then a legal card (everything here IS tappable),
// then a plain supported-OS line at the very bottom.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onMaintainerClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onTermsClick: () -> Unit,
    onAcknowledgementsClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("About") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Identity header: the same "A+R" mark HomeScreen's top bar shows
            // small and inline, just much bigger here (65% of the screen's width,
            // not full-bleed - a full-width logo on a tall phone reads as a splash
            // screen, not an about page) since this is the one screen whose whole
            // point is "this is what ARKarium is". ic source image is a square
            // (black card + white mark, see LogoPoints.kt's doc comment), hence
            // aspectRatio(1f) rather than letting Coil guess one from load.
            AsyncImage(
                model = "file:///android_asset/app-icon-source.png",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(30))
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "ARKarium",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))
            Divider(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(20.dp))

            // Version/build/package: plain label:value rows, deliberately NOT
            // styled as SettingsCardRow (no icon badge, no chevron) since there's
            // nothing to tap - same "info sits right alongside Legal information"
            // idea Android's own About Phone page uses, rather than forcing a
            // separate card per section the way Settings' index does for
            // Preferences vs About. "About Rae ARK" (ex-"About Me", the author's
            // own portfolio site opened in-app via WebViewScreen - see
            // AppNavGraph's "about_me" route) closes out this card: it's "about
            // who built this", not a legal document, so it belongs here rather
            // than in the Legal card below.
            SettingsCard {
                AboutInfoRow(label = "Version", value = BuildConfig.VERSION_NAME, showDivider = true)
                AboutInfoRow(label = "Build", value = BuildConfig.VERSION_CODE.toString(), showDivider = true)
                AboutInfoRow(label = "Package", value = BuildConfig.APPLICATION_ID, showDivider = true)
                SettingsCardRow(
                    icon = Icons.Filled.Person,
                    title = "About Rae ARK",
                    onClick = onMaintainerClick,
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            SettingsSectionLabel("Legal")
            SettingsCard {
                SettingsCardRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = "Privacy Policy",
                    onClick = onPrivacyPolicyClick,
                    showDivider = true
                )
                SettingsCardRow(
                    icon = Icons.Filled.Gavel,
                    title = "Terms & Conditions",
                    onClick = onTermsClick,
                    showDivider = true
                )
                // Stub for now (see AcknowledgementsScreen.kt) - tappable and
                // navigable like every other row here, just landing on a
                // placeholder until the actual open-source notices get written in.
                SettingsCardRow(
                    icon = Icons.Filled.Description,
                    title = "Acknowledgements",
                    subtitle = "Coming soon",
                    onClick = onAcknowledgementsClick,
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))

            // Plain OS-support line - Android version names only, no "API 34"
            // phrasing, since that's a developer-facing detail a reader opening
            // an About page doesn't need. Keep these two in sync by hand with
            // app/build.gradle.kts' defaultConfig.minSdk (24 = Android 7.0) and
            // .targetSdk (34 = Android 14) if either one is ever bumped - same
            // "no Gradle value to read at render time, kept in sync by hand"
            // tradeoff this screen's own Version/Build rows avoid by reading
            // BuildConfig directly, but minSdk/targetSdk aren't exposed there the
            // way versionName/versionCode are.
            Text(
                "Recommended: Android 14 or higher",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Minimum: Android 7.0 or higher",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// A static label/value row for info that isn't navigable (Version, Build,
// Package) - same horizontal padding as SettingsCardRow so it lines up with
// the tappable "About Rae ARK" row sharing its card, just without the icon
// badge or chevron, since there's nowhere to tap and nothing to badge.
@Composable
private fun AboutInfoRow(label: String, value: String, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showDivider) {
            Divider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
        }
    }
}
