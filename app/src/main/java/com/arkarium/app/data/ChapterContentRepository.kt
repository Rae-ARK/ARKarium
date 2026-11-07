package com.arkarium.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.CodingErrorAction

sealed class ChapterContent {
    data class Text(val body: String) : ChapterContent()
}

// Thrown by TextChapterContentRepository.getTextContent when the chapter's bytes
// can't be read at all (lost SAF permission, provider hiccup, file moved/deleted
// underneath us). Previously such failures were swallowed into an empty ByteArray,
// which rendered as a silent, indistinguishable-from-genuinely-empty chapter.
class ChapterReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ChapterContentRepository {
    suspend fun getTextContent(sourcePath: String): ChapterContent.Text
}

class TextChapterContentRepository(private val context: Context) : ChapterContentRepository {
    override suspend fun getTextContent(sourcePath: String): ChapterContent.Text = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourcePath)
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowing read failures (lost SAF permission, provider hiccup, file
            // moved/deleted underneath us) here used to render as a silent empty
            // chapter, indistinguishable from a genuinely empty file. Surface it
            // instead so the reader can show a real error with a retry rather than
            // quietly losing the chapter's content.
            throw ChapterReadException("Couldn't read chapter content from $sourcePath", e)
        }
        
        // Try UTF-8 first, fallback to ISO-8859-1. Note: bytes.toString(Charsets.UTF_8)
        // never throws on malformed input (it silently substitutes replacement chars),
        // so we need a strict decoder to actually detect invalid UTF-8 and trigger the
        // fallback instead of rendering mojibake.
        val text = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            bytes.toString(Charsets.ISO_8859_1)
        }
        
        ChapterContent.Text(body = text)
    }
}
