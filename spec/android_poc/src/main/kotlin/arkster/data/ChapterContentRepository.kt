package arkster.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point to obtain chapter content. The implementation hides TXT/EPUB/PDF differences.
 */
interface ChapterContentRepository {
    suspend fun getTextContent(chapter: Chapter): ChapterContent
}

sealed class ChapterContent {
    data class Text(val body: String) : ChapterContent()
    data class Html(val body: String) : ChapterContent()
    data class PdfPage(val pageIndex: Int) : ChapterContent()
}

/**
 * Simple text-first implementation (v0.1): read file bytes, decode with UTF-8 then fallback to ISO-8859-1.
 */
class TextChapterContentRepository : ChapterContentRepository {
    override suspend fun getTextContent(chapter: Chapter): ChapterContent = withContext(Dispatchers.IO) {
        // Open the DocumentFile/ContentResolver stream for chapter.sourcePath
        // Attempt UTF-8 decode; if fails, try ISO-8859-1.
        ChapterContent.Text(body = "(chapter content)")
    }
}
