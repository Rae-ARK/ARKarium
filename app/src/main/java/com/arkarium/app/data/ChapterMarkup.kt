package com.arkarium.app.data

// Lightweight inline markup for fiction chapter bodies, parsed out of the plain-text
// chapter file before ReaderScreen renders it. Deliberately NOT XML/HTML - no nesting,
// no attributes, no entity-escaping - just three tag pairs an author can drop directly
// into a chapter .txt file:
//
//   <chapter break></chapter break>  - a centered scene/chapter separator. Usually
//                                      empty (renders a plain glyph divider); any text
//                                      between the tags is shown centered rather than
//                                      the body's normal justified alignment.
//   <author>...</author>             - "A Note from the Author" callout.
//   <news>...</news>                 - a "News" callout (schedule changes,
//                                      announcements, etc).
//
// A chapter with none of these tags parses to a single Paragraph segment covering the
// whole body - same text, same rendering as before this existed. Unrecognized/unmatched
// tags (a stray "<author>" with no closing tag) are left as literal text in the output
// rather than silently eaten or thrown as a parse error - a typo should be visible on
// the page, not swallow the rest of the chapter.
sealed class ChapterSegment {
    data class Paragraph(val text: String) : ChapterSegment()
    data class ChapterBreak(val label: String) : ChapterSegment()
    data class AuthorNote(val text: String) : ChapterSegment()
    data class News(val text: String) : ChapterSegment()
}

private class TagSpec(val build: (String) -> ChapterSegment, val open: Regex, val close: Regex)

// "chapter break" requires at least one whitespace char between the two words, so
// "<chapterbreak>" (not a tag this scheme recognizes) is left alone rather than
// matched by accident.
private val TAG_SPECS = listOf(
    TagSpec(
        { ChapterSegment.ChapterBreak(it) },
        Regex("(?i)<\\s*chapter\\s+break\\s*>"),
        Regex("(?i)<\\s*/\\s*chapter\\s+break\\s*>")
    ),
    TagSpec(
        { ChapterSegment.AuthorNote(it) },
        Regex("(?i)<\\s*author\\s*>"),
        Regex("(?i)<\\s*/\\s*author\\s*>")
    ),
    TagSpec(
        { ChapterSegment.News(it) },
        Regex("(?i)<\\s*news\\s*>"),
        Regex("(?i)<\\s*/\\s*news\\s*>")
    )
)

fun parseChapterSegments(raw: String): List<ChapterSegment> {
    val segments = mutableListOf<ChapterSegment>()
    val plain = StringBuilder()
    var cursor = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            segments.add(ChapterSegment.Paragraph(plain.toString()))
            plain.clear()
        }
    }

    while (cursor < raw.length) {
        // Find the earliest open-tag match (of any recognized type) at or after cursor.
        var openMatch: MatchResult? = null
        var spec: TagSpec? = null
        for (candidate in TAG_SPECS) {
            val m = candidate.open.find(raw, cursor) ?: continue
            if (openMatch == null || m.range.first < openMatch!!.range.first) {
                openMatch = m
                spec = candidate
            }
        }

        if (openMatch == null || spec == null) {
            // No more recognized tags - everything left is plain text.
            plain.append(raw, cursor, raw.length)
            break
        }

        val closeMatch = spec.close.find(raw, openMatch.range.last + 1)
        if (closeMatch == null) {
            // Unterminated tag - keep the opening tag itself as literal text (see the
            // doc comment above) and keep scanning right after it.
            plain.append(raw, cursor, openMatch.range.last + 1)
            cursor = openMatch.range.last + 1
            continue
        }

        plain.append(raw, cursor, openMatch.range.first)
        flushPlain()

        val inner = raw.substring(openMatch.range.last + 1, closeMatch.range.first).trim()
        segments.add(spec.build(inner))

        cursor = closeMatch.range.last + 1
    }

    flushPlain()
    return segments
}
