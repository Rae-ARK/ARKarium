package com.arkarium.app.data

import java.io.File
import java.io.IOException
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

// Rae's Journal (docs/proposal/notice-board.md): the author's writing journal, read from
// an RSS feed. Deliberately its own small stack - it's a different host from the fiction
// relay (RELAY_HOST) and a different format (XML, not manifest.json), so none of
// SyncClient's manifest machinery applies.
const val RAES_JOURNAL_FEED_URL = "https://rae-ark.horizonarkstudio.workers.dev/feed.xml"

// One journal entry. Notes on the live feed that shaped this type:
// - Every item's <link> points at the same journal page, so identity is the <guid>
//   (isPermaLink="false"); there is no per-entry URL, and none is exposed here.
// - <pubDate> is optional (some entries only have a month or season in their guid), so
//   publishedAt is nullable: "undated", never "now".
// - <description> is plain text; <category> is one free-form string like "SBM · EPMC".
data class JournalEntry(
    val guid: String,
    val title: String,
    val description: String,
    val categories: List<String>,
    val publishedAt: Long?
) {
    // "SBM · EPMC" -> ["SBM", "EPMC"]; the feed packs several fictions into one category.
    val tags: List<String>
        get() = categories.flatMap { it.split('·', ',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

data class JournalFeed(val title: String?, val description: String?, val entries: List<JournalEntry>)

class JournalFeedException(message: String, cause: Throwable? = null) : IOException(message, cause)

object JournalFeedParser {

    // RSS 2.0 only - the one format this feed uses. Entries come back in the feed's own
    // order (newest first as published); nothing re-sorts them, since undated entries
    // have no timestamp to sort by.
    fun parse(xml: String): JournalFeed {
        val root = try {
            val factory = DocumentBuilderFactory.newInstance()
            // Untrusted-ish input over the network: refuse DTDs outright (no entity
            // expansion tricks). Not every platform's parser supports the feature flag
            // (Android's throws), and there it just stays at the platform default.
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (e: Exception) {
                // unsupported on this parser - see above
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (e: Exception) {
            throw JournalFeedException("The journal feed couldn't be read.", e)
        }

        val channel = (if (root.nodeName == "rss") root.children("channel").firstOrNull() else null)
            ?: throw JournalFeedException("The journal feed isn't an RSS 2.0 feed.")

        // distinctBy: the UI keys its list on guid, and a duplicate key would crash it.
        val entries = channel.children("item").mapNotNull { toEntry(it) }.distinctBy { it.guid }
        return JournalFeed(
            title = channel.childText("title"),
            description = channel.childText("description"),
            entries = entries
        )
    }

    private fun toEntry(item: Element): JournalEntry? {
        val title = item.childText("title")
        val description = item.childText("description")
        // An item with neither has nothing to show.
        if (title == null && description == null) return null
        val pubDate = item.childText("pubDate")
        return JournalEntry(
            guid = item.childText("guid") ?: listOfNotNull(title, pubDate).joinToString("|"),
            title = title ?: "Untitled",
            description = description.orEmpty(),
            categories = item.children("category").mapNotNull { it.text() },
            publishedAt = parseRfc822(pubDate)
        )
    }

    // RFC 822 dates as RSS writes them ("Sat, 03 Oct 2026 12:00:00 GMT" or with a numeric
    // zone). SimpleDateFormat rather than java.time: minSdk is 24, java.time needs 26
    // without desugaring. A new formatter per call - SimpleDateFormat isn't thread-safe.
    internal fun parseRfc822(value: String?): Long? {
        val text = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss zzz")) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.isLenient = false
                return format.parse(text)?.time
            } catch (e: java.text.ParseException) {
                // try the next shape
            }
        }
        return null
    }

    private fun Element.children(name: String): List<Element> {
        val result = mutableListOf<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == name) result.add(node as Element)
            node = node.nextSibling
        }
        return result
    }

    // textContent folds CDATA and resolves entities (&quot; etc.); blank means absent.
    private fun Element.text(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.childText(name: String): String? = children(name).firstOrNull()?.text()
}

// The one thing a repository needs from the network - an interface so tests can hand it
// canned XML instead of a socket.
fun interface JournalFeedFetcher {
    suspend fun fetch(): String
}

// Plain HttpURLConnection on Dispatchers.IO, same convention as SyncClient - no HTTP
// client dependency for one GET.
class JournalFeedClient(private val url: String = RAES_JOURNAL_FEED_URL) : JournalFeedFetcher {
    override suspend fun fetch(): String = withContext(Dispatchers.IO) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml;q=0.9, */*;q=0.8")
            if (connection.responseCode !in 200..299) {
                throw IOException("The journal responded with HTTP ${connection.responseCode}.")
            }
            // A feed this small has no business being large; cap the read so a wrong or
            // hostile response can't fill memory.
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_FEED_BYTES) throw IOException("The journal feed is larger than expected.")
                }
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_FEED_BYTES = 1024 * 1024
    }
}

sealed interface JournalLoadResult {
    // Just fetched and parsed.
    data class Fresh(val feed: JournalFeed) : JournalLoadResult

    // The fetch failed but a previously saved copy parsed: show it, say when it's from
    // (C3-style - staleness is visible, not silently trusted).
    data class Offline(val feed: JournalFeed, val savedAtMillis: Long, val reason: String) : JournalLoadResult

    data class Failed(val reason: String) : JournalLoadResult
}

// Fetch -> parse -> save the raw XML -> hand back. The saved copy is only a fallback for
// when the network fails; it lives in the app's cache dir (the OS may clear it, and
// everything in it is re-fetchable), and it is only ever overwritten by a response that
// parsed, so a bad response can't clobber the last good copy.
class JournalRepository(
    private val fetcher: JournalFeedFetcher,
    private val cacheFile: File
) {
    suspend fun load(): JournalLoadResult {
        val reason = try {
            val xml = fetcher.fetch()
            val feed = JournalFeedParser.parse(xml)
            withContext(Dispatchers.IO) { writeCache(xml) }
            return JournalLoadResult.Fresh(feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "The journal couldn't be reached."
        }

        val saved = withContext(Dispatchers.IO) { readCache() }
        return if (saved != null) {
            JournalLoadResult.Offline(saved.first, saved.second, reason)
        } else {
            JournalLoadResult.Failed(reason)
        }
    }

    private fun writeCache(xml: String) {
        // A failed cache write costs only the offline fallback, never the fresh result.
        try {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(xml)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
            tmp.delete()
        } catch (e: IOException) {
            // see above
        }
    }

    private fun readCache(): Pair<JournalFeed, Long>? {
        if (!cacheFile.isFile) return null
        return try {
            JournalFeedParser.parse(cacheFile.readText()) to cacheFile.lastModified()
        } catch (e: IOException) {
            null
        }
    }
}
