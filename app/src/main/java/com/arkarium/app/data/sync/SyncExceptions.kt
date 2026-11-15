package com.arkarium.app.data.sync

import java.io.IOException

class ManifestParseException(message: String) : IOException(message)

// Thrown when a manifest file entry's path would escape the novel's own folder (see
// SyncPaths.sanitize). Treated as a whole-manifest failure rather than skipping just
// that one entry - a manifest containing an unsafe path is a trust problem with the
// manifest itself (see docs/foundational/architecture.md, Sync invariants S2), not a one-off bad
// file worth silently dropping and continuing past.
class UnsafePathException(path: String) : IOException("Unsafe path in manifest: $path")

// Thrown specifically when a relay 404s on a novel's manifest.json - distinguished from
// a generic IOException so callers can tell "this source doesn't exist anymore" apart
// from a transient network blip (see docs/foundational/architecture.md, Sync invariants S6). Never thrown for a 404 on
// an individual file download - only the manifest fetch itself, since that's the one
// request whose absence means "this fiction isn't served here anymore" rather than "one
// file is temporarily unreachable."
class SourceGoneException(baseUrl: String) : IOException("No manifest found at $baseUrl - this source may no longer be available")

// Thrown by SyncManager.sync() when a previously-synced novel's on-disk folder can't be
// found by either id (findNovelFolder) or its deterministic slug name, and the caller
// hasn't explicitly opted into recreating it (see docs/foundational/architecture.md, Sync invariants S6). Distinguishes
// "the user (or something else) removed this folder" from every other sync failure, so
// a caller can ask before silently redownloading the whole fiction.
class MissingLocalFolderException(val novelId: String) : IOException("Local folder for novel $novelId is missing")
