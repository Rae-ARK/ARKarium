package com.arkarium.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// mergeNovelForRescan is deliberately plain Kotlin (see its doc comment in
// LibraryScan.kt) so it can run here on the JVM with plain JUnit - no Robolectric, no
// instrumented device/emulator, no Room. See docs/foundational/design-notes.md Stage 2.1.
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
        syncStatus: String = SyncStatus.ACTIVE.name,
        notifyNewChapters: Boolean = false,
        syncFolderName: String? = null
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
        syncStatus = syncStatus,
        notifyNewChapters = notifyNewChapters,
        syncFolderName = syncFolderName
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

    // ScannerImpl.buildNovelEntity can now set syncSourceUrl on `scanned` itself (a
    // FictionLut title match - see that function's comment and
    // docs/implementation/engineering-backlog.md Rung 11), for a folder
    // that reached the library by some route other than this app's own sync and so
    // never got a chance to earn the column the normal way. This is the fill-in half
    // of that fix: a novel that was scanned (and stored) as purely local before this
    // recognition existed - or before the folder was ever renamed to match a published
    // title - picks up syncSourceUrl on the very next rescan instead of staying stuck
    // local forever.
    @Test
    fun `a previously-local novel this scan newly recognizes as a Rae ARK fiction gets linked`() {
        val scanned = novel(syncSourceUrl = "https://novels.horizonarkstudio.workers.dev/sbm/")
        val existing = novel(syncSourceUrl = null, syncSourceVersion = null, lastSyncedAt = null)

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals("https://novels.horizonarkstudio.workers.dev/sbm/", result.syncSourceUrl)
        // Only the URL is filled in by recognition - version/lastSyncedAt stay null
        // until an actual sync pass runs; recognizing a fiction is not the same as
        // having synced it.
        assertNull(result.syncSourceVersion)
        assertNull(result.lastSyncedAt)
    }

    // The fill-in direction only ever applies when the existing row has nothing -
    // it must never override a syncSourceUrl that's already set, whether that's a
    // different source entirely or this same fiction already linked the normal way.
    @Test
    fun `an already-linked novel's sync source is never replaced by what a rescan recognizes`() {
        val scanned = novel(syncSourceUrl = "https://novels.horizonarkstudio.workers.dev/sbm/")
        val existing = novel(syncSourceUrl = "https://relay.example/manifest.json", syncSourceVersion = 3)

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals("https://relay.example/manifest.json", result.syncSourceUrl)
        assertEquals(3, result.syncSourceVersion)
    }

    // Settings-wipe bug: ScannerImpl never sets notifyNewChapters, so `scanned`
    // always has it at the NovelEntity default (false) - if the merge didn't
    // explicitly carry the existing row's value forward, a user turning the
    // toggle on would see it silently flip back off on the very next rescan
    // (i.e. the next cold launch, since startScan always runs one).
    @Test
    fun `notify-new-chapters toggle survives a rescan even though the scanner never sets it`() {
        val scanned = novel(notifyNewChapters = false)
        val existing = novel(notifyNewChapters = true)

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals(true, result.notifyNewChapters)
    }

    // Same bug, for the column SyncManager relies on as its primary folder lookup
    // (see syncFolderName's doc comment on NovelEntity) - ScannerImpl never sets
    // it either, so without carrying it forward here, the very next rescan after
    // a sync backfilled it would null it back out, forcing every later sync to
    // fall back to a slug guess that can miss a folder whose on-disk name
    // doesn't match the slug.
    @Test
    fun `sync folder name survives a rescan even though the scanner never sets it`() {
        val scanned = novel(syncFolderName = null)
        val existing = novel(syncFolderName = "Summoned By Mistake")

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals("Summoned By Mistake", result.syncFolderName)
    }

    // The fill-in direction (same shape as syncSourceUrl above) matters here too,
    // in case a future ScannerImpl change ever does start setting syncFolderName
    // on `scanned` itself - it must never clobber a folder name already recorded.
    @Test
    fun `an already-recorded sync folder name is never replaced by what a rescan finds`() {
        val scanned = novel(syncFolderName = "summoned-by-mistake-slug")
        val existing = novel(syncFolderName = "Summoned By Mistake")

        val result = mergeNovelForRescan(scanned, existing)

        assertEquals("Summoned By Mistake", result.syncFolderName)
    }
}
