package com.arkarium.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
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
//
// `tldr` is new: a single plain-language sentence rendered above `body` in
// the card (see LegalDocumentScreen's LegalSectionCard) - the "say it like a
// human, then say it properly" pattern real privacy-summary redesigns use so
// a reader gets the gist without parsing formal phrasing first. It is purely
// a reading aid and carries no legal weight of its own: `body` remains the
// actual policy/terms text and is what governs. Defaults to "" (renders
// nothing extra) so this is optional per section.
data class LegalSection(
    val heading: String,
    val body: String,
    val icon: ImageVector = Icons.Filled.Description,
    val tldr: String = ""
)

// Content here is deliberately scoped to what ARKarium's code actually does
// (see MainActivity/ScannerImpl/SyncManager/NovelMetadataProvider/PreferencesManager)
// rather than generic boilerplate - and, as importantly, scoped to what ARKarium
// IS: a direct author-to-reader sync client (see docs/foundational/what-is-arkarium.md),
// not just a local file browser. ARKarium makes network requests in the normal
// course of being used, not only as an edge-case opt-in - see "Content sync"
// below. That's a real difference from ARKster/ARKium (offline-only local
// readers this project grew out of; see "Relationship to sibling projects" in
// docs/foundational/what-is-arkarium.md), and this file
// must not describe ARKarium as if it still only reads files you already have
// lying around on disk. Keep this file in sync if the app's actual network/data
// behavior changes (a new network call, a new local data store, etc.).
// Wording pass: rewritten in the register of a standard consumer-app privacy
// policy / terms of service (defined terms, formal but plain language,
// present-tense statements of fact rather than first-person asides) rather
// than the earlier conversational "here's what the code does" phrasing.
// Factual content is unchanged from the previous version except where noted
// inline (e.g. the sync destination no longer names its specific hostname -
// see "Content synchronization" below).
object LegalContent {

    // Document-level "quick facts" rendered as a row of chips above the
    // section cards (see LegalDocumentScreen) - the tl;dr of the whole
    // document in four words-or-fewer each, the same job the good/bad icon
    // summary does on sites like ToS;DR, just inline instead of linking out
    // to one. Purely a reading aid; the section cards below remain the
    // actual policy.
    val privacyQuickFacts = listOf(
        "No accounts or sign-in",
        "No ads or analytics",
        "Local-first library",
        "HTTPS sync, by title only"
    )

    val termsQuickFacts = listOf(
        "Proprietary, all rights reserved",
        "No purchases or subscriptions",
        "Provided as-is",
        "Actively evolving"
    )

    val privacyPolicy = listOf(
        LegalSection(
            "Overview",
            "This Privacy Policy explains what information ARKarium (\"the App\") " +
                "accesses, stores, and transmits, and why. The App is designed to be " +
                "local-first: your library is stored on your device, reading works " +
                "fully offline once content has been synchronized, and the App has " +
                "no user accounts, no sign-in, no analytics, no crash-reporting " +
                "services, and no advertising. The App does make network requests as " +
                "part of its normal operation - synchronizing fiction from its " +
                "distribution service is a core function of the App - and each " +
                "section below describes exactly what those requests do and do not " +
                "involve.",
            icon = Icons.Filled.PrivacyTip,
            tldr = "No accounts, no ads, no analytics - your library lives on your device."
        ),
        LegalSection(
            "Your library folder",
            "The App reads from, and writes synchronized content into, a single, " +
                "fixed folder on your device's external storage " +
                "(Android/media/com.arkarium.app/), rather than a folder you pick " +
                "yourself; this location requires no storage permission to access " +
                "and is visible to file managers and to a computer connected over " +
                "USB. It writes only the files it downloads for a title you have " +
                "explicitly added or re-synchronized (see \"Content synchronization\" " +
                "below), and it removes only files within that folder that it " +
                "previously wrote as part of a synchronization; it does not modify, " +
                "delete, or reorganize any file you placed there yourself. The App " +
                "does not copy, upload, or otherwise transmit the existing contents " +
                "of your library; all scanning and indexing of your files takes " +
                "place entirely on your device.",
            icon = Icons.Filled.Folder,
            tldr = "The App only touches files it put there itself - everything else in your folder is untouched."
        ),
        LegalSection(
            "Content synchronization",
            "When you add a title from the App's built-in catalog, or request that " +
                "an existing title be re-synchronized, the App makes an HTTPS " +
                "request to the publisher's own content distribution service to " +
                "retrieve that title's manifest and chapter files, and writes them " +
                "into your library folder. This service is first-party " +
                "infrastructure operated by the same project as the App, is not an " +
                "unaffiliated third-party service, and requires no account or " +
                "sign-in. Each request identifies only the title being retrieved, by " +
                "a fixed identifier built into the App; no information about your " +
                "device, your library contents, or your reading history is included " +
                "or required. As with any network request, the distribution service " +
                "receives ordinary connection metadata (such as your IP address) " +
                "that any server receives from any client; the App does not add any " +
                "further tracking, identifiers, or analytics to that request.",
            icon = Icons.Filled.CloudSync,
            tldr = "Adding a title talks to our own server, not a stranger's - just enough to fetch it, nothing about you."
        ),
        LegalSection(
            "Metadata lookup (optional, per title)",
            "Separately from synchronization, the App can look up cover art, a " +
                "description, and genre tags for a title already in your library. " +
                "This occurs only when you select \"Fetch info\" for that title, and " +
                "the request sent includes only that title's name - no other " +
                "information about your library or device is included. This lookup " +
                "is performed using the Google Books API; Google's handling of that " +
                "request is governed by Google's own privacy policy, not this one.",
            icon = Icons.Filled.Wifi,
            tldr = "Tap \"Fetch info\" and we ask Google Books for a cover and blurb - only if you ask us to."
        ),
        LegalSection(
            "Feedback (optional, per chapter)",
            "Each chapter includes a \"Send Feedback\" option. Selecting it opens " +
                "your device's own default email app with the recipient and a " +
                "subject line (naming only the fiction you were reading) already " +
                "filled in. The App itself sends nothing: no message is transmitted " +
                "unless and until you choose to send it yourself from within your " +
                "email app, and the App does not see, store, or have any record of " +
                "what you write. Any message you do send is received as an " +
                "ordinary email, no different from one you addressed yourself.",
            icon = Icons.Filled.Email,
            tldr = "\"Send Feedback\" just opens your own email app with the address pre-filled - nothing sends until you do."
        ),
        LegalSection(
            "Information stored on your device",
            "Title, arc, chapter, and author information discovered while scanning " +
                "or synchronizing is stored locally in an on-device database, along " +
                "with your reading progress, a record of which titles you have " +
                "synchronized and at what version, and your app preferences (such as " +
                "theme and selected library folder). None of this information leaves " +
                "your device during normal use of the App; it exists solely so the " +
                "App can determine what has already been synchronized, what has " +
                "changed, and where you left off reading.",
            icon = Icons.Filled.Storage,
            tldr = "Everything we remember - progress, titles, settings - stays in a database on your phone."
        ),
        LegalSection(
            "Crash reports",
            "If the App crashes, a diagnostic trace is saved locally on your device " +
                "and may be shown to you on the next launch to assist with " +
                "troubleshooting. This trace remains on your device and is never " +
                "transmitted automatically.",
            icon = Icons.Filled.BugReport,
            tldr = "If the App crashes, the crash log stays on your device. We never see it."
        ),
        LegalSection(
            "Third parties",
            "The App does not sell, share, or otherwise disclose your information " +
                "to third parties. The only two network destinations described in " +
                "this policy are: the App's own content distribution service " +
                "(first-party, operated by the same project, used to deliver the " +
                "titles you choose to synchronize) and, only where you have opted " +
                "in for a specific title, the Google Books API (used solely for " +
                "metadata lookup). Outside of these two destinations, the App does " +
                "not collect information that could be shared in the first place.",
            icon = Icons.Filled.Groups,
            tldr = "Two destinations, period: our own sync server, and Google Books if you opt in. Nobody else."
        ),
        LegalSection(
            "Changes to this policy",
            "The App is under active development, and this Privacy Policy may be " +
                "updated from time to time to reflect changes to the App. The " +
                "project's official repository is the authoritative source for the " +
                "current version of this policy.",
            icon = Icons.Filled.Update,
            tldr = "We're still building this app, so this page can change - the repo is always the source of truth."
        )
    )

    val termsAndConditions = listOf(
        LegalSection(
            "Acceptance of terms",
            "By installing or using ARKarium (\"the App\"), you agree to be bound by " +
                "these Terms and Conditions (\"these Terms\"). If you do not agree to " +
                "these Terms, you should not install or use the App.",
            icon = Icons.Filled.Gavel,
            tldr = "Using the App means you agree to the terms below."
        ),
        LegalSection(
            "What ARKarium is",
            "The App is a reading application built around direct, author-to-reader " +
                "distribution of independently published fiction: it synchronizes " +
                "titles made available through its publisher's own content " +
                "distribution service directly into a local library on your device, " +
                "which can then be read fully offline. The App also indexes and " +
                "displays other compatible novel files (currently .txt and .md, with " +
                "additional formats planned) that you have placed in that same " +
                "library folder yourself. In either case, the App functions solely " +
                "as a client: it does not host content on a server on your behalf, " +
                "and with respect to files you have added yourself, it does not " +
                "endorse, moderate, or take any position on their source.",
            icon = Icons.Filled.Info,
            tldr = "A reading client, not a bookstore - it only syncs or displays what you bring to it."
        ),
        LegalSection(
            "Content you synchronize vs. content you add yourself",
            "Fiction synchronized through the App's built-in catalog remains the " +
                "property of its respective author and is distributed under " +
                "whatever terms that author sets for it; this is separate from, and " +
                "not affected by, the App's own software license described below. " +
                "For any other files you place in your library folder yourself, you " +
                "are solely responsible for ensuring you have the right to possess " +
                "and use them; the App does not inspect, moderate, or take any " +
                "position on the source of files it did not place there.",
            icon = Icons.Filled.MenuBook,
            tldr = "Synced titles belong to their authors; anything else you drop in your folder is on you."
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
            "The App is proprietary software. Copyright \u00A9 2026 Rae-ARK. All " +
                "rights reserved. No part of this software or its source code may be " +
                "copied, modified, distributed, sublicensed, or otherwise used, in " +
                "whole or in part, without the prior written permission of the " +
                "copyright holder.",
            icon = Icons.Filled.Description,
            tldr = "The App itself is closed-source and all rights reserved - this isn't the old GPL version."
        ),
        LegalSection(
            "Disclaimer of warranty",
            "The App is provided \"as is\", without warranty of any kind, express or " +
                "implied, including but not limited to the implied warranties of " +
                "merchantability, fitness for a particular purpose, and " +
                "non-infringement, to the maximum extent permitted by applicable " +
                "law. You use the App at your own risk.",
            icon = Icons.Filled.VerifiedUser,
            tldr = "Provided as-is - use it at your own risk, like basically every app you've ever installed."
        ),
        LegalSection(
            "No accounts or purchases",
            "The App does not offer user accounts, subscriptions, or in-app " +
                "purchases. There is nothing to manage beyond the App's own local " +
                "settings.",
            icon = Icons.Filled.Block,
            tldr = "Nothing to buy, nothing to log into - just local settings."
        ),
        LegalSection(
            "Changes to the App",
            "The App is under active development, and its features, screens, and " +
                "behavior are subject to change between versions. The project's " +
                "official repository is the authoritative record of the current " +
                "state of the App and the history of such changes.",
            icon = Icons.Filled.Update,
            tldr = "Still in active development - features and screens can change between versions."
        )
    )
}
