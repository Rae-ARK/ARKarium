package com.arkarium.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
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
// android.webkit.WebViewClient, for access to the AndroidX WebView support
// library's feature-detection APIs if this screen grows more configuration
// later.
//
// Link policy (F18, engineering-review.md): navigation stays inside this WebView only
// while it's on the same site the screen was opened to show (see
// isSameSite below). Any other link - a different host, or a non-http(s)
// scheme like mailto: or intent: - is handed to the system via ACTION_VIEW
// instead, so a link embedded in author-controlled page content can't pivot
// the user to an arbitrary site while still looking like it's inside
// ARKarium.
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
            windowInsets = WindowInsets(0, 0, 0, 0),
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
                    webViewClient = object : WebViewClientCompat() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val target = request.url
                            if (isSameSite(url, target.toString())) return false
                            // Only hand off web links; never launch arbitrary schemes
                            // (intent:, file:, javascript:, ...) from page content.
                            if (target.scheme == "http" || target.scheme == "https" || target.scheme == "mailto") {
                                try {
                                    view.context.startActivity(
                                        Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (e: ActivityNotFoundException) {
                                    // No app can open it - nothing to do, and still not
                                    // something to load in here.
                                }
                            }
                            return true
                        }
                    }
                    loadUrl(url)
                }
            },
            // WebView leak: this factory's WebView was never released - leaving
            // composition (Back, or the Activity recreating on rotation, since nothing in
            // the manifest opts this Activity out of that) just detached it from the view
            // hierarchy. AndroidView never called WebView.destroy() for it, and per
            // Android's own guidance a WebView "should not outlive its host Activity" -
            // skipping destroy() leaves its Chromium-backed native engine, JS context and
            // DOM storage alive, pinning the Context passed into the factory above
            // (an Activity leak) along with everything it holds. Fixed the way Android's
            // own WebView-memory guidance (developer.android.com/develop/ui/views/layout/
            // webapps/manage-webview-memory) spells out: detach from its parent first
            // (a WebView keeps a reference to its host Activity context even once removed
            // from the hierarchy, so this step is what actually lets that reference go),
            // stop any in-flight load and drop navigation history, then destroy() - in
            // that order, since destroying first and detaching after is the ordering the
            // same guidance specifically warns can still leak.
            onRelease = { webView ->
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.clearHistory()
                webView.destroy()
            }
        )
    }
}

// True when `target` is http(s) on the same host as `start`, ignoring a leading
// "www." on either side (example.com <-> www.example.com is still the author's
// own site). Uses java.net.URI rather than android.net.Uri so it runs in plain
// JVM unit tests; a malformed or host-less target is never "same site".
internal fun isSameSite(start: String, target: String): Boolean {
    return try {
        val a = java.net.URI(start)
        val b = java.net.URI(target)
        if (b.scheme?.lowercase() != "http" && b.scheme?.lowercase() != "https") return false
        val hostA = a.host?.lowercase()?.removePrefix("www.") ?: return false
        val hostB = b.host?.lowercase()?.removePrefix("www.") ?: return false
        hostA == hostB
    } catch (e: java.net.URISyntaxException) {
        false
    }
}
