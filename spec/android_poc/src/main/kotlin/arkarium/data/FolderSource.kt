package arkarium.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pseudocode for a Folder-backed BookSource using SAF DocumentFile.
 * This is intentionally high-level; actual implementation will use DocumentFile APIs and error handling.
 */
class FolderSource(private val treeUri: String /* persistable tree uri */,
                   private val folderDocId: String /* id or relative path */) : BookSource {

    override suspend fun listChapters(): List<Chapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<Chapter>()

        // 1) enumerate files in folderDocId using DocumentFile
        // 2) collect candidate files (txt, md, epub, pdf)
        // 3) apply heuristics (see SCANNER_POC.md) to extract number and title
        // 4) build Chapter(id, number, title, sourcePath)

        // This pseudocode returns a best-effort list; do not throw on parsing errors.
        chapters
    }

    override suspend fun getMetadata(): BookMetadata = withContext(Dispatchers.IO) {
        // Attempt in-order: metadata.json -> cover.* -> folder name
        BookMetadata(id = folderDocId, title = "Unknown Title", author = null, coverUri = null)
    }

    override suspend fun fingerprint(): SourceFingerprint = withContext(Dispatchers.IO) {
        // Use DocumentFile properties where available: lastModified, size, uri
        SourceFingerprint(uri = treeUri + ":" + folderDocId, lastModified = null, size = null)
    }
}
