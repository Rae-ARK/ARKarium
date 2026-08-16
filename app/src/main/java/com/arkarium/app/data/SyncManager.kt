package com.arkarium.app.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

// See docs/arkarium/SYNC_MVP.md for the full design. This file is the sync engine itself -
// manifest fetch/parse, hash diff, and the actual file IO. It never touches Room
// directly (no AppDatabase/Dao references anywhere below); callers persist the
// SyncOutcome it returns. That mirrors ScannerImpl's own split between "walk
// DocumentFiles and figure out what changed" and "the caller decides what to do with
// that in the DB" - keeps this class testable without a database and keeps the two
// concerns (network/IO vs. persistence) from tangling.

// One entry from a manifest.json's "files" map - see docs/arkarium/SYNC_MVP.md §1. relativePath
// here has already been through SyncPaths.sanitize by the time a Manifest exists; a
// Manifest is never constructed with an unsafe path in it.
data class ManifestFileEntry(
    val relativePath: String,
    val size: Long,
    val sha256: String
)

data class Manifest(
    val version: Int,
    val files: List<ManifestFileEntry>
)

class ManifestParseException(message: String) : IOException(message)

// Thrown when a manifest file entry's path would escape the novel's own folder (see
// SyncPaths.sanitize). Treated as a whole-manifest failure rather than skipping just
// that one entry - a manifest containing an unsafe path is a trust problem with the
// manifest itself (see docs/arkarium/SYNC_MVP.md "Future considerations" #2), not a one-off bad
// file worth silently dropping and continuing past.
class UnsafePathException(path: String) : IOException("Unsafe path in manifest: $path")

// Thrown specifically when a relay 404s on a novel's manifest.json - distinguished from
// a generic IOException so callers can tell "this source doesn't exist anymore" apart
// from a transient network blip (see docs/arkarium/NEXT_FIXES.md #2). Never thrown for a 404 on
// an individual file download - only the manifest fetch itself, since that's the one
// request whose absence means "this fiction isn't served here anymore" rather than "one
// file is temporarily unreachable."
class SourceGoneException(baseUrl: String) : IOException("No manifest found at $baseUrl - this source may no longer be available")

// Thrown by SyncManager.sync() when a previously-synced novel's on-disk folder can't be
// found by either id (findNovelFolder) or its deterministic slug name, and the caller
// hasn't explicitly opted into recreating it (see docs/arkarium/NEXT_FIXES.md #2). Distinguishes
// "the user (or something else) removed this folder" from every other sync failure, so
// a caller can ask before silently redownloading the whole fiction.
class MissingLocalFolderException(val novelId: String) : IOException("Local folder for novel $novelId is missing")

object SyncPaths {
    // Rejects absolute paths, empty/"."/".." segments, and backslashes before a
    // manifest path is ever used to create a file or directory. A manifest is
    // untrusted input the moment "Add fiction from URL" accepts an arbitrary base
    // URL - see docs/arkarium/SYNC_MVP.md "Future considerations" #2.
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

fun relayBaseUrlForSlug(slug: String): String = "$RELAY_HOST$slug/"

// Talks to a relay: fetches and parses manifest.json, downloads individual files.
// Plain HttpURLConnection on Dispatchers.IO, same convention as
// GoogleBooksMetadataProvider - no new HTTP client dependency for one GET-only use case.
class SyncClient {
    suspend fun fetchManifest(baseUrl: String): Manifest = withContext(Dispatchers.IO) {
        // manifestBaseUrl is threaded through so a 404 here specifically (as opposed to
        // a 404 on an individual file download below) surfaces as SourceGoneException -
        // see docs/arkarium/NEXT_FIXES.md #2.
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
            files.add(ManifestFileEntry(relativePath = SyncPaths.sanitize(path), size = size, sha256 = sha256))
        }
        return Manifest(version = version, files = files)
    }
}

// Result of one sync pass against a relay - callers persist this into
// NovelDao.updateSyncState + SyncedFileDao themselves; SyncManager never writes to
// Room. `files` is the *complete* new set of synced files (unchanged entries carried
// over, not just deltas), so a caller can replace its SyncedFileEntity rows wholesale
// rather than patch them.
data class SyncOutcome(
    val newVersion: Int,
    val files: List<SyncedFileEntity>,
    val changed: Boolean  // false when the manifest version already matched - nothing was downloaded or deleted
)

// Handles the file IO for a sync pass: downloading new/changed files into a novel's
// on-disk folder, and removing files the new manifest no longer lists (see
// docs/arkarium/SYNC_MVP.md "Future considerations" #1). Never deletes or overwrites a file
// this class didn't itself write - see the `existing`/SyncedFileEntity plumbing below,
// which is exactly what limits a resync's blast radius to files sync actually owns
// (Consideration #3).
class SyncManager(private val context: Context, private val client: SyncClient = SyncClient()) {

    companion object {
        // Re-derives which DocumentFile under libraryRoot belongs to a given novel id,
        // without needing to persist a separate folder reference across app restarts.
        // Mirrors ScannerImpl.scanRoot's own id formula exactly
        // (UUID.nameUUIDFromBytes(root.uri + ":" + child.uri)) - since sync always
        // reuses a folder the scanner itself discovered (see docs/arkarium/SYNC_MVP.md §4), this
        // is guaranteed to find the same folder the scanner assigned that id to, in
        // either storage mode (SAF tree or default app-private dir).
        fun findNovelFolder(libraryRoot: DocumentFile, novelId: String): DocumentFile? =
            libraryRoot.listFiles().firstOrNull { child ->
                UUID.nameUUIDFromBytes((libraryRoot.uri.toString() + ":" + child.uri.toString()).toByteArray())
                    .toString() == novelId
            }

        // Deterministic per-URL folder name for a brand-new synced fiction, used before
        // its own metadata.json (itself part of the synced file set) has been
        // downloaded and can supply a real title. ScannerImpl.scanRoot falls back to
        // the folder name as the title until then, same as any manually-dropped-in
        // folder with no metadata.json - see docs/arkarium/SYNC_MVP.md §4. Deterministic so
        // re-adding the same source URL reuses the same folder rather than creating a
        // duplicate novel.
        fun slugForUrl(baseUrl: String): String = "synced-" + sha256Hex(baseUrl.toByteArray()).take(10)
    }

    // Creates (or reuses) a folder under libraryRoot for a brand-new fiction source and
    // downloads every file the manifest lists into it. The returned SyncOutcome's files
    // all carry novelId = "" - the real novel id isn't known until the very next
    // library scan discovers this folder for the first time, same as any other novel
    // folder (see docs/arkarium/SYNC_MVP.md §4). Callers must .copy(novelId = ...) each entry
    // once that id is known, before persisting.
    suspend fun downloadInitial(
        baseUrl: String,
        libraryRoot: DocumentFile,
        onProgress: suspend (message: String) -> Unit = {}
    ): Pair<String, SyncOutcome> = withContext(Dispatchers.IO) {
        onProgress("Fetching manifest...")
        val manifest = client.fetchManifest(baseUrl)
        val slug = slugForUrl(baseUrl)
        val folder = libraryRoot.findFile(slug)
            ?: libraryRoot.createDirectory(slug)
            ?: throw IOException("Could not create a folder for this fiction")

        val records = downloadManifestFiles(baseUrl, manifest, folder, libraryRoot, existing = emptyList(), onProgress)
        slug to SyncOutcome(newVersion = manifest.version, files = records, changed = true)
    }

    // Re-syncs an already-added fiction. Fetches the manifest and returns immediately
    // (changed = false) if `novel.syncSourceVersion` already matches - see
    // docs/arkarium/SYNC_MVP.md "Future considerations" #5, this is the cheap-skip the schema's
    // sync_source_version column exists to enable. Otherwise downloads new/changed
    // files and deletes local files the new manifest no longer lists.
    // `allowRecreateMissingFolder` defaults to false: if the novel's folder can't be
    // found by either its derived id (findNovelFolder) or its deterministic slug name,
    // this throws MissingLocalFolderException rather than silently recreating it and
    // redownloading everything - see docs/arkarium/NEXT_FIXES.md #2. The caller (MainActivity)
    // only ever passes true once the user has explicitly confirmed they want that
    // (the "Sync again" resolution action), never from an automatic/background check.
    suspend fun sync(
        novel: NovelEntity,
        libraryRoot: DocumentFile,
        knownFiles: List<SyncedFileEntity>,
        allowRecreateMissingFolder: Boolean = false,
        onProgress: suspend (message: String) -> Unit = {}
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val baseUrl = novel.syncSourceUrl
            ?: throw IllegalStateException("Novel ${novel.id} has no sync source URL")

        onProgress("Fetching manifest...")
        val manifest = client.fetchManifest(baseUrl)

        if (novel.syncSourceVersion != null && novel.syncSourceVersion == manifest.version) {
            onProgress("Already up to date")
            return@withContext SyncOutcome(newVersion = manifest.version, files = knownFiles, changed = false)
        }

        // findNovelFolder first (re-derives the folder from novel.id, the normal case),
        // then fall back to the deterministic slug name via findFile - mirroring
        // downloadInitial's own findFile-before-createDirectory pattern (see
        // docs/arkarium/NEXT_FIXES.md #1) so a folder that already exists under that slug name
        // (e.g. left over from an interrupted previous sync) is reused instead of
        // colliding with a same-named duplicate. Only creates a brand-new folder, and
        // only when the caller has explicitly allowed it, once both lookups miss.
        val folder = findNovelFolder(libraryRoot, novel.id)
            ?: libraryRoot.findFile(slugForUrl(baseUrl))
            ?: if (allowRecreateMissingFolder) {
                libraryRoot.createDirectory(slugForUrl(baseUrl))
                    ?: throw IOException("Could not find or recreate this fiction's folder")
            } else {
                throw MissingLocalFolderException(novel.id)
            }

        val records = downloadManifestFiles(baseUrl, manifest, folder, libraryRoot, existing = knownFiles, onProgress)

        // Delete local files the new manifest no longer lists (docs/arkarium/SYNC_MVP.md
        // "Future considerations" #1) - only ever files previously tracked in
        // knownFiles, i.e. files this sync client itself wrote. A hand-added or
        // manually-edited file living alongside synced content in the same folder is
        // never in knownFiles, so it's never touched here (Consideration #3).
        val newPaths = records.map { it.relativePath }.toSet()
        val removed = knownFiles.filterNot { it.relativePath in newPaths }
        for (stale in removed) {
            onProgress("Removing ${stale.relativePath}...")
            // authors/... entries were written under libraryRoot itself (see
            // downloadManifestFiles below), not under this novel's own folder - route
            // the deletion to whichever root actually holds the file.
            if (stale.relativePath.startsWith("authors/")) {
                deleteRelativePath(libraryRoot, stale.relativePath)
            } else {
                deleteRelativePath(folder, stale.relativePath)
            }
        }

        SyncOutcome(newVersion = manifest.version, files = records, changed = true)
    }

    // Downloads whatever in `manifest` isn't already present-and-unchanged in
    // `existing` (by path+hash+size), and returns the complete new file-record set
    // (unchanged entries carried over as-is). Every downloaded file's hash is verified
    // against the manifest's claimed sha256 before it's written or counted as synced -
    // per docs/arkarium/SYNC_MVP.md "Future considerations" #4, a file is only ever recorded as
    // synced once it's actually been verified and written successfully; a failure here
    // throws and aborts the whole sync pass rather than committing a partial result,
    // which is why this whole method's caller (sync/downloadInitial) never persists
    // sourceVersion until every file has succeeded.
    private suspend fun downloadManifestFiles(
        baseUrl: String,
        manifest: Manifest,
        folder: DocumentFile,
        libraryRoot: DocumentFile,
        existing: List<SyncedFileEntity>,
        onProgress: suspend (message: String) -> Unit
    ): List<SyncedFileEntity> {
        val existingByPath = existing.associateBy { it.relativePath }
        val records = mutableListOf<SyncedFileEntity>()
        manifest.files.forEachIndexed { index, entry ->
            val current = existingByPath[entry.relativePath]
            if (current != null && current.sha256 == entry.sha256 && current.size == entry.size) {
                records.add(current)
                return@forEachIndexed
            }
            onProgress("Downloading ${entry.relativePath} (${index + 1}/${manifest.files.size})...")
            val bytes = client.downloadFile(baseUrl, entry.relativePath)
            val actualHash = sha256Hex(bytes)
            if (actualHash != entry.sha256) {
                throw IOException("Hash mismatch for ${entry.relativePath} - download corrupted or manifest is stale")
            }
            // ScannerImpl.findAuthorsFolder only ever looks for "authors" as a direct
            // child of the library root, not inside a per-novel folder - so an
            // "authors/..." manifest entry has to land at libraryRoot/authors/... to
            // ever be picked up as a real author profile, not folder/authors/....
            // Everything else stays scoped to this novel's own folder, same as before.
            if (entry.relativePath.startsWith("authors/")) {
                writeRelativePath(libraryRoot, entry.relativePath, bytes)
            } else {
                writeRelativePath(folder, entry.relativePath, bytes)
            }
            records.add(
                SyncedFileEntity(
                    novelId = "",  // filled in by the caller once the real novel id is known
                    relativePath = entry.relativePath,
                    sha256 = entry.sha256,
                    size = entry.size
                )
            )
        }
        return records
    }

    // Writes bytes to folder/relativePath, creating any intermediate arc subfolders
    // (e.g. "arcs/arc-1/001.txt") along the way. relativePath has already been through
    // SyncPaths.sanitize by construction (see Manifest/ManifestFileEntry above), so no
    // further validation happens here.
    private fun writeRelativePath(folder: DocumentFile, relativePath: String, bytes: ByteArray) {
        val segments = relativePath.split("/")
        var dir = folder
        for (dirName in segments.dropLast(1)) {
            dir = dir.findFile(dirName)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(dirName)
                ?: throw IOException("Could not create folder $dirName")
        }
        val fileName = segments.last()
        // Replace-on-write, matching docs/arkarium/SYNC_MVP.md §3 ("no delta/binary patching,
        // whole-file replace on any mismatch") rather than trying to patch in place.
        dir.findFile(fileName)?.delete()
        val newFile = dir.createFile(guessMimeType(fileName), fileName)
            ?: throw IOException("Could not create file $fileName")
        context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(bytes) }
            ?: throw IOException("Could not open $fileName for writing")
    }

    private fun deleteRelativePath(folder: DocumentFile, relativePath: String) {
        val segments = relativePath.split("/")
        var dir: DocumentFile? = folder
        for (dirName in segments.dropLast(1)) {
            dir = dir?.findFile(dirName)
            if (dir == null) return
        }
        dir?.findFile(segments.last())?.delete()
    }

    private fun guessMimeType(fileName: String): String = when {
        fileName.endsWith(".json", ignoreCase = true) -> "application/json"
        fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
        fileName.endsWith(".md", ignoreCase = true) -> "text/markdown"
        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "application/octet-stream"
    }
}
