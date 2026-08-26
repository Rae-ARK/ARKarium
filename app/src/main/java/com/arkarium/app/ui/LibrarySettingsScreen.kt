package com.arkarium.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Stage 2 (docs/arkarium/SETTINGS_REDESIGN.md, "mechanical extraction, no
// behavior change") - the "Use custom folder" switch, Select/Change Folder
// button, and Rescan Library button move here verbatim from the old
// monolithic SettingsScreen.kt. MainActivity's "settings/library" composable
// wires useCustomFolder/hasCustomFolderSelected and the three callbacks the
// same way its old "settings" composable did.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    // Whether the scanner reads from a user-picked SAF folder (true) or the app's own
    // private storage (false, the default - see MainActivity.resolveLibraryRoot). This
    // replaces the old `hasLibrary` flag: the library now always "exists" in the default
    // case (there's always a folder to scan), so the meaningful question isn't "is a
    // library configured" anymore, it's "which source is active."
    useCustomFolder: Boolean,
    // Whether a custom folder has actually been picked yet - only consulted while
    // useCustomFolder is true, to decide between "Select Folder" and "Change Folder".
    hasCustomFolderSelected: Boolean = false,
    onUseCustomFolderToggle: (Boolean) -> Unit,
    onSelectFolderClick: () -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Library") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // weight(1f) is load-bearing here, not cosmetic: an unweighted Column in a
                // Row is measured with loose (unbounded) width, so this description text -
                // long enough to need wrapping - never actually wraps against the space
                // left for it. It just claims however much width it wants and pushes the
                // Switch out past the screen edge instead of sitting next to it. Weighting
                // the Column forces it to share the Row with the Switch and wrap within
                // its share, which is what makes the Switch reliably visible/tappable.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
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
        }
    }
}
