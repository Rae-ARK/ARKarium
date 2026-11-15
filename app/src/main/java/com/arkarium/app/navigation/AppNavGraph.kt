package com.arkarium.app.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.arkarium.app.MainActivity
import com.arkarium.app.data.AppScope
import com.arkarium.app.data.ChapterLoadResult
import com.arkarium.app.data.NovelEntity
import com.arkarium.app.data.ReaderPosition
import com.arkarium.app.data.ReadingProgressSaveWorker
import com.arkarium.app.data.StorageBreakdown
import com.arkarium.app.data.Theme
import com.arkarium.app.data.clearImageCaches
import com.arkarium.app.data.computeStorageBreakdown
import com.arkarium.app.data.resolveLibraryRoot
import com.arkarium.app.data.sync.relayBaseUrlForSlug
import com.arkarium.app.ui.AuthorPageScreen
import com.arkarium.app.ui.ChapterEditorScreen
import com.arkarium.app.ui.ChapterLoadErrorScreen
import com.arkarium.app.ui.FictionBrowseScreen
import com.arkarium.app.ui.HomeScreen
import com.arkarium.app.ui.LegalContent
import com.arkarium.app.ui.LegalDocumentScreen
import com.arkarium.app.ui.LibrarySettingsScreen
import com.arkarium.app.ui.NoticeBoardScreen
import com.arkarium.app.ui.NovelDetailScreen
import com.arkarium.app.ui.PigeonRallyScreen
import com.arkarium.app.ui.RaesJournalScreen
import com.arkarium.app.ui.reader.ReaderFontFamily
import com.arkarium.app.ui.reader.ReaderScreen
import com.arkarium.app.ui.ReaderSettingsScreen
import com.arkarium.app.ui.SettingsScreen
import com.arkarium.app.ui.SplashSettingsScreen
import com.arkarium.app.ui.ThemeSettingsScreen
import com.arkarium.app.ui.TtsSettingsScreen
import com.arkarium.app.ui.TtsVoicePickerScreen
import com.arkarium.app.ui.WebViewScreen
import com.arkarium.app.ui.formatBytes
import com.arkarium.app.ui.rememberChapterTts
import com.arkarium.app.viewmodel.ChapterRestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Pulled out of MainActivity.renderMainContent() - that function alone used to hold the
// entire NavHost with all 19 route destinations inline (~1500 lines), making
// MainActivity.kt read like a navigation graph wearing an Activity's clothes. This is
// that graph, as its own thing: a single composable taking the Activity (for its
// lifecycleScope, Context, ViewModels and the handful of Activity-owned helpers no
// destination below could do without) plus the small pile of hoisted preference State
// the various settings/* destinations render, and nothing else.
//
// `activity` stands in for everywhere this code used to write `this@MainActivity`
// (Context for resolveLibraryRoot/FileProvider/permission checks) or a bare
// `lifecycleScope`/`applicationContext`/`libraryViewModel` (an implicit Activity
// receiver). The handful of members referenced here that used to be `private` on
// MainActivity (pendingNotifyToggleNovelId, notificationPermission,
// navigateToChapterOrDetail, requestNotificationPermissionIfNeeded,
// openLibraryFolderInFileManager, prefsManager, and the seven ViewModel fields) are
// `internal` now instead - same module, different file - rather than duplicated or
// re-homed; MainActivity still owns their lifecycle exactly as before, this file just
// reads/calls them through the Activity reference it's handed.
//
// The State<T> parameters below are exactly the hoisted PreferencesManager
// collectAsState() reads renderMainContent already did before this extraction (to avoid
// a first-frame flash while the real DataStore read is still in flight - see each one's
// own history in MainActivity) - still read once, at the top of the composable tree, and
// threaded down here rather than re-collected per-destination.
@Composable
fun AppNavGraph(
    activity: MainActivity,
    navController: NavHostController,
    resolvedTheme: Theme,
    currentTheme: State<Theme>,
    currentSystemDefaultLightVariant: State<Theme>,
    amoledModeEnabled: State<Boolean>,
    splashAnimationEnabled: State<Boolean>,
    splashMusicEnabled: State<Boolean>,
    ttsDefaultRate: State<Float>,
    ttsPitch: State<Float>,
    ttsAutoContinue: State<Boolean>,
    ttsKeepScreenOn: State<Boolean>,
    ttsVoiceName: State<String?>,
    readerFontSize: State<Float>,
    readerLineHeight: State<Float>,
    readerReadingMode: State<String?>,
    readerFontFamily: State<String>,
    readerKeepScreenOn: State<Boolean>,
    readerFullscreen: State<Boolean>,
    readerBreakLines: State<Boolean>,
    readerBrightnessSliderEnabled: State<Boolean>,
    readerBrightness: State<Float>,
    ttsFavoriteVoiceNames: State<Set<String>>,
    hasSeenCoverEnlargeHint: State<Boolean?>
) {
    // Stage 3.1-3.4 (see docs/foundational/design-notes.md): every destination routes
    // through NavHost - the manual currentScreen/Screen `when` block, and the "legacy"
    // destination that wrapped it, were removed in Stage 3.5.
    NavHost(
        navController = navController,
        // Stage 3.2 (see docs/foundational/design-notes.md): Home is the NavHost's own
        // startDestination - the thing that actually decides what's on screen first.
        startDestination = "home",
        modifier = Modifier.fillMaxSize(),
        // Without these NavHost falls back to its stock 700ms full-screen crossfade. See
        // NavMotion.kt for the motion design: shared-axis slide + fade-through for going
        // deeper and back, a plain fade-through for Previous/Next chapter.
        enterTransition = { NavMotion.enter(initialState, targetState) },
        exitTransition = { NavMotion.exit(initialState, targetState) },
        popEnterTransition = { NavMotion.popEnter(initialState, targetState) },
        popExitTransition = { NavMotion.popExit(initialState, targetState) }
    ) {
        composable("home") {
            Column(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    novels = activity.libraryViewModel.novels,
                    // recentlyRead, not inProgressNovels - see HomeScreen's own
                    // continueReadingNovels doc comment for why (status vs. an actual
                    // reading_progress row).
                    continueReadingNovels = activity.libraryViewModel.recentlyRead,
                    streakDays = activity.libraryViewModel.readingStreakDays.value,
                    chaptersReadThisWeek = activity.libraryViewModel.chaptersReadThisWeek.value,
                    onNovelClick = { novel ->
                        activity.lifecycleScope.launch {
                            activity.libraryViewModel.loadNovelDetails(novel)
                            // Stage 3.3: NovelDetail is now its own route, keyed by
                            // novelId - launchSingleTop avoids piling up a new
                            // back-stack entry every time Home is revisited and a
                            // novel is tapped again.
                            navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
                        }
                    },
                    onContinueReading = { novel ->
                        activity.lifecycleScope.launch {
                            // Stage 3.4: Reader is its own "reader/{novelId}/{chapterId}"
                            // route - readerAuthor resolution and the chapter body fetch
                            // both happen in that composable's own LaunchedEffects, keyed
                            // off the route arguments, instead of here. See
                            // MainActivity.navigateToChapterOrDetail's own doc comment
                            // for the loadNovelDetails()-before-navigating sequencing and
                            // why this is shared with the Continue Reading shortcut.
                            val chapterId = activity.libraryViewModel.resolveLastReadChapterId(novel)
                            activity.navigateToChapterOrDetail(novel, chapterId, navController)
                        }
                    },
                    onBrowseClick = {
                        navController.navigate("fictionBrowse")
                    },
                    // onSettingsClick's wiring removed - Settings' own top-bar shortcut
                    // icon is gone (see HomeScreen's actions comment); the bottom bar is
                    // the only way there now, same as Browse.
                    onSearch = { query ->
                        if (query.isNotEmpty()) {
                            navController.navigate("fictionBrowse?initialQuery=${Uri.encode(query)}")
                        }
                    },
                    onAddFictionClick = { activity.metadataViewModel.addFictionState.value = AddFictionState.EnteringName },
                    onSyncAllClick = {
                        val root = resolveLibraryRoot(activity)
                        if (root != null) {
                            activity.syncViewModel.syncAllRaeArkNovels(root)
                        } else {
                            activity.syncViewModel.syncAllState.value =
                                SyncAllState.Error("Couldn't reach the library folder - try again in a moment.")
                        }
                    }
                )
            }
        }

        composable(
            "fictionBrowse?initialQuery={initialQuery}",
            arguments = listOf(navArgument("initialQuery") {
                type = NavType.StringType
                defaultValue = ""
            })
        ) { backStackEntry ->
            // Rung 5: show cached catalogue rows right away, re-fetch only when they've
            // gone stale (see SyncViewModel.refreshCatalogueIfStale).
            LaunchedEffect(Unit) { activity.syncViewModel.refreshCatalogueIfStale() }
            // C2 at the UI level too: whatever the row/cache cleanup is doing, a fiction
            // that's already in the library never shows as a catalogue card.
            val followedUrls = activity.libraryViewModel.novels.mapNotNull { it.syncSourceUrl }.toSet()
            val catalogue = activity.syncViewModel.catalogue.value.filterNot { relayBaseUrlForSlug(it.slug) in followedUrls }
            Column(modifier = Modifier.fillMaxSize()) {
                FictionBrowseScreen(
                    novels = activity.libraryViewModel.novels,
                    catalogue = catalogue,
                    onCatalogueSelected = { entry ->
                        // Rung 7: the route's {novelId} carries the slug for an
                        // unfollowed fiction (encoded - slugs can contain spaces).
                        navController.navigate("novelDetail/${Uri.encode(entry.slug)}") { launchSingleTop = true }
                    },
                    initialQuery = backStackEntry.arguments?.getString("initialQuery") ?: "",
                    onNovelSelected = { novel ->
                        activity.lifecycleScope.launch {
                            activity.libraryViewModel.loadNovelDetails(novel)
                            navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
                        }
                    }
                )
            }
        }

        // Stage 3.3 (see docs/foundational/design-notes.md): the first stage that
        // changes a Screen case's shape instead of just relocating it - NovelDetail and
        // ChapterEditor now take a novelId route argument and resolve the NovelEntity
        // themselves via libraryViewModel.novels.firstOrNull { it.id == novelId }, the
        // same lookup Screen.Reader's composable already used for readerAuthor/chapter
        // neighbors - rather than having a full NovelEntity handed in as Screen payload.
        composable(
            "novelDetail/{novelId}",
            arguments = listOf(navArgument("novelId") { type = NavType.StringType })
        ) { backStackEntry ->
            val novelId = backStackEntry.arguments?.getString("novelId") ?: ""
            // Rung 7 of docs/implementation/catalogue-browse-redesign.md: {novelId} is a
            // real NovelEntity.id first; failing that it may be a catalogue slug. If a
            // novel has since been followed for that slug (Follow just finished), the
            // same route re-resolves to the real novel in place - no new back-stack
            // entry - once its chapters are loaded below.
            val directNovel = activity.libraryViewModel.novels.firstOrNull { it.id == novelId }
            val followedBySlug = if (directNovel == null) {
                activity.libraryViewModel.novels.firstOrNull { it.syncSourceUrl == relayBaseUrlForSlug(novelId) }
            } else null
            val novel = directNovel ?: followedBySlug
            val catalogueEntry = if (novel == null) {
                activity.syncViewModel.catalogue.value.firstOrNull { it.slug == novelId }
            } else null
            // libraryViewModel.chapters/arcs still hold whichever novel was loaded last,
            // so the real detail isn't drawn for a just-followed slug until its own
            // details have been loaded.
            val detailsReadyFor = remember(novelId) { mutableStateOf<String?>(null) }
            if (followedBySlug != null) {
                LaunchedEffect(followedBySlug.id) {
                    activity.libraryViewModel.loadNovelDetails(followedBySlug)
                    detailsReadyFor.value = followedBySlug.id
                }
            }
            if (novel == null && catalogueEntry != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    NovelDetailScreen(
                        // Stand-in for NovelDetailScreen's required NovelEntity - never
                        // persisted; catalogue-preview mode (see its doc comment) hides
                        // everything that would act on it.
                        novel = NovelEntity(
                            id = catalogueEntry.slug,
                            title = catalogueEntry.title,
                            author = catalogueEntry.author,
                            coverUri = catalogueEntry.coverPath,
                            description = catalogueEntry.description,
                            genres = catalogueEntry.genres,
                            publishedDate = catalogueEntry.publishedDate,
                            publicationStatus = catalogueEntry.status,
                            chaptersPerWeek = catalogueEntry.chaptersPerWeek
                        ),
                        chapters = emptyList(),
                        arcs = emptyList(),
                        // Back to wherever the preview was opened from (Browse), not the
                        // Home-collapsing behavior of a library novel.
                        onBack = { navController.popBackStack() },
                        onChapterSelected = {},
                        catalogueChapterCount = catalogueEntry.chapterCount,
                        onFollowClick = {
                            val root = resolveLibraryRoot(activity)
                            if (root != null) {
                                activity.syncViewModel.addFictionBySlug(catalogueEntry.slug, root)
                            } else {
                                activity.metadataViewModel.addFictionState.value =
                                    AddFictionState.Error("No library folder is set up yet - pick one in Settings first.")
                            }
                        }
                    )
                }
            } else if (novel == null) {
                // The novel this route pointed to is no longer in the library - most
                // likely SyncResolutionDialog's onRemoveFromLibrary deleted it via
                // syncViewModel.resolveByRemovingFromLibrary. Since this destination
                // resolves its own novel from libraryViewModel.novels instead of
                // carrying one as Screen payload, the removal is enough on its own - the
                // next recomposition sees a null novel here, and this LaunchedEffect
                // just leaves for Home instead of trying to render nothing.
                LaunchedEffect(novelId) {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            } else if (followedBySlug != null && detailsReadyFor.value != novel.id) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    NovelDetailScreen(
                        novel = novel,
                        chapters = activity.libraryViewModel.chapters,
                        arcs = activity.libraryViewModel.arcs,
                        chapterManifestMeta = activity.libraryViewModel.chapterManifestMeta,
                        overriddenChapterIds = activity.libraryViewModel.overriddenChapterIds.value,
                        onBack = {
                            // NovelDetail always went straight back to Home regardless of
                            // how it was reached (direct from Home, FictionBrowse, or an
                            // author's byline) - popUpTo("home", inclusive = false)
                            // preserves that exact behavior by collapsing whatever's on
                            // top of "home" back down to it, rather than a plain
                            // popBackStack() which would only undo the single most recent
                            // hop.
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onChapterSelected = { chapter ->
                            activity.lifecycleScope.launch {
                                // Mark novel as IN_PROGRESS when starting to read
                                activity.libraryViewModel.markNovelInProgress(novel.id)
                                // Stage 3.4: Reader is now its own
                                // "reader/{novelId}/{chapterId}" route - readerAuthor
                                // resolution and the chapter body fetch both move into
                                // that composable's own LaunchedEffects, keyed off the
                                // route arguments, instead of happening here.
                                navController.navigate("reader/${novel.id}/${chapter.id}") {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onResizePages = { pageSize ->
                            activity.lifecycleScope.launch {
                                activity.libraryViewModel.setPageSize(novel.id, pageSize)
                            }
                        },
                        onEditClick = { navController.navigate("chapterEditor/${novel.id}") },
                        onFetchInfoClick = { activity.metadataViewModel.fetchMetadataFor(novel) },
                        onAuthorClick = {
                            val authorId = novel.authorId
                            if (authorId != null) {
                                // Stage 3.4: Author is now its own "author/{authorId}"
                                // route - no more `from` payload to carry, since
                                // NavController's own back stack already knows to pop
                                // back to "novelDetail/${novel.id}" from here.
                                navController.navigate("author/$authorId")
                            }
                        },
                        onSyncClick = if (novel.syncSourceUrl != null) {
                            {
                                resolveLibraryRoot(activity)?.let { root ->
                                    activity.syncViewModel.checkForUpdates(novel, root)
                                }
                            }
                        } else null,
                        notifyEnabled = novel.notifyNewChapters,
                        onToggleNotify = if (novel.syncSourceUrl != null) {
                            { enabled ->
                                // Only turning it ON ever needs the runtime permission -
                                // turning it off just persists false regardless, same as
                                // any other permission-gated toggle. API < 33 doesn't
                                // have a POST_NOTIFICATIONS runtime prompt to show at all
                                // (granted at install time there), so this only branches
                                // on Tiramisu+.
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        activity, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    activity.pendingNotifyToggleNovelId.value = novel.id
                                    activity.notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // AppScope.io, not lifecycleScope - see
                                    // libraryViewModel.setNotifyEnabled's own doc comment
                                    // and AppScope.kt.
                                    AppScope.io.launch { activity.libraryViewModel.setNotifyEnabled(novel.id, enabled) }
                                }
                            }
                        } else null,
                        hasSeenCoverEnlargeHint = hasSeenCoverEnlargeHint.value ?: true, // null = still loading, show nothing
                        onCoverEnlargeHintDismissed = {
                            activity.lifecycleScope.launch { activity.prefsManager.setHasSeenCoverEnlargeHint() }
                        }
                    )
                }
            }
        }

        composable(
            "chapterEditor/{novelId}",
            arguments = listOf(navArgument("novelId") { type = NavType.StringType })
        ) { backStackEntry ->
            val novelId = backStackEntry.arguments?.getString("novelId") ?: ""
            val novel = activity.libraryViewModel.novels.firstOrNull { it.id == novelId }
            if (novel != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ChapterEditorScreen(
                        chapters = activity.libraryViewModel.chapters,
                        initialArcStartIds = activity.libraryViewModel.arcStartChapterIds.value,
                        onSave = { updatedChapters, arcStartIds ->
                            activity.libraryViewModel.saveChapterEdits(novel, updatedChapters, arcStartIds)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }

        // Stage 3.4 (see docs/foundational/design-notes.md): the last stage of the
        // Screen-case migration, and the only one that actually needs NavController's
        // own back-stack handling rather than just its routing - see this route's
        // onPrevious/onNext (replace, not push) and the "author/{authorId}" route below
        // (onBack is now a plain popBackStack()). Like NovelDetail/ChapterEditor
        // (Stage 3.3), this destination resolves its own ChapterEntity from
        // libraryViewModel.chapters via the chapterId route argument rather than having
        // one handed in as Screen payload; the chapter's body text now loads via a
        // LaunchedEffect keyed on chapterId instead of being fetched once at each entry
        // point above.
        composable(
            "reader/{novelId}/{chapterId}",
            arguments = listOf(
                navArgument("novelId") { type = NavType.StringType },
                navArgument("chapterId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val novelId = backStackEntry.arguments?.getString("novelId") ?: ""
            val chapterId = backStackEntry.arguments?.getString("chapterId") ?: ""
            val novel = activity.libraryViewModel.novels.firstOrNull { it.id == novelId }
            val chapter = activity.libraryViewModel.chapters.firstOrNull { it.id == chapterId }
            if (chapter == null) {
                // `libraryViewModel.chapters` is only a cache of whichever novel was
                // loaded last; the database is the authority on whether this chapter
                // exists. After process death (or any cold start that restores the back
                // stack straight onto this route) the cache is simply empty, and the old
                // code read that as "chapter deleted" and bounced the reader to
                // Home/NovelDetail - losing "where I left off". So ask the database
                // first: if the chapter and its novel are really there, (re)load the
                // novel's chapters, which makes this composable re-run with `chapter`
                // non-null. Only a chapter the database doesn't know (a rescan really
                // dropped it) leaves for NovelDetail/Home.
                LaunchedEffect(novelId, chapterId) {
                    // The db lookups live on libraryViewModel.restoreMissingChapter;
                    // only the navigation stays here.
                    when (val restored = activity.libraryViewModel.restoreMissingChapter(novelId, chapterId)) {
                        is ChapterRestore.Restored -> return@LaunchedEffect
                        is ChapterRestore.FallbackToNovel ->
                            navController.navigate("novelDetail/${restored.novelId}") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        is ChapterRestore.FallbackToHome ->
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                    }
                }
            } else {
                // Resolved once per novel, not per chapter - a Previous/Next hop (same
                // novelId, different chapterId) shouldn't re-fetch it.
                LaunchedEffect(novelId) {
                    activity.readerViewModel.loadReaderAuthor(novel?.authorId)
                }
                // Keyed on chapterId (via `remember(chapterId)`) so a Previous/Next
                // hop's `navigate(...) { popUpTo(...) { inclusive = true } }` below -
                // which replaces this backStackEntry with a fresh one rather than
                // recomposing the existing one in place - starts each new chapter's
                // content back at "not loaded yet" instead of briefly showing the
                // previous chapter's text.
                val chapterContentState = remember(chapterId) { mutableStateOf<ChapterLoadResult?>(null) }
                // Bumped by the error screen's "Try again" to re-run the load below in
                // place, without navigating away and back.
                val chapterRetryCount = remember(chapterId) { mutableStateOf(0) }
                LaunchedEffect(chapterId, chapterRetryCount.value) {
                    chapterContentState.value = null
                    chapterContentState.value = activity.readerViewModel.loadChapterContent(chapter.sourcePath)
                }
                val loadResult = chapterContentState.value
                val content = (loadResult as? ChapterLoadResult.Ok)?.content?.body
                // Rung 14 (F9) - resolved once per chapter, same "remember +
                // LaunchedEffect" pattern as readerAuthor above. Only restorable when
                // the saved row is actually for *this* chapter.
                val initialReaderPosition = remember(chapterId) { mutableStateOf<ReaderPosition?>(null) }
                // "No saved position" (null above) and "haven't looked yet" used to be
                // the same value, and ReaderScreen was composed the moment the chapter
                // text arrived - usually *before* this lookup finished. This flag is
                // what ReaderScreen's composition now waits on: the position is
                // resolved - found or genuinely absent - before the reader exists at
                // all.
                val initialPositionResolved = remember(chapterId) { mutableStateOf(false) }
                LaunchedEffect(novelId, chapterId, content) {
                    // Both the CHAR_OFFSET row and the legacy PERCENTAGE conversion are
                    // settled against the loaded text, and ReaderScreen can't show
                    // without it anyway - so wait for it rather than answering early.
                    if (content == null) {
                        // A retry reload: forget the old answer so the reader isn't
                        // shown again until the position is re-read against the new
                        // text.
                        initialPositionResolved.value = false
                        return@LaunchedEffect
                    }
                    initialReaderPosition.value =
                        activity.readerViewModel.resolveInitialPosition(novelId, chapterId, content)
                    initialPositionResolved.value = true
                }
                if (loadResult is ChapterLoadResult.Failed) {
                    // Provider hiccup, file moved/deleted underneath us, etc. - a real
                    // error screen with a retry rather than error text standing in for
                    // the chapter body (which read-aloud would have read out loud).
                    ChapterLoadErrorScreen(
                        chapterTitle = chapter.title,
                        onRetry = { chapterRetryCount.value += 1 },
                        onBack = {
                            navController.navigate(if (novel != null) "novelDetail/${novel.id}" else "home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    )
                } else if (content != null && initialPositionResolved.value) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val currentIndex = activity.libraryViewModel.chapters.indexOfFirst { it.id == chapterId }
                        val previousChapter = activity.libraryViewModel.chapters.getOrNull(currentIndex - 1).takeIf { currentIndex > 0 }
                        val nextChapter = activity.libraryViewModel.chapters.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
                        val arcTitle = chapter.arcId?.let { arcId -> activity.libraryViewModel.arcs.firstOrNull { it.id == arcId }?.name }
                        // Arc cover -> fiction cover -> null (renders the placeholder) -
                        // see bugs.md Bug 3b. Resolved here rather than in ReaderScreen
                        // so it stays decoupled from ArcEntity/NovelEntity, same
                        // rationale as novelTitle/arcTitle.
                        val readerCoverUri = chapter.arcId
                            ?.let { arcId -> activity.libraryViewModel.arcs.firstOrNull { it.id == arcId }?.coverUri }
                            ?: novel?.coverUri
                        ReaderScreen(
                            chapter = chapter,
                            content = content,
                            // resolvedTheme, not currentTheme.value directly -
                            // ReaderScreen's readingModeFor only maps the three concrete
                            // themes (see its own `when`), and SYSTEM_DEFAULT isn't one
                            // of them.
                            appTheme = resolvedTheme,
                            novelTitle = novel?.title,
                            arcTitle = arcTitle,
                            coverUri = readerCoverUri,
                            author = activity.readerViewModel.readerAuthor.value,
                            initialPosition = initialReaderPosition.value,
                            // The four handlers below (onBack, onBackToFiction,
                            // onPrevious, onNext) use AppScope.io plus the durable
                            // Worker backstop, like onSystemBack: no later ON_PAUSE
                            // checkpoint exists once the reader has left composition, so
                            // this write is the only attempt. Navigation hops back to
                            // Main after the write lands (NavController isn't
                            // thread-safe).
                            onBack = { position ->
                                AppScope.io.launch {
                                    activity.libraryViewModel.saveReadingProgress(activity.applicationContext, novelId, chapterId, position)
                                    withContext(Dispatchers.Main) {
                                        // Same popUpTo("home") reasoning as NovelDetail's
                                        // onBack - Reader's Back always went straight to
                                        // Home regardless of entry path.
                                        navController.navigate("home") {
                                            popUpTo("home") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                                ReadingProgressSaveWorker.enqueue(
                                    activity.applicationContext, novelId, chapterId, position
                                )
                            },
                            onBackToFiction = { position ->
                                if (novel != null) {
                                    AppScope.io.launch {
                                        activity.libraryViewModel.saveReadingProgress(activity.applicationContext, novelId, chapterId, position)
                                        withContext(Dispatchers.Main) {
                                            navController.navigate("novelDetail/${novel.id}") {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                    ReadingProgressSaveWorker.enqueue(
                                        activity.applicationContext, novelId, chapterId, position
                                    )
                                }
                            },
                            onPrevious = previousChapter?.let { prev ->
                                { position: ReaderPosition ->
                                    AppScope.io.launch {
                                        activity.libraryViewModel.saveReadingProgress(activity.applicationContext, novelId, chapterId, position)
                                        withContext(Dispatchers.Main) {
                                            // Replace, not push - Back from chapter 5
                                            // returns to NovelDetail, not chapter 4,
                                            // matching pre-NavHost behavior where
                                            // onPrevious/onNext never touched a back
                                            // stack because there wasn't one.
                                            navController.navigate("reader/$novelId/${prev.id}") {
                                                popUpTo("reader/{novelId}/{chapterId}") { inclusive = true }
                                            }
                                        }
                                    }
                                    ReadingProgressSaveWorker.enqueue(
                                        activity.applicationContext, novelId, chapterId, position
                                    )
                                }
                            },
                            onNext = nextChapter?.let { next ->
                                { position: ReaderPosition ->
                                    AppScope.io.launch {
                                        activity.libraryViewModel.saveReadingProgress(activity.applicationContext, novelId, chapterId, position)
                                        withContext(Dispatchers.Main) {
                                            navController.navigate("reader/$novelId/${next.id}") {
                                                popUpTo("reader/{novelId}/{chapterId}") { inclusive = true }
                                            }
                                        }
                                    }
                                    ReadingProgressSaveWorker.enqueue(
                                        activity.applicationContext, novelId, chapterId, position
                                    )
                                }
                            },
                            onAuthorClick = {
                                val authorId = activity.readerViewModel.readerAuthor.value?.id
                                if (authorId != null) {
                                    navController.navigate("author/$authorId")
                                }
                            },
                            // Gear icon in TtsControlsBar (ui/ReaderScreen.kt) - same
                            // route "settings" already navigates to via onTtsClick.
                            // Forward nav, not popUpTo/launchSingleTop, so Back from
                            // settings/tts returns to this same Reader backStackEntry
                            // (and its in-progress read-aloud session) rather than
                            // replacing it.
                            onOpenTtsSettings = { navController.navigate("settings/tts") },
                            // The "Reader Preferences" pill: one place for reader
                            // settings. Forward nav so Back returns to this reader.
                            onOpenReaderSettings = { navController.navigate("settings/reader") },
                            // Rung 4 fix (docs/implementation/tts-playback-controls.md):
                            // the only existing POST_NOTIFICATIONS request was wired to
                            // the "Notify new chapters" toggle, so a reader who starts
                            // read-aloud without ever touching that toggle was never
                            // asked - on API 33+ that silently suppresses
                            // TtsPlaybackService's foreground notification entirely,
                            // transport buttons included, with no crash and no error to
                            // point at it.
                            onRequestNotificationPermission = { activity.requestNotificationPermissionIfNeeded() },
                            // Continue-Reading-loses-progress fix (see ReaderScreen's
                            // own doc comment on this parameter): same save as
                            // onBack/onPrevious/onNext above, just without any
                            // navigation attached, so ReaderScreen can call it
                            // opportunistically (on Activity pause, and once more on
                            // leaving composition) instead of only at an explicit
                            // in-app exit tap.
                            //
                            // AppScope.io, not lifecycleScope: this fires on ON_PAUSE,
                            // which is "about to go away" by design - exactly when
                            // MainActivity is most likely to be recreated next
                            // (rotation, memory reclaim, or a real process restart).
                            // lifecycleScope is cancelled at this *instance's*
                            // ON_DESTROY, which during a recreation runs within the same
                            // handful of frames as this ON_PAUSE - almost guaranteeing
                            // the Room write gets cancelled mid-flight instead of
                            // committing. No navigation follows this one, so there's
                            // nothing that needs to hop back to Main afterward.
                            onPositionChanged = { position ->
                                AppScope.io.launch {
                                    activity.libraryViewModel.saveReadingProgress(activity.applicationContext, novelId, chapterId, position)
                                }
                                // Durable backstop (see ReadingProgressSaveWorker's own
                                // doc comment): AppScope.io survives Activity recreation
                                // but not the process being killed outright mid-write,
                                // which is exactly the risk at this specific checkpoint
                                // (ON_PAUSE, "about to go away"). WorkManager persists
                                // the request itself before doWork() runs, so it
                                // survives and retries even if this process dies before
                                // the line above commits. Same upsert-by-key write
                                // either way, so running both is idempotent.
                                ReadingProgressSaveWorker.enqueue(
                                    activity.applicationContext, novelId, chapterId, position
                                )
                            },
                            // Continue-Reading-loses-progress fix, part 2: the
                            // system/gesture Back action never ran onBack (see
                            // ReaderScreen's own doc comment on this parameter), so it
                            // never saved. Saves, then pops the back stack directly -
                            // the same thing NavController's own default
                            // OnBackPressedCallback would have done here - rather than
                            // routing through onBack's own navigate("home"), which
                            // would change where gesture Back lands versus today.
                            //
                            // AppScope.io for the same reason as onPositionChanged above
                            // - this is also a "the Activity might not stick around"
                            // checkpoint, not an ordinary in-app tap. Unlike
                            // onPositionChanged, something *does* need the main thread
                            // afterward (NavController isn't thread-safe), so this hops
                            // back to Dispatchers.Main once the write has actually
                            // landed, instead of popping the back stack from a
                            // background thread.
                            onSystemBack = { position ->
                                AppScope.io.launch {
                                    activity.libraryViewModel.saveReadingProgress(activity.applicationContext, novelId, chapterId, position)
                                    withContext(Dispatchers.Main) {
                                        navController.popBackStack()
                                    }
                                }
                                // See onPositionChanged above for why this runs
                                // alongside, not instead of, the AppScope.io write.
                                ReadingProgressSaveWorker.enqueue(
                                    activity.applicationContext, novelId, chapterId, position
                                )
                            }
                        )
                    }
                }
            }
        }

        // Stage 3.4 (see docs/foundational/design-notes.md): `from` is dropped from
        // what used to be Screen.Author - onBack is now a plain
        // navController.popBackStack(), since NavController's own back stack already
        // knows whether the previous entry was "novelDetail/{novelId}" (tapped from a
        // fiction page byline) or "reader/{novelId}/{chapterId}" (tapped from the
        // reader's "About the author" card) without this destination needing to carry
        // that itself.
        composable(
            "author/{authorId}",
            arguments = listOf(navArgument("authorId") { type = NavType.StringType })
        ) { backStackEntry ->
            val authorId = backStackEntry.arguments?.getString("authorId") ?: ""
            // Reload whenever the authorId changes (tapping into a different author's
            // page while one is already showing isn't a real path today, but this keeps
            // the screen correct if it ever is) - not on every recomposition.
            LaunchedEffect(authorId) {
                activity.libraryViewModel.loadAuthorPage(authorId)
            }
            val author = activity.libraryViewModel.authorPageAuthor.value
            if (author != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AuthorPageScreen(
                        author = author,
                        novels = activity.libraryViewModel.authorPageNovels,
                        onBack = { navController.popBackStack() },
                        onNovelClick = { novel ->
                            activity.lifecycleScope.launch {
                                activity.libraryViewModel.loadNovelDetails(novel)
                                navController.navigate("novelDetail/${novel.id}") { launchSingleTop = true }
                            }
                        }
                    )
                }
            }
        }

        composable("settings") {
            Column(modifier = Modifier.fillMaxSize()) {
                // Stage 1 (see docs/foundational/design-notes.md): SettingsScreen is
                // now a pure index/menu - it no longer takes currentTheme/splash* state
                // or their setter callbacks, just navigation into each row's own
                // destination.
                SettingsScreen(
                    onThemeClick = { navController.navigate("settings/theme") },
                    onLibraryClick = { navController.navigate("settings/library") },
                    onSplashClick = { navController.navigate("settings/splash") },
                    onTtsClick = { navController.navigate("settings/tts") },
                    onReaderClick = { navController.navigate("settings/reader") },
                    onPrivacyPolicy = { navController.navigate("privacy_policy") },
                    onTermsAndConditions = { navController.navigate("terms") },
                    onAboutMe = { navController.navigate("about_me") }
                )
            }
        }

        // Home-screen rework's third bottom-bar tab: now an index (a cork board of
        // pinned notes, see NoticeBoardScreen.kt) that opens one page per note. The
        // sub-routes below aren't bottom-bar routes (isBottomBarRoute matches tabs by
        // exact route), so they're drill-down pages like settings/* - the bar hides on
        // them and Back pops to the board. Pigeon Rally is now backed by
        // NoticeBoardViewModel/chapter_update_events; Journal remains its own thing
        // (JournalViewModel, an RSS feed, not a notification log).
        composable("noticeBoard") {
            Column(modifier = Modifier.fillMaxSize()) {
                // Re-reads unseenCount every time the board is (re)opened - e.g. after
                // backing out of Pigeon Rally having marked rows read, or after the
                // background worker logged a new row while this screen wasn't on top.
                LaunchedEffect(Unit) { activity.noticeBoardViewModel.refresh() }
                NoticeBoardScreen(
                    pigeonRallyUnseenCount = activity.noticeBoardViewModel.unseenCount.intValue,
                    onOpenPigeonRally = { navController.navigate("noticeBoard/pigeonRally") { launchSingleTop = true } },
                    onOpenJournal = { navController.navigate("noticeBoard/journal") { launchSingleTop = true } }
                )
            }
        }

        composable("noticeBoard/pigeonRally") {
            Column(modifier = Modifier.fillMaxSize()) {
                LaunchedEffect(Unit) { activity.noticeBoardViewModel.refresh() }
                PigeonRallyScreen(
                    events = activity.noticeBoardViewModel.events,
                    novels = activity.libraryViewModel.novels,
                    onBack = { navController.popBackStack() },
                    onEventClick = { event ->
                        activity.noticeBoardViewModel.markSeen(event.id)
                        // Straight to the novel page, not the reader - same reasoning
                        // as tapping a novel anywhere else in the app
                        // (notice-board.md's "List" bullet): a reader who wants the new
                        // chapter itself is one more tap in via Continue Reading/the
                        // chapter list.
                        navController.navigate("novelDetail/${event.novelId}") {
                            launchSingleTop = true
                        }
                    },
                    onMarkAllRead = { activity.noticeBoardViewModel.markAllSeen() }
                )
            }
        }

        composable("noticeBoard/journal") {
            Column(modifier = Modifier.fillMaxSize()) {
                // Re-entering within ten minutes reuses the last fresh copy; see
                // JournalViewModel.load.
                LaunchedEffect(Unit) { activity.journalViewModel.load() }
                RaesJournalScreen(
                    result = activity.journalViewModel.result.value,
                    isLoading = activity.journalViewModel.isLoading.value,
                    onRefresh = { activity.journalViewModel.load(force = true) },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings/theme") {
            Column(modifier = Modifier.fillMaxSize()) {
                // Stage 2 (see docs/foundational/design-notes.md): the Theme radio
                // group's callback wiring, unchanged from the old "settings" composable
                // - just terminating on this screen instead of the index. See that
                // composable's own history for why these route through
                // settingsViewModel rather than prefsManager directly.
                ThemeSettingsScreen(
                    currentTheme = currentTheme.value,
                    systemDefaultLightVariant = currentSystemDefaultLightVariant.value,
                    amoledModeEnabled = amoledModeEnabled.value,
                    onThemeSelected = { theme -> activity.settingsViewModel.setTheme(theme) },
                    onSystemDefaultLightVariantSelected = { variant ->
                        activity.settingsViewModel.setSystemDefaultLightVariant(variant)
                    },
                    onAmoledModeToggle = { enabled ->
                        activity.settingsViewModel.setAmoledModeEnabled(enabled)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings/library") {
            Column(modifier = Modifier.fillMaxSize()) {
                // Rewritten per docs/proposal/library-maintenance.md - see
                // ui/LibrarySettingsScreen.kt's own doc comment for the full scope.
                // These four read straight off prefsManager (same pattern
                // splashAnimationEnabled/ttsDefaultRate etc. already use above) rather
                // than through settingsViewModel, which only ever wrapped the theme
                // flows (see that class's own doc comment).
                val autoScanOnLaunch = activity.prefsManager.autoScanOnLaunch.collectAsState(initial = true)
                val backgroundScanEnabled = activity.prefsManager.backgroundScanEnabled.collectAsState(initial = false)
                val scanOnFileChangeEnabledState = activity.prefsManager.scanOnFileChangeEnabled.collectAsState(initial = false)
                val wifiOnlySync = activity.prefsManager.wifiOnlySync.collectAsState(initial = false)
                val lastScanInfo = activity.prefsManager.lastScanInfo.collectAsState(initial = null)
                // Storage breakdown is a one-shot suspend computation
                // (computeStorageBreakdown walks the filesystem), not a Flow -
                // recomputed on entering this route and again after anything that could
                // have changed it (rescan, clear cache), rather than live.
                val storageBreakdown = remember { mutableStateOf<StorageBreakdown?>(null) }
                // Result text for whichever of Clear cache / Remove missing novels last
                // ran - see LibrarySettingsScreen's own doc comment on the
                // actionMessage param for why this is a plain Text rather than a
                // Snackbar.
                val libraryActionMessage = remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    storageBreakdown.value = computeStorageBreakdown(activity, activity.libraryViewModel.novels.toList())
                }

                LibrarySettingsScreen(
                    autoScanOnLaunch = autoScanOnLaunch.value,
                    onAutoScanOnLaunchToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setAutoScanOnLaunch(enabled) }
                    },
                    backgroundScanEnabled = backgroundScanEnabled.value,
                    onBackgroundScanToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setBackgroundScanEnabled(enabled) }
                    },
                    scanOnFileChangeEnabled = scanOnFileChangeEnabledState.value,
                    onScanOnFileChangeToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setScanOnFileChangeEnabled(enabled) }
                    },
                    wifiOnlySync = wifiOnlySync.value,
                    onWifiOnlySyncToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setWifiOnlySync(enabled) }
                    },
                    lastScanInfo = lastScanInfo.value,
                    storageBreakdown = storageBreakdown.value,
                    actionMessage = libraryActionMessage.value,
                    onClearImageCaches = {
                        activity.lifecycleScope.launch {
                            val freed = clearImageCaches(activity)
                            libraryActionMessage.value = "Cleared ${formatBytes(freed)} of cached covers and thumbnails."
                            storageBreakdown.value = computeStorageBreakdown(activity, activity.libraryViewModel.novels.toList())
                        }
                    },
                    onOpenInFileManager = { activity.openLibraryFolderInFileManager() },
                    onRemoveMissingNovels = {
                        activity.lifecycleScope.launch {
                            val removed = activity.libraryViewModel.removeMissingNovels()
                            libraryActionMessage.value = if (removed > 0) {
                                "Removed $removed missing novel(s)."
                            } else {
                                "No missing novels to remove."
                            }
                        }
                    },
                    onRescan = {
                        activity.lifecycleScope.launch {
                            // No novels.clear() here - see bugs.md Bug 4. startScan's
                            // onScanCompleted now reconciles stale novels against the DB
                            // once the scan actually finishes, instead of blanking the
                            // visible library up front and hoping the scan fully
                            // repopulates it.
                            val root = resolveLibraryRoot(activity)
                            if (root != null) {
                                activity.libraryViewModel.startScan(root)
                                storageBreakdown.value = computeStorageBreakdown(activity, activity.libraryViewModel.novels.toList())
                            } else {
                                // Shared storage genuinely isn't available right now
                                // (see resolveLibraryRoot's doc comment) - nothing to
                                // rescan against yet.
                                activity.libraryViewModel.reportScanSetupError(
                                    "Couldn't reach the library folder - try again in a moment."
                                )
                            }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings/splash") {
            Column(modifier = Modifier.fillMaxSize()) {
                // Stage 2 (see docs/foundational/design-notes.md): the Splash switches'
                // callback wiring, unchanged from the old "settings" composable - just
                // terminating on this screen instead of the index.
                SplashSettingsScreen(
                    splashAnimationEnabled = splashAnimationEnabled.value,
                    splashMusicEnabled = splashMusicEnabled.value,
                    onSplashAnimationToggle = { enabled ->
                        activity.lifecycleScope.launch {
                            activity.prefsManager.setSplashAnimationEnabled(enabled)
                        }
                    },
                    onSplashMusicToggle = { enabled ->
                        activity.lifecycleScope.launch {
                            activity.prefsManager.setSplashMusicEnabled(enabled)
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings/reader") {
            Column(modifier = Modifier.fillMaxSize()) {
                // reader-settings.md's "cheap" batch. Direct PreferencesManager access,
                // same pattern as settings/splash and settings/tts - no
                // ReaderSettingsViewModel.
                ReaderSettingsScreen(
                    fontSize = readerFontSize.value,
                    lineHeight = readerLineHeight.value,
                    readingMode = readerReadingMode.value?.let {
                        runCatching { com.arkarium.app.data.ReadingModeDefault.valueOf(it) }.getOrNull()
                    },
                    fontFamily = runCatching { ReaderFontFamily.valueOf(readerFontFamily.value) }
                        .getOrDefault(ReaderFontFamily.SERIF),
                    keepScreenOn = readerKeepScreenOn.value,
                    fullscreen = readerFullscreen.value,
                    breakLines = readerBreakLines.value,
                    brightnessSliderEnabled = readerBrightnessSliderEnabled.value,
                    brightness = readerBrightness.value,
                    onFontSizeChange = { size ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderFontSize(size) }
                    },
                    onLineHeightChange = { height ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderLineHeight(height) }
                    },
                    onReadingModeChange = { mode ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderReadingMode(mode) }
                    },
                    onFontFamilyChange = { family ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderFontFamily(family.name) }
                    },
                    onKeepScreenOnToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderKeepScreenOn(enabled) }
                    },
                    onFullscreenToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderFullscreen(enabled) }
                    },
                    onBreakLinesToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderBreakLines(enabled) }
                    },
                    onBrightnessSliderToggle = { enabled ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderBrightnessSliderEnabled(enabled) }
                    },
                    onBrightnessChange = { level ->
                        activity.lifecycleScope.launch { activity.prefsManager.setReaderBrightness(level) }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings/tts") {
            Column(modifier = Modifier.fillMaxSize()) {
                // Stage 3.2 (see docs/foundational/design-notes.md): direct
                // PreferencesManager access hoisted at the Activity, same pattern
                // settings/splash above already uses - no TtsSettingsViewModel.
                //
                // Binds TtsSettingsScreen to TtsPlaybackService the same way
                // rememberChapterTts() already does for the reader - that binding
                // (ServiceConnection + BIND_AUTO_CREATE), so this reuses it directly
                // rather than writing a second one. This composable leaves
                // ReaderScreen's own rememberChapterTts() call (the one actually
                // reading chapters aloud) alone - each call site gets its own
                // ChapterTtsState, and the service supports being bound from more than
                // one place at once.
                val ttsState = rememberChapterTts()
                TtsSettingsScreen(
                    ttsDefaultRate = ttsDefaultRate.value,
                    ttsPitch = ttsPitch.value,
                    ttsAutoContinue = ttsAutoContinue.value,
                    ttsKeepScreenOn = ttsKeepScreenOn.value,
                    availableVoices = ttsState.availableVoices,
                    // Prefers the service's live currentVoiceName once a service is
                    // attached and has resolved one (matches what setVoice() below will
                    // actually apply), falling back to the stored preference before
                    // that attach completes so the row doesn't flash "System default"
                    // first.
                    currentVoiceName = ttsState.currentVoiceName ?: ttsVoiceName.value,
                    onDefaultRateChange = { rate ->
                        activity.lifecycleScope.launch {
                            activity.prefsManager.setTtsDefaultRate(rate)
                        }
                    },
                    onPitchChange = { pitch ->
                        activity.lifecycleScope.launch {
                            activity.prefsManager.setTtsPitch(pitch)
                        }
                    },
                    onAutoContinueToggle = { enabled ->
                        activity.lifecycleScope.launch {
                            activity.prefsManager.setTtsAutoContinue(enabled)
                        }
                    },
                    onKeepScreenOnToggle = { enabled ->
                        activity.lifecycleScope.launch {
                            activity.prefsManager.setTtsKeepScreenOn(enabled)
                        }
                    },
                    // Rung 4: the Voice card now opens the full settings/tts/voice page
                    // (below) instead of an in-place dialog; selection itself (and the
                    // "push live" + persist split) now happens there.
                    onOpenVoicePicker = { navController.navigate("settings/tts/voice") },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings/tts/voice") {
            Column(modifier = Modifier.fillMaxSize()) {
                // The voice page (design-notes.md's "TTS settings split"). Binds its
                // own ChapterTtsState the same way settings/tts above does -
                // rememberChapterTts()'s DisposableEffect unbinds settings/tts's
                // ChapterTtsState as this composable replaces it in the NavHost (only
                // the current destination's content stays in composition), so there's
                // only ever one listener attached to TtsPlaybackService at a time; a
                // session already playing in the reader is a separate ChapterTtsState
                // the service is bound to concurrently, same as before this rung.
                val voicePickerState = rememberChapterTts()
                TtsVoicePickerScreen(
                    availableVoices = voicePickerState.availableVoices,
                    currentVoiceName = voicePickerState.currentVoiceName ?: ttsVoiceName.value,
                    favoriteVoiceNames = ttsFavoriteVoiceNames.value,
                    onVoiceSelected = { name ->
                        // Same "push live" + persist split the old settings/tts
                        // onVoiceChange used.
                        voicePickerState.setVoice(name)
                        activity.lifecycleScope.launch {
                            activity.prefsManager.setTtsVoiceName(name)
                        }
                    },
                    onToggleFavorite = { name ->
                        activity.lifecycleScope.launch {
                            activity.prefsManager.toggleTtsFavoriteVoice(name)
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("privacy_policy") {
            Column(modifier = Modifier.fillMaxSize()) {
                LegalDocumentScreen(
                    title = "Privacy Policy",
                    sections = LegalContent.privacyPolicy,
                    onBack = { navController.popBackStack() },
                    quickFacts = LegalContent.privacyQuickFacts
                )
            }
        }

        composable("terms") {
            Column(modifier = Modifier.fillMaxSize()) {
                LegalDocumentScreen(
                    title = "Terms & Conditions",
                    sections = LegalContent.termsAndConditions,
                    onBack = { navController.popBackStack() },
                    quickFacts = LegalContent.termsQuickFacts
                )
            }
        }

        composable("about_me") {
            Column(modifier = Modifier.fillMaxSize()) {
                WebViewScreen(
                    title = "About Me",
                    url = "https://rae-ark.horizonarkstudio.workers.dev/",
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
