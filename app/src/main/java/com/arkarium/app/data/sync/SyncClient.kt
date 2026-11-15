package com.arkarium.app.data.sync

import com.arkarium.app.data.PublicationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Talks to a relay: fetches and parses manifest.json, downloads individual files.
// Plain HttpURLConnection on Dispatchers.IO, same convention as
// GoogleBooksMetadataProvider - no new HTTP client dependency for one GET-only use case.
class SyncClient {
    suspend fun fetchManifest(baseUrl: String): Manifest = withContext(Dispatchers.IO) {
        // manifestBaseUrl is threaded through so a 404 here specifically (as opposed to
        // a 404 on an individual file download below) surfaces as SourceGoneException -
        // see docs/foundational/architecture.md, Sync invariants S6.
        parseManifest(fetchText(joinUrl(baseUrl, "manifest.json"), manifestBaseUrl = baseUrl))
    }

    suspend fun downloadFile(baseUrl: String, relativePath: String): ByteArray = withContext(Dispatchers.IO) {
        fetchBytes(joinUrl(baseUrl, relativePath))
    }

    // Manifest paths are real folder/file names (novel titles, arc names) - they
    // routinely contain spaces, commas, and other characters that are legal in a
    // filename but not in a URL path unescaped. Percent-encode each segment
    // individually (never the "/" separators themselves) rather than encoding the
    // path as a whole, which would also escape the slashes and break routing.
    private fun joinUrl(baseUrl: String, path: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val encodedPath = path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return base + encodedPath
    }

    private fun fetchText(url: String, manifestBaseUrl: String? = null): String {
        val connection = openConnection(url)
        try {
            checkOk(connection, manifestBaseUrl)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val connection = openConnection(url)
        try {
            checkOk(connection)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return connection
    }

    // manifestBaseUrl is only ever passed by fetchManifest - a 404 on any other request
    // (an individual file download) stays a plain IOException, since a missing file
    // isn't evidence the whole source is gone (see SourceGoneException's doc comment).
    private fun checkOk(connection: HttpURLConnection, manifestBaseUrl: String? = null) {
        if (connection.responseCode == 404 && manifestBaseUrl != null) {
            throw SourceGoneException(manifestBaseUrl)
        }
        if (connection.responseCode !in 200..299) {
            throw IOException("HTTP ${connection.responseCode} for ${connection.url}")
        }
    }

    private fun parseManifest(body: String): Manifest {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ManifestParseException("manifest.json is not valid JSON")
        }
        val version = root.optInt("version", -1)
        if (version < 0) throw ManifestParseException("manifest.json is missing a numeric \"version\"")

        val filesObj = root.optJSONObject("files")
            ?: throw ManifestParseException("manifest.json is missing \"files\"")

        val files = mutableListOf<ManifestFileEntry>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val entry = filesObj.optJSONObject(path) ?: continue
            val sha256 = entry.optString("sha256").takeIf { it.isNotBlank() }
                ?: throw ManifestParseException("manifest.json entry \"$path\" is missing sha256")
            val size = entry.optLong("size", -1)
            if (size < 0) throw ManifestParseException("manifest.json entry \"$path\" is missing size")
            files.add(
                ManifestFileEntry(
                    relativePath = SyncPaths.sanitize(path),
                    size = size,
                    sha256 = sha256,
                    wordCount = entry.optIntOrNull("wordCount"),
                    readTimeMinutes = entry.optIntOrNull("readTimeMinutes")
                )
            )
        }

        // Both new, optional, root-level fields - see Manifest's doc comment. Neither
        // one failing to parse takes down the whole manifest: a malformed
        // "announcements" entry is just skipped (same forgiving-per-item philosophy as
        // files/entry.optJSONObject(path) ?: continue above), and an unrecognized
        // "status" string normalizes to null via PublicationStatus.fromRaw rather than
        // throwing.
        val status = PublicationStatus.fromRaw(root.optString("status").takeIf { it.isNotBlank() })
        val chaptersPerWeek = root.optDoubleOrNull("chaptersPerWeek")?.takeIf { it >= 0.0 }
        val announcementsArray = root.optJSONArray("announcements")
        val announcements = if (announcementsArray != null) {
            (0 until announcementsArray.length()).mapNotNull { i ->
                // Accept either a bare string or {"text": ..., "publishedAt": ...} -
                // see ManifestAnnouncement's doc comment.
                val obj = announcementsArray.optJSONObject(i)
                if (obj != null) {
                    obj.optString("text").takeIf { it.isNotBlank() }?.let { text ->
                        ManifestAnnouncement(text = text, publishedAt = obj.optString("publishedAt").takeIf { it.isNotBlank() })
                    }
                } else {
                    announcementsArray.optString(i).takeIf { it.isNotBlank() }?.let { text ->
                        ManifestAnnouncement(text = text)
                    }
                }
            }
        } else {
            null
        }

        return Manifest(
            version = version, files = files, status = status, announcements = announcements,
            chaptersPerWeek = chaptersPerWeek
        )
    }
}

// JSONObject.optInt always returns a real Int (0 by default), which can't distinguish
// "the manifest explicitly said 0" from "this field was never there" - exactly the gap
// ManifestFileEntry.wordCount/readTimeMinutes need to stay null for an old manifest
// instead of silently becoming "0 words, 0 minutes" for every chapter. org.json has no
// built-in optIntOrNull, hence this tiny helper instead of a bare optInt call at either
// of parseManifest's two call sites above.
private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name).takeIf { it >= 0 } else null

// Same absent-vs-zero distinction as optIntOrNull above, for chaptersPerWeek - a
// manifest explicitly declaring a 0 cadence (paused, no set schedule) is different
// from one that never mentions a cadence at all.
private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
