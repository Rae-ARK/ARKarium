package com.arkarium.app.ui

import com.arkarium.app.data.CatalogueEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Rung 5 of docs/implementation/catalogue-browse-redesign.md - the catalogue half of
// Browse's merged grid: search, the status-filter rule, and the NEWEST / alphabetical
// sort rule.
class CatalogueBrowseFilterTest {

    private fun entry(slug: String, title: String, author: String? = null, published: String? = null) =
        CatalogueEntryEntity(
            slug = slug, title = title, author = author, description = null, genres = null,
            coverPath = null, chapterCount = 1, publishedDate = published, cachedAt = 0L
        )

    private val catalogue = listOf(
        entry("b", "bravo", published = "2024-05-01"),
        entry("a", "Alpha", author = "Rae ARK"),
        entry("c", "Charlie", published = "2025-01-01"),
        entry("d", "delta", published = "2024-05-01")
    )

    private fun slugs(list: List<CatalogueEntryEntity>) = list.map { it.slug }

    @Test
    fun newestOrdersByPublishedDateDescendingUndatedLastThenTitle() {
        val result = filterAndSortCatalogue(catalogue, "", StatusFilter.ALL, SortBy.NEWEST)
        // c (2025) first; b/d tie on date -> by title; a (undated) last.
        assertEquals(listOf("c", "b", "d", "a"), slugs(result))
    }

    @Test
    fun otherSortsFallBackToCaseInsensitiveAlphabetical() {
        for (sort in listOf(SortBy.RECENTLY_UPDATED, SortBy.MOST_POPULAR, SortBy.HIGHEST_RATED)) {
            val result = filterAndSortCatalogue(catalogue, "", StatusFilter.ALL, sort)
            assertEquals(listOf("a", "b", "c", "d"), slugs(result))
        }
    }

    @Test
    fun anyStatusFilterOtherThanAllHidesEveryCatalogueEntry() {
        for (status in StatusFilter.values().filter { it != StatusFilter.ALL }) {
            assertTrue(filterAndSortCatalogue(catalogue, "", status, SortBy.NEWEST).isEmpty())
        }
    }

    @Test
    fun searchMatchesTitleOrAuthorCaseInsensitively() {
        assertEquals(listOf("c"), slugs(filterAndSortCatalogue(catalogue, "CHAR", StatusFilter.ALL, SortBy.NEWEST)))
        assertEquals(listOf("a"), slugs(filterAndSortCatalogue(catalogue, "rae", StatusFilter.ALL, SortBy.NEWEST)))
        assertTrue(filterAndSortCatalogue(catalogue, "zzz", StatusFilter.ALL, SortBy.NEWEST).isEmpty())
    }
}
