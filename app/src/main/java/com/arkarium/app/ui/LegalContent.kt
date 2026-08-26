package com.arkarium.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
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
// (see MainActivity/ScannerImpl/GoogleBooksMetadataProvider/PreferencesManager)
// rather than generic boilerplate - e.g. the only network call this app ever
// makes is the user-triggered Google Books metadata lookup, so that's the only
// thing called out under "Network access" below. Keep this file in sync if
// that behavior changes (a new network call, a new local data store, etc.).
object LegalContent {

    val privacyPolicy = listOf(
        LegalSection(
            "Overview",
            "ARKarium is an offline-first, privacy-first app. There are no user " +
                "accounts, no sign-in, no analytics, no crash-reporting services, and " +
                "no ads. Nothing about your usage is collected or transmitted unless " +
                "this policy says otherwise below.",
            icon = Icons.Filled.PrivacyTip
        ),
        LegalSection(
            "Your library folder",
            "ARKarium reads the single folder you choose via Android's Storage " +
                "Access Framework (SAF). It reads chapter files to index and display " +
                "them; it never copies, uploads, or otherwise sends the contents of " +
                "your library anywhere. All indexing happens entirely on your device.",
            icon = Icons.Filled.Folder
        ),
        LegalSection(
            "Data stored on your device",
            "Novel, arc, chapter, and author details discovered while scanning are " +
                "stored locally in an on-device database, along with your reading " +
                "progress and app preferences (theme, selected library folder, and " +
                "similar settings). None of this leaves your device as part of normal " +
                "app use.",
            icon = Icons.Filled.Storage
        ),
        LegalSection(
            "Network access (opt-in, per novel)",
            "The only network request ARKarium ever makes is triggered by you, " +
                "manually, when you tap \"Fetch info\" on a novel. That sends the " +
                "novel's title to the Google Books API to look up a matching cover, " +
                "description, and genre tags. Nothing else about your library or " +
                "device is sent with that request, and it only happens when you ask " +
                "for it. Google's handling of that request is governed by their own " +
                "privacy policy, not this one.",
            icon = Icons.Filled.Wifi
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
                "parties, because outside of the opt-in metadata lookup above, it " +
                "does not collect any data to share in the first place.",
            icon = Icons.Filled.Groups
        ),
        LegalSection(
            "Changes to this policy",
            "ARKarium is open-source and under active development. This policy may " +
                "be updated alongside the app; the project's GitHub repository is " +
                "the source of truth for the current version.",
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
            "ARKarium is a local library organizer and reader for novel files (txt/md, " +
                "with more formats planned) that already exist on your device. It does " +
                "not provide, host, distribute, or endorse any written content itself - " +
                "it only indexes and displays files from the folder you point it at.",
            icon = Icons.Filled.Info
        ),
        LegalSection(
            "Your responsibility for content",
            "You are solely responsible for the legality of the files you choose to " +
                "store on your device and open with ARKarium. The app doesn't inspect, " +
                "moderate, or take any position on the source or legality of your files.",
            icon = Icons.Filled.Folder
        ),
        LegalSection(
            "License",
            "ARKarium is licensed under the GNU General Public License v3.0 (GPLv3). " +
                "The full license text ships with the project's source and is available " +
                "on the GitHub repository.",
            icon = Icons.Filled.Description
        ),
        LegalSection(
            "No warranty",
            "ARKarium is provided \"as is\", without warranty of any kind, express or " +
                "implied, to the maximum extent permitted by applicable law - consistent " +
                "with the GPLv3 license it's distributed under. Use it at your own risk.",
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
