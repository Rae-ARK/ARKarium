package com.arkarium.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// One candidate match returned by a metadata search - deliberately separate from
// NovelEntity so a search result never gets persisted until the user explicitly
// confirms it (see MainActivity.applyMetadata).
data class NovelMetadataCandidate(
    val title: String,
    val authors: List<String>,
    val description: String?,
    val thumbnailUrl: String?,
    val publishedDate: String?,
    val categories: List<String>,
    val infoLink: String?
)

interface NovelMetadataProvider {
    suspend fun search(query: String): List<NovelMetadataCandidate>
}

// Google Books was picked over NovelUpdates/similar web-novel aggregators because it
// has a real, free, public JSON API that needs no API key or auth for basic search -
// appropriate for a lightweight, user-triggered lookup like this. The real trade-off:
// it only indexes actually-published books, so it will come up empty for most
// web-novel/fan-translation folders, which is what this app's scanner is mainly built
// for. That's a known gap to revisit later (e.g. layering in a NovelUpdates scraper),
// not something this class tries to paper over.
class GoogleBooksMetadataProvider : NovelMetadataProvider {

    override suspend fun search(query: String): List<NovelMetadataCandidate> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val encodedQuery = URLEncoder.encode("intitle:$query", "UTF-8")
        val url = URL("https://www.googleapis.com/books/v1/volumes?q=$encodedQuery&maxResults=5")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode !in 200..299) {
                return@withContext emptyList()
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseVolumes(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVolumes(body: String): List<NovelMetadataCandidate> {
        val root = JSONObject(body)
        val items = root.optJSONArray("items") ?: return emptyList()

        val results = mutableListOf<NovelMetadataCandidate>()
        for (i in 0 until items.length()) {
            val volumeInfo = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue

            val title = volumeInfo.optString("title")
            if (title.isBlank()) continue

            val authors = volumeInfo.optJSONArray("authors")?.toStringList() ?: emptyList()
            val description = volumeInfo.optString("description").takeIf { it.isNotBlank() }
            val thumbnailUrl = volumeInfo.optJSONObject("imageLinks")
                ?.optString("thumbnail")
                ?.takeIf { it.isNotBlank() }
                // Google Books links are http:// by default; Android blocks cleartext
                // traffic for apps targeting recent SDKs, which would make Coil silently
                // fail to load the thumbnail with no obvious error.
                ?.replace("http://", "https://")
            val publishedDate = volumeInfo.optString("publishedDate").takeIf { it.isNotBlank() }
            val categories = volumeInfo.optJSONArray("categories")?.toStringList() ?: emptyList()
            val infoLink = volumeInfo.optString("infoLink").takeIf { it.isNotBlank() }

            results.add(
                NovelMetadataCandidate(
                    title = title,
                    authors = authors,
                    description = description,
                    thumbnailUrl = thumbnailUrl,
                    publishedDate = publishedDate,
                    categories = categories,
                    infoLink = infoLink
                )
            )
        }
        return results
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it) }
}
