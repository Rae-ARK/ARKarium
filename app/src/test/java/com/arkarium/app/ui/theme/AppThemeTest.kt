package com.arkarium.app.ui.theme

import com.arkarium.app.data.Theme
import org.junit.Assert.assertEquals
import org.junit.Test

// resolveTheme is deliberately plain Kotlin (see its doc comment in AppTheme.kt) so it
// can run here on the JVM with plain JUnit - no Robolectric, no instrumented device/
// emulator. This is a template for pulling more of MainActivity's decision logic out
// into small, directly-testable functions rather than needing a full Activity/Compose
// harness for it (see docs/arkarium/REFACTOR_PLAN.md).
class AppThemeTest {

    @Test
    fun `non-SYSTEM_DEFAULT themes pass through unchanged regardless of day-night state`() {
        assertEquals(Theme.LIGHT, resolveTheme(Theme.LIGHT, lightVariant = Theme.WARM_PAPER, systemInDarkTheme = true))
        assertEquals(Theme.DARK, resolveTheme(Theme.DARK, lightVariant = Theme.WARM_PAPER, systemInDarkTheme = false))
        assertEquals(
            Theme.WARM_PAPER,
            resolveTheme(Theme.WARM_PAPER, lightVariant = Theme.LIGHT, systemInDarkTheme = true)
        )
    }

    @Test
    fun `SYSTEM_DEFAULT resolves to DARK when the system is in dark theme`() {
        assertEquals(
            Theme.DARK,
            resolveTheme(Theme.SYSTEM_DEFAULT, lightVariant = Theme.WARM_PAPER, systemInDarkTheme = true)
        )
    }

    @Test
    fun `SYSTEM_DEFAULT resolves to the chosen light variant when the system is in light theme`() {
        assertEquals(
            Theme.WARM_PAPER,
            resolveTheme(Theme.SYSTEM_DEFAULT, lightVariant = Theme.WARM_PAPER, systemInDarkTheme = false)
        )
        assertEquals(
            Theme.LIGHT,
            resolveTheme(Theme.SYSTEM_DEFAULT, lightVariant = Theme.LIGHT, systemInDarkTheme = false)
        )
    }
}
