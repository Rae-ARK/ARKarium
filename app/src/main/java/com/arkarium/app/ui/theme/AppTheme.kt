package com.arkarium.app.ui.theme

// Extracted from MainActivity.kt as part of the MainActivity slim-down (see
// docs/foundational/design-notes.md). Pure color-scheme lookup/resolution - no Compose
// state, no Context - so it's usable (and unit-testable, see resolveTheme below) on
// its own without pulling in the Activity.

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.arkarium.app.data.Theme

// All three schemes below are calibrated with Google's own Material Color Utilities
// algorithm (the HCT color space + SchemeTonalSpot - the same math lightColorScheme()/
// darkColorScheme()'s *own* Material-You baseline is built from), run against real seed
// colors instead of relying on those two functions' stock seed, which is a fixed violet
// (#6750A4) - that's the "white with a purple gradient" look that prompted this pass.
// Every role is derived from one seed per scheme rather than hand-picked, so nothing
// here clashes or (as warmPaperColorScheme used to, before this pass) silently falls
// back to that stock violet for a role nobody got around to overriding.
//
// Brand seed is a deep teal (#2B6F77) - away from the purple hue family entirely - shared
// by Light and Dark so they read as one identity rather than two unrelated palettes.
fun lightColorSchemeApp() = lightColorScheme(
    primary = Color(0xFF1B6871),
    onPrimary = Color(0xFFEAFCFF),
    primaryContainer = Color(0xFFA9EEF7),
    onPrimaryContainer = Color(0xFF005B63),
    secondary = Color(0xFF4B6367),
    onSecondary = Color(0xFFEAFCFF),
    secondaryContainer = Color(0xFFCDE7EB),
    onSecondaryContainer = Color(0xFF3D5659),
    tertiary = Color(0xFF406280),
    onTertiary = Color(0xFFF6F9FF),
    tertiaryContainer = Color(0xFFB8DBFE),
    onTertiaryContainer = Color(0xFF2B4E6B),
    error = Color(0xFFA83836),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFA746F),
    onErrorContainer = Color(0xFF6E0A12),
    background = Color(0xFFF6FAFA),
    onBackground = Color(0xFF2A3436),
    surface = Color(0xFFF6FAFA),
    onSurface = Color(0xFF2A3436),
    surfaceVariant = Color(0xFFDAE4E6),
    onSurfaceVariant = Color(0xFF576162),
    outline = Color(0xFF727D7E),
    outlineVariant = Color(0xFFA9B4B5),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF0A0F10),
    inverseOnSurface = Color(0xFF999E9E),
    inversePrimary = Color(0xFFAEF4FD),
    surfaceTint = Color(0xFF1B6871)
)

fun darkColorSchemeApp() = darkColorScheme(
    primary = Color(0xFF9CCFD6),
    onPrimary = Color(0xFF0D474D),
    primaryContainer = Color(0xFF265A60),
    onPrimaryContainer = Color(0xFFB8ECF3),
    secondary = Color(0xFFB1CBCF),
    onSecondary = Color(0xFF2C4447),
    secondaryContainer = Color(0xFF273F43),
    onSecondaryContainer = Color(0xFFAAC4C8),
    tertiary = Color(0xFFD3E8FF),
    onTertiary = Color(0xFF345674),
    tertiaryContainer = Color(0xFFB8DBFE),
    onTertiaryContainer = Color(0xFF2B4E6B),
    error = Color(0xFFFA746F),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFF9993),
    background = Color(0xFF0A0F10),
    onBackground = Color(0xFFDDE7E9),
    surface = Color(0xFF0A0F10),
    onSurface = Color(0xFFDDE7E9),
    surfaceVariant = Color(0xFF1E2729),
    onSurfaceVariant = Color(0xFFA2ADAE),
    outline = Color(0xFF6D7779),
    outlineVariant = Color(0xFF404A4B),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF6FAFA),
    inverseOnSurface = Color(0xFF515657),
    inversePrimary = Color(0xFF34666D),
    surfaceTint = Color(0xFF9CCFD6)
)

// Warm, sepia-toned reading theme - lower contrast than pure light/dark, meant to
// be easier on the eyes for long reading sessions (a common e-reader "paper" mode).
//
// Primary/secondary/tertiary/error are TonalSpot-derived from the same warm brown
// (#8B5E34) the app already used for primary, so the brand still reads as itself in
// paper mode. background/surface/surfaceVariant/onBackground/onSurface/onSurfaceVariant
// keep this app's existing hand-tuned cream/sepia neutrals (a slightly warmer, higher-
// chroma hue (~95) than primary's own (~61) is what actually reads as "paper" rather
// than "pale primary tint", and HCT confirms the original pick already landed on
// well-spaced tones - 93.6/90.9/86.4 - so there was no reason to redo it). outline/
// outlineVariant are new: computed at that same neutral hue so they don't fall back to
// stock violet either, which - along with primaryContainer/onPrimaryContainer below -
// was the actual gap in the old warmPaperColorScheme.
fun warmPaperColorScheme() = lightColorScheme(
    primary = Color(0xFF845425),
    onPrimary = Color(0xFFFFF7F4),
    primaryContainer = Color(0xFFFDBC83),
    onPrimaryContainer = Color(0xFF62380A),
    secondary = Color(0xFF745A44),
    onSecondary = Color(0xFFFFF7F4),
    secondaryContainer = Color(0xFFFFDCC1),
    onSecondaryContainer = Color(0xFF664D37),
    tertiary = Color(0xFF63612B),
    onTertiary = Color(0xFFFFFBCC),
    tertiaryContainer = Color(0xFFFEFAB4),
    onTertiaryContainer = Color(0xFF625F29),
    error = Color(0xFFA73B21),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFD795A),
    onErrorContainer = Color(0xFF6E1400),
    background = Color(0xFFF5ECD9),
    onBackground = Color(0xFF3E2C1C),
    surface = Color(0xFFF0E4CB),
    onSurface = Color(0xFF3E2C1C),
    surfaceVariant = Color(0xFFE6D7B8),
    onSurfaceVariant = Color(0xFF4E3B26),
    outline = Color(0xFF736A53),
    outlineVariant = Color(0xFFD0C5AF),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF130D09),
    inverseOnSurface = Color(0xFFA69B93),
    inversePrimary = Color(0xFFBD8551),
    surfaceTint = Color(0xFF845425)
)

fun colorSchemeFor(theme: Theme) = when (theme) {
    Theme.LIGHT -> lightColorSchemeApp()
    Theme.DARK -> darkColorSchemeApp()
    Theme.WARM_PAPER -> warmPaperColorScheme()
    // Never actually reached - resolveTheme() below always substitutes SYSTEM_DEFAULT
    // for a concrete theme before this function sees it. Falling back to plain Light
    // here (rather than e.g. throwing) just means a future call site that forgets to
    // resolve first degrades gracefully instead of crashing.
    Theme.SYSTEM_DEFAULT -> lightColorSchemeApp()
}

// Substitutes System Default for whichever concrete theme it should currently behave
// as, given the system's day/night state - DARK at night, or `lightVariant` (the
// user's chosen LIGHT/WARM_PAPER daytime preference) during the day. Every other
// theme passes through unchanged. Pulled out as its own function (rather than inlined
// where colorSchemeFor is called) so the same resolution logic can't drift between
// call sites if a second one is ever added.
//
// Deliberately plain Kotlin (Theme in, Theme out, no Color/Compose types) so it can be
// unit-tested on the JVM with no Android framework or Compose runtime involved - see
// AppThemeTest.
fun resolveTheme(theme: Theme, lightVariant: Theme, systemInDarkTheme: Boolean): Theme =
    if (theme == Theme.SYSTEM_DEFAULT) {
        if (systemInDarkTheme) Theme.DARK else lightVariant
    } else {
        theme
    }
