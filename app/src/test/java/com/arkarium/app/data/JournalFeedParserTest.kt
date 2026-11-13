package com.arkarium.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Rae's Journal feed parsing - shaped after the real feed at RAES_JOURNAL_FEED_URL
// (shared <link>, optional <pubDate>, "A · B" categories, escaped entities).
class JournalFeedParserTest {

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>Rae ARK — Writing Journal</title>
    <link>https://example.test/journal</link>
    <atom:link href="https://example.test/feed.xml" rel="self" type="application/rss+xml" />
    <description>Not news. Just the process.</description>
    <item>
      <title>Arc 3 Is Done</title>
      <link>https://example.test/journal</link>
      <guid isPermaLink="false">https://example.test/journal#arc-3</guid>
      <pubDate>Sat, 03 Oct 2026 12:00:00 GMT</pubDate>
      <category>SBM</category>
      <description>Arc 3 has wrapped. I read every comment.</description>
    </item>
    <item>
      <title>Mirror &amp; copies</title>
      <link>https://example.test/journal</link>
      <guid isPermaLink="false">https://example.test/journal#mirror</guid>
      <category>SBM · EPMC · STBF</category>
      <description>A theme called &quot;The Person You Pretend to Be&quot;.</description>
    </item>
  </channel>
</rss>"""

    @Test
    fun parsesChannelAndItemsInFeedOrder() {
        val feed = JournalFeedParser.parse(sample)

        assertEquals("Rae ARK — Writing Journal", feed.title)
        assertEquals("Not news. Just the process.", feed.description)
        assertEquals(listOf("Arc 3 Is Done", "Mirror & copies"), feed.entries.map { it.title })
        assertEquals("https://example.test/journal#arc-3", feed.entries[0].guid)
    }

    @Test
    fun datedEntryParsesAndMissingPubDateIsNullNotNow() {
        val feed = JournalFeedParser.parse(sample)

        // Sat, 03 Oct 2026 12:00:00 GMT
        assertEquals(1_791_028_800_000L, feed.entries[0].publishedAt)
        assertNull(feed.entries[1].publishedAt)
    }

    @Test
    fun decodesEntitiesAndSplitsPackedCategoriesIntoTags() {
        val second = JournalFeedParser.parse(sample).entries[1]

        assertEquals("A theme called \"The Person You Pretend to Be\".", second.description)
        assertEquals(listOf("SBM", "EPMC", "STBF"), second.tags)
    }

    @Test
    fun numericZoneDatesParseToo() {
        assertEquals(
            1_791_028_800_000L,
            JournalFeedParser.parseRfc822("Sat, 03 Oct 2026 14:00:00 +0200")
        )
        assertNull(JournalFeedParser.parseRfc822("sometime in spring"))
        assertNull(JournalFeedParser.parseRfc822(null))
    }

    @Test
    fun itemWithNoTitleAndNoDescriptionIsSkippedAndMissingGuidFallsBack() {
        val feed = JournalFeedParser.parse(
            """<rss version="2.0"><channel><title>t</title>
                 <item><link>x</link></item>
                 <item><title>No guid</title><description>d</description></item>
               </channel></rss>"""
        )

        assertEquals(1, feed.entries.size)
        assertEquals("No guid", feed.entries[0].guid)
    }

    @Test
    fun duplicateGuidsCollapseToOneEntry() {
        val feed = JournalFeedParser.parse(
            """<rss version="2.0"><channel>
                 <item><title>A</title><guid>same</guid></item>
                 <item><title>B</title><guid>same</guid></item>
               </channel></rss>"""
        )

        assertEquals(listOf("A"), feed.entries.map { it.title })
    }

    @Test
    fun nonRssAndMalformedInputThrowJournalFeedException() {
        for (bad in listOf("<html><body>hi</body></html>", "<rss><nochannel/></rss>", "not xml at all", "")) {
            try {
                JournalFeedParser.parse(bad)
                fail("expected JournalFeedException for: $bad")
            } catch (e: JournalFeedException) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun docTypeDeclarationsAreRefused() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY boom "boom">]>
            <rss version="2.0"><channel><item><title>&boom;</title></item></channel></rss>"""
        try {
            JournalFeedParser.parse(xml)
            fail("expected the DOCTYPE to be refused")
        } catch (e: JournalFeedException) {
            assertTrue(e.cause != null)
        }
    }
}
