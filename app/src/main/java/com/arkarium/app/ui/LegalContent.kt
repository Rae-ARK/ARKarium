package com.arkarium.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

// One heading + body pair rendered by LegalDocumentScreen. Kept as plain data
// (no markdown/HTML) since the content below is short and static - a real
// markup renderer would be overkill for a handful of paragraphs. `icon` is
// purely cosmetic (drives the icon badge next to the heading in the card
// renderer) and defaults to a generic doc glyph so adding a section without
// picking an icon still compiles and still looks intentional.
data class LegalSection(
    val heading: String,
    val body: String,
    val icon: ImageVector = Icons.Filled.Description
)

// Content here is deliberately scoped to what ARKarium's code actually does
// (see MainActivity/ScannerImpl/SyncManager/NovelMetadataProvider/PreferencesManager)
// rather than generic boilerplate - and, as importantly, scoped to what ARKarium
// IS: a direct author-to-reader sync client (see docs/arkarium/foundational/what-is-arkarium.md),
// not just a local file browser. ARKarium makes network requests in the normal
// course of being used, not only as an edge-case opt-in - see "Content sync"
// below. That's a real difference from ARKster/ARKium (offline-only local
// readers this project grew out of; see docs/reference/arkster/), and this file
// must not describe ARKarium as if it still only reads files you already have
// lying around on disk. Keep this file in sync if the app's actual network/data
// behavior changes (a new network call, a new local data store, etc.).
object LegalContent {

    val privacyPolicy = listOf(
        LegalSection(
            "Overview",
            "ARKarium is a local-first, privacy-first app: your library lives on " +
                "your device, reading works fully offline once content is synced, and " +
                "there are no user accounts, no sign-in, no analytics, no " +
                "crash-reporting services, and no ads. ARKarium does make network " +
                "requests as a normal part of how it works - syncing fiction from its " +
                "distribution relay is the whole point of the app - and the sections " +
                "below describe exactly what those requests are and are not.",
            icon = Icons.Filled.PrivacyTip
        ),
        LegalSection(
            "Your library folder",
            "ARKarium reads from, and writes synced fiction into, the single folder " +
                "you choose via Android's Storage Access Framework (SAF). It only " +
                "writes files it downloaded for a fiction you explicitly added or " +
                "re-synced (see \"Content sync\" below), and it only removes files " +
                "inside that folder that it previously wrote itself as part of a " +
                "sync - it never touches, deletes, or reorganizes files you placed " +
                "there by hand. It never copies, uploads, or otherwise sends the " +
                "existing contents of your library anywhere; all scanning and " +
                "indexing of your files happens entirely on your device.",
            icon = Icons.Filled.Folder
        ),
        LegalSection(
            "Content sync (core feature, not a side lookup)",
            "When you add a fiction from ARKarium's built-in list, or tap to sync " +
                "one again, the app makes an HTTPS request to Rae-ARK's own content " +
                "relay (currently hosted at novels.horizonarkstudio.workers.dev) to " +
                "fetch that fiction's manifest and chapter files, and writes them " +
                "into your library folder. This is first-party infrastructure run by " +
                "the same project as ARKarium itself, not an unrelated third-party " +
                "service, and it requires no account or sign-in. Each request only " +
                "identifies the fiction being fetched (by a fixed, built-in slug) - " +
                "nothing about your device, your library contents, or your reading " +
                "history is included or needed. As with any network request, the " +
                "relay sees ordinary connection metadata (such as your IP address) " +
                "that any server sees from any client; ARKarium does not layer any " +
                "additional tracking, identifiers, or analytics on top of that.",
            icon = Icons.Filled.CloudSync
        ),
        LegalSection(
            "Metadata lookup (opt-in, per novel)",
            "Separately from sync, ARKarium can look up cover art, description, and " +
                "genre tags for a novel already in your library. This only happens " +
                "when you manually tap \"Fetch info\" on that novel, and it sends " +
                "only that novel's title to the Google Books API - nothing else " +
                "about your library or device is sent with that request. Google's " +
                "handling of that request is governed by their own privacy policy, " +
                "not this one.",
            icon = Icons.Filled.Wifi
        ),
        LegalSection(
            "Data stored on your device",
            "Novel, arc, chapter, and author details discovered while scanning or " +
                "syncing are stored locally in an on-device database, along with your " +
                "reading progress, which fictions you've synced and at what version, " +
                "and app preferences (theme, selected library folder, and similar " +
                "settings). None of this leaves your device as part of normal app " +
                "use - it exists only so ARKarium can tell what's already synced, " +
                "what's changed, and where you left off.",
            icon = Icons.Filled.Storage
        ),
        LegalSection(
            "Crash reports",
            "If ARKarium crashes, a trace is saved locally on your device so it can " +
                "be shown to you on the next launch to help with debugging. This " +
                "trace stays on your device and is never sent anywhere automatically.",
            icon = Icons.Filled.BugReport
        ),
        LegalSection(
            "Third parties",
            "ARKarium does not sell, share, or otherwise disclose your data to third " +
                "parties. The two network destinations described above are: " +
                "ARKarium's own content relay (first-party, run by the same project, " +
                "used to deliver the fiction you choose to sync) and, only if you " +
                "opt in per novel, the Google Books API (used solely for metadata " +
                "lookup). Outside of those two, ARKarium does not collect data to " +
                "share in the first place.",
            icon = Icons.Filled.Groups
        ),
        LegalSection(
            "Changes to this policy",
            "ARKarium is under active development. This policy may be updated " +
                "alongside the app; the project's official repository is the source " +
                "of truth for the current version.",
            icon = Icons.Filled.Update
        )
    )

    val termsAndConditions = listOf(
        LegalSection(
            "Acceptance of terms",
            "By installing or using ARKarium, you agree to these terms. If you don't " +
                "agree with them, please don't use the app.",
            icon = Icons.Filled.Gavel
        ),
        LegalSection(
            "What ARKarium is",
            "ARKarium is a reader built around direct, author-to-reader distribution " +
                "of independently published fiction: it syncs fiction published by " +
                "Rae ARK's own content relay directly into a local library on your " +
                "device, then reads it fully offline afterward. It also indexes and " +
                "displays any other novel files (txt/md, with more formats planned) " +
                "you already have in that same library folder. Either way, ARKarium " +
                "is a client, not a publisher: it doesn't host content on a server of " +
                "its own on your behalf, and for files you've added yourself it " +
                "doesn't endorse, moderate, or take a position on where they came " +
                "from.",
            icon = Icons.Filled.Info
        ),
        LegalSection(
            "Content you sync vs. content you add yourself",
            "Fiction you sync through ARKarium's built-in list is the author's own " +
                "work, distributed under whatever terms the author sets for it - " +
                "that's separate from, and not affected by, ARKarium's own software " +
                "license below. For any other files you place in your library folder " +
                "yourself, you are solely responsible for their legality; ARKarium " +
                "doesn't inspect, moderate, or take a position on the source of files " +
                "it didn't put there.",
            icon = Icons.Filled.MenuBook
        ),
        LegalSection(
            "Ownership and license",
            // Was previously (incorrectly) GPLv3 - a leftover from the project's
            // earlier "ARKster" naming/scaffolding, never updated when the project
            // went proprietary. This must always match README.md's own copyright
            // notice exactly ("All rights reserved... proprietary") - the two are
            // the same legal claim made in two places, and having them disagree is
            // worse than either one alone. If the project's actual license ever
            // changes, update both together.
            "ARKarium is proprietary software. Copyright \u00A9 2026 Rae-ARK. All " +
                "rights reserved. No permission is granted to copy, modify, " +
                "distribute, sublicense, or otherwise use this software or its " +
                "source code, in whole or in part, without prior written permission " +
                "from the copyright holder.",
            icon = Icons.Filled.Description
        ),
        LegalSection(
            "No warranty",
            "ARKarium is provided \"as is\", without warranty of any kind, express or " +
                "implied, including but not limited to warranties of merchantability, " +
                "fitness for a particular purpose, or non-infringement, to the maximum " +
                "extent permitted by applicable law. Use it at your own risk.",
            icon = Icons.Filled.VerifiedUser
        ),
        LegalSection(
            "No accounts, no purchases",
            "ARKarium has no accounts, subscriptions, or in-app purchases. There is " +
                "nothing to manage beyond the app's own local settings.",
            icon = Icons.Filled.Block
        ),
        LegalSection(
            "Changes to the app",
            "ARKarium is actively developed and features, screens, and behavior may " +
                "change between versions. The project's GitHub repository tracks the " +
                "current state and history of those changes.",
            icon = Icons.Filled.Update
        )
    )
}
