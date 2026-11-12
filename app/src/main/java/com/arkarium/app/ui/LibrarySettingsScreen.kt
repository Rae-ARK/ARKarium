package com.arkarium.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Stage 2 (see docs/arkarium/foundational/design-notes.md, "mechanical extraction, no
// behavior change") - the "Use custom folder" switch, Select/Change Folder
// button, and Rescan Library button originally moved here verbatim from the old
// monolithic SettingsScreen.kt.
//
// Reworked per the SAF-removal decision (see "Settled decisions" in
// docs/arkarium/foundational/design-notes.md): the switch and folder picker
// are gone - there is nothing left to toggle or pick,
// since the library root is now the one fixed Android/media/com.arkarium.app/
// folder (see data/LibraryScan.kt's resolveLibraryRoot). The "Use custom
// folder" row stays on this screen, but it is now purely informational: tapping
// its info icon opens a dialog explaining that custom folders were removed and
// where the library lives now, instead of doing anything to the scanner. The
// same dialog opens automatically, once, for anyone upgrading from a build
// where they'd actually turned the old switch on (shouldShowMigrationNotice,
// backed by PreferencesManager.shouldShowSafRemovedNotice) so they aren't left
// wondering where their novels went.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    // True exactly once, for an install that had the old "Use custom folder" switch
    // on - see PreferencesManager.shouldShowSafRemovedNotice's doc comment. Drives
    // showing SafRemovedDialog automatically on first composition.
    shouldShowMigrationNotice: Boolean,
    onMigrationNoticeDismissed: () -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    // Manual trigger (the info icon below) - independent of shouldShowMigrationNotice,
    // which only ever fires once automatically. Someone can reopen the same
    // explanation at any time by tapping the icon again.
    var showDeprecatedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(shouldShowMigrationNotice) {
        if (shouldShowMigrationNotice) {
            showDeprecatedDialog = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("Library") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            SettingsHeaderBadge(
                icon = Icons.Filled.LibraryBooks,
                caption = "ARKarium reads your novels from its own Android/media " +
                    "folder. Drop novel folders in there, and rescan whenever " +
                    "files change on disk."
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
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
                        // info icon out past the screen edge instead of sitting next to it.
                        // Weighting the Column forces it to share the Row with the icon and wrap
                        // within its share.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text("Use custom folder", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "No longer supported - tap the info icon to see why.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = { showDeprecatedDialog = true }) {
                            Icon(Icons.Filled.Info, contentDescription = "Why custom folder was removed")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Rescan Library")
            }
        }
    }

    if (showDeprecatedDialog) {
        SafRemovedDialog(
            onDismiss = {
                showDeprecatedDialog = false
                if (shouldShowMigrationNotice) {
                    onMigrationNoticeDismissed()
                }
            }
        )
    }
}

// Pulled out of LibrarySettingsScreen's body only so its text has one place to live -
// both the automatic (migration) and manual (info icon) triggers above show the exact
// same dialog.
@Composable
private fun SafRemovedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom folder removed") },
        text = {
            Text(
                "ARKarium no longer reads from a folder you pick. It now always " +
                    "reads from its own Android/media folder, which any file " +
                    "manager or PC can see and write to directly - just drag novel " +
                    "folders in there and rescan. If you had a custom folder set, " +
                    "your novels are still exactly where you left them; ARKarium " +
                    "just doesn't point at that folder anymore."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}
