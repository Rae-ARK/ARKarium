package com.arkarium.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkarium.app.BuildConfig
import com.arkarium.app.data.Theme

// A single tappable "goes to its own page" settings row - used for Privacy
// Policy / Terms & Conditions, both of which just navigate to a
// LegalDocumentScreen rather than doing anything inline on this screen.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: Theme,
    // Whether the scanner reads from a user-picked SAF folder (true) or the app's own
    // private storage (false, the default - see MainActivity.resolveLibraryRoot). This
    // replaces the old `hasLibrary` flag: the library now always "exists" in the default
    // case (there's always a folder to scan), so the meaningful question isn't "is a
    // library configured" anymore, it's "which source is active."
    useCustomFolder: Boolean,
    // Whether a custom folder has actually been picked yet - only consulted while
    // useCustomFolder is true, to decide between "Select Folder" and "Change Folder".
    hasCustomFolderSelected: Boolean = false,
    onThemeSelected: (Theme) -> Unit,
    onUseCustomFolderToggle: (Boolean) -> Unit,
    onSelectFolderClick: () -> Unit,
    onRescan: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onTermsAndConditions: () -> Unit,
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
            Text("Theme", modifier = Modifier.padding(bottom = 8.dp))
            listOf(Theme.LIGHT, Theme.DARK, Theme.WARM_PAPER).forEach { theme ->
                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = currentTheme == theme,
                        onClick = { onThemeSelected(theme) }
                    )
                    Text(theme.name.replace("_", " "))
                }
            }

            Divider(modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))

            Text("Library", modifier = Modifier.padding(bottom = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text("Use custom folder")
                    Text(
                        if (useCustomFolder) {
                            "Reading from a folder you picked."
                        } else {
                            "Reading from ARKarium's own storage. Drop novel " +
                                "folders into the app's Android/data folder, or " +
                                "turn this on to pick a folder yourself."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = useCustomFolder, onCheckedChange = onUseCustomFolderToggle)
            }

            if (useCustomFolder) {
                Button(
                    onClick = onSelectFolderClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(if (hasCustomFolderSelected) "Change Folder" else "Select Folder")
                }
            }

            Button(
                onClick = onRescan,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Rescan Library")
            }

            Divider(modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))

            Text("Legal", modifier = Modifier.padding(bottom = 8.dp))
            LegalRow(label = "Privacy Policy", onClick = onPrivacyPolicy)
            LegalRow(label = "Terms & Conditions", onClick = onTermsAndConditions)

            Divider(modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))

            // BuildConfig.VERSION_NAME is generated from app/build.gradle.kts'
            // defaultConfig.versionName - the single source of truth for the app's
            // version. This is the only place in the running app's normal UI that
            // shows it; the crash screens in MainActivity read the same field.
            Text(
                "ARKarium v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
