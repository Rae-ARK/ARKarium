package com.arkarium.app.data

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Resolves a user-typed fiction name (e.g. "Summoned By Mistake, I Decided To Learn
// How To Live") to the relay slug ARKarium actually fetches ("sbm"), via a small
// name->slug lookup table bundled into the APK as an asset.
//
// IMPORTANT - what this actually protects, and what it doesn't: this is
// obfuscation, not real cryptographic security. The goal (per the design
// discussion this came out of) is just "don't make the slug scheme trivially
// grep-able out of the APK" - i.e. raise the bar above `unzip app.apk && cat
// assets/fiction_lut.dat`. It does NOT protect against a motivated person with
// a decompiler (jadx/apktool): the AES key below ships inside the app binary
// itself, split across two int arrays and XORed together at load time only to
// avoid it sitting around as one obviously-a-key byte string. Anyone willing to
// read assembleKey() below and run it gets the real key, same as anyone willing
// to just decompile the whole app. If the actual threat model is ever "assume
// the APK itself is hostile," a bundled symmetric key can't solve that - the
// LUT would need to live server-side instead. For now, this matches the stated
// goal (deter casual poking, not defend against reverse engineering).
object FictionLut {
    private const val ASSET_PATH = "fiction_lut.dat"

    // Two independently-meaningless byte arrays; assembleKey() XORs them back into
    // the real AES-256 key. Regenerate both (and the asset file) together using
    // tools/gen_fiction_lut.py if you ever want to rotate the key.
    private val keyPartA = intArrayOf(
        117, 248, 46, 162, 147, 225, 7, 9, 177, 9, 49, 133, 59, 0, 201, 170,
        206, 138, 81, 184, 170, 242, 118, 169, 154, 100, 100, 218, 73, 77, 213, 9
    )
    private val keyPartB = intArrayOf(
        224, 89, 192, 21, 182, 21, 73, 59, 115, 2, 108, 117, 198, 167, 132, 118,
        116, 163, 203, 62, 221, 192, 47, 7, 78, 209, 252, 155, 154, 125, 89, 195
    )

    private fun assembleKey(): ByteArray =
        ByteArray(keyPartA.size) { i -> (keyPartA[i] xor keyPartB[i]).toByte() }

    // relay-name (lowercased, trimmed, whitespace-collapsed) -> slug. Lazily loaded
    // once per process and cached; the asset never changes at runtime, only between
    // app builds (see tools/gen_fiction_lut.py).
    @Volatile
    private var cached: Map<String, String>? = null

    // Original (non-normalized) display name -> slug, in the order the asset JSON
    // declares them. Same underlying data as `cached`, just without lowercasing the
    // keys - lookup() doesn't need this (it normalizes its input instead), but
    // "Sync all Rae ARK's novels" needs real display names for its progress messages.
    @Volatile
    private var cachedDisplay: List<Pair<String, String>>? = null

    // Smart-quote / smart-apostrophe variants that autocorrect commonly substitutes for
    // their plain ASCII equivalents - folded before comparison so a curly apostrophe
    // from a phone keyboard doesn't produce a spurious "not found" (see
    // docs/arkarium/NEXT_FIXES.md #3). NFKD alone doesn't touch these: Unicode decomposes
    // accented letters into base+combining-mark pairs, but a curly quote has no such
    // decomposition - it's simply a different codepoint from its straight-quote cousin.
    private val quoteVariants = mapOf(
        '\u2018' to '\'', '\u2019' to '\'', '\u201B' to '\'', '\u2032' to '\'',
        '\u201C' to '"', '\u201D' to '"', '\u201F' to '"', '\u2033' to '"'
    )

    // Punctuation stripped before comparison - commas/colons/periods are exactly the
    // kind of thing a user typing a title from memory is likely to drop or misplace
    // (see docs/arkarium/NEXT_FIXES.md #3's "Summoned By Mistake..." example). Deliberately not
    // stripping apostrophes/hyphens: those are usually load-bearing within a word
    // ("Mistake I Decided" vs "Mistake, I Decided" reads the same without the comma,
    // but "Rae ARK's" vs "Rae ARKs" doesn't).
    private val strippablePunctuation = Regex("[,:.;!?]")

    // Normalizes a name for comparison: trim, lowercase, fold smart quotes to their
    // ASCII equivalents, NFKD-normalize (so visually-identical accented characters
    // typed via different input methods compare equal), strip commas/colons/periods/
    // semicolons/exclamation/question marks, then collapse whitespace. Still exact
    // matching after all of that - no fuzzy/edit-distance matching (see the code
    // comment on `lookup` below for why that's the right call while the LUT stays
    // small) - this just widens what counts as "the same string" to cover the most
    // common ways real typing/autocorrect differs from the canonical title, rather
    // than making near-misses silently resolve to a possibly-wrong fiction.
    private fun normalize(name: String): String {
        val quotesFolded = name.map { quoteVariants[it] ?: it }.joinToString("")
        val decomposed = java.text.Normalizer.normalize(quotesFolded, java.text.Normalizer.Form.NFKD)
        val punctuationStripped = strippablePunctuation.replace(decomposed, "")
        return punctuationStripped.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    // Returns the slug for a user-typed fiction name, or null if there's no match.
    // Matching is exact after normalization (case-insensitive, whitespace-collapsed,
    // punctuation/smart-quote/Unicode-form insensitive - see normalize() above) - no
    // fuzzy matching, so a typo just means "not found" rather than silently
    // resolving to the wrong fiction.
    fun lookup(context: Context, name: String): String? =
        loadTable(context)[normalize(name)]

    // Every (display name, slug) pair in the LUT - i.e. every fiction ARKarium's relay
    // currently serves. The relay only ever hosts Rae ARK's own fictions (see
    // docs/arkarium/SYNC_MVP.md and tools/gen_fiction_lut.py), so this doubles as "all of Rae
    // ARK's novels" for the home screen's empty-library "Sync all Rae ARK's novels"
    // action - there's no separate per-author filter because there's only one author.
    fun allEntries(context: Context): List<Pair<String, String>> {
        cachedDisplay?.let { return it }
        loadTable(context) // populates cachedDisplay as a side effect, see decrypt()
        return cachedDisplay ?: emptyList()
    }

    private fun loadTable(context: Context): Map<String, String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val (table, display) = decrypt(context)
            cached = table
            cachedDisplay = display
            return table
        }
    }

    private fun decrypt(context: Context): Pair<Map<String, String>, List<Pair<String, String>>> {
        val blob = context.assets.open(ASSET_PATH).use { it.readBytes() }
        // Layout: [12-byte GCM IV][ciphertext + 16-byte GCM tag]
        val iv = blob.copyOfRange(0, 12)
        val ciphertext = blob.copyOfRange(12, blob.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(assembleKey(), "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)

        val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        val normalized = mutableMapOf<String, String>()
        val display = mutableListOf<Pair<String, String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val slug = json.getString(key)
            normalized[normalize(key)] = slug
            display.add(key to slug)
        }
        return normalized to display
    }
}
