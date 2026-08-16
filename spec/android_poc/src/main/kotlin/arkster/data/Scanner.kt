package arkster.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Incremental scanner pseudocode. Emits discovered books progressively so the UI can update.
 * In production this will be a suspend function running in a Worker or CoroutineScope.
 */
class Scanner(private val rootTreeUri: String) {

    /**
     * Emits progress events: discovered book sources and summary when complete.
     */
    fun scanIncremental(): Flow<ScanEvent> = flow {
        // 1) DocumentFile.fromTreeUri(rootTreeUri) -> list children
        // 2) For each child that looks like a book folder/file:
        //    - create BookSource (FolderSource or single-file source)
        //    - compute fingerprint
        //    - consult Room: if fingerprint unchanged -> reuse metadata
        //    - else call listChapters() and getMetadata(), upsert into Room
        //    - emit ScanEvent.BookDiscovered(bookId, title)
        // 3) After processing, emit ScanEvent.Completed(summary)
    }
}

sealed class ScanEvent {
    data class BookDiscovered(val id: String, val title: String) : ScanEvent()
    data class Error(val id: String?, val reason: String) : ScanEvent()
    data class Completed(val scanned: Int, val added: Int, val updated: Int) : ScanEvent()
}
