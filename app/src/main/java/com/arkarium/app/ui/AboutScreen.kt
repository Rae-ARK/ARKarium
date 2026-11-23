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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkarium.app.BuildConfig
import com.arkarium.app.R

// Replaces Settings' old flat "Legal" + "About" section cards (Privacy
// Policy, Terms & Conditions, About Me each as their own index row) and the
// bare "ARKarium vX" text line that used to sit at the very bottom of that
// index. All of it - plus Acknowledgements, which didn't exist before - now
// lives on this one dedicated screen instead, in the same "big logo + name up
// top, then grouped info rows below" shape Android's own About Phone screen
// uses: a centered identity header first, then a plain label:value info
// card (nothing to tap), then a legal card (everything here IS tappable),
// then a pair of OS-support badges at the very bottom.
//
// Review pass fixes (see conversation that found these - no doc under docs/
// to point at, this is the only record of the "why"):
//  - Every user-facing string on this screen now comes from strings.xml
//    instead of being hardcoded inline - this is the one screen in the app
//    with enough repeated/reused copy (row titles echoed in content
//    descriptions, etc.) that letting two call sites drift out of sync with
//    each other was a real risk; see strings.xml's own doc comment on why
//    the rest of the app stays hardcoded for now.
//  - Both full-width section dividers used to be a plain default Divider(),
//    at full DividerDefaults color/opacity - visibly heavier than the subtle
//    8%-alpha hairlines this same screen already uses between rows inside a
//    card. Matched to that same subtle weight below so the whole screen reads
//    as one consistent "thin hairline" language instead of two.
//  - The "Legal" section label above Privacy Policy/Terms/Acknowledgements
//    is gone - those three rows are self-explanatory by their own icons and
//    titles, and every other section on this screen (the identity header,
//    the version/build/package card) gets by without a label of its own too.
//  - SettingsCardRow's ripple used to ripple a plain rectangle with no idea
//    it sat inside this card's rounded corners - the first/last row in each
//    card below now passes isFirstInCard/isLastInCard so that ripple is
//    clipped to follow the card's actual rounded shape instead of a flash of
//    square corners cutting across a round one (see SettingsCardRow's own
//    doc comment for the underlying fix).
//  - The OS-support line used to be two small bodySmall/onSurfaceVariant
//    caption lines - easy to miss sitting under the Legal card's much more
//    visually weighted rows (icon badges, filled card surface). Replaced
//    with a pair of small badges (the Android glyph, tinted primary, plus a
//    bold version number) carrying enough visual weight of their own not to
//    read as an afterthought.
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
            title = { Text(stringResource(R.string.about_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.about_back_content_description))
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
            AboutSectionDivider()
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
                AboutInfoRow(label = stringResource(R.string.about_version_label), value = BuildConfig.VERSION_NAME, showDivider = true)
                AboutInfoRow(label = stringResource(R.string.about_build_label), value = BuildConfig.VERSION_CODE.toString(), showDivider = true)
                AboutInfoRow(label = stringResource(R.string.about_package_label), value = BuildConfig.APPLICATION_ID, showDivider = true)
                SettingsCardRow(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.about_maintainer_row_title),
                    onClick = onMaintainerClick,
                    showDivider = false,
                    isLastInCard = true
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // No "Legal" section label above this card (there used to be one) -
            // Privacy Policy/Terms & Conditions/Acknowledgements are unambiguous
            // on sight from their own icon+title, the way every other row on this
            // screen already is without a section label of its own.
            SettingsCard {
                SettingsCardRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = stringResource(R.string.about_privacy_policy_row_title),
                    onClick = onPrivacyPolicyClick,
                    showDivider = true,
                    isFirstInCard = true
                )
                SettingsCardRow(
                    icon = Icons.Filled.Gavel,
                    title = stringResource(R.string.about_terms_row_title),
                    onClick = onTermsClick,
                    showDivider = true
                )
                // Stub for now (see AcknowledgementsScreen.kt) - tappable and
                // navigable like every other row here, just landing on a
                // placeholder until the actual open-source notices get written in.
                SettingsCardRow(
                    icon = Icons.Filled.Description,
                    title = stringResource(R.string.about_acknowledgements_row_title),
                    subtitle = stringResource(R.string.about_acknowledgements_row_subtitle),
                    onClick = onAcknowledgementsClick,
                    showDivider = false,
                    isLastInCard = true
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            AboutSectionDivider()
            Spacer(modifier = Modifier.height(20.dp))

            // Recommended/Minimum OS support, now a pair of small badges instead
            // of two plain caption lines - see this file's class-level doc
            // comment for why. Keep these two in sync by hand with
            // app/build.gradle.kts' defaultConfig.minSdk (24 = Android 7.0) and
            // .targetSdk (34 = Android 14) if either one is ever bumped - same
            // "no Gradle value to read at render time, kept in sync by hand"
            // tradeoff this screen's own Version/Build rows avoid by reading
            // BuildConfig directly, but minSdk/targetSdk aren't exposed there the
            // way versionName/versionCode are.
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                OsVersionBadge(
                    version = stringResource(R.string.about_os_recommended_version),
                    label = stringResource(R.string.about_os_recommended_label)
                )
                OsVersionBadge(
                    version = stringResource(R.string.about_os_minimum_version),
                    label = stringResource(R.string.about_os_minimum_label)
                )
            }
        }
    }
}

// Thin, subtle full-width separator - HorizontalDivider (Divider is
// deprecated) at the same low-opacity onSurface tint SettingsCardRow's own
// between-row hairlines already use, so the two section breaks on this
// screen read as the same weight as everything else here instead of a
// heavier, more visible line.
@Composable
private fun AboutSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
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
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
        }
    }
}

// One "Recommended"/"Minimum" OS-support badge: the Android glyph tinted the
// theme's primary color, a bold version number, then a small caption - gives
// this info its own visual anchor (icon + weight) instead of the plain gray
// caption-line treatment it used to get, which read as an afterthought next
// to the Legal card's much more visually weighted rows just above it.
@Composable
private fun OsVersionBadge(version: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Text(
            version,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
