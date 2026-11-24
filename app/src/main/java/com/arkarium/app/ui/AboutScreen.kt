package com.arkarium.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
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
// group (nothing to tap), then a legal group (everything here IS tappable),
// then an OS-support row at the very bottom.
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
//
// Second review pass (same "no doc under docs/" situation as above):
//  - The version/build/package rows and the Privacy Policy/Terms/
//    Acknowledgements rows used to each sit inside a SettingsCard: a
//    surfaceVariant-tinted, rounded, slightly elevated container, same as
//    SettingsScreen's own index cards. On a dedicated single-purpose screen
//    (as opposed to that index, which groups several unrelated sections and
//    genuinely benefits from a tinted boundary per group) that container
//    read as an unexplained highlight - two random-looking panels sitting
//    proud of the screen's own background for no reason tied to anything
//    else on the page. Both are now plain Columns: same rows, same icon
//    badges, same hairline dividers between rows, just resting directly on
//    the screen background instead of inside a tinted card. (SettingsCard/
//    SettingsCardRow themselves are untouched - SettingsScreen's index still
//    uses them exactly as before; this screen just stopped calling
//    SettingsCard and stopped passing isFirstInCard/isLastInCard, since
//    there's no longer a rounded card edge for the ripple to be clipped to.)
//  - The OS-support line used to be a pair of small badges (Android glyph
//    stacked above a bold version number above a small caption) spaced far
//    enough apart to leave the middle of the row empty. Two problems with
//    that: it wasted the width every other row on this screen uses in full,
//    and an icon sitting above a label, repeated twice at a wide fixed gap,
//    is the exact "icon-over-label destination" shape a bottom navigation
//    bar uses - close enough that it read as a stray nav bar rather than
//    plain information. Replaced with one full-width row, split in half by
//    a thin vertical rule, each half centered and using Modifier.weight(1f)
//    so the two sides actually fill the row instead of huddling in the
//    middle. Each half also now names the Android version's codename (7.0
//    is Nougat, 14 is Upside Down Cake) under the version number - omitting
//    those when both versions happen to be ones Android actually gave a
//    public codename to was leaving an easy, on-brand detail on the table.
// LocalOverscrollConfiguration (used below to turn off the stretch overscroll
// on this screen's scroll container) is gated behind ExperimentalFoundationApi -
// opted into here alongside the existing Material3 opt-in rather than suppressed
// at the module level, since it's the one API on this screen that needs it.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

        // Android 12+'s stretch overscroll (Compose's verticalScroll opts every
        // scrollable into it by default via LocalOverscrollConfiguration) warps
        // the whole scrolled content - squishing and stretching it along the
        // scroll axis - whenever a drag goes past the content's natural bounds,
        // which on a page this short happens on almost every scroll gesture, not
        // just a deliberate overscroll at the very top/bottom. On a screen that's
        // mostly text that stretch is subtle enough to miss; stretching a crisp
        // square logo image is not, and reads as wobbly/jelly-like rather than a
        // fling. `null` here opts this screen's scroll container (and the whole
        // page scrolling within it, logo included) out of that stretch, leaving
        // a plain non-rubbery scroll with no change to drag/fling feel otherwise.
        // (androidx.compose:compose-bom:2024.09.00 pins Foundation ~1.7, where
        // this is deprecated-but-functional; Foundation 1.8+ removes it in favor
        // of passing an `overscrollEffect` directly to `Modifier.verticalScroll`,
        // worth switching to if/when this project's BOM is bumped past that.)
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
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
                // AppNavGraph's "about_me" route) closes out this group: it's "about
                // who built this", not a legal document, so it belongs here rather
                // than in the Legal group below.
                //
                // Plain Column, not SettingsCard - see this file's class-level doc
                // comment (second review pass) for why this group no longer sits
                // inside a tinted, rounded container.
                Column(modifier = Modifier.fillMaxWidth()) {
                    AboutInfoRow(label = stringResource(R.string.about_version_label), value = BuildConfig.VERSION_NAME, showDivider = true)
                    AboutInfoRow(label = stringResource(R.string.about_build_label), value = BuildConfig.VERSION_CODE.toString(), showDivider = true)
                    AboutInfoRow(label = stringResource(R.string.about_package_label), value = BuildConfig.APPLICATION_ID, showDivider = true)
                    SettingsCardRow(
                        icon = Icons.Filled.Person,
                        title = stringResource(R.string.about_maintainer_row_title),
                        onClick = onMaintainerClick,
                        showDivider = false
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // No "Legal" section label above this group (there used to be one) -
                // Privacy Policy/Terms & Conditions/Acknowledgements are unambiguous
                // on sight from their own icon+title, the way every other row on this
                // screen already is without a section label of its own.
                //
                // Plain Column, not SettingsCard - see this file's class-level doc
                // comment (second review pass) for why this group no longer sits
                // inside a tinted, rounded container.
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsCardRow(
                        icon = Icons.Filled.PrivacyTip,
                        title = stringResource(R.string.about_privacy_policy_row_title),
                        onClick = onPrivacyPolicyClick,
                        showDivider = true
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
                        showDivider = false
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                AboutSectionDivider()
                Spacer(modifier = Modifier.height(20.dp))

                // Recommended/Minimum OS support, now one full-width row split in half
                // by a thin vertical rule instead of two badges huddled in the middle
                // of the row with empty space either side - see this file's
                // class-level doc comment (second review pass) for why. Keep these
                // two, and their codenames, in sync by hand with
                // app/build.gradle.kts' defaultConfig.minSdk (24 = Android 7.0,
                // "Nougat") and .targetSdk (34 = Android 14, "Upside Down Cake") if
                // any of them is ever bumped - same "no Gradle value to read at
                // render time, kept in sync by hand" tradeoff this screen's own
                // Version/Build rows avoid by reading BuildConfig directly, but
                // minSdk/targetSdk aren't exposed there the way versionName/
                // versionCode are.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OsSupportColumn(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.about_os_minimum_label),
                        version = stringResource(R.string.about_os_minimum_version),
                        codename = stringResource(R.string.about_os_minimum_codename)
                    )
                    OsSupportDivider()
                    OsSupportColumn(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.about_os_recommended_label),
                        version = stringResource(R.string.about_os_recommended_version),
                        codename = stringResource(R.string.about_os_recommended_codename)
                    )
                }
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

// Half of the OS-support row: a small Android glyph inline with its caption
// ("MINIMUM"/"RECOMMENDED") rather than a large glyph sitting by itself above
// it - that bigger icon-over-label shape, repeated twice, is what made the
// old pair of badges read as bottom-navigation-bar destinations rather than
// plain info. Below the caption, the version number stays the same bold
// weight it had before; the codename is new, one step down in weight so the
// version number it belongs to still reads first.
@Composable
private fun OsSupportColumn(modifier: Modifier = Modifier, label: String, version: String, codename: String) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            version,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            codename,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Thin vertical rule splitting the OS-support row in half - same 8%-alpha
// onSurface tint AboutSectionDivider and the between-row hairlines already
// use elsewhere on this screen, just drawn as one vertical dp-wide line
// instead of a full-width horizontal one. A plain Box rather than Material3's
// VerticalDivider so this doesn't depend on a specific material3 version
// having added it.
@Composable
private fun OsSupportDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(40.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    )
}
