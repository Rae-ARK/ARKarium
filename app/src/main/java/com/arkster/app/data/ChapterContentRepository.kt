package com.arkster.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.CodingErrorAction

sealed class ChapterContent {
    data class Text(val body: String) : ChapterContent()
}

interface ChapterContentRepository {
    suspend fun getTextContent(sourcePath: String): ChapterContent.Text
}

class TextChapterContentRepository(private val context: Context) : ChapterContentRepository {
    override suspend fun getTextContent(sourcePath: String): ChapterContent.Text = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourcePath)
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
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
