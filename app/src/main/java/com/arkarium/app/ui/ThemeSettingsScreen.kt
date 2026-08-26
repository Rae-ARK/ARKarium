package com.arkarium.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkarium.app.data.Theme

// Stage 2 (docs/arkarium/SETTINGS_REDESIGN.md, "mechanical extraction, no
// behavior change") - the Theme radio group (plus its System Default
// "daytime look" sub-choice) moves here verbatim from the old monolithic
// SettingsScreen.kt, unchanged apart from the params now terminating on
// this screen instead of the index. MainActivity's "settings/theme"
// composable wires currentTheme/systemDefaultLightVariant and the two
// setter callbacks the same way its old "settings" composable did.
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
            listOf(Theme.LIGHT, Theme.DARK, Theme.WARM_PAPER, Theme.SYSTEM_DEFAULT).forEach { theme ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = currentTheme == theme,
                        onClick = { onThemeSelected(theme) }
                    )
                    Text(
                        theme.name.replace("_", " "),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }

                // System Default's own daytime-look sub-choice - indented under its
                // parent option, same "reveal on select" pattern useCustomFolder's
                // Select/Change Folder button (now on settings/library) uses. Only
                // shown right under SYSTEM_DEFAULT's own row (not e.g. after every
                // row) so it reads as belonging to that one option rather than
                // floating loose.
                if (theme == Theme.SYSTEM_DEFAULT && currentTheme == Theme.SYSTEM_DEFAULT) {
                    Column(modifier = Modifier.padding(start = 40.dp, bottom = 4.dp)) {
                        Text(
                            "Daytime look",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        listOf(Theme.LIGHT, Theme.WARM_PAPER).forEach { variant ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                RadioButton(
                                    selected = systemDefaultLightVariant == variant,
                                    onClick = { onSystemDefaultLightVariantSelected(variant) }
                                )
                                Text(
                                    variant.name.replace("_", " "),
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
