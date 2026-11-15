package com.arkarium.app.data.sync

import java.security.MessageDigest

object SyncPaths {
    // Rejects absolute paths, empty/"."/".." segments, and backslashes before a
    // manifest path is ever used to create a file or directory. A manifest is
    // untrusted input the moment "Add fiction from URL" accepts an arbitrary base
    // URL - see docs/foundational/architecture.md, Sync invariants S2.
    fun sanitize(relativePath: String): String {
        val normalized = relativePath.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            throw UnsafePathException(relativePath)
        }
        val segments = normalized.split("/")
        for (segment in segments) {
            if (segment.isBlank() || segment == "." || segment == "..") {
                throw UnsafePathException(relativePath)
            }
        }
        return normalized
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// ARKarium only ever syncs from this one relay now - "Add fiction from URL" (any
// origin) has been replaced by "Add fiction by name" (this origin only, slug looked
// up via FictionLut). A per-fiction base URL is still `"$RELAY_HOST$slug/"`; the rest
// of SyncManager doesn't care where a baseUrl came from, so this is the only place
// that needed to change to make the whole client single-origin.
const val RELAY_HOST = "https://novels.horizonarkstudio.workers.dev/"

// The slug is baked into FictionLut's asset as a plain display-ish string (see
// gen_fiction_lut.py) - "sbm" happens to be URL-safe as-is, but nothing guarantees
// that: the "Enigmatic Pathways..." entry's slug is the relay's literal unslugified
// folder name, spaces included. Unlike joinUrl() below (which encodes each segment
// of the *relative* manifest path), this base-URL builder was never encoding the
// slug segment, so a slug with a space or other reserved character produced a
// malformed URL that failed before any request was even made - this is why "sbm"
// worked and names with spaces didn't. Encode it the same way joinUrl does.
fun relayBaseUrlForSlug(slug: String): String =
    RELAY_HOST + java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20") + "/"
