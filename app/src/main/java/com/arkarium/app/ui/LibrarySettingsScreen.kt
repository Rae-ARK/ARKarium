package com.arkarium.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkarium.app.data.LastScanInfo
import com.arkarium.app.data.StorageBreakdown
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// Rewritten per docs/proposal/library-maintenance.md's "Scanning behavior" /
// "Storage & organization" / "Library health / maintenance" / "Sync-adjacent"
// sections - this replaces the old "Use custom folder" row (and the
// SafRemovedDialog/migration-notice plumbing it drove) entirely, now that the
// one-time notice has shipped for a while and nothing upgrading still needs it
// (see PreferencesManager's doc comment on the key pair it used to back). Every
// control below is backed by a real PreferencesManager flow/setter
// (MainActivity's "settings/library" composable wires them) - there is nothing
// purely informational left on this screen.
//
// Items from that doc's list that aren't wired up yet (scan log/history,
// rename/reorganize detection rules, validate library, rebuild index, export/
// import, check-for-updates frequency, permission/access status, diagnostic
// logs) are named in the "Planned" section at the bottom rather than given a
// control here - see docs/proposal/library-maintenance.md for the open
// proposal on each.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    autoScanOnLaunch: Boolean,
    onAutoScanOnLaunchToggle: (Boolean) -> Unit,
    backgroundScanEnabled: Boolean,
    onBackgroundScanToggle: (Boolean) -> Unit,
    scanOnFileChangeEnabled: Boolean,
    onScanOnFileChangeToggle: (Boolean) -> Unit,
    wifiOnlySync: Boolean,
    onWifiOnlySyncToggle: (Boolean) -> Unit,
    // Null until the first scan (interactive or background) completes this
    // install - see PreferencesManager.lastScanInfo's own doc comment. The
    // "Last scanned ..." row simply isn't shown in that case rather than
    // rendering a fabricated "just now".
    lastScanInfo: LastScanInfo?,
    // Null while the first computeStorageBreakdown() call (kicked off by
    // MainActivity's LaunchedEffect on entering this route) hasn't resolved
    // yet - the storage card shows a brief "Calculating..." line instead of
    // a breakdown until then.
    storageBreakdown: StorageBreakdown?,
    // Set by MainActivity after Clear cache / Remove missing novels actually
    // finish, so the result of the action that just ran stays visible until
    // the next one - a snackbar would also work, but this screen has no
    // Scaffold/SnackbarHost of its own to anchor one to.
    actionMessage: String?,
    onClearImageCaches: () -> Unit,
    onOpenInFileManager: () -> Unit,
    onRemoveMissingNovels: () -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    var showRemoveMissingConfirm by remember { mutableStateOf(false) }

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
                    "folder. Drop novel folders in there, and ARKarium will notice " +
                    "depending on the scanning options below."
            )

            SectionLabel("Scanning behavior")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "Auto-scan on launch",
                        description = "Rescan the library every time ARKarium opens.",
                        checked = autoScanOnLaunch,
                        onCheckedChange = onAutoScanOnLaunchToggle
                    )
                    SectionDivider()
                    SwitchRow(
                        title = "Background scan",
                        description = "Rescan every few hours even while ARKarium " +
                            "isn't open, so chapters dropped in overnight show up " +
                            "without a manual launch.",
                        checked = backgroundScanEnabled,
                        onCheckedChange = onBackgroundScanToggle
                    )
                    SectionDivider()
                    SwitchRow(
                        title = "Scan on file change",
                        description = "Watch the library folder while ARKarium is " +
                            "open and rescan as soon as a file changes on disk - " +
                            "only while the app is in the foreground.",
                        checked = scanOnFileChangeEnabled,
                        onCheckedChange = onScanOnFileChangeToggle
                    )
                    if (lastScanInfo != null) {
                        SectionDivider()
                        Text(
                            "Last scanned ${formatRelativeTime(lastScanInfo.completedAtMillis)} " +
                                "(${formatDuration(lastScanInfo.durationMillis)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
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

            Spacer(modifier = Modifier.height(24.dp))

            SectionLabel("Storage & organization")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (storageBreakdown == null) {
                        Text(
                            "Calculating storage usage...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        BreakdownRow("Library (novels)", formatBytes(storageBreakdown.libraryBytes))
                        BreakdownRow("Cached covers & thumbnails", formatBytes(storageBreakdown.cacheBytes))
                        if (storageBreakdown.novelBytes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Largest novels",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            storageBreakdown.novelBytes.take(5).forEach { (title, bytes) ->
                                BreakdownRow(title, formatBytes(bytes), indent = true)
                            }
                            val remaining = storageBreakdown.novelBytes.size - 5
                            if (remaining > 0) {
                                Text(
                                    "+ $remaining more",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(onClick = onClearImageCaches, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Clear cached covers & thumbnails")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(onClick = onOpenInFileManager, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Open library folder in file manager")
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionLabel("Library health & maintenance")
            OutlinedButton(
                onClick = { showRemoveMissingConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Remove missing novels")
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionLabel("Sync")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "Wi-Fi-only sync",
                        description = "Only check for new chapters on followed " +
                            "novels while connected to Wi-Fi.",
                        checked = wifiOnlySync,
                        onCheckedChange = onWifiOnlySyncToggle,
                        icon = Icons.Filled.Wifi
                    )
                }
            }

            if (actionMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    actionMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionLabel("Planned for a later pass")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 10.dp, top = 2.dp)
                    )
                    Text(
                        "Not implemented yet: scan log/history, rename/reorganize " +
                            "detection rules, validate library, rebuild index/full " +
                            "rescan, export/import reading progress and library " +
                            "metadata, check-for-updates frequency, permission/" +
                            "access status, and send-diagnostics. See " +
                            "docs/proposal/library-maintenance.md for each one's " +
                            "open proposal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }

    if (showRemoveMissingConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveMissingConfirm = false },
            title = { Text("Remove missing novels?") },
            text = {
                Text(
                    "This deletes every novel ARKarium currently believes is " +
                        "missing from disk - along with its reading progress and " +
                        "reading history - from the last scan's results. If a " +
                        "removed novel's folder reappears later, rescanning will " +
                        "add it back as new."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveMissingConfirm = false
                    onRemoveMissingNovels()
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveMissingConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Small uppercase-ish section label above each card, matching the "Daytime
// look" sub-header idiom ThemeSettingsScreen already uses for the same
// purpose (SettingsHeaderBadge sits above all of them, once, instead of one
// per section).
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
    )
}

@Composable
private fun SectionDivider() {
    Divider(
        modifier = Modifier.padding(vertical = 14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}

// One title+description+Switch row - same weight(1f)-on-the-text-column shape
// as SplashSettingsScreen's rows (see that file's own doc comment for why the
// weight is load-bearing, not cosmetic).
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, indent: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 12.dp else 0.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// Byte-count formatting for the storage breakdown above. `internal`, not
// private - MainActivity's "settings/library" composable reuses it to word
// the "Clear cached covers & thumbnails" result message with the same units,
// rather than duplicating this table there.
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val rounded = (value * 10).roundToInt() / 10.0
    return "$rounded ${units[unitIndex]}"
}

private fun formatDuration(durationMillis: Long): String {
    val seconds = durationMillis / 1000.0
    return if (seconds < 1) "${durationMillis}ms" else "${(seconds * 10).roundToInt() / 10.0}s"
}

private fun formatRelativeTime(completedAtMillis: Long): String {
    val elapsedMs = (System.currentTimeMillis() - completedAtMillis).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
    val days = TimeUnit.MILLISECONDS.toDays(elapsedMs)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        else -> "$days day${if (days == 1L) "" else "s"} ago"
    }
}
