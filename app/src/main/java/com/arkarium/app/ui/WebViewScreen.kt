package com.arkarium.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewClientCompat

// Generic in-app browser for a single fixed URL - same TopAppBar-with-back
// pattern as LegalDocumentScreen, but rendering a live web page instead of
// static text. Used by Settings' "About Me" row so the author's site opens
// inside ARKarium via androidx.webkit rather than handing off to an external
// browser app.
//
// WebViewClientCompat (androidx.webkit) rather than the platform
// android.webkit.WebViewClient: it keeps navigation inside this WebView
// (shouldOverrideUrlLoading returning false) while giving access to the
// AndroidX WebView support library's feature-detection APIs if this screen
// grows more configuration later.
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    title: String,
    url: String,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    // JavaScript is required for most real-world sites (including
                    // the author page this screen links to) to render as intended.
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // WebViewClientCompat keeps link taps inside this screen
                    // rather than launching an external browser/app chooser.
                    webViewClient = object : WebViewClientCompat() {}
                    loadUrl(url)
                }
            }
        )
    }
}
