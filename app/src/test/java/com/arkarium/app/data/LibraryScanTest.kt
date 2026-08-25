package com.arkarium.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// mergeNovelForRescan is deliberately plain Kotlin (see its doc comment in
// LibraryScan.kt) so it can run here on the JVM with plain JUnit - no Robolectric, no
// instrumented device/emulator, no Room. See docs/arkarium/REFACTOR_PLAN.md Stage 2.1.
class LibraryScanTest {

    // Minimal-diff fixture builder: every test below only overrides the fields it
    // actually cares about, so a change to an unrelated NovelEntity column doesn't
    // require touching every test.
    private fun novel(
        id: String = "novel-1",
        title: String = "Some Novel",
        author: String? = "Some Author",
        authorId: String? = null,
        pageSize: Int = 10,
        readingStatus: String = NovelStatus.NOT_STARTED.name,
        description: String? = null,
        genres: String? = null,
        remoteCoverUrl: String? = null,
        publishedDate: String? = null,
        externalSourceUrl: String? = null,
        metadataFetchedAt: Long? = null,
        syncSourceUrl: String? = null,
        syncSourceVersion: Int? = null,
        lastSyncedAt: Long? = null,
        syncStatus: String = SyncStatus.ACTIVE.name
    ) = NovelEntity(
        id = id,
        title = title,
        author = author,
        authorId = authorId,
        coverUri = null,
        pageSize = pageSize,
        readingStatus = readingStatus,
        description = description,
        genres = genres,
        remoteCoverUrl = remoteCoverUrl,
        publishedDate = publishedDate,
        externalSourceUrl = externalSourceUrl,
        metadataFetchedAt = metadataFetchedAt,
        syncSourceUrl = syncSourceUrl,
        syncSourceVersion = syncSourceVersion,
        lastSyncedAt = lastSyncedAt,
        syncStatus = syncStatus
    )

    @Test
    fun `no existing row - scanned novel passes through unchanged`() {
        val scanned = novel(pageSize = 10, readingStatus = NovelStatus.NOT_STARTED.name)

        val result = mergeNovelForRescan(scanned, existing = null)

        assertEquals(scanned, result)
    }

    @Test
    fun `user-set fields survive a rescan even though the scanner never sets them`() {
        val scanned = novel(pageSize = 10, readingStatus = NovelStatus.NOT_STARTED.name)
        val existing = novel(pageSize = 25, readingStatus = NovelStatus.IN_PROGRESS.name)

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals(25, result.pageSize)
        assertEquals(NovelStatus.IN_PROGRESS.name, result.readingStatus)
    }

    @Test
    fun `remote metadata is kept once fetched, even if the local scan found its own`() {
        val scanned = novel(description = "Local blurb from metadata.json", genres = "Fantasy")
        val existing = novel(
            description = "Curated remote blurb",
            genres = "Isekai, Fantasy",
            metadataFetchedAt = 1_000L
        )

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals("Curated remote blurb", result.description)
        assertEquals("Isekai, Fantasy", result.genres)
    }

    @Test
    fun `local scan metadata fills in only when nothing has ever been fetched remotely`() {
        val scanned = novel(description = "Local blurb from metadata.json", genres = "Fantasy")
        val existing = novel(description = null, genres = null, metadataFetchedAt = null)

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals("Local blurb from metadata.json", result.description)
        assertEquals("Fantasy", result.genres)
    }

    @Test
    fun `authorId from the scan wins when the authors folder was actually found`() {
        val scanned = novel(authorId = null)
        val existing = novel(authorId = "author-42")

        val result = mergeNovelForRescan(scanned, existing, authorsFolderFound = true)

        assertNull(result.authorId)
    }

    @Test
    fun `authorId falls back to the existing link when the authors folder wasn't found this pass`() {
        val scanned = novel(authorId = null)
        val existing = novel(authorId = "author-42")

        val result = mergeNovelForRescan(scanned, existing, authorsFolderFound = false)

        assertEquals("author-42", result.authorId)
    }

    @Test
    fun `sync bookkeeping is always carried over from the existing row, scanner never sets it`() {
        val scanned = novel(syncSourceUrl = null, syncSourceVersion = null, lastSyncedAt = null)
        val existing = novel(
            syncSourceUrl = "https://relay.example/manifest.json",
            syncSourceVersion = 3,
            lastSyncedAt = 5_000L,
            syncStatus = SyncStatus.MISSING_LOCALLY.name
        )

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals("https://relay.example/manifest.json", result.syncSourceUrl)
        assertEquals(3, result.syncSourceVersion)
        assertEquals(5_000L, result.lastSyncedAt)
        assertEquals(SyncStatus.MISSING_LOCALLY.name, result.syncStatus)
    }
}
