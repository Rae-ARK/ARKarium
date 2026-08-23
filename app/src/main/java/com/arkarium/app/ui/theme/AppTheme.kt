package com.arkarium.app.ui.theme

// Extracted from MainActivity.kt as part of the MainActivity slim-down (see
// docs/arkarium/REFACTOR_PLAN.md). Pure color-scheme lookup/resolution - no Compose
// state, no Context - so it's usable (and unit-testable, see resolveTheme below) on
// its own without pulling in the Activity.

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.arkarium.app.data.Theme

// Warm, sepia-toned reading theme - lower contrast than pure light/dark, meant to
// be easier on the eyes for long reading sessions (a common e-reader "paper" mode).
//
// NOTE: primaryContainer/onPrimaryContainer are explicitly set here too. Every other
// role above overrides lightColorScheme()'s default, but these two were left out -
// so anything using them (e.g. HomeScreen's "Browse All Novels" card) fell back to
// Material3's stock light-purple/dark-purple pair, which clashes with this warm/sepia
// palette and is what made that button look inconsistent with the rest of the theme.
fun warmPaperColorScheme() = lightColorScheme(
    primary = Color(0xFF8B5E34),
    onPrimary = Color(0xFFFFFBF5),
    primaryContainer = Color(0xFFD9BE97),
    onPrimaryContainer = Color(0xFF3E2C1C),
    background = Color(0xFFF5ECD9),
    onBackground = Color(0xFF3E2C1C),
    surface = Color(0xFFF0E4CB),
    onSurface = Color(0xFF3E2C1C),
    surfaceVariant = Color(0xFFE6D7B8),
    onSurfaceVariant = Color(0xFF4E3B26)
)

fun colorSchemeFor(theme: Theme) = when (theme) {
    Theme.LIGHT -> lightColorScheme()
    Theme.DARK -> darkColorScheme()
    Theme.WARM_PAPER -> warmPaperColorScheme()
    // Never actually reached - resolveTheme() below always substitutes SYSTEM_DEFAULT
    // for a concrete theme before this function sees it. Falling back to plain Light
    // here (rather than e.g. throwing) just means a future call site that forgets to
    // resolve first degrades gracefully instead of crashing.
    Theme.SYSTEM_DEFAULT -> lightColorScheme()
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
