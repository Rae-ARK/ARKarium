package com.arkarium.app.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Scanner implementation for FolderSource-like behavior, built on the DocumentFile API.
 * Works against either a SAF tree (user-picked custom folder) or a plain-file-backed
 * DocumentFile (default app-private storage) - see scanRoot's `root` parameter.
 * Enumerates immediate children, creates novel entries, parses chapters, and detects arcs.
 * Supports incremental rescan via fingerprinting.
 */
class ScannerImpl(private val context: Context) {

    companion object {
        // Bumped because rescan logic changed from delete-all-reinsert to a diff-based
        // upsert, which is required for chapter_overrides to survive a rescan.
        // v4: fingerprint is now computed from actual file contents (see
        // computeFingerprint) instead of the novel folder's own lastModified/length,
        // which many SAF providers don't update on child-file changes. Bumping forces
        // every existing fingerprint to be treated as stale exactly once.
        // v5: parseChapter now classifies bonus/closing content into sort_tier (see
        // bugs.md Bug 2) - bumped so every already-scanned novel gets reparsed once
        // and picks up the new tiers, instead of sitting unclassified until its next
        // unrelated rescan.
        // v6: arc folders are now sorted numerically before ArcEntity.position is
        // assigned (see arcSortNumber/bugs.md Stage 5) instead of trusting SAF
        // listFiles() order. scanChaptersForNovel returns early on an unchanged
        // fingerprint *before* ever reaching arc detection, so an already-scanned
        // novel with arcs stored in the old, possibly-wrong order would never
        // self-correct without this bump forcing one more full rescan.
        private const val CURRENT_SCAN_VERSION = 6

        private val ARC_PATTERNS = listOf(
            Regex("^(?:Arc|Volume|Book|Part)\\s*(\\d+)?", RegexOption.IGNORE_CASE),
            Regex("^(?:弧|卷|部)\\s*(\\d+)?", RegexOption.IGNORE_CASE)
        )

        // Chapter sort tiers - see parseChapter() and bugs.md Bug 2. Regular chapters
        // (0) sort first by their own number/title, exactly as before this fix; bonus
        // content (1) and closing content (2) always sort after every regular chapter
        // in the same folder, in that order.
        private const val TIER_REGULAR = 0
        private const val TIER_BONUS = 1
        private const val TIER_CLOSING = 2

        // Marker prefixes an author can put directly on a filename to control tier,
        // independent of whatever word they use ("Interlude", "SS", "Omake", "番外",
        // ...) - see bugs.md Bug 2 for the full rationale.
        private const val BONUS_MARKER = '~'
        private const val CLOSING_MARKER = '!'

        // Best-effort fallback for files that predate the marker convention and don't
        // use it - keeps existing libraries from regressing to unordered rather than
        // requiring every author to immediately rename every file. Only consulted when
        // no marker prefix is present; once a file is renamed with ~ / ! that's
        // authoritative and these keywords no longer apply to it.
        private val LEGACY_CLOSING_KEYWORDS = listOf("afterword", "author's note", "authors note")
        private val LEGACY_BONUS_KEYWORDS = listOf("interlude", "side story", "omake", "extra chapter", "bonus chapter")
    }

    suspend fun scanRoot(
        // Root of the library. Either a SAF tree (DocumentFile.fromTreeUri, when the user
        // has opted into a custom folder in Settings) or a plain-file-backed DocumentFile
        // (DocumentFile.fromFile, the default app-private storage folder) - both expose
        // the identical DocumentFile API, so nothing below this line needs to know or
        // care which kind of root it was handed. See MainActivity.resolveLibraryRoot.
        root: DocumentFile,
        // Hands back the DocumentFile for the novel's own folder alongside the entity,
        // so callers (e.g. to then scan its chapters/arcs) don't need to re-list the
        // root and search for it by name again - that was previously redone once per
        // novel, making a full scan O(n^2) in the number of novels, and was fragile if
        // a folder happened to share a name with another entry.
        onDiscovered: suspend (NovelEntity, DocumentFile) -> Unit,
        // Fired exactly once per scanRoot call, after the root-level authors/ folder
        // (if any) has been parsed and before novel folders are iterated - callers do
        // the actual DB upsert/stale-removal themselves, same "ScannerImpl never holds
        // a db reference in scanRoot" style already used for onDiscovered above.
        onAuthorsDiscovered: suspend (List<AuthorEntity>) -> Unit = {},
        // Fired exactly once, only after the full children loop below has finished a
        // genuine, uninterrupted pass (i.e. NOT on the early SecurityException/
        // "folder no longer accessible" returns above it, and not per-child - a single
        // child throwing is caught and skipped without aborting the whole loop). This
        // is the caller's cue that it's safe to reconcile "which novels still exist"
        // against the DB - see MainActivity.startScan's seenNovelIds. Firing this on a
        // partial/aborted scan would let a transient SAF error wipe the entire library
        // instead of just leaving it stale until the next successful scan.
        onScanCompleted: suspend () -> Unit = {},
        onProgress: suspend (current: Int, total: Int, message: String) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        // Everything below touches the Storage Access Framework, which throws
        // SecurityException the moment a persisted grant is no longer valid (folder
        // moved/deleted, permission revoked by the system, app data partially wiped,
        // etc). This scan runs unattended on every app launch once a library has been
        // picked once (see MainActivity's libraryUri.collect), so an uncaught exception
        // here previously crashed the app on startup with no way to recover short of
        // clearing app data. Fail soft instead: surface it as a status message and stop
        // the scan for this call, rather than propagating and taking the whole app down.
        try {
            if (!root.canRead()) {
                onProgress(0, 0, "Library folder permission was revoked")
                return@withContext
            }
            // Root-level authors/ folder, parsed once per scan (not per-novel) - see
            // scanAuthorsFolder for the id-collision/fail-soft rules. Built before the
            // novel loop below so each novel's metadata.json can be resolved against it.
            val authorsFolder = findAuthorsFolder(root)
            val discoveredAuthors = if (authorsFolder != null) {
                scanAuthorsFolder(authorsFolder) { message ->
                    onProgress(0, 0, message)
                }
            } else emptyList()
            onAuthorsDiscovered(discoveredAuthors)
            val authorsById = discoveredAuthors.associateBy { it.id }
            // Fallback lookup for fictions that set a free-text `author` but no
            // `authorId` - first author with a given normalized name wins ties, same as
            // the id-collision rule in scanAuthorsFolder.
            val authorIdByNormalizedName = mutableMapOf<String, String>()
            discoveredAuthors.forEach { authorIdByNormalizedName.putIfAbsent(it.name.trim().lowercase(), it.id) }

            val children = root.listFiles()
                // The authors/ folder holds author profiles, not a fiction - never treat
                // it as a novel folder even though it lives at the same level.
                .filterNot { it.isDirectory && it.name?.equals("authors", ignoreCase = true) == true }
            val total = children.size
            children.forEachIndexed { index, child ->
                onProgress(index + 1, total, "Scanning ${child.name}...")
                if (child.isDirectory) {
                    try {
                        val title = child.name ?: "Unknown"
                        // root.uri works out to a stable per-source namespace either way:
                        // a content:// tree URI for SAF, or a file:// URI (Uri.fromFile)
                        // for the default app-storage DocumentFile.fromFile root - so
                        // switching between the two in Settings naturally mints fresh
                        // novel IDs for the newly active source rather than colliding
                        // with IDs from whichever source was active before.
                        val id = UUID.nameUUIDFromBytes((root.uri.toString() + ":" + child.uri.toString()).toByteArray()).toString()
                        val coverUri = findCoverUri(child)
                        // Optional per-novel metadata.json in the novel's own folder, next to
                        // cover.* and the chapter/arc folders - see readLocalMetadata() for the
                        // schema. Lets a user set title/author/description/genres/publishedDate
                        // by hand (or via a script) without needing the "Fetch info" lookup,
                        // which only searches Google Books and mostly won't have web novels.
                        val localMetadata = readLocalMetadata(child)
                        // Resolution order (see "Linking a fiction to an author" in
                        // AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md): explicit authorId first, then a
                        // case-insensitive name fallback, then no link at all.
                        val resolvedAuthorId = localMetadata?.authorId
                            ?.trim()?.lowercase()
                            ?.takeIf { authorsById.containsKey(it) }
                            ?: localMetadata?.author?.let { authorIdByNormalizedName[it.trim().lowercase()] }
                        val novel = NovelEntity(
                            id = id,
                            title = localMetadata?.title?.takeIf { it.isNotBlank() } ?: title,
                            author = localMetadata?.author,
                            authorId = resolvedAuthorId,
                            coverUri = coverUri,
                            description = localMetadata?.description,
                            genres = localMetadata?.genres,
                            publishedDate = localMetadata?.publishedDate
                        )
                        onDiscovered(novel, child)
                    } catch (e: Exception) {
                        // Don't let one bad/inaccessible novel folder abort the whole scan.
                        onProgress(index + 1, total, "Skipped ${child.name}: ${e.message}")
                    }
                }
            }
            onScanCompleted()
            onProgress(total, total, "Scan complete")
        } catch (e: SecurityException) {
            onProgress(0, 0, "Lost access to the library folder - please reselect it")
        } catch (e: Exception) {
            onProgress(0, 0, "Scan failed: ${e.message}")
        }
    }

    suspend fun scanChaptersForNovel(
        novelFolder: DocumentFile,
        novelId: String,
        db: AppDatabase,
        onProgress: suspend (message: String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
      try {
        onProgress("Checking fingerprint...")
        val fingerprint = computeFingerprint(novelFolder, novelId)

        // Check if we can skip rescan (incremental optimization)
        val existingFingerprint = db.scanFingerprintDao().forNovel(novelId)
        if (existingFingerprint != null && existingFingerprint.scanVersion == CURRENT_SCAN_VERSION &&
            existingFingerprint.lastModified == fingerprint.lastModified &&
            existingFingerprint.size == fingerprint.size &&
            existingFingerprint.fileCount == fingerprint.fileCount) {
            // Unchanged, skip rescan
            onProgress("No changes detected")
            return@withContext
        }

        // Diff-based rescan: chapter IDs are deterministic (hashed from novelId + file URI),
        // so upserting a chapter that already exists overwrites it in place without a
        // delete step, which means chapter_overrides (FK CASCADE on chapter_id) is never
        // touched for files that are still present. Overrides only disappear if their
        // underlying chapter file has actually been removed - see removal pass below.
        // This matches the design rule: manual overrides persist across rescans unless
        // the source file itself is gone.

        // Detect arcs (subfolders matching arc patterns) and upsert them in place.
        //
        // listFiles() on a SAF DocumentFile makes no ordering guarantee at all - most
        // providers return entries in filesystem/creation order, not alphabetically -
        // so without an explicit sort, "Arc 2 - Whatever" could easily enumerate before
        // "Arc 1 - Whatever". ArcEntity.position (assigned by index right below) then
        // bakes that arbitrary order in permanently, which is what was surfacing as
        // arcs listed out of order in the fiction page's tabs/table of contents. Sort
        // explicitly by the numeric arc index ARC_PATTERNS already captures (e.g. "2"
        // out of "Arc 2 - Whatever"), numerically rather than lexicographically so
        // "Arc 10" doesn't sort before "Arc 2" - a plain string sort would get that
        // wrong the same way the old unsorted listFiles() order could. Folders that
        // match an arc pattern but have no captured number (just "Arc" / "Volume" with
        // nothing after it) fall back to sorting after every numbered arc, by name.
        onProgress("Detecting arcs...")
        val arcFolders = novelFolder.listFiles()
            .filter { it.isDirectory && isArcFolder(it.name ?: "") }
            .sortedWith(
                compareBy(
                    { arcSortNumber(it.name ?: "") ?: Int.MAX_VALUE },
                    { it.name ?: "" }
                )
            )
        val arcs = mutableMapOf<String, ArcEntity>()
        val seenArcIds = mutableSetOf<String>()
        arcFolders.forEachIndexed { idx, arcFolder ->
            val arcName = arcFolder.name ?: "Arc ${idx + 1}"
            val arcId = UUID.nameUUIDFromBytes("${novelId}:${arcFolder.uri}".toByteArray()).toString()
            val arcCoverUri = findCoverUri(arcFolder)
            val arc = ArcEntity(id = arcId, novelId = novelId, name = arcName, coverUri = arcCoverUri, position = idx)
            db.arcDao().upsert(arc)
            arcs[arcFolder.uri.toString()] = arc
            seenArcIds.add(arcId)
        }
        // Remove arcs whose folder no longer exists (chapters under it fall back to
        // novel-level via the ON DELETE SET NULL foreign key rather than being deleted).
        val existingArcs = db.arcDao().forNovel(novelId)
        existingArcs.filter { it.id !in seenArcIds }.forEach { stale ->
            db.arcDao().delete(stale.id)
        }

        // Parse chapter files from root and arc subfolders, tracking which chapter IDs
        // are still present so we can remove only the ones that truly vanished.
        onProgress("Parsing chapters...")
        val seenChapterIds = mutableSetOf<String>()
        seenChapterIds += parseChaptersInFolder(novelFolder, novelId, null, db)
        for (arcFolder in arcFolders) {
            val arcId = arcs[arcFolder.uri.toString()]?.id
            seenChapterIds += parseChaptersInFolder(arcFolder, novelId, arcId, db)
        }
        val existingChapters = db.chapterDao().forNovel(novelId)
        existingChapters.filter { it.id !in seenChapterIds }.forEach { stale ->
            db.chapterDao().delete(stale.id)
        }

        // Save fingerprint
        onProgress("Saving fingerprint...")
        db.scanFingerprintDao().upsert(fingerprint)
      } catch (e: SecurityException) {
          // Permission to this novel's folder was revoked mid-scan - skip it instead of
          // taking down the whole library scan (and the app) with it.
          onProgress("Lost access to this novel's folder, skipping")
      } catch (e: Exception) {
          onProgress("Failed to scan this novel: ${e.message}")
      }
    }

    private suspend fun parseChaptersInFolder(
        folder: DocumentFile,
        novelId: String,
        arcId: String?,
        db: AppDatabase
    ): List<String> = withContext(Dispatchers.IO) {
        val files = folder.listFiles().filter { it.isFile && it.name?.matches(Regex("(?i).*\\.(txt|md)$")) == true }
        val ids = mutableListOf<String>()
        files.forEachIndexed { idx, file ->
            val parsed = parseChapter(file.name ?: "Chapter ${idx + 1}")
            val chapterId = UUID.nameUUIDFromBytes("${novelId}:${file.uri}".toByteArray()).toString()
            val chapter = ChapterEntity(
                id = chapterId,
                novelId = novelId,
                arcId = arcId,
                number = parsed.number,
                title = parsed.title,
                sortTier = parsed.sortTier,
                sourcePath = file.uri.toString()
            )
            db.chapterDao().upsert(chapter)
            ids.add(chapterId)
        }
        ids
    }

    private fun isArcFolder(folderName: String): Boolean {
        return ARC_PATTERNS.any { it.containsMatchIn(folderName) }
    }

    // Extracts the numeric arc index ARC_PATTERNS captures (e.g. 2 from "Arc 2 -
    // Whatever"/"Volume 2"/"卷2"), trying each pattern in order and returning the
    // first captured number found. Null when the folder matches an arc pattern but
    // has no number after it (e.g. a bare "Arc" folder) - callers sort those last.
    private fun arcSortNumber(folderName: String): Int? {
        for (pattern in ARC_PATTERNS) {
            val match = pattern.find(folderName) ?: continue
            val number = match.groupValues.getOrNull(1)?.toIntOrNull()
            if (number != null) return number
        }
        return null
    }

    private data class ParsedChapter(val number: Int?, val title: String, val sortTier: Int)

    // Determines a chapter's (number, title, sortTier) from its filename. See bugs.md
    // Bug 2: a leading "~" marks bonus/side content (interlude, extra chapter, side
    // story, omake, ...) and a leading "!" marks closing/meta content (afterword,
    // author's note, ...) - both sort after every regular chapter in their folder,
    // closing always last. Markers are stripped before the title is derived, so
    // "!Author's Afterword.txt" displays as "Author's Afterword", not
    // "!Author's Afterword". An optional number right after the marker (e.g.
    // "~2 Side Story.txt") still controls order within that tier.
    private fun parseChapter(filename: String): ParsedChapter {
        val withoutExt = filename.replaceFirst(Regex("\\.(txt|md)$", RegexOption.IGNORE_CASE), "")

        val (marker, rest) = when (withoutExt.firstOrNull()) {
            BONUS_MARKER -> TIER_BONUS to withoutExt.drop(1)
            CLOSING_MARKER -> TIER_CLOSING to withoutExt.drop(1)
            else -> TIER_REGULAR to withoutExt
        }

        // Heuristic: leading number + delimiter, e.g. "026 Not Again" or, once a
        // marker's been stripped, "2 Side Story".
        val numberPattern = Regex("^\\s*(\\d+)\\s+(.+)$")
        val match = numberPattern.matchEntire(rest.trim())
        val number: Int?
        val rawTitle: String
        if (match != null) {
            number = match.groupValues[1].toIntOrNull()
            rawTitle = match.groupValues[2].trim()
        } else {
            number = null
            rawTitle = rest.replace(Regex("[_-]"), " ").trim()
        }
        val title = rawTitle.ifBlank { filename }

        // No marker on this file - fall back to keyword sniffing so libraries that
        // predate the marker convention don't regress to unordered.
        val sortTier = if (marker != TIER_REGULAR) {
            marker
        } else {
            val lower = title.lowercase()
            when {
                LEGACY_CLOSING_KEYWORDS.any { lower.contains(it) } -> TIER_CLOSING
                LEGACY_BONUS_KEYWORDS.any { lower.contains(it) } -> TIER_BONUS
                else -> TIER_REGULAR
            }
        }

        return ParsedChapter(number, title, sortTier)
    }

    // Walks the novel folder (root + one level of arc subfolders, matching where
    // chapters actually live) and aggregates real file metadata, rather than trusting
    // the novel folder DocumentFile's own lastModified()/length(). Many SAF providers
    // leave a directory's own metadata unchanged when a file inside it is added,
    // edited, or removed, which would otherwise make the incremental-rescan check
    // silently miss changes. File count is tracked alongside size/lastModified so a
    // same-size rename (delete A, add B) still changes the fingerprint.
    private fun computeFingerprint(novelFolder: DocumentFile, novelId: String): ScanFingerprintEntity {
        var fileCount = 0
        var maxLastModified = 0L
        var totalSize = 0L

        fun accumulateFiles(folder: DocumentFile) {
            folder.listFiles().forEach { entry ->
                if (entry.isFile) {
                    fileCount++
                    val entryModified = entry.lastModified()
                    if (entryModified > maxLastModified) maxLastModified = entryModified
                    totalSize += try {
                        entry.length()
                    } catch (e: Exception) {
                        0L
                    }
                }
                // Directories at this level are skipped here; they're walked explicitly
                // one level down below, matching where chapters actually live.
            }
        }
        // Root-level files, plus exactly one level into each subfolder (arc folders).
        // Deliberately NOT recursive - a genuinely recursive walk would keep descending
        // into arbitrarily deep folder trees the scanner never reads chapters from,
        // which both wastes time and would make the fingerprint sensitive to changes
        // scanChaptersForNovel() doesn't even look at.
        accumulateFiles(novelFolder)
        novelFolder.listFiles().filter { it.isDirectory }.forEach { accumulateFiles(it) }

        val fingerprintId = UUID.nameUUIDFromBytes("${novelId}:fingerprint".toByteArray()).toString()
        return ScanFingerprintEntity(
            id = fingerprintId,
            novelId = novelId,
            folderUri = novelFolder.uri.toString(),
            lastModified = if (maxLastModified > 0) maxLastModified else null,
            size = totalSize,
            fileCount = fileCount,
            scanVersion = CURRENT_SCAN_VERSION
        )
    }

    // See bugs.md Bug 3a: this used to require the filename to be *exactly*
    // "cover.<ext>", so per-arc covers named e.g. "Arc1_Cover.jpg" or "cover (1).png"
    // were silently skipped. Now: prefer an exact "cover.<ext>" match if one exists
    // (keeps today's convention as the unambiguous default), otherwise fall back to
    // any image in the folder whose name contains "cover" as a standalone word.
    private fun findCoverUri(folder: DocumentFile): String? {
        val images = folder.listFiles().filter {
            it.isFile && it.name?.matches(Regex("(?i).*\\.(jpg|jpeg|png|webp)$")) == true
        }
        images.firstOrNull { it.name?.matches(Regex("(?i)cover\\.(jpg|jpeg|png|webp)$")) == true }
            ?.let { return it.uri.toString() }
        return images.firstOrNull { image ->
            val base = image.name?.substringBeforeLast('.') ?: return@firstOrNull false
            Regex("(?i)(^|[^a-z0-9])cover([^a-z0-9]|$)").containsMatchIn(base)
        }?.uri?.toString()
    }

    private data class LocalMetadata(
        val title: String?,
        val author: String?,
        val authorId: String?,
        val description: String?,
        val genres: String?,
        val publishedDate: String?
    )

    // Reads an optional `metadata.json` sitting directly in the novel's folder, e.g.:
    // {
    //   "title": "Summoned By Mistake, I Decided To Learn How To Live",
    //   "author": "Some Author",
    //   "authorId": "rae-ark",
    //   "description": "A short synopsis...",
    //   "genres": ["Fantasy", "Isekai"],
    //   "publishedDate": "2023"
    // }
    // All fields are optional and applied only where present - a missing/unparseable
    // file just means no local metadata, never a scan failure (this is loaded once per
    // novel on every scan, so a bad file simply doesn't override anything that scan).
    // "genres" accepts either a JSON array of strings or a single comma-separated string.
    // "authorId" links this fiction to authors/<authorId>.json - see
    // AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md's "Linking a fiction to an author".
    private fun readLocalMetadata(folder: DocumentFile): LocalMetadata? {
        val file = folder.listFiles().firstOrNull {
            it.isFile && it.name?.equals("metadata.json", ignoreCase = true) == true
        } ?: return null
        return try {
            val text = context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() } ?: return null
            val json = JSONObject(text)
            val genresArray = json.optJSONArray("genres")
            val genres = if (genresArray != null) {
                (0 until genresArray.length()).joinToString(", ") { genresArray.optString(it) }
            } else {
                json.optString("genres").takeIf { it.isNotBlank() }
            }
            LocalMetadata(
                title = json.optString("title").takeIf { it.isNotBlank() },
                author = json.optString("author").takeIf { it.isNotBlank() },
                authorId = json.optString("authorId").takeIf { it.isNotBlank() },
                description = json.optString("description").takeIf { it.isNotBlank() },
                genres = genres,
                publishedDate = json.optString("publishedDate").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            // Malformed metadata.json shouldn't take down the scan for this novel - it
            // just means this novel falls back to folder-name title / no metadata, same
            // as if the file weren't there.
            null
        }
    }

    private data class AuthorMetadata(
        val id: String?,
        val name: String?,
        val avatar: String?,
        val bio: String?,
        val joined: String?,
        val location: String?,
        val gender: String?,
        val linksJson: String?,
        val followers: Int?,
        val favorites: Int?,
        val reviewsReceived: Int?,
        val ratingsReceived: Int?
    )

    private fun findAuthorsFolder(root: DocumentFile): DocumentFile? =
        root.listFiles().firstOrNull { it.isDirectory && it.name?.equals("authors", ignoreCase = true) == true }

    // Resolves an author.json "avatar" filename against the authors/ folder, the same
    // way findCoverUri resolves cover.* against a novel folder.
    private fun resolveAuthorAvatarUri(authorsFolder: DocumentFile, avatarFilename: String): String? =
        authorsFolder.listFiles().firstOrNull {
            it.isFile && it.name?.equals(avatarFilename, ignoreCase = true) == true
        }?.uri?.toString()

    // Convention-based fallback for when author.json doesn't specify an "avatar" (or
    // has none at all): a same-folder image named after the author, e.g.
    // "authors/<name>.png" or "authors/<id>.png", the same pattern as a novel/arc's
    // cover.*. Tries the id first (usually already filename-safe, lowercase) then the
    // display name, and accepts jpg/jpeg/png/webp.
    private fun findAuthorAvatarUri(authorsFolder: DocumentFile, id: String, name: String): String? {
        val images = authorsFolder.listFiles().filter {
            it.isFile && it.name?.matches(Regex("(?i).*\\.(jpg|jpeg|png|webp)$")) == true
        }
        fun matching(key: String): String? = images.firstOrNull {
            it.name?.substringBeforeLast('.')?.equals(key, ignoreCase = true) == true
        }?.uri?.toString()
        return matching(id) ?: matching(name)
    }

    // Reads and parses one authors/<id>.json file. See "authors/<id>.json schema" in
    // AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md for the full field list. All fields optional;
    // malformed JSON just means this file contributes nothing (fail soft, same
    // philosophy as readLocalMetadata above) - it's up to the caller
    // (scanAuthorsFolder) to still fall back to the filename for the id/name in that case.
    private fun readAuthorMetadata(file: DocumentFile): AuthorMetadata? {
        return try {
            val text = context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() } ?: return null
            val json = JSONObject(text)
            val links = json.optJSONObject("links")
            val stats = json.optJSONObject("stats")
            AuthorMetadata(
                id = json.optString("id").takeIf { it.isNotBlank() },
                name = json.optString("name").takeIf { it.isNotBlank() },
                avatar = json.optString("avatar").takeIf { it.isNotBlank() },
                bio = json.optString("bio").takeIf { it.isNotBlank() },
                joined = json.optString("joined").takeIf { it.isNotBlank() },
                location = json.optString("location").takeIf { it.isNotBlank() },
                gender = json.optString("gender").takeIf { it.isNotBlank() },
                linksJson = links?.toString(),
                followers = stats?.takeIf { it.has("followers") }?.optInt("followers"),
                favorites = stats?.takeIf { it.has("favorites") }?.optInt("favorites"),
                reviewsReceived = stats?.takeIf { it.has("reviews_received") }?.optInt("reviews_received"),
                ratingsReceived = stats?.takeIf { it.has("ratings_received") }?.optInt("ratings_received")
            )
        } catch (e: Exception) {
            null
        }
    }

    // Parses every authors/<id>.json file in the given authors/ folder into an
    // AuthorEntity. The filename (minus extension) is the id unless the json's own
    // "id" field overrides it. Ids are never hand-validated for global uniqueness -
    // filenames are already unique within a folder by construction, so the only
    // possible collision is two files whose "id" override happens to match; when that
    // happens the file seen first (folder listing order) wins and the later one is
    // skipped with a soft warning via onProgress, same fail-soft philosophy as every
    // other malformed/ambiguous input this scanner already tolerates - never a thrown
    // error, never an aborted scan.
    private suspend fun scanAuthorsFolder(
        authorsFolder: DocumentFile,
        onProgress: suspend (message: String) -> Unit
    ): List<AuthorEntity> {
        val files = authorsFolder.listFiles().filter {
            it.isFile && it.name?.endsWith(".json", ignoreCase = true) == true
        }
        val result = mutableListOf<AuthorEntity>()
        val seenIds = mutableSetOf<String>()
        for (file in files) {
            val filenameId = file.name?.let {
                it.substring(0, it.length - ".json".length).trim().lowercase()
            }
            val parsed = readAuthorMetadata(file)
            val id = parsed?.id?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: filenameId
            if (id.isNullOrBlank()) continue
            if (!seenIds.add(id)) {
                // Collision on an explicit "id" override (or, theoretically, two files
                // differing only by case) - keep the first one seen, skip this one, and
                // surface it the same soft-warning way every other scan issue already is.
                onProgress("Skipped ${file.name}: author id \"$id\" is already used by another file in authors/")
                continue
            }
            val name = parsed?.name?.takeIf { it.isNotBlank() }
                ?: file.name?.let { it.substring(0, it.length - 5) }
                ?: id
            result.add(
                AuthorEntity(
                    id = id,
                    name = name,
                    // Explicit "avatar" field in author.json wins if it resolves;
                    // otherwise fall back to a same-folder authors/<id-or-name>.png
                    // (jpg/webp also accepted) - see findAuthorAvatarUri above.
                    avatarUri = parsed?.avatar?.let { resolveAuthorAvatarUri(authorsFolder, it) }
                        ?: findAuthorAvatarUri(authorsFolder, id, name),
                    bio = parsed?.bio,
                    joined = parsed?.joined,
                    location = parsed?.location,
                    gender = parsed?.gender,
                    linksJson = parsed?.linksJson,
                    followers = parsed?.followers,
                    favorites = parsed?.favorites,
                    reviewsReceived = parsed?.reviewsReceived,
                    ratingsReceived = parsed?.ratingsReceived,
                    sourcePath = file.uri.toString()
                )
            )
        }
        return result
    }
}
