package com.arkarium.app.data

import android.content.Context
import java.io.File
import java.io.IOException

// Rung 3 of docs/implementation/catalogue-browse-redesign.md - the private-storage
// writer for a catalogue entry's cover and metadata.json, under
// context.filesDir/catalogue/<slug>/. Deliberately outside the SAF library root
// ScannerImpl walks (see the doc's "Storage" section): needs no permission, can never
// be misread as a locally-added novel, and every byte here is re-derivable from the
// relay, so losing it costs nothing.
//
// Takes a plain File root rather than a Context so it's testable on the JVM with a
// TemporaryFolder; the Context constructor is the one production call site uses.
class CatalogueStorage(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "catalogue"))

    // Writes (or replaces) this slug's cached files and returns the cover's file:// URI
    // string for CatalogueEntryEntity.coverPath, or null when there is no cover.
    //
    // Both inputs are "what the relay has right now", so a null means "not there
    // anymore" and any previously cached copy is removed rather than left stale (C3:
    // nothing here claims to reflect more than the last successful write).
    fun write(
        slug: String,
        coverBytes: ByteArray?,
        coverFileName: String?,
        metadataBytes: ByteArray?
    ): String? {
        val dir = dirFor(slug)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")

        if (metadataBytes != null) {
            writeAtomically(File(dir, METADATA_FILE), metadataBytes)
        } else {
            File(dir, METADATA_FILE).delete()
        }

        // The extension may change between refreshes (cover.jpg -> cover.png), so clear
        // every cached cover.* first instead of only overwriting the same name.
        dir.listFiles { f -> COVER_NAME.matches(f.name) }?.forEach { it.delete() }

        if (coverBytes == null || coverFileName == null) return null
        val coverFile = File(dir, coverCacheName(coverFileName))
        writeAtomically(coverFile, coverBytes)
        return "file://" + coverFile.toURI().rawPath
    }

    // For Rung 4's C2 hook (a followed fiction's cache must not linger), but harmless
    // and idempotent on its own - a slug with nothing cached is a no-op.
    fun delete(slug: String) {
        dirFor(slug).deleteRecursively()
    }

    // C1: a slug is untrusted input before it's a folder name. SyncPaths.sanitize
    // rejects absolute, empty, "." / ".." and backslash paths but deliberately allows
    // "a/b" (manifest paths are nested); a slug must be exactly one folder, so a
    // surviving "/" is rejected here too. The canonical-path check is the belt to those
    // braces - whatever got through, the result must still sit directly under root.
    private fun dirFor(slug: String): File {
        val safe = SyncPaths.sanitize(slug)
        if ('/' in safe) throw UnsafePathException(slug)
        val dir = File(root, safe)
        if (dir.canonicalFile.parentFile != root.canonicalFile) throw UnsafePathException(slug)
        return dir
    }

    // The manifest-relative name ("cover.jpg", any case) is only used for its extension;
    // the cached file gets a fixed, lowercase name so nothing from the manifest ever
    // chooses a filename on disk.
    private fun coverCacheName(manifestPath: String): String {
        val ext = SyncPaths.sanitize(manifestPath).substringAfterLast('.', "").lowercase()
        if (ext !in COVER_EXTENSIONS) throw UnsafePathException(manifestPath)
        return "cover.$ext"
    }

    // Temp file + rename in the same directory, so a crash mid-write never leaves a
    // half-written cover for Coil to choke on.
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not write ${target.path}")
            }
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val METADATA_FILE = "metadata.json"
        val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val COVER_NAME = Regex("^cover\\.(jpg|jpeg|png|webp)$")
    }
}
