package com.arkarium.app.ui.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.data.ReaderPosition
import com.arkarium.app.data.ReadingModeDefault
import com.arkarium.app.data.Theme
import com.arkarium.app.ui.components.CoverThumbnail
import com.arkarium.app.util.WordLookup
import kotlinx.coroutines.flow.first
import com.arkarium.app.ui.ChapterTtsState
import com.arkarium.app.ui.ReaderBlock
import com.arkarium.app.ui.ReaderBottomPill
import com.arkarium.app.ui.READER_LEADING_ITEMS
import com.arkarium.app.ui.ReaderItemEdge
import com.arkarium.app.ui.ReaderScrollModel
import com.arkarium.app.ui.TtsSessionAction
import com.arkarium.app.ui.readerAnchorBlock
import com.arkarium.app.ui.readerBlockIndexForCharOffset
import com.arkarium.app.ui.readerBlockIndexForChunkStart
import com.arkarium.app.ui.readerBlocks
import com.arkarium.app.ui.readerBottomPill
import com.arkarium.app.ui.reconcileTtsSession
import com.arkarium.app.ui.rememberChapterTts
import com.arkarium.app.ui.ttsHighlightRange

enum class ReadingMode {
    LIGHT, SEPIA, DARK
}

// Font family choice for the chapter body, set on the Reader settings page. All
// entries resolve to Android's built-in system families by name (Typeface.create),
// which falls back silently to the default family if a device lacks one. DYSLEXIC
// uses system sans-serif with wider letter spacing rather than a bundled dyslexia
// font (see extraLetterSpacing below) - avoids shipping/licensing a font file, and
// letter spacing has evidence as a readability aid independent of glyph shapes.
//
// Stored by name (PreferencesManager.setReaderFontFamily): never rename or reorder
// an existing entry; unknown/removed names fall back to SERIF at every read site.
enum class ReaderFontFamily(val displayName: String) {
    SERIF("Serif"),
    SANS("Sans"),
    DYSLEXIC("Dyslexia-friendly"),
    SANS_LIGHT("Sans light"),
    SANS_MEDIUM("Sans medium"),
    CONDENSED("Condensed"),
    MONO("Monospace"),
    CASUAL("Casual")
}

// Maps a ReaderFontFamily choice to the actual Compose FontFamily to render with.
// Internal rather than private so the Reader settings font card can preview each
// choice using exactly the resolution the reader itself uses.
internal fun resolveFontFamily(choice: ReaderFontFamily): FontFamily = when (choice) {
    ReaderFontFamily.SERIF -> FontFamily.Serif
    ReaderFontFamily.SANS -> FontFamily.SansSerif
    ReaderFontFamily.DYSLEXIC -> FontFamily.SansSerif
    ReaderFontFamily.SANS_LIGHT -> systemFamily("sans-serif-light")
    ReaderFontFamily.SANS_MEDIUM -> systemFamily("sans-serif-medium")
    ReaderFontFamily.CONDENSED -> systemFamily("sans-serif-condensed")
    ReaderFontFamily.MONO -> FontFamily.Monospace
    ReaderFontFamily.CASUAL -> systemFamily("casual")
}

private fun systemFamily(name: String): FontFamily =
    FontFamily(android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL))

// Extra tracking on top of the font choice. Only DYSLEXIC gets any: wider letter
// spacing reduces crowding between adjacent letters, a readability aid with more
// consistent evidence than glyph-shape "dyslexia fonts". 0.5.sp is tuned to stay
// comfortable at the reader's default 18sp body size.
internal fun extraLetterSpacing(choice: ReaderFontFamily) = when (choice) {
    ReaderFontFamily.DYSLEXIC -> 0.5.sp
    else -> 0.sp
}

// Reader's own LIGHT/SEPIA/DARK palette is intentionally separate from the app-wide
// Theme (sepia has no Theme equivalent, and the reading background may differ from
// the nav-screen background), but it starts aligned with the app theme the user
// picked in Settings rather than always opening in LIGHT.
private fun readingModeFor(theme: Theme): ReadingMode = when (theme) {
    Theme.LIGHT -> ReadingMode.LIGHT
    Theme.DARK -> ReadingMode.DARK
    Theme.WARM_PAPER -> ReadingMode.SEPIA
    // Unreachable in practice: MainActivity.resolveTheme() always resolves
    // SYSTEM_DEFAULT to a concrete theme first. Kept to satisfy exhaustiveness.
    Theme.SYSTEM_DEFAULT -> ReadingMode.LIGHT
}

// Maps the persisted Settings > Reader override (ReadingModeDefault - three
// concrete choices, no "follow app theme" entry) onto this file's ReadingMode.
// Kept separate from readingModeFor above: different input type, no fallback.
private fun toReadingMode(mode: ReadingModeDefault): ReadingMode = when (mode) {
    ReadingModeDefault.LIGHT -> ReadingMode.LIGHT
    ReadingModeDefault.SEPIA -> ReadingMode.SEPIA
    ReadingModeDefault.DARK -> ReadingMode.DARK
}

// Compose's LocalContext.current isn't guaranteed to be the Activity itself - it can
// be a ContextWrapper sitting in front of one - so this walks up to find the real
// Activity rather than assuming a direct cast holds. A silent no-op (window stays
// null) is the safer failure mode here than crashing on a bad cast.
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Applies the window's edge-to-edge and display-cutout (notch) layout mode.
//
// Invariants (see architecture.md's "Reading and local state", `reader_fullscreen`
// bullet, for the fuller lesson):
// - `LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT` letterboxes the cutout any time the
//   window is in landscape *or* fullscreen, not landscape alone - so `drawIntoCutout`
//   must be driven by both, in both orientations, not just landscape.
// - `edgeToEdge` must track `drawIntoCutout` rather than staying fullscreen-only:
//   Android's cutout guidance requires an edge-to-edge window before SHORT_EDGES
//   content is defined behavior. A visible status bar over an edge-to-edge window is
//   fine - the Reader TopAppBar below is deliberately the one in this app that does
//   not zero its own `windowInsets`, so it self-pads below a real status bar.
// - `ViewCompat.requestApplyInsets` must be called every time this function changes
//   the attribute: Compose's `windowInsetsPadding(WindowInsets.displayCutout)` only
//   picks up a changed `layoutInDisplayCutoutMode` on a fresh dispatch, not a
//   one-time read, so skipping this leaves stale padding after the mode changes.
// - `layoutInDisplayCutoutMode` is API 28+; below that there is no cutout API.
//
// Not private: MainActivity's `renderMainContent` reuses this for every non-reader
// route (landscape-tablet.md's cutout fix). Reader's own
// DisposableEffect(fullscreenEnabled, isLandscape) below owns applying it while a
// reader route is on screen; MainActivity's effect only re-applies its own default
// once `currentRoute` leaves the reader, so the two never run on the same frame.
internal fun applyWindowLayout(window: android.view.Window, edgeToEdge: Boolean, drawIntoCutout: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, !edgeToEdge)
    if (Build.VERSION.SDK_INT >= 28) {
        val params = window.attributes
        params.layoutInDisplayCutoutMode = if (drawIntoCutout)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        else
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        window.attributes = params
    }
    ViewCompat.requestApplyInsets(window.decorView)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    chapter: ChapterEntity,
    content: String,
    appTheme: Theme = Theme.LIGHT,
    // novelTitle/arcTitle are plain strings, not NovelEntity/ArcEntity, so ReaderScreen
    // stays decoupled from the data layer beyond the ChapterEntity it already takes.
    novelTitle: String? = null,
    arcTitle: String? = null,
    // Resolution order is the caller's job: arc cover -> fiction cover -> null. Null
    // renders the same placeholder AuthorPageScreen uses elsewhere, not a blank gap.
    coverUri: String? = null,
    // Sourced from the same author.json-backed AuthorEntity AuthorPageScreen reads.
    // Null renders no "About the author" card, same as a fiction with no linked author.
    author: AuthorEntity? = null,
    // The character-offset anchor saved the last time this chapter was left, if any
    // (null for a never-read chapter, or one whose saved row predates this anchor
    // format). The caller resolves this from ReadingProgressEntity rather than this
    // screen reading the DB itself, same decoupling as novelTitle/arcTitle/author.
    initialPosition: ReaderPosition? = null,
    // All four callbacks below report a ReaderPosition anchor (see ReaderPosition.kt
    // and currentAnchor()) rather than a bare scroll fraction.
    onBack: (ReaderPosition) -> Unit,
    // Distinct from onBack: onBack is the top-app-bar arrow (goes wherever the caller
    // sends it, e.g. Home); onBackToFiction is the breadcrumb link that always means
    // "this fiction's detail page."
    onBackToFiction: (ReaderPosition) -> Unit = {},
    // Null (not just a no-op callback) means "there is no previous/next chapter" - the
    // caller passes null at the first/last chapter so the buttons render disabled.
    onPrevious: ((ReaderPosition) -> Unit)? = null,
    onNext: ((ReaderPosition) -> Unit)? = null,
    onAuthorClick: () -> Unit = {},
    // Opens the "settings/tts" page from the gear icon in TtsControlsBar below.
    onOpenTtsSettings: () -> Unit = {},
    onOpenReaderSettings: () -> Unit = {},
    // On API 33+, POST_NOTIFICATIONS is required for any notification to post,
    // including a foreground service's; TtsPlaybackService.startForeground() does not
    // fail when it's missing, it just posts nothing. Called alongside tts.speak()
    // below, best-effort - it does not gate starting playback, since the session
    // works either way and the OS shows the notification on a later update once/if
    // the permission is granted.
    onRequestNotificationPermission: () -> Unit = {},
    // onBack/onBackToFiction/onPrevious/onNext are the only places progress is saved
    // from an in-app tap. System/gesture Back and process death while backgrounded
    // don't go through any of them, so onPositionChanged is the opportunistic-save
    // callback wired to the lifecycle DisposableEffect and BackHandler near
    // currentAnchor() below - called on every Activity pause and right before a
    // system Back is allowed to proceed, in addition to the explicit in-app exits.
    onPositionChanged: (ReaderPosition) -> Unit = {},
    // The system/gesture Back action bypasses the TopAppBar's own arrow entirely -
    // NavController installs its own OnBackPressedCallback that pops the back stack
    // directly - so this is a separate callback from onBack rather than a reuse of
    // it: onBack always navigates to Home (an explicit design choice for the in-app
    // arrow), while system/gesture Back should go wherever the back stack naturally
    // leads. See the BackHandler near currentAnchor() below.
    onSystemBack: (ReaderPosition) -> Unit = {}
) {
    // fontSize/lineHeight/fontFamilyChoice/readingMode are session-local `remember`
    // state: the pill adjusts them live and never writes back to Settings. Each
    // one's starting value is seeded once below from the Settings > Reader default,
    // or the stored override if set (readingMode).
    val fontSize = remember { mutableFloatStateOf(18f) }
    val lineHeight = remember { mutableFloatStateOf(1.8f) }
    // Font family is not adjustable from the pill (moved to Settings > Reader): read
    // straight from the persisted default below, never changed for this composition.
    val fontFamilyChoice = remember { mutableStateOf(ReaderFontFamily.SERIF) }
    val readingMode = remember { mutableStateOf(readingModeFor(appTheme)) }
    // Read-aloud. See Tts.kt - one engine instance persists for the whole reading
    // session, including across Previous/Next.
    val tts = rememberChapterTts()
    // Reassigned every recomposition so the closure always captures whichever onNext
    // this screen currently has (it changes chapter-to-chapter, null at the last
    // chapter) - cheap and idempotent, so no LaunchedEffect/key needed. Passes an
    // end-of-text anchor rather than currentAnchor(): the reader finished by ear, not
    // by eye, so scroll position isn't a meaningful completion signal. Gated by
    // tts itself on autoContinueEnabled (off by default).
    tts.onChapterFinished = { onNext?.invoke(ReaderPosition(content.length, "", 1f)) }
    val context = LocalContext.current
    // A chunk that fails stops the session and reports why via
    // ChapterTtsState.lastErrorMessage rather than the chapter silently "completing"
    // with no sound. Shown once as a Toast (ReaderScreen has no Snackbar host) and
    // cleared right after so it doesn't reappear on the next recomposition.
    LaunchedEffect(tts.lastErrorMessage) {
        tts.lastErrorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            tts.clearError()
        }
    }
    val prefsManager = remember { PreferencesManager(context) }
    val keepScreenOnEnabled by prefsManager.ttsKeepScreenOn.collectAsStateWithLifecycle(initialValue = false)
    // Reader-level keep-screen-on, independent of the TTS one above: the two
    // combine (either condition alone keeps the flag set) rather than one
    // clearing the other's. Plain FLAG_KEEP_SCREEN_ON, not an idle-timer variant.
    val readerKeepScreenOnEnabled by prefsManager.readerKeepScreenOn.collectAsStateWithLifecycle(initialValue = false)
    DisposableEffect(keepScreenOnEnabled, readerKeepScreenOnEnabled, tts.isSpeaking) {
        val window = context.findActivity()?.window
        if (readerKeepScreenOnEnabled || (keepScreenOnEnabled && tts.isSpeaking)) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // DisposableEffect needs showControls, which isn't declared until below (Kotlin
    // requires declaration-before-use here) - see that val's own comment for where
    // the full-screen effect actually lives.
    val fullscreenEnabled by prefsManager.readerFullscreen.collectAsStateWithLifecycle(initialValue = false)
    // Whether bare "***"-style lines are chapter breaks; both parses below key on it.
    val breakLinesEnabled by prefsManager.readerBreakLines.collectAsStateWithLifecycle(initialValue = true)

    val brightnessOverrideEnabled by prefsManager.readerBrightnessSliderEnabled.collectAsStateWithLifecycle(initialValue = false)
    val brightnessLevel by prefsManager.readerBrightness.collectAsStateWithLifecycle(initialValue = -1f)
    DisposableEffect(Unit) {
        onDispose {
            context.findActivity()?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
            }
        }
    }
    LaunchedEffect(brightnessOverrideEnabled, brightnessLevel) {
        val window = context.findActivity()?.window ?: return@LaunchedEffect
        val params = window.attributes
        params.screenBrightness =
            if (brightnessOverrideEnabled && brightnessLevel >= 0f) brightnessLevel
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    // Seeds fontSize/lineHeight/fontFamilyChoice/readingMode from the Settings >
    // Reader defaults once per chapter; after that the pill adjusts them live
    // without this effect fighting it on recomposition.
    //
    // `settingsSeeded` flips once that read has landed. Until then the text lays out
    // with placeholder size/spacing/family, then reflows when the real values
    // arrive - so a position restored against that first layout would be a pixel
    // offset into text that's about to move. The restore below waits for this flag.
    var settingsSeeded by remember(chapter.id) { mutableStateOf(false) }
    LaunchedEffect(chapter.id) {
        fontSize.floatValue = prefsManager.readerFontSize.first()
        lineHeight.floatValue = prefsManager.readerLineHeight.first()
        fontFamilyChoice.value = runCatching {
            ReaderFontFamily.valueOf(prefsManager.readerFontFamily.first())
        }.getOrDefault(ReaderFontFamily.SERIF)
        val storedMode = prefsManager.readerReadingMode.first()
        readingMode.value = storedMode?.let {
            runCatching { ReadingModeDefault.valueOf(it) }.getOrNull()
        }?.let(::toReadingMode) ?: readingModeFor(appTheme)
        // The two layout-affecting flags collected with a placeholder initial value
        // above: make sure DataStore has answered for them before anything restores.
        prefsManager.readerBreakLines.first()
        prefsManager.readerFullscreen.first()
        settingsSeeded = true
    }
    // The tap-anywhere immersive toggle: true = chrome showing (top bar, Reader
    // Preferences pill, progress readout), false = immersive. rememberSaveable, not
    // remember: navigating away (e.g. to settings/tts from the read-aloud pill) takes
    // this screen out of composition, and plain `remember` state would not survive it.
    val showControls = rememberSaveable { mutableStateOf(true) }

    // The Reader Preferences pill's own open/closed state for its in-reader panel
    // (Text size/Spacing/Mode/read-aloud speed). It never writes to Settings > Reader
    // and vice versa; the two meet only at the default fontSize/lineHeight/readingMode
    // seed above. Font family, brightness and the always-on toggles stay Settings-only.
    val showPreferences = remember { mutableStateOf(false) }

    // Full-screen mode: hides the system status/nav bars (not just ARKarium's own
    // chrome) for as long as the setting is on, restoring them on every exit path
    // that tears down this composition. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE lets
    // the reader still pull the bars back temporarily with an edge swipe.
    //
    // Gated on `fullscreenEnabled` alone, not `showControls` - `showControls` is
    // `rememberSaveable` per back-stack entry, and Previous/Next replace the
    // reader's route with a brand-new entry, so keying on it would reset full-screen
    // on every page turn. ARKarium's own top bar/chrome still follows `showControls`
    // exactly as before (see the tap handler and TopAppBar below); only the
    // *system* bars' visibility is keyed on `fullscreenEnabled`.
    //
    // The window is always edge-to-edge with `drawIntoCutout = true` while this
    // effect is active (see applyWindowLayout's own doc comment for why both flags
    // must move together, in both orientations) - the root Column re-pads by the
    // cutout's own insets on all sides so text still never sits under the notch,
    // and the TopAppBar (the one in this app that doesn't zero its own
    // `windowInsets`) self-pads below a real, visible status bar. Neither pad ever
    // reads the other's inset, so there is nothing to double-pad. `isLandscape` is
    // still a DisposableEffect key purely so rotating while full-screen is on
    // re-runs `applyWindowLayout`'s `requestApplyInsets` and keeps the cutout inset
    // fresh - it does not otherwise change which flags are set.
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(fullscreenEnabled, isLandscape) {
        val window = context.findActivity()?.window
        val view = window?.decorView
        val controller = if (window != null && view != null) {
            WindowCompat.getInsetsController(window, view)
        } else null
        if (fullscreenEnabled) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        val drawIntoCutout = true
        if (window != null) {
            applyWindowLayout(window, edgeToEdge = drawIntoCutout, drawIntoCutout = drawIntoCutout)
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            // Deliberately does not also call applyWindowLayout(..., false, false)
            // here: MainActivity's own LaunchedEffect(currentRoute) is the single
            // owner of the window's edge-to-edge state once currentRoute leaves the
            // reader, and the two running in the same frame produces a double
            // status-bar padding gap. This only needs to guarantee the system bars
            // are shown again immediately, which controller?.show above does.
        }
    }

    // The always-visible "Reader Preferences" pill keeps settings discoverable without
    // depending on the tap gesture.
    // A read-aloud session exists (playing or paused), mirrored from the service via
    // `tts` - also true when this composition has only just re-attached to a session
    // that kept running while the reader was away.
    val ttsSessionActive = tts.isSpeaking || tts.isPaused
    // Which pill owns the bottom edge: Reader Preferences when chrome is showing, the
    // read-aloud controls in immersive mode while a session exists, otherwise nothing.
    // The two are one slot that swaps (with a water ripple), never shown together.
    val bottomPill = readerBottomPill(
        immersive = !showControls.value,
        ttsSessionActive = ttsSessionActive,
        ttsAvailable = tts.isAvailable
    )
    // The chapter body is a LazyColumn over readerBlocks() (ReaderBlocks.kt): only the
    // blocks actually scrolled into view are composed/laid out, and position/progress
    // are derived from the LazyListState's own index/offset bookkeeping
    // (ReaderScrollModel, readerAnchorBlock, readerBlockIndexForCharOffset,
    // readerBlockIndexForChunkStart) rather than a hand-maintained pixel map.
    val listState = rememberLazyListState()

    // `remember`-backed state above, including listState, survives across chapters
    // (ReaderScreen's call site doesn't change when Previous/Next swaps chapters) -
    // correct for fontSize/lineHeight/readingMode, but scroll position is per-chapter,
    // so it's reset explicitly whenever the chapter changes.
    LaunchedEffect(chapter.id) {
        listState.scrollToItem(0)
    }

    // The flat list of lazily-composed blocks for this chapter's text (ReaderBlocks.kt)
    // - one per prose paragraph plus one per divider/callout - and the weighted model
    // that turns the LazyListState's visible-item bookkeeping into a 0f..1f
    // reading-progress fraction (ReaderScrollModel's own doc comment).
    val blocks = remember(content, breakLinesEnabled) { readerBlocks(content, breakLinesEnabled) }
    val scrollModel = remember(blocks) { ReaderScrollModel(blocks) }
    // Each prose block's last TextLayoutResult (key: the block's rawStart), so the
    // long-press word lookup below can turn a tap position into a character offset.
    // Plain map, not state - nothing re-runs off its contents.
    val paragraphLayouts = remember(chapter.id) { HashMap<Int, TextLayoutResult>() }
    var positionRestored by remember(chapter.id) { mutableStateOf(initialPosition == null) }

    // Restores initialPosition once the reader settings that affect layout have been
    // seeded (settingsSeeded - a font-size/line-height change reflows the text, but
    // never changes which block index a given charOffset falls into, so unlike the old
    // pixel-offset restore this doesn't also need to wait for a layout pass to report
    // back: scrollToItem by index works the moment the target block is known). Gives up
    // and leaves the reader at the top if initialPosition's paragraph no longer exists
    // (e.g. the chapter was edited shorter since it was saved).
    LaunchedEffect(chapter.id, settingsSeeded, blocks) {
        if (positionRestored) return@LaunchedEffect
        val target = initialPosition ?: run { positionRestored = true; return@LaunchedEffect }
        if (!settingsSeeded) return@LaunchedEffect
        val blockIndex = readerBlockIndexForCharOffset(blocks, target.charOffset)
            ?: run { positionRestored = true; return@LaunchedEffect }
        listState.scrollToItem(READER_LEADING_ITEMS + blockIndex)
        positionRestored = true
    }

    // The restore above is a one-shot "jump to the saved block". If the reader takes
    // over before it lands (they start scrolling, or read-aloud auto-scroll moves the
    // list), the jump would now be wrong *and* currentAnchor() must stop reporting the
    // not-yet-applied initialPosition in place of where they actually are - so the
    // reader's own drag ends the wait. Deliberately a DragInteraction, not
    // isScrollInProgress: the chapter-start scrollToItem(0) and the restore's own
    // scrollToItem are programmatic scrolls and must not count as the reader taking over.
    LaunchedEffect(chapter.id) {
        listState.interactionSource.interactions.first { it is DragInteraction.Start }
        positionRestored = true
    }

    // Reconciles this composition with a read-aloud session that may have outlived the
    // previous one: the session lives in TtsPlaybackService, not here, so it keeps
    // playing across a rotation or reopening the chapter, and `tts` only learns about
    // it once the service bind completes (isAvailable going true).
    //  - Session is reading this chapter: return to immersive mode, leave playback alone.
    //  - Session is reading a different chapter: stop it.
    // Runs once per composition (per chapter, since Previous/Next swap the whole route).
    var ttsSessionReconciled by remember(chapter.id) { mutableStateOf(false) }
    LaunchedEffect(chapter.id, tts.isAvailable) {
        if (!tts.isAvailable || ttsSessionReconciled) return@LaunchedEffect
        ttsSessionReconciled = true
        val action = reconcileTtsSession(ttsSessionActive, tts.sessionKey, chapter.id)
        if (action == TtsSessionAction.ENTER_IMMERSIVE) {
            showControls.value = false
        } else if (action == TtsSessionAction.STOP_STALE) {
            tts.stop()
        }
    }

    // Follow the narration: whenever the highlighted range changes (a chunk boundary
    // or a paragraph boundary crossed mid-chunk, via the same ttsHighlightRange the
    // highlight itself uses), scroll to the block it starts in, unless that block is
    // already comfortably near the top of the viewport. Never fights a reader who
    // scrolls away mid-paragraph; the next paragraph or chunk brings them back. Held
    // back until reconcileTtsSession has run, so a stale session about to be stopped
    // above can't scroll this one.
    val ttsFollowing = ttsSessionActive && ttsSessionReconciled
    val (ttsScrollTargetStart, _) = ttsHighlightRange(
        paragraphStart = tts.currentParagraphStart,
        paragraphEnd = tts.currentParagraphEnd,
        chunkStart = tts.currentChunkStart,
        chunkEnd = tts.currentChunkEnd
    )
    LaunchedEffect(ttsScrollTargetStart, ttsFollowing, blocks) {
        if (!ttsFollowing || ttsScrollTargetStart < 0) return@LaunchedEffect
        val blockIndex = readerBlockIndexForChunkStart(blocks, content, ttsScrollTargetStart)
            ?: return@LaunchedEffect
        val itemIndex = READER_LEADING_ITEMS + blockIndex
        val info = listState.layoutInfo
        val margin = info.viewportSize.height * 0.1f
        val alreadyNearTop = info.visibleItemsInfo
            .firstOrNull { it.index == itemIndex }
            ?.let { it.offset <= margin } ?: false
        if (!alreadyNearTop) listState.animateScrollToItem(itemIndex)
    }

    // Progress fraction (0f..1f) from the LazyListState's own visible-item bookkeeping -
    // see ReaderScrollModel's doc comment for why this weighs blocks by text length
    // instead of relying on a measured scroll range the old Column + verticalScroll had
    // up front but a lazily-composed list never fully measures.
    fun currentProgress(): Float {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return 1f
        val last = info.visibleItemsInfo.last()
        return scrollModel.progress(
            first = ReaderItemEdge(first.index, first.offset, first.size),
            last = ReaderItemEdge(last.index, last.offset, last.size),
            viewportStart = info.viewportStartOffset,
            viewportEnd = info.viewportEndOffset
        )
    }

    // The resume anchor: the paragraph at (or just above) the top of the viewport,
    // identified by its own offset into the chapter's text rather than by screen
    // position at the current font size/width. readerAnchorBlock walks back from the
    // top item to the nearest prose block (a divider/callout has no offset of its
    // own). Falls back to currentProgress()'s proportional estimate only when there's
    // no prose above the top item, so this never saves nothing.
    //
    // Never reports a position the reader hasn't actually reached: while a saved
    // initialPosition is still waiting to be scrolled to, the list is still at item
    // 0, and saving that would overwrite the real saved position with the top of the
    // chapter. Until the restore lands, the position reported is the one being restored.
    fun currentAnchor(): ReaderPosition {
        if (!positionRestored && initialPosition != null) return initialPosition
        val topItemIndex = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.offset >= 0 }?.index
            ?: listState.firstVisibleItemIndex
        val anchor = readerAnchorBlock(blocks, topItemIndex - READER_LEADING_ITEMS)
        val charOffset = anchor?.rawStart
            ?: (currentProgress() * content.length).toInt().coerceIn(0, content.length)
        // completionFraction is the real scroll fraction, independent of charOffset
        // (the resume anchor, not a completion measure).
        return ReaderPosition(charOffset, ReaderPosition.snippetFrom(content, charOffset), currentProgress())
    }

    // Saves on every Activity pause, not just an explicit in-app exit tap. ON_PAUSE
    // (not ON_STOP) is the last callback guaranteed to run before the process can be
    // killed in the background, and it fires before a config change finishes tearing
    // this composition down too. onDispose also saves once more, covering plain
    // in-composition exits that don't flow through onBack/onPrevious/onNext.
    // Re-saving the same or a further-along anchor on an ordinary exit is harmless:
    // ReadingProgressDao.upsert is keyed one row per novel, so this never creates a
    // duplicate or a stale-wins race against onBack's own save.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, chapter.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                onPositionChanged(currentAnchor())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onPositionChanged(currentAnchor())
        }
    }

    // Hands the anchor to onSystemBack rather than re-invoking the platform
    // dispatcher from inside this callback: re-calling
    // onBackPressedDispatcher.onBackPressed() here would just call this same
    // BackHandler right back (it's still the topmost enabled callback until this
    // lambda returns), looping forever instead of actually leaving. onSystemBack does
    // the real navigation, same as NavController's own default callback would have,
    // just with a save first.
    BackHandler(enabled = true) {
        onSystemBack(currentAnchor())
    }

    // Rough page estimate for the progress readout: ~2000 characters per "page",
    // matching a typical screenful of body text at the default font size.
    val estimatedTotalPages = remember(content) { (content.length / 2000).coerceAtLeast(1) }

    // Light/Sepia/Dark cross-fade via animateColorAsState rather than cutting
    // backgroundColor/textColor outright on the frame readingMode.value changes - a
    // hard cut right where a reader's eyes are focused. Both colors animate through
    // the same 200ms tween so contrast never dips mid-transition; only color
    // animates, not font size or line spacing, so the chapter body never relayouts.
    val backgroundColor by animateColorAsState(
        targetValue = when (readingMode.value) {
            ReadingMode.LIGHT -> Color(0xFFFAF9F6)
            ReadingMode.SEPIA -> Color(0xFFF5ECD9)
            ReadingMode.DARK -> Color(0xFF1A1A1A)
        },
        animationSpec = tween(200),
        label = "readerBackgroundColor"
    )

    val textColor by animateColorAsState(
        targetValue = when (readingMode.value) {
            ReadingMode.LIGHT -> Color(0xFF2C2C2C)
            ReadingMode.SEPIA -> Color(0xFF3E2C1C)
            ReadingMode.DARK -> Color(0xFFF0F0F0)
        },
        animationSpec = tween(200),
        label = "readerTextColor"
    )

    // The footer (progress readout + Reader Preferences pill) is a real layout sibling
    // of the scrollable area, not an overlay: the root is a plain Column (TopAppBar,
    // then the scrollable body with weight(1f), then the footer), so the scrollable
    // area's available height is always exactly "screen height minus whatever the
    // footer currently occupies" - body text can never end up underneath the footer.
    //
    // Wrapped in a Box so TtsControlsBar can float over the content as a true
    // overlay once opened, matching uiux.md's playback-controls reference design -
    // unlike the footer, which is deliberately not an overlay.

    Box(modifier = Modifier.fillMaxSize()) {
        // The reader's chrome (top bar), the chapter body itself and the footer
        // (Reader Preferences panel/pill + progress readout) - see ReaderContent.kt.
        // All the state/effects above are passed through as-is; this call makes no
        // behavior change on its own, it is purely the god-composable split landing
        // on the "page content" half of the screen.
        ReaderPageContent(
            chapter = chapter,
            content = content,
            novelTitle = novelTitle,
            arcTitle = arcTitle,
            coverUri = coverUri,
            author = author,
            backgroundColor = backgroundColor,
            textColor = textColor,
            showControls = showControls,
            showPreferences = showPreferences,
            fullscreenEnabled = fullscreenEnabled,
            tts = tts,
            ttsFollowing = ttsFollowing,
            context = context,
            listState = listState,
            blocks = blocks,
            paragraphLayouts = paragraphLayouts,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamilyChoice = fontFamilyChoice,
            readingMode = readingMode,
            bottomPill = bottomPill,
            estimatedTotalPages = estimatedTotalPages,
            onBack = onBack,
            onBackToFiction = onBackToFiction,
            onPrevious = onPrevious,
            onNext = onNext,
            onAuthorClick = onAuthorClick,
            onOpenReaderSettings = onOpenReaderSettings,
            onRequestNotificationPermission = onRequestNotificationPermission,
            currentAnchor = ::currentAnchor,
            currentProgress = ::currentProgress
        )

        // The read-aloud controls pill + its water-ripple transition - see
        // ReaderTtsControls.kt. Positioned via BoxScope.align, so it shares this Box
        // with ReaderPageContent above rather than being nested inside it.
        ReaderTtsControlsOverlay(
            bottomPill = bottomPill,
            tts = tts,
            chapter = chapter,
            content = content,
            coverUri = coverUri,
            context = context,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onOpenTtsSettings = onOpenTtsSettings
        )
    }
}
