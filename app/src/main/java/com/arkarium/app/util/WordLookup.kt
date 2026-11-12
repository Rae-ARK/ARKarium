package com.arkarium.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

// Tap-a-word lookup (see docs/foundational/architecture.md's "Reading and
// local state"). Deliberately not a bundled dictionary: ACTION_PROCESS_TEXT is the
// same intent TextView's own long-press selection toolbar uses to offer installed
// dictionary/translate apps (Google Dictionary, GBoard's translate, etc.) - handing a
// single tapped word to whatever's already on the device, rather than ARKarium
// shipping or querying a word list of its own.
object WordLookup {

    // Finds the word under a tap: expands left and right from `index` through
    // letters/digits and the two apostrophe characters ARKster-era content actually
    // uses (so "don't"/"don’t" come back whole, not split at the quote), then trims
    // any apostrophes left dangling at either edge (a tap just inside a quoted
    // '​word' shouldn't hand the quote mark itself to the lookup). Returns null for a
    // tap that landed on whitespace or punctuation - the caller just does nothing
    // rather than looking up an empty or punctuation-only string.
    fun wordAt(text: String, index: Int): String? {
        if (text.isEmpty()) return null
        val i = index.coerceIn(0, text.length - 1)
        if (!isWordChar(text[i])) return null
        var start = i
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = i
        while (end < text.length - 1 && isWordChar(text[end + 1])) end++
        while (start <= end && !text[start].isLetterOrDigit()) start++
        while (end >= start && !text[end].isLetterOrDigit()) end--
        if (start > end) return null
        return text.substring(start, end + 1)
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '\u2019'

    // EXTRA_PROCESS_TEXT_READONLY: this is a lookup, not an edit-in-place selection -
    // ARKarium has nowhere to put a replacement even if a handler returned one.
    // createChooser rather than a bare startActivity so a device with more than one
    // handler (dictionary, translate) asks instead of silently picking one; a device
    // with none throws ActivityNotFoundException, caught and swallowed - no handler
    // installed is a normal, silent no-op here, not a crash or an error state to
    // surface (same as Haptics.kt returning early with no vibrator).
    fun lookup(context: Context, word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, trimmed)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        try {
            context.startActivity(Intent.createChooser(intent, null))
        } catch (e: ActivityNotFoundException) {
            // No ACTION_PROCESS_TEXT handler on the device at all - nothing to do.
        }
    }
}
