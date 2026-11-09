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
// Besides the tags, a line that is nothing but one character three times over - "***",
// "---", "###", "~~~" - is a chapter break too (see isBreakLine), so an author can just
// type the marker in the plain .txt with no tag. Both forms produce the same
// ChapterBreak segment with an empty label. The line form is a reader preference
// (Reader settings, `reader_break_lines`, on by default): with `detectBreakLines` false
// every such line is ordinary text again. The tags are unaffected by it.
//
// A chapter with none of these tags or break lines parses to a single Paragraph segment
// covering the whole body - same text, same rendering as before this existed. Unrecognized/unmatched
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

// A break line: after trimming surrounding whitespace (which also takes care of the \r
// of a CRLF line ending and a leading byte-order mark), exactly three characters, all the
// same one. Counted in code points, not UTF-16 chars, so a marker such as "✨✨✨" or a
// symbol outside the BMP is three characters, not six. Deliberately nothing smarter: no
// character whitelist, no spaced "* * *" form, no blank lines required around it.
fun isBreakLine(line: String): Boolean {
    val t = line.trim { it.isWhitespace() || it == '\uFEFF' }
    if (t.isEmpty() || t.codePointCount(0, t.length) != BREAK_LINE_LENGTH) return false
    val first = t.codePointAt(0)
    var i = Character.charCount(first)
    while (i < t.length) {
        val cp = t.codePointAt(i)
        if (cp != first) return false
        i += Character.charCount(cp)
    }
    return true
}

private const val BREAK_LINE_LENGTH = 3

// A stretch of a plain-text run: [start, end) in the run's own coordinates, either a
// break line or ordinary text.
internal data class TextRun(val start: Int, val end: Int, val isBreak: Boolean)

// Splits one plain-text run (what is left between tags) at its break lines. Used by
// BOTH parseChapterSegments and paragraphOffsets so their Paragraph entries stay
// one-to-one - ReaderScreen walks the two in lockstep (see paragraphOffsets).
//
// A run with no break line comes back whole and untouched, so chapters that don't use
// the marker render exactly as before. Around a break line the text is trimmed on the
// side that touches it (the newlines before and after it), nothing else; every text run
// is an unmodified substring, so rawStart + text.length still addresses the same
// characters of the chapter. Runs left empty by that trimming are dropped.
internal fun splitOnBreakLines(text: String, detectBreakLines: Boolean = true): List<TextRun> {
    if (text.isEmpty()) return emptyList()
    if (!detectBreakLines) return listOf(TextRun(0, text.length, false))
    // [start, nextLineStart) of every break line, found line by line.
    val breaks = mutableListOf<IntRange>()
    var lineStart = 0
    while (lineStart < text.length) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline
        val next = if (newline < 0) text.length else newline + 1
        if (isBreakLine(text.substring(lineStart, lineEnd))) breaks.add(lineStart until next)
        lineStart = next
    }
    if (breaks.isEmpty()) return listOf(TextRun(0, text.length, false))

    val runs = mutableListOf<TextRun>()
    var pos = 0
    var afterBreak = false
    for (b in breaks) {
        var start = pos
        if (afterBreak) while (start < b.first && text[start].isWhitespace()) start++
        var end = b.first
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end > start) runs.add(TextRun(start, end, false))
        runs.add(TextRun(b.first, b.last + 1, true))
        pos = b.last + 1
        afterBreak = true
    }
    var start = pos
    while (start < text.length && text[start].isWhitespace()) start++
    if (start < text.length) runs.add(TextRun(start, text.length, false))
    return runs
}

fun parseChapterSegments(raw: String, detectBreakLines: Boolean = true): List<ChapterSegment> {
    val segments = mutableListOf<ChapterSegment>()
    val plain = StringBuilder()
    var cursor = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            val text = plain.toString()
            splitOnBreakLines(text, detectBreakLines).forEach { run ->
                segments.add(
                    if (run.isBreak) ChapterSegment.ChapterBreak("")
                    else ChapterSegment.Paragraph(text.substring(run.start, run.end))
                )
            }
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

// Rung 14 (F9, docs/arkarium/proposal/reader-playback.md): pairs each Paragraph
// segment parseChapterSegments() would produce with the offset in `raw` where its
// text begins, so ReaderScreen can anchor a saved reading position to a point in the
// chapter's own text (CHAR_OFFSET, see data/Entities.kt's ReadingProgressEntity doc
// comment) instead of a scroll-pixel fraction that only means anything at the exact
// font size/width it was captured at.
//
// Deliberately a separate function rather than changing parseChapterSegments' return
// type - every existing caller of that function is unaffected, and this one runs the
// identical tag-matching loop (same TAG_SPECS, same flush condition) so its Paragraph
// entries are guaranteed to line up one-for-one, in order, with the Paragraph segments
// parseChapterSegments(raw) returns for the same `raw`. ReaderScreen zips the two by
// walking both in lockstep rather than this function trying to also build
// ChapterBreak/AuthorNote/News entries it has no use for.
data class ParagraphOffset(val rawStart: Int, val text: String)

fun paragraphOffsets(raw: String, detectBreakLines: Boolean = true): List<ParagraphOffset> {
    val result = mutableListOf<ParagraphOffset>()
    val plain = StringBuilder()
    var plainStart = -1
    var cursor = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            // `plain` is always one contiguous stretch of `raw` starting at plainStart
            // (every append() below continues exactly where the last stopped), so a
            // run's offset inside it is its offset inside the chapter minus plainStart.
            val text = plain.toString()
            splitOnBreakLines(text, detectBreakLines).filterNot { it.isBreak }.forEach { run ->
                result.add(ParagraphOffset(plainStart + run.start, text.substring(run.start, run.end)))
            }
            plain.clear()
            plainStart = -1
        }
    }

    fun append(from: Int, to: Int) {
        if (to <= from) return
        if (plainStart == -1) plainStart = from
        plain.append(raw, from, to)
    }

    while (cursor < raw.length) {
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
            append(cursor, raw.length)
            break
        }

        val closeMatch = spec.close.find(raw, openMatch.range.last + 1)
        if (closeMatch == null) {
            append(cursor, openMatch.range.last + 1)
            cursor = openMatch.range.last + 1
            continue
        }

        append(cursor, openMatch.range.first)
        flushPlain()

        cursor = closeMatch.range.last + 1
    }

    flushPlain()
    return result
}

// The paragraph a saved CHAR_OFFSET should restore into: the last paragraph that
// starts at or before the offset (the paragraph the offset falls inside or just past),
// falling back to the first paragraph when the offset is before everything (e.g. a
// stale offset from a chapter that has since been edited shorter). Null only when the
// chapter has no paragraphs at all (an empty or all-markup body).
fun paragraphForCharOffset(offsets: List<ParagraphOffset>, charOffset: Int): ParagraphOffset? =
    offsets.lastOrNull { it.rawStart <= charOffset } ?: offsets.firstOrNull()

// The paragraph that actually contains `charOffset` - rawStart <= charOffset <
// rawStart + text.length - or null when none does (most commonly: the offset falls
// inside a markup-tagged block, which the offsets this is given deliberately don't
// cover - see paragraphOffsets'/proseParagraphOffsets' own doc comments). Unlike
// paragraphForCharOffset above, this never falls back to "the nearest paragraph
// anyway": a null here is a real, meaningful "no paragraph covers this exact
// position", which is what the TTS paragraph highlight (rung 6 of
// tts-playback-controls.md) needs in order to fall back to its own chunk-level
// highlight (rung 5) instead of wrongly shading some unrelated paragraph.
fun paragraphContaining(offsets: List<ParagraphOffset>, charOffset: Int): ParagraphOffset? =
    offsets.firstOrNull { charOffset >= it.rawStart && charOffset < it.rawStart + it.text.length }

// A blank-line-separated regex matches one or more blank lines, used below to split a
// paragraphOffsets() block into the actual prose paragraphs it's often made of -
// "[ \t]*" rather than bare "\n\n" so a line that's merely whitespace (trailing spaces
// left on an otherwise-blank line) still counts as blank.
private val BLANK_LINE_SPLIT = Regex("\n[ \t]*(?:\n[ \t]*)+")

// Rung 6 of docs/arkarium/implementation/tts-playback-controls.md (paragraph-highlight
// variant - see that doc for why it replaced the originally-planned word-level
// enhancement): paragraphOffsets() above finds one block per markup-delimited stretch
// of plain text, which the reader draws as a single Text - usually the whole chapter
// when an author uses no tags or break lines at all (see ReaderScreen's own comment on
// that). That block is too coarse a unit for a "currently reading" highlight, so this
// further splits each block on blank lines - the ordinary prose paragraph break,
// distinct from both the markup tags paragraphOffsets already separates on and the
// `isBreakLine` "***"-style marker splitOnBreakLines separates on - into one
// ParagraphOffset per actual prose paragraph. A lone newline inside a paragraph
// (soft-wrapped prose, not a paragraph break) is left alone. Offsets stay in the same
// `raw` coordinate space paragraphOffsets() already returns, so this is a drop-in
// finer-grained replacement wherever a ParagraphOffset list is consumed -
// paragraphContaining above doesn't care which function produced its input.
fun proseParagraphOffsets(raw: String, detectBreakLines: Boolean = true): List<ParagraphOffset> =
    paragraphOffsets(raw, detectBreakLines).flatMap { block ->
        splitBlankLines(block.text).map { ParagraphOffset(block.rawStart + it.rawStart, it.text) }
    }

// Splits `text` on one-or-more blank lines into trimmed pieces, each still carrying its
// own offset within `text` (not the caller's coordinate space - proseParagraphOffsets
// above adds the block's own rawStart back in). A piece that's blank after trimming -
// purely whitespace between two real paragraphs, including the "no blank line at all"
// case at the very start/end of `text` - is dropped rather than reported as an empty
// paragraph.
private fun splitBlankLines(text: String): List<ParagraphOffset> {
    val result = mutableListOf<ParagraphOffset>()
    var cursor = 0
    fun addPiece(from: Int, to: Int) {
        val start = (from until to).firstOrNull { !text[it].isWhitespace() } ?: return
        var end = to
        while (end > start && text[end - 1].isWhitespace()) end--
        result.add(ParagraphOffset(start, text.substring(start, end)))
    }
    for (match in BLANK_LINE_SPLIT.findAll(text)) {
        addPiece(cursor, match.range.first)
        cursor = match.range.last + 1
    }
    addPiece(cursor, text.length)
    return result
}
