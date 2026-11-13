package com.arkarium.app.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// JournalRepository: fresh fetch writes the offline copy; a failed or unparseable fetch
// falls back to it; with no copy the failure surfaces.
class JournalRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rss(title: String) =
        """<rss version="2.0"><channel><title>J</title><item><title>$title</title><guid>$title</guid></item></channel></rss>"""

    private fun cacheFile() = File(tmp.root, "journal-feed.xml")

    private fun repo(fetch: suspend () -> String) =
        JournalRepository(JournalFeedFetcher { fetch() }, cacheFile())

    @Test
    fun freshFetchReturnsFeedAndWritesTheSavedCopy() = runTest {
        val result = repo { rss("one") }.load()

        assertTrue(result is JournalLoadResult.Fresh)
        assertEquals("one", (result as JournalLoadResult.Fresh).feed.entries.single().title)
        assertTrue(cacheFile().readText().contains("one"))
    }

    @Test
    fun networkFailureFallsBackToTheSavedCopyAndSaysSo() = runTest {
        repo { rss("saved") }.load()

        val result = repo { throw IOException("no signal") }.load()

        assertTrue(result is JournalLoadResult.Offline)
        result as JournalLoadResult.Offline
        assertEquals("saved", result.feed.entries.single().title)
        assertEquals("no signal", result.reason)
    }

    @Test
    fun networkFailureWithNoSavedCopyIsFailed() = runTest {
        val result = repo { throw IOException("no signal") }.load()

        assertEquals(JournalLoadResult.Failed("no signal"), result)
    }

    @Test
    fun anUnparseableResponseNeverOverwritesTheLastGoodCopy() = runTest {
        repo { rss("good") }.load()

        val result = repo { "<html>captive portal</html>" }.load()

        assertTrue(result is JournalLoadResult.Offline)
        assertTrue(cacheFile().readText().contains("good"))
    }
}
