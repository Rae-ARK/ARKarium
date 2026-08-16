package arkarium.data

/**
 * Normalized model consumed by UI/ViewModel layers.
 */
data class Chapter(
    val id: String,            // stable id (source-specific)
    val number: Int?,
    val title: String,
    val sourcePath: String     // path/uri to locate content in the source
)

data class BookMetadata(
    val id: String,
    val title: String,
    val author: String?,
    val coverUri: String? // content URI or null
)

/**
 * Source abstraction: Folder / EPUB / PDF implementations return a normalized set of chapters and metadata.
 */
interface BookSource {
    suspend fun listChapters(): List<Chapter>
    suspend fun getMetadata(): BookMetadata
    suspend fun fingerprint(): SourceFingerprint
}

data class SourceFingerprint(
    val uri: String,
    val lastModified: Long?,
    val size: Long?
)
