package com.arkarium.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Stage 1 (docs/arkarium/SETTINGS_REDESIGN.md, "navigation scaffolding") -
// destination for the new "settings/tts" route, wired up in MainActivity's
// NavHost alongside this file. Unlike the other three new screens, this one
// has no existing control code to move - it's genuinely new (see the design
// doc's §2). Its controls (default speech rate, pitch, engine, auto-continue,
// keep screen on) and the PreferencesManager keys backing them arrive in
// Stage 3, once the engine-picker and ViewModel-ownership open questions in
// the design doc are settled.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Text-to-Speech") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Text-to-speech defaults (rate, pitch, engine, auto-continue, " +
                    "keep screen on) arrive in Stage 3.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
