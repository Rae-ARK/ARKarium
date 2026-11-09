package com.arkarium.app.ui

import com.arkarium.app.data.ChapterSegment
import com.arkarium.app.data.paragraphOffsets
import com.arkarium.app.data.parseChapterSegments
import com.arkarium.app.data.splitBlankLines

// Plain-Kotlin model behind ReaderScreen's lazily composed chapter body, kept free of
// Compose and Android types so it can be unit-tested on the JVM (ReaderBlocksTest).
//
// Why this exists (other/scrolling-bug.md, causes 2 and 3): the chapter body used to be a
// plain Column + verticalScroll, which composes and lays out every child up front -
// during the screen's own enter transition, on a long chapter - and, because an untagged
// chapter parses to ONE Paragraph segment (see ChapterMarkup.kt), "every child" was a
// single Text holding the whole chapter, which no LazyColumn can split. So the body is
// now a flat list of blocks, one per prose paragraph (split on blank lines) plus one per
// divider/callout, and ReaderScreen draws each block as its own LazyColumn item.
//
// Each prose block carries its own offset into the raw chapter text. That is the unit a
// saved reading position (ReaderPosition.charOffset) and a read-aloud chunk are both
// expressed in, so mapping either to a place in the list is an index lookup here instead
// of a measured pixel position - which is what lets the list stay lazy.
sealed class ReaderBlock {
    // One prose paragraph. `rawStart` is where `text` begins in the raw chapter body, so
    // content.substring(rawStart, rawStart + text.length) == text. `blankLinesBefore` is
    // how many empty lines the chapter had between this paragraph and the previous one in
    // the same stretch of running text (0 for the first), so the reader can leave the
    // same gap the single big Text used to show for "\n\n".
    data class Prose(val rawStart: Int, val text: String, val blankLinesBefore: Int) : ReaderBlock() {
        val rawEnd: Int get() = rawStart + text.length
    }

    data class Break(val label: String) : ReaderBlock()
    data class AuthorNote(val text: String) : ReaderBlock()
    data class News(val text: String) : ReaderBlock()
}

// LazyColumn items that come before the first block (the title/breadcrumb/nav header).
// List index of blocks[i] is READER_LEADING_ITEMS + i; the footer is the item after the
// last block.
const val READER_LEADING_ITEMS = 1

// Parses `raw` into the flat block list. Built from the same two parsers the reader
// already walked in lockstep (parseChapterSegments and paragraphOffsets - their
// Paragraph entries line up one-for-one, see ChapterMarkupOffsetsTest), so markup tags and
// break lines behave exactly as before; the only new step is splitting each stretch of
// running text on blank lines, using the splitter TTS's paragraph highlight already uses
// (proseParagraphOffsets) so a block boundary here is a paragraph boundary there.
fun readerBlocks(raw: String, detectBreakLines: Boolean = true): List<ReaderBlock> {
    val segments = parseChapterSegments(raw, detectBreakLines)
    val origins = paragraphOffsets(raw, detectBreakLines)
    val blocks = ArrayList<ReaderBlock>()
    var paragraphCursor = 0
    for (segment in segments) {
        when (segment) {
            is ChapterSegment.Paragraph -> {
                val origin = origins.getOrNull(paragraphCursor)
                paragraphCursor++
                if (segment.text.isBlank() || origin == null) continue
                var previousEnd = -1
                splitBlankLines(origin.text).forEachIndexed { i, piece ->
                    val blankLines = if (i == 0) 0 else {
                        // Newlines in the whitespace between two pieces: the first ends
                        // the previous line, every further one is an empty line.
                        val newlines = origin.text.substring(previousEnd, piece.rawStart).count { it == '\n' }
                        (newlines - 1).coerceAtLeast(1)
                    }
                    blocks.add(ReaderBlock.Prose(origin.rawStart + piece.rawStart, piece.text, blankLines))
                    previousEnd = piece.rawStart + piece.text.length
                }
            }
            is ChapterSegment.ChapterBreak -> blocks.add(ReaderBlock.Break(segment.label))
            is ChapterSegment.AuthorNote -> blocks.add(ReaderBlock.AuthorNote(segment.text))
            is ChapterSegment.News -> blocks.add(ReaderBlock.News(segment.text))
        }
    }
    return blocks
}

// Index in `blocks` of the paragraph a saved charOffset restores into: the last prose
// block starting at or before the offset, else the first one (a stale offset from a
// chapter since edited shorter). Same rule as paragraphForCharOffset, on blocks. Null
// only when there is no prose at all.
fun readerBlockIndexForCharOffset(blocks: List<ReaderBlock>, charOffset: Int): Int? {
    var atOrBefore = -1
    var first = -1
    blocks.forEachIndexed { i, block ->
        if (block is ReaderBlock.Prose) {
            if (first < 0) first = i
            if (block.rawStart <= charOffset) atOrBefore = i
        }
    }
    return when {
        atOrBefore >= 0 -> atOrBefore
        first >= 0 -> first
        else -> null
    }
}

// The prose paragraph to save as the reading anchor when `blockIndex` is the block at the
// top of the viewport: that block if it is prose, otherwise the nearest prose above it (a
// divider or callout has no offset of its own to resume into). A negative index (the
// header is still what's at the top) or no prose above gives null, and the caller falls
// back to a proportional estimate, as it always did before any paragraph had reported.
fun readerAnchorBlock(blocks: List<ReaderBlock>, blockIndex: Int): ReaderBlock.Prose? {
    var i = minOf(blockIndex, blocks.lastIndex)
    while (i >= 0) {
        (blocks[i] as? ReaderBlock.Prose)?.let { return it }
        i--
    }
    return null
}

// Index in `blocks` of the paragraph a read-aloud chunk starting at `chunkStart` should
// scroll to, or null when it starts somewhere the reader draws as something other than
// running text (a callout or divider), in which case the reader just doesn't scroll.
//
// A chunk can start in the blank space between two paragraphs - the blocks are trimmed,
// the chunk spans are not - so a start that lies in pure whitespace before a paragraph
// belongs to that paragraph. Whitespace only: a start inside markup text is still null.
fun readerBlockIndexForChunkStart(blocks: List<ReaderBlock>, raw: String, chunkStart: Int): Int? {
    if (chunkStart < 0) return null
    var previous = -1
    for (i in blocks.indices) {
        val block = blocks[i] as? ReaderBlock.Prose ?: continue
        if (block.rawStart <= chunkStart) {
            previous = i
            continue
        }
        // `block` is the first paragraph starting after chunkStart.
        val before = if (previous >= 0) blocks[previous] as ReaderBlock.Prose else null
        if (before != null && chunkStart < before.rawEnd) return previous
        return if (chunkStart <= raw.length && raw.substring(chunkStart, block.rawStart).isBlank()) i else null
    }
    val last = if (previous >= 0) blocks[previous] as ReaderBlock.Prose else null
    return if (last != null && chunkStart < last.rawEnd) previous else null
}

// Where a visible LazyColumn item sits, as the four numbers ReaderScrollModel needs.
// `offset` is relative to the viewport's start edge and negative once the item has begun
// to scroll off the top (LazyListItemInfo.offset).
data class ReaderItemEdge(val index: Int, val offset: Int, val size: Int)

// Reading progress (0f..1f) for a list whose item sizes aren't known until they are
// composed. The old Column could say scrollState.value / maxValue because it had measured
// everything; a LazyColumn hasn't, so this weighs each item by how much text it holds -
// paragraphs by their length, the title header and footer cards by a flat amount - and
// reports where the viewport's top edge sits in that total, scaled so the top of the
// chapter is exactly 0 and the bottom exactly 1 (the viewport's own extent comes off the
// scrollable range, like maxValue = content - viewport did). Characters track rendered
// height closely for prose, so the percentage moves smoothly and ends where the old one did.
class ReaderScrollModel(blocks: List<ReaderBlock>) {
    private val weights: DoubleArray
    private val prefix: DoubleArray

    init {
        weights = DoubleArray(blocks.size + READER_LEADING_ITEMS + 1)
        weights[0] = CHROME_WEIGHT
        blocks.forEachIndexed { i, block ->
            weights[READER_LEADING_ITEMS + i] = when (block) {
                is ReaderBlock.Prose -> block.text.length.coerceAtLeast(1).toDouble()
                is ReaderBlock.Break -> CHROME_WEIGHT / 2
                is ReaderBlock.AuthorNote -> block.text.length.coerceAtLeast(CALLOUT_MIN_WEIGHT).toDouble()
                is ReaderBlock.News -> block.text.length.coerceAtLeast(CALLOUT_MIN_WEIGHT).toDouble()
            }
        }
        weights[weights.size - 1] = CHROME_WEIGHT
        prefix = DoubleArray(weights.size + 1)
        for (i in weights.indices) prefix[i + 1] = prefix[i] + weights[i]
    }

    // `first`/`last` are the first and last items currently visible; the viewport spans
    // [viewportStart, viewportEnd) in the same coordinates their offsets use.
    fun progress(
        first: ReaderItemEdge,
        last: ReaderItemEdge,
        viewportStart: Int,
        viewportEnd: Int
    ): Float {
        if (first.index !in weights.indices || last.index !in weights.indices) return 0f
        val total = prefix[weights.size]
        val scrolledPastFirst =
            if (first.size > 0) ((viewportStart - first.offset).toDouble() / first.size).coerceIn(0.0, 1.0) else 0.0
        val shownOfLast =
            if (last.size > 0) ((viewportEnd - last.offset).toDouble() / last.size).coerceIn(0.0, 1.0) else 1.0
        val top = prefix[first.index] + weights[first.index] * scrolledPastFirst
        val bottom = prefix[last.index] + weights[last.index] * shownOfLast
        val scrollable = total - (bottom - top).coerceAtLeast(0.0)
        if (scrollable <= 0.0) return 1f
        return (top / scrollable).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        // Roughly a short paragraph's worth of characters: enough that scrolling through
        // the title block or the footer cards visibly moves the readout, small next to a chapter.
        const val CHROME_WEIGHT = 200.0
        const val CALLOUT_MIN_WEIGHT = 100
    }
}
