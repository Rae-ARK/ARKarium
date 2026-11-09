package com.arkarium.app.navigation

// New: the home-screen rework the owner asked for. Home, Browse All Novels, Notice
// Board and Settings used to be reached in four different ways - Home was the
// NavHost's startDestination with no way back to it except Back/Up from wherever you
// drilled into, Browse and Settings were icons buried in Home's own top row, and
// Notice Board didn't exist at all. Per the owner's own framing ("those four things
// becomes a btn/options in the bottom bar"), they're now one persistent bottom
// navigation bar, shown on exactly those four screens (see MainActivity's
// `showBottomBar` and the Scaffold wrapping NavHost) - not on NovelDetail, Reader, or
// any settings sub-page, same as every other app that pairs a bottom bar with a
// drill-down stack. Tapping a tab uses the standard Navigation-Compose "switch tabs,
// don't push a new one every time" pattern (popUpTo(start) { saveState = true },
// launchSingleTop, restoreState) so each tab keeps its own scroll position/state
// between visits instead of restarting, and switching tabs never piles up duplicate
// back-stack entries.

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// One tab = one route to navigate to (via AppBottomBar.navigate below) plus however the
// current route is matched against it (see AppDestination.HOME's own doc comment on why
// that's not always a plain equality check).
enum class AppDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    // The route this tab navigates *to* is the bare "fictionBrowse?initialQuery="
    // (no query - a fresh tab tap is "show me everything", not whatever the last
    // in-app search happened to be), but the route NavHost actually registers is
    // "fictionBrowse?initialQuery={initialQuery}" - matchesRoute below is what lets
    // this tab still highlight as selected when Home's search bar or a novel's
    // "browse more" link lands on that route with a real query in it.
    BROWSE("fictionBrowse?initialQuery=", "Browse", Icons.Filled.MenuBook),
    // Stub for now, per the owner: a separate pass fleshes this out later. The tab
    // exists and is reachable today so it's not a dead promise in the bottom bar -
    // see NoticeBoardScreen.kt for the placeholder it opens.
    NOTICE_BOARD("noticeBoard", "Notice Board", Icons.Filled.Campaign),
    SETTINGS("settings", "Settings", Icons.Filled.Settings);

    // NavDestination.route (what MainActivity's currentRoute reads off the back-stack
    // entry) reports the *registered* route pattern, not the filled-in argument value -
    // for Browse that's always the literal template string
    // "fictionBrowse?initialQuery={initialQuery}", never "fictionBrowse?initialQuery="
    // or "...=foo", regardless of what query the reader actually searched. This tab's
    // own `route` (the one BROWSE navigates *to*) is the plain unparameterized
    // "fictionBrowse?initialQuery=", so a direct `currentRoute == route` would never
    // match - BROWSE compares by prefix instead. Every other tab's destination takes no
    // arguments, so its registered route and its current route are the same literal
    // string, and a plain equality check is both correct and enough for them.
    fun matchesRoute(currentRoute: String?): Boolean =
        if (this == BROWSE) currentRoute?.startsWith("fictionBrowse") == true
        else currentRoute == route
}

@Composable
fun AppBottomBar(
    currentRoute: String?,
    onTabSelected: (AppDestination) -> Unit
) {
    // Bug 7 fix (docs/arkarium/proposal/bugs/other/uiux-bugs.md): NavigationBar is passed
    // to Scaffold's bottomBar slot (MainActivity.kt), which lays it out as a full-width
    // sibling of the padded content area - Scaffold's innerPadding never touches it, and
    // nothing here compensated for the display cutout on its own. In landscape, with the
    // cutout on a side edge, that left tab icons/labels able to sit under it. Horizontal
    // is enough since this bar is bottom-anchored, not full-screen - same pattern
    // ReaderScreen's root Column already proves works for its own content, the one place
    // in the app this was handled correctly before this fix.
    NavigationBar(
        modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
    ) {
        AppDestination.entries.forEach { destination ->
            val selected = destination.matchesRoute(currentRoute)
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors()
            )
        }
    }
}

// Whether MainActivity should show the bar at all for a given route - the four tab
// destinations only, never NovelDetail, Reader, Author, any settings/* sub-page, or
// the legal/about pages. A drill-down screen showing the bar too would both look
// wrong (a "Settings" tab highlighted while actually on settings/reader) and invite
// tapping a tab mid-read and silently losing the reader's scroll position outside the
// Continue-Reading-loses-progress fix's own save paths.
fun isBottomBarRoute(route: String?): Boolean =
    AppDestination.entries.any { it.matchesRoute(route) }
