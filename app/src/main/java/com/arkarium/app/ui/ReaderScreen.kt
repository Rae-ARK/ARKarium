package com.arkarium.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.ChapterSegment
import com.arkarium.app.data.PreferencesManager
import com.arkarium.app.data.ReaderPosition
import com.arkarium.app.data.ReadingModeDefault
import com.arkarium.app.data.Theme
import com.arkarium.app.data.paragraphForCharOffset
import com.arkarium.app.data.paragraphOffsets
import com.arkarium.app.data.parseChapterSegments
import kotlinx.coroutines.flow.first

enum class ReadingMode {
    LIGHT, SEPIA, DARK
}

// Font family choice for the chapter body, picked on the Reader settings page (the
// font card there lists every entry below, each previewed in its own face). None of
// these bundle a font file: they're all Android's built-in system families, resolved
// by name through Typeface.create, which quietly falls back to the default family on a
// device that lacks one - so a missing face can never crash the reader, it just looks
// like Sans. DYSLEXIC doesn't bundle a dedicated dyslexia-friendly typeface (e.g.
// OpenDyslexic) - that would mean shipping and licensing a font file for a
// comparatively small typography change. Instead it uses the system sans-serif family
// with wider letter spacing, which is itself a recognized readability aid for dyslexic
// readers independent of glyph shapes (see extraLetterSpacing below). SERIF matches
// the reader's previous hardcoded default so existing users see no change unless they
// opt into something else.
//
// Stored by name (PreferencesManager.setReaderFontFamily), so never rename or reorder
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
// choice in its own face using exactly the resolution the reader itself uses.
// DYSLEXIC intentionally reuses SansSerif rather than a bundled custom font - see
// the ReaderFontFamily doc comment above.
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

// Extra tracking applied on top of the font choice. Only DYSLEXIC gets any: wider
// spacing between letters reduces crowding effects that make adjacent letters harder
// to tell apart - one of the few typography changes with reasonably consistent
// evidence behind it, unlike glyph-shape claims made by some "dyslexia fonts". 0.5.sp
// is a light touch, tuned to stay comfortable at the reader's default 18sp body size.
internal fun extraLetterSpacing(choice: ReaderFontFamily) = when (choice) {
    ReaderFontFamily.DYSLEXIC -> 0.5.sp
    else -> 0.sp
}

// Reader's own LIGHT/SEPIA/DARK palette is intentionally separate from the app-wide
// Theme (it needs a sepia option Theme doesn't have, and users may want a different
// reading background than their nav-screen background) - but it should still *start*
// aligned with the app theme rather than always opening in LIGHT regardless of what
// the user picked in Settings, which read as the app "forgetting" a Dark/Warm Paper
// preference every time a chapter was opened.
private fun readingModeFor(theme: Theme): ReadingMode = when (theme) {
    Theme.LIGHT -> ReadingMode.LIGHT
    Theme.DARK -> ReadingMode.DARK
    Theme.WARM_PAPER -> ReadingMode.SEPIA
    // MainActivity always resolves SYSTEM_DEFAULT to a concrete theme (see
    // resolveTheme()) before it ever reaches here - this branch only exists to keep
    // the `when` exhaustive over Theme's full set of entries.
    Theme.SYSTEM_DEFAULT -> ReadingMode.LIGHT
}

// Maps the persisted Settings > Reader override (data.ReadingModeDefault,
// three concrete choices, no "follow app theme" entry) onto this file's own
// ReadingMode - a one-for-one rename across the data/ui layer boundary, kept
// separate from readingModeFor above since that one's input (Theme) and
// "unset" fallback are both different.
private fun toReadingMode(mode: ReadingModeDefault): ReadingMode = when (mode) {
    ReadingModeDefault.LIGHT -> ReadingMode.LIGHT
    ReadingModeDefault.SEPIA -> ReadingMode.SEPIA
    ReadingModeDefault.DARK -> ReadingMode.DARK
}

// Compose's LocalContext.current isn't guaranteed to be the Activity itself - it can be
// a ContextWrapper (e.g. a ContextThemeWrapper) sitting in front of one - so this walks
// up to find the real Activity instead of assuming a direct cast holds. Used below
// (Stage 3.5, see docs/arkarium/foundational/design-notes.md) to reach the reader's window for
// FLAG_KEEP_SCREEN_ON; a silent no-op (window stays null, flag never set) is a safer
// failure mode here than crashing on a bad cast.
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Switches the window between "fit inside the system insets, cutout letterboxed" (the
// default, drawIntoCutout = false) and "draw edge to edge including under the display
// cutout" (true) - see the full-screen block in ReaderScreen. layoutInDisplayCutoutMode
// is API 28+; below that there is no cutout API and the default behavior is all there is.
private fun applyCutoutLayout(window: android.view.Window, drawIntoCutout: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, !drawIntoCutout)
    if (Build.VERSION.SDK_INT >= 28) {
        val params = window.attributes
        params.layoutInDisplayCutoutMode = if (drawIntoCutout)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        else
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        window.attributes = params
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    chapter: ChapterEntity,
    content: String,
    appTheme: Theme = Theme.LIGHT,
    // Everything below is new for the Stage 3 chapter-page redesign. All optional/
    // no-op by default so this stays source-compatible with any other call site.
    // novelTitle/arcTitle are plain strings (not NovelEntity/ArcEntity) so ReaderScreen
    // stays decoupled from the data layer beyond the ChapterEntity it already took.
    novelTitle: String? = null,
    arcTitle: String? = null,
    // Resolution order handled by the caller (arc cover -> fiction cover -> null, see
    // bugs.md Bug 3b): arc's own ArcEntity.coverUri if this chapter belongs to an arc
    // that has one, else the fiction's NovelEntity.coverUri, else null. Null renders
    // the same placeholder ReaderAuthorAvatar/AuthorPageScreen already use elsewhere,
    // rather than leaving a blank gap.
    coverUri: String? = null,
    // Sourced from the same author.json-backed AuthorEntity the Stage 2 AuthorPageScreen
    // reads - see AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md's Stage 3 section. Null renders no
    // "About the author" card at all, same as a fiction with no linked author today.
    author: AuthorEntity? = null,
    // Rung 14 (F9): the character-offset anchor saved the last time this chapter was
    // left, if any (null for a chapter never read before, or whose saved row predates
    // this rung - a plain PERCENTAGE row, which this screen no longer restores into).
    // The caller resolves this from ReadingProgressEntity before composing
    // ReaderScreen rather than this screen reading the DB itself, same decoupling
    // novelTitle/arcTitle/author already follow.
    initialPosition: ReaderPosition? = null,
    // All four callbacks below took (Float) -> Unit (a scroll-pixel fraction) before
    // this rung; they now take the same anchor instead, since the caller's job is
    // still just "save the exit position", just with a better position to save - see
    // ReaderPosition.kt and currentAnchor() below.
    onBack: (ReaderPosition) -> Unit,
    // Distinct from onBack: onBack is the existing top-app-bar arrow (goes to whatever
    // the caller currently sends it, e.g. Home); onBackToFiction is the new Royal
    // Road-style breadcrumb link that always means "this fiction's detail page". Kept
    // separate rather than repurposing onBack so this stage doesn't silently change the
    // existing top-bar arrow's behavior.
    onBackToFiction: (ReaderPosition) -> Unit = {},
    // Null (not just "does nothing when clicked") means "there is no previous/next
    // chapter" - the caller passes null at the first/last chapter so the buttons render
    // disabled instead of merely inert.
    onPrevious: ((ReaderPosition) -> Unit)? = null,
    onNext: ((ReaderPosition) -> Unit)? = null,
    onAuthorClick: () -> Unit = {},
    // Opens the "settings/tts" page (TtsSettingsScreen) from the gear icon in
    // TtsControlsBar below. No-op by default so this stays source-compatible with any
    // other call site, same rationale as onAuthorClick's own default.
    onOpenTtsSettings: () -> Unit = {},
    onOpenReaderSettings: () -> Unit = {}
) {
    // reader-settings.md's "cheap" batch: these four used to be hardcoded/
    // in-session-only `remember` state with no persistence at all. They're
    // still exactly that kind of state here - the pill adjusts them live for
    // this session and never writes back to Settings - but each one's
    // *starting* value is now seeded once from the Settings > Reader default
    // below (fontSize/lineHeight/fontFamilyChoice), or from the stored
    // override if one was set (readingMode), instead of a literal constant.
    val fontSize = remember { mutableFloatStateOf(18f) }
    val lineHeight = remember { mutableFloatStateOf(1.8f) }
    // Font family is no longer adjustable from the pill at all (moved to
    // Settings > Reader, see reader-settings.md's Font family section) - this
    // is read straight from the persisted default below and never changes
    // for the life of this composition.
    val fontFamilyChoice = remember { mutableStateOf(ReaderFontFamily.SERIF) }
    val readingMode = remember { mutableStateOf(readingModeFor(appTheme)) }
    // Read-aloud. See Tts.kt - one engine instance persists for the whole reading
    // session (including across Previous/Next), same reuse behavior as the state above.
    val tts = rememberChapterTts()
    // Stage 3.4 (see docs/arkarium/foundational/design-notes.md): reassigned every recomposition
    // so the closure always captures whichever onNext this screen currently has (it
    // changes chapter-to-chapter, and is null at the last chapter) - cheap and
    // idempotent, so no LaunchedEffect/key needed. Passes an anchor at the very end of
    // the text rather than currentAnchor(): auto-continue fires because the reader
    // finished the chapter by ear, not by eye, so scroll position isn't a meaningful
    // signal of how much of the chapter was actually heard - same "chapter complete"
    // position a manual Next tap would leave behind if the reader had scrolled to the
    // bottom first. tts itself gates this on autoContinueEnabled (off by default), so
    // onChapterFinished being set here is not itself a behavior change until a reader
    // opts in on settings/tts.
    tts.onChapterFinished = { onNext?.invoke(ReaderPosition(content.length, "")) }
    // Stage 3.5 (see docs/arkarium/foundational/design-notes.md): keeps the screen on for exactly
    // the duration of active read-aloud, gated by tts_keep_screen_on (off by default,
    // same reasoning as auto-continue above). Direct PreferencesManager access, same
    // pattern as everywhere else these four TTS keys are read - see
    // docs/arkarium/foundational/design-notes.md's "Settled decisions" for why TTS settings don't get a
    // ViewModel of their own. TTS is specifically a screen-off-friendly use case
    // (listening, not reading) that the rest of the reader isn't, so this ties the flag
    // to tts.isSpeaking rather than to the reader screen merely being open.
    val context = LocalContext.current
    // Design-notes.md's "A failed chunk stops the session": a chunk that fails now stops the session
    // and reports why via ChapterTtsState.lastErrorMessage, instead of the chapter
    // silently "completing" with no sound. Shown once as a Toast - ReaderScreen has
    // no Snackbar host today, and adding one is out of this ladder's scope - and
    // cleared right after so it doesn't reappear on the next recomposition.
    LaunchedEffect(tts.lastErrorMessage) {
        tts.lastErrorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            tts.clearError()
        }
    }
    val prefsManager = remember { PreferencesManager(context) }
    val keepScreenOnEnabled by prefsManager.ttsKeepScreenOn.collectAsState(initial = false)
    // reader-settings.md's Screen on section: a second, reader-level
    // keep-screen-on, independent of the TTS one above. The two combine
    // ("reader option on, or read-aloud speaking with its own option on")
    // rather than one clearing the other's flag - either condition alone is
    // enough to keep the flag set, and the flag only clears once *both* are
    // false. Plain FLAG_KEEP_SCREEN_ON, not the idle-timer variant the doc
    // flagged as the better-but-bigger option for someone who falls asleep
    // reading - the owner picked the plain flag for this pass.
    val readerKeepScreenOnEnabled by prefsManager.readerKeepScreenOn.collectAsState(initial = false)
    DisposableEffect(keepScreenOnEnabled, readerKeepScreenOnEnabled, tts.isSpeaking) {
        val window = context.findActivity()?.window
        if (readerKeepScreenOnEnabled || (keepScreenOnEnabled && tts.isSpeaking)) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Runs both when any key above changes again (read-aloud stops, either
        // setting is toggled) and on final disposal (leaving ReaderScreen) - so the
        // flag never outlives both conditions going false, or the reader screen
        // itself, with no separate cleanup path needed for "still speaking when the
        // reader is closed."
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // reader-settings.md's Full-screen mode section. Its DisposableEffect
    // needs showControls, which isn't declared until below (Kotlin requires
    // declaration-before-use in this linear composable body) - see that
    // val's own doc comment for where the effect actually lives.
    val fullscreenEnabled by prefsManager.readerFullscreen.collectAsState(initial = false)

    // reader-settings.md's Brightness section, as amended by the owner: a
    // window-level screenBrightness override for the reader's lifetime, restoring
    // "follow the system" (-1f, BRIGHTNESS_OVERRIDE_NONE) on dispose. Both the
    // opt-in and the level are set on the Reader settings page now (the in-reader
    // pill panel that used to hold a session-only slider is gone), so this just
    // applies whatever's stored while the reader is open and clears it on exit.
    val brightnessOverrideEnabled by prefsManager.readerBrightnessSliderEnabled.collectAsState(initial = false)
    val brightnessLevel by prefsManager.readerBrightness.collectAsState(initial = -1f)
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

    // Seeds fontSize/lineHeight/fontFamilyChoice/readingMode from the
    // Settings > Reader defaults exactly once per composition (per chapter,
    // since Previous/Next swap the whole route the same way chapter.id
    // already keys other once-per-chapter effects above) - after that the
    // pill is free to adjust them live for the rest of the session without
    // this effect fighting it on every recomposition. A never-visited
    // Settings page reads back PreferencesManager's own defaults (18f, 1.8f,
    // SERIF, null), which match the literal constants fontSize/lineHeight/
    // fontFamilyChoice/readingMode already initialized with above, so a
    // fresh install sees no behavior change versus before this existed.
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
    }
    // The tap-anywhere immersive toggle: true = chrome showing (top bar, Reader
    // Preferences pill, progress readout), false = immersive. rememberSaveable rather
    // than remember because navigating away (e.g. to settings/tts from the read-aloud
    // pill) takes this screen out of composition, and plain `remember` state does not
    // survive that - the reader used to come back out of immersive mode every time.
    val showControls = rememberSaveable { mutableStateOf(true) }

    // Reader Preferences pill and Reader settings page are two separate surfaces, not
    // one: this is the pill's own open/closed state for its in-reader panel (Text
    // size/Spacing/Mode/read-aloud speed - the ones a reader adjusts live, in-session,
    // per reader-settings.md). It never writes to Settings > Reader and Settings >
    // Reader never writes to it; the two meet only at the *default* fontSize/lineHeight/
    // readingMode seed above, same "default vs this session's live override" split as
    // before. Font family, brightness and the always-on toggles stay Settings-only (see
    // ReaderSettingsScreen) so no option is ever shown in both places.
    val showPreferences = remember { mutableStateOf(false) }

    // reader-settings.md's Full-screen mode section: hides the system status
    // and navigation bars (not just ARKarium's own chrome) while the reader
    // is in immersive mode (showControls false) and this opt-in is on;
    // restores them on every exit path - Back, Previous/Next, navigating to
    // settings/tts mid-read-aloud, and leaving the reader entirely - since
    // all of those tear down this composition the same way, which is what
    // onDispose below runs for. transient-bars-by-swipe (BEHAVIOR_SHOW_
    // TRANSIENT_BARS_BY_SWIPE) lets a reader still pull the bars back
    // temporarily with an edge swipe instead of being fully locked out.
    //
    // Display cutout (notch / camera hole): in landscape the cutout sits on a side
    // edge, and by default Android letterboxes it - the strip beside the cutout is
    // painted as a black bar and the app's content is fitted inside the rest, so
    // "full screen" wasn't. While full-screen + immersive + landscape, the window now
    // opts in to drawing into the cutout area (SHORT_EDGES) and stops fitting itself
    // inside the system insets, so the page background fills every pixel; the root
    // column below then re-pads *only* by the cutout's own insets, so text still never
    // sits under the notch itself. In portrait or reverse portrait none of this
    // applies: the cutout is at the top, the status bar is hidden anyway in this mode,
    // and nothing gains from drawing up there. Everything is put back (cutout mode
    // DEFAULT, decor fitting on) on every exit path, same as the bars.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(fullscreenEnabled, showControls.value, isLandscape) {
        val window = context.findActivity()?.window
        val view = window?.decorView
        val controller = if (window != null && view != null) {
            WindowCompat.getInsetsController(window, view)
        } else null
        val immersiveFullscreen = fullscreenEnabled && !showControls.value
        val drawIntoCutout = immersiveFullscreen && isLandscape
        if (immersiveFullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        if (window != null) {
            applyCutoutLayout(window, drawIntoCutout)
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                applyCutoutLayout(window, false)
            }
        }
    }

    // The explicit, always-visible "Reader Preferences" pill (Royal Road style) keeps
    // the settings discoverable without depending on the tap gesture; it now opens the
    // Reader settings page instead of a duplicate in-reader panel.
    // A read-aloud session exists (playing or paused) - mirrored from the service via
    // `tts`, so it is also true when this composition has only just re-attached to a
    // session that kept running while the reader was away.
    val ttsSessionActive = tts.isSpeaking || tts.isPaused
    // Which pill owns the bottom edge: Reader Preferences when chrome is showing, the
    // read-aloud controls in immersive mode while a session exists, otherwise nothing.
    // The two are one slot that swaps (with a water ripple), never shown together. See
    // ReaderTtsLogic.kt.
    val bottomPill = readerBottomPill(
        immersive = !showControls.value,
        ttsSessionActive = ttsSessionActive,
        ttsAvailable = tts.isAvailable
    )
    val scrollState = rememberScrollState()

    // ReaderScreen's call site in MainActivity doesn't change when Previous/Next swaps
    // to a new chapter (it's still the same `is Screen.Reader ->` branch, just
    // re-invoked with a new Screen.Reader instance) - so `remember`-backed state above,
    // including scrollState, survives across chapters rather than being torn down and
    // recreated. That's the right behavior for fontSize/lineHeight/readingMode (a
    // reading preference shouldn't reset every chapter), but scroll position is
    // per-chapter: without this, landing on a new chapter kept whatever scroll offset
    // the previous chapter was left at instead of opening at the top. Reset explicitly
    // whenever the chapter actually changes.
    LaunchedEffect(chapter.id) {
        scrollState.scrollTo(0)
    }

    // Rung 14 (F9) - the paragraph offsets for this chapter's own text, and the pixel
    // position each paragraph's Text composable actually lands at once laid out
    // (scrollContainerTop + paragraphContentTop below), used both to capture an exit
    // position (currentAnchor()) and to restore initialPosition once layout catches up.
    val paragraphOffsetList = remember(content) { paragraphOffsets(content) }
    // Key: a paragraph's ParagraphOffset.rawStart. Value: that paragraph's scroll
    // offset - the value scrollState.value would need to be for this paragraph to sit
    // flush with the top of the scrollable area - computed from its onGloballyPositioned
    // position in root plus the scroll amount at the time, minus the scrollable
    // container's own (scroll-invariant) position in root. Cleared per chapter so a
    // stale entry from the previous chapter's paragraphs (different rawStarts, but
    // best not to rely on that alone) can't linger.
    val paragraphContentTop = remember(chapter.id) { mutableStateMapOf<Int, Float>() }
    var scrollContainerTop by remember(chapter.id) { mutableFloatStateOf(0f) }
    var positionRestored by remember(chapter.id) { mutableStateOf(initialPosition == null) }

    // Restores initialPosition once enough paragraphs have reported a position to
    // resolve it - re-runs as paragraphContentTop fills in (each onGloballyPositioned
    // callback below touches it), stopping the first time the target paragraph's
    // offset is actually known. A paragraph past the very first screenful may not have
    // laid out yet on a long chapter, so this can take a few recompositions rather than
    // firing once; it gives up and leaves the reader at the top if initialPosition's
    // paragraph never shows up at all (e.g. the chapter was edited shorter since).
    LaunchedEffect(chapter.id, paragraphContentTop.size) {
        if (positionRestored) return@LaunchedEffect
        val target = initialPosition ?: run { positionRestored = true; return@LaunchedEffect }
        val paragraph = paragraphForCharOffset(paragraphOffsetList, target.charOffset)
            ?: run { positionRestored = true; return@LaunchedEffect }
        val offset = paragraphContentTop[paragraph.rawStart] ?: return@LaunchedEffect
        scrollState.scrollTo(offset.toInt().coerceIn(0, scrollState.maxValue))
        positionRestored = true
    }

    // Reconciles this composition with a read-aloud session that may have outlived the
    // previous one. The session lives in TtsPlaybackService, not here, so it keeps
    // playing while the reader is covered by another screen (the TTS settings page
    // above all), across a rotation, or after backing out and reopening the chapter -
    // but everything on this screen starts from scratch each time, and `tts` only learns
    // about the session once the service bind completes (isAvailable going true).
    //  - Session is reading this chapter: return to immersive mode so its controls pill
    //    is showing again, and leave playback alone.
    //  - Session is reading a different chapter (Previous/Next, or another chapter
    //    opened meanwhile): stop it. This is what the old unconditional tts.stop() on
    //    every new chapter was for; it ran before the bind finished, so whether it hit
    //    the live session was down to timing. Keyed to the session now, not the timing.
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

    fun currentProgress(): Float {
        val max = scrollState.maxValue
        return if (max <= 0) 1f else (scrollState.value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    // Rung 14 (F9) - what onBack/onBackToFiction/onPrevious/onNext now save instead of
    // currentProgress()'s plain fraction: the paragraph currently at (or just above)
    // the top of the viewport, identified by its own offset into the chapter's text
    // rather than by where that happens to land on screen at the current font size and
    // width. Picks the entry in paragraphContentTop with the largest recorded offset
    // that's still <= the current scroll position - the paragraph the reader has
    // scrolled down to, not past. Falls back to currentProgress()'s proportional
    // estimate only if no paragraph has reported a position yet at all (leaving before
    // the first layout pass completes - e.g. backing out instantly), so this never
    // saves nothing.
    fun currentAnchor(): ReaderPosition {
        val charOffset = paragraphContentTop.entries
            .filter { it.value <= scrollState.value + 1f }
            .maxByOrNull { it.value }
            ?.key
            ?: (currentProgress() * content.length).toInt().coerceIn(0, content.length)
        return ReaderPosition(charOffset, ReaderPosition.snippetFrom(content, charOffset))
    }

    // Rough page estimate for the progress readout: ~2000 characters per "page",
    // matching a typical screenful of body text at the default font size.
    val estimatedTotalPages = remember(content) { (content.length / 2000).coerceAtLeast(1) }

    val backgroundColor = when (readingMode.value) {
        ReadingMode.LIGHT -> Color(0xFFFAF9F6)
        ReadingMode.SEPIA -> Color(0xFFF5ECD9)
        ReadingMode.DARK -> Color(0xFF1A1A1A)
    }

    val textColor = when (readingMode.value) {
        ReadingMode.LIGHT -> Color(0xFF2C2C2C)
        ReadingMode.SEPIA -> Color(0xFF3E2C1C)
        ReadingMode.DARK -> Color(0xFFF0F0F0)
    }

    // The footer (progress readout + Reader Preferences pill) used to be a bare overlay
    // Box on top of the scrollable content, positioned with manual bottom-padding offsets
    // and z-order. Because verticalScroll content passes freely underneath a BottomCenter
    // overlay, the footer ended up floating directly on top of whatever paragraph
    // happened to be scrolled to the bottom of the viewport at the time - not anchored to
    // a genuinely empty strip at the screen edge the way a real footer bar is. A one-time
    // spacer after the chapter body only helped once scrolled all the way to the very
    // end; it did nothing mid-chapter.
    //
    // Fixed below by making the footer a real layout sibling of the scrollable area
    // instead of an overlay: the root is now a plain Column (TopAppBar, then the
    // scrollable body with weight(1f), then the footer), so the scrollable area's
    // available height is always exactly "screen height minus whatever the footer
    // currently occupies" - body text can never end up underneath the footer, in the
    // middle of a chapter or anywhere else, and the readout genuinely sits flush against
    // the bottom edge with the pill stacked directly above it.
    // Wrapped in a Box (previously just the Column below) so TtsControlsBar can float
    // over the content as a true overlay once opened - unlike the footer above, which
    // deliberately moved *off* being an overlay (see the comment above that Column),
    // the controls bar is meant to sit on top of whatever's currently on screen,
    // immersive or not, matching the reference design in uiux.md's playback-controls
    // entry. The Column itself is left exactly as it was rather than reindented, to
    // keep this diff to the wrap plus the one addition after it closes.
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            // Pads by the cutout's own horizontal insets only - zero unless the
            // full-screen block above let the window draw into the cutout, in which
            // case the background (set just above) already covers it and this keeps
            // the text and chrome clear of the notch itself.
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
    ) {
        // Top bar
        if (showControls.value) {
            TopAppBar(
                title = { Text(chapter.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { onBack(currentAnchor()) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // Only shown once the engine's finished initializing successfully - see
                // ChapterTtsState.isAvailable's doc comment. Pressing it starts reading
                // aloud and drops into immersive mode, where the read-aloud controls
                // pill (TtsControlsBar) takes over the bottom edge from the Reader
                // Preferences pill. If a session already exists it isn't restarted from
                // the top: a paused one resumes, a playing one is left alone, and either
                // way the reader just goes back to its controls. The top bar only exists
                // outside immersive mode, so this is the only way to *start* read-aloud;
                // the pill stops, pauses and skips it from there.
                actions = {
                    if (tts.isAvailable) {
                        IconButton(onClick = {
                            when {
                                tts.isPaused -> tts.resume()
                                tts.isSpeaking -> Unit
                                else -> tts.speak(context, chapter.title, content, chapter.id)
                            }
                            showControls.value = false
                        }) {
                            Icon(
                                Icons.Filled.VolumeUp,
                                contentDescription = "Read aloud"
                            )
                        }
                    }
                },
                modifier = Modifier.background(backgroundColor)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Rung 14 (F9) - this Column's own position in root is the fixed
                // baseline paragraphContentTop below is measured against; unlike its
                // children, it doesn't move as scrollState changes, so this only ever
                // needs (re)capturing when the layout around it changes (e.g. the top
                // bar showing/hiding), not per scroll tick.
                .onGloballyPositioned { scrollContainerTop = it.positionInRoot().y }
                .verticalScroll(scrollState)
                // `showControls` already existed and correctly gated both the top bar and
                // the bottom Text/Spacing/Mode panel below - but nothing ever toggled it, so
                // it was permanently stuck at its initial `true` and the controls could never
                // actually be hidden. Tapping the reading area is the standard e-reader
                // gesture for entering/leaving immersive mode, so wire it up here.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showControls.value = !showControls.value }
        ) {
            // Fiction title / "back to fiction" breadcrumb + arc name, matching Royal
            // Road's chapter-page top block. Only rendered when the caller has a title to
            // show - existing callers that don't pass novelTitle see no change at all.
            if (novelTitle != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    ReaderCoverThumbnail(coverUri = coverUri, modifier = Modifier.size(width = 40.dp, height = 56.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Row(
                            // The row's only visible text is the fiction's title, which on
                            // its own doesn't say that activating it goes back to the fiction.
                            modifier = Modifier.clickable(onClickLabel = "Back to fiction") {
                                onBackToFiction(currentAnchor())
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                novelTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        if (!arcTitle.isNullOrBlank()) {
                            Text(
                                arcTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Chapter title
            Text(
                chapter.title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                textAlign = TextAlign.Center,
                color = textColor
            )

            // Previous/Next above the chapter body, mirroring the row below it - Royal
            // Road shows this pair on both ends of the chapter.
            ChapterNavRow(
                hasPrevious = onPrevious != null,
                hasNext = onNext != null,
                textColor = textColor,
                onPrevious = { onPrevious?.invoke(currentAnchor()) },
                onNext = { onNext?.invoke(currentAnchor()) }
            )

            // Chapter content with enhanced typography. Parsed into segments so inline
            // <chapter break>/<author>/<news> tags (see data/ChapterMarkup.kt) render as
            // their own centered divider / "A Note from the Author" / "News" callouts
            // instead of printing the raw tag text. A chapter with none of these tags
            // parses to a single Paragraph segment covering the whole body, so untagged
            // chapters render exactly as before.
            val segments = remember(content) { parseChapterSegments(content) }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                color = backgroundColor,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Rung 14 (F9) - walked in lockstep with paragraphOffsetList, which
                    // was built by the exact same tag-matching loop over the same
                    // `content` (see paragraphOffsets' doc comment), so the Nth
                    // Paragraph segment here is always paragraphOffsetList[nth]. Only
                    // advanced inside the Paragraph branch below, in the same place
                    // and under the same isNotBlank() condition as flushPlain()/this
                    // loop's own filter - a blank Paragraph still consumes an entry
                    // (paragraphOffsets kept it too) but never renders a Text to attach
                    // onGloballyPositioned to, so it's skipped without recording a
                    // position for it.
                    var paragraphCursor = 0
                    segments.forEach { segment ->
                        when (segment) {
                            is ChapterSegment.Paragraph -> {
                                val rawStart = paragraphOffsetList.getOrNull(paragraphCursor)?.rawStart
                                paragraphCursor++
                                if (segment.text.isNotBlank()) {
                                    Text(
                                        text = segment.text,
                                        style = TextStyle(
                                            fontSize = fontSize.value.sp,
                                            lineHeight = (fontSize.value * lineHeight.value).sp,
                                            color = textColor,
                                            fontFamily = resolveFontFamily(fontFamilyChoice.value),
                                            letterSpacing = extraLetterSpacing(fontFamilyChoice.value),
                                            textAlign = TextAlign.Justify
                                        ),
                                        modifier = if (rawStart != null) {
                                            Modifier.onGloballyPositioned { coordinates ->
                                                paragraphContentTop[rawStart] = coordinates.positionInRoot().y +
                                                    scrollState.value - scrollContainerTop
                                            }
                                        } else Modifier
                                    )
                                }
                            }
                            is ChapterSegment.ChapterBreak -> ChapterBreakDivider(
                                label = segment.label,
                                textColor = textColor
                            )
                            is ChapterSegment.AuthorNote -> AuthorNoteCallout(
                                author = author,
                                body = segment.text,
                                textColor = textColor
                            )
                            is ChapterSegment.News -> ChapterCalloutCard(
                                icon = "📰",
                                heading = "News",
                                body = segment.text,
                                textColor = textColor
                            )
                        }
                    }
                }
            }

            ChapterNavRow(
                hasPrevious = onPrevious != null,
                hasNext = onNext != null,
                textColor = textColor,
                onPrevious = { onPrevious?.invoke(currentAnchor()) },
                onNext = { onNext?.invoke(currentAnchor()) },
                // Only offered where there's actually somewhere to send the reader -
                // the top copy of this row (above the chapter body) is left as a plain
                // disabled button at the end of a fiction, since "About the Author" as
                // the very first thing on the page would read oddly before they've even
                // reached the content.
                onAuthorClick = author?.let { { onAuthorClick() } }
            )

            // "About the author" card - only rendered when this fiction actually has a
            // linked author (see NovelEntity.authorId / AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md).
            // No author.json means no card, same as today's "no metadata.json" behavior
            // elsewhere in the scanner.
            if (author != null) {
                AboutAuthorCard(
                    author = author,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    onClick = onAuthorClick
                )
            }

            // Small trailing gap so the last line of the chapter doesn't sit flush
            // against the footer below it.
            Box(modifier = Modifier.padding(bottom = 24.dp))
        }

        // Footer: progress readout + Reader Preferences pill/panel. A real Column
        // sibling of the scrollable body above (not a floating overlay - see the
        // comment where this screen's root Column was introduced), so it always sits
        // in genuinely empty space at the bottom of the screen: the readout flush
        // against the bottom edge, with the pill stacked
        // directly above it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                // Smooths the footer's height change when the readout appears/
                // disappears with showControls, instead of jumping instantly. Since
                // this Column is a real layout sibling of the weight(1f) scroll area
                // above rather than an overlay, the scroll area's available height
                // animates in step with it automatically - no separate offset math
                // needed to keep the two in sync, unlike the old overlay approach.
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // The pill's own panel: Text size/Spacing/read-aloud Speed/Mode, adjusted
            // live for this session only (see showPreferences's doc comment above) and
            // never written back to Settings > Reader. Also requires showControls (if a
            // reader had this open and then went immersive, it used to stay floating
            // over the text with no pill left to close it with - the pill itself is
            // gated on showControls too, just below). Leaving showPreferences itself
            // untouched means the panel reopens exactly as left once the reader leaves
            // immersive mode again, rather than forgetting it was open.
            if (showPreferences.value && showControls.value) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(backgroundColor)
                        .padding(16.dp)
                ) {
                    // Font size control. A slider, matching Spacing/Speed below rather
                    // than a +/- stepper - a stepper reads oddly once you know the value
                    // is bounded (12..28) rather than open-ended, and a slider shows the
                    // bound instead of hiding it.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Text:", style = MaterialTheme.typography.labelSmall, color = textColor)
                        Slider(
                            value = fontSize.floatValue,
                            onValueChange = { fontSize.floatValue = it },
                            valueRange = 12f..28f,
                            steps = 7, // 12,14,...,28 - 9 stops, 2pt increments
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            "${fontSize.floatValue.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // Line height control
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Spacing:", style = MaterialTheme.typography.labelSmall, color = textColor)
                        Slider(
                            value = lineHeight.floatValue,
                            onValueChange = { lineHeight.floatValue = it },
                            valueRange = 1.0f..2.5f,
                            steps = 10,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            "${String.format("%.1f", lineHeight.floatValue)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // Read-aloud speed control. Only shown once the engine's available -
                    // same gating as the TopAppBar play/stop button, since a speed
                    // slider for a feature the device can't run would be a dead control.
                    // Applies immediately via ChapterTtsState.setRate even mid-read.
                    if (tts.isAvailable) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Speed:", style = MaterialTheme.typography.labelSmall, color = textColor)
                            Slider(
                                value = tts.speechRate,
                                onValueChange = { tts.setRate(it) },
                                valueRange = 0.5f..2.5f,
                                steps = 7,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )
                            Text(
                                "${String.format("%.1f", tts.speechRate)}x",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Font family and brightness are deliberately NOT here - they're
                    // "set and forget" preferences that live only on the Reader settings
                    // page (see ReaderSettingsScreen), so the same option is never shown
                    // in both places. This panel only holds the ones a reader actually
                    // adjusts mid-session.

                    // Reading mode toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Mode:", style = MaterialTheme.typography.labelSmall, color = textColor)
                        ReadingMode.values().forEach { mode ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(2.dp)
                                    .clickable { readingMode.value = mode },
                                color = if (readingMode.value == mode)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    when (mode) {
                                        ReadingMode.LIGHT -> Icon(
                                            Icons.Default.Brightness7,
                                            contentDescription = "Light mode",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (readingMode.value == mode)
                                                MaterialTheme.colorScheme.onPrimary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        ReadingMode.SEPIA -> Text(
                                            "📖",
                                            fontSize = 12.sp
                                        )
                                        ReadingMode.DARK -> Icon(
                                            Icons.Default.Brightness4,
                                            contentDescription = "Dark mode",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (readingMode.value == mode)
                                                MaterialTheme.colorScheme.onPrimary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The pill itself - Royal Road-style rounded button, centered. The main tap
            // target toggles the panel above (quick, in-session, live) - it's a single
            // persistent affordance, not a one-shot navigation. The small gear beside it
            // is the only way from here into the Reader settings page (font family,
            // brightness, keep-screen-on, fullscreen - the "set and forget" ones), so the
            // two stay separate surfaces with no option shown on both.
            //
            // Non-immersive only (bottomPill == PREFERENCES): font/spacing/mode aren't
            // needed while immersed. In immersive mode this slot belongs to the
            // read-aloud controls pill instead (TtsControlsBar, below) - the two swap
            // places with a water ripple (see WaterRipple.kt) rather than showing
            // together.
            RippleReveal(
                visible = bottomPill == ReaderBottomPill.PREFERENCES,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.clickable { showPreferences.value = !showPreferences.value },
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(50),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Aa", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                            Text(
                                "Reader Preferences",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            IconButton(
                                onClick = onOpenReaderSettings,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "Reader settings",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Reading progress readout ("0% • page 1 of x") - only relevant in
            // non-immersive mode, so it's simply not composed in immersive mode. The
            // last child of this Column, so it's the one that ends up flush against
            // the bottom edge of the screen, with the pill floating directly above it.
            if (showControls.value) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val progressPercent = (currentProgress() * 100).toInt()
                    val currentPage = (currentProgress() * estimatedTotalPages).toInt().coerceIn(1, estimatedTotalPages)
                    Text(
                        "$progressPercent% • page $currentPage of $estimatedTotalPages",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    // The read-aloud controls pill (rungs 1-2 of docs/arkarium/implementation/
    // tts-playback-controls.md). Immersive mode only, and only while a session exists
    // (playing or paused) - bottomPill == TTS says exactly that, see ReaderTtsLogic.kt.
    // Outside immersive mode the Reader Preferences pill above holds this spot, and
    // playback simply carries on without a visible control; entering immersive mode
    // brings the pill back. Also gated on tts.isAvailable inside bottomPill - no engine,
    // nothing to control.
    RippleReveal(
        visible = bottomPill == ReaderBottomPill.TTS,
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        TtsControlsBar(
            isSpeaking = tts.isSpeaking,
            isPaused = tts.isPaused,
            onPlayPause = {
                when {
                    tts.isPaused -> tts.resume()
                    tts.isSpeaking -> tts.pause()
                    else -> tts.speak(context, chapter.title, content, chapter.id)
                }
            },
            onSkipPrevious = { tts.skipPrevious() },
            onSkipNext = { tts.skipNext() },
            onSettingsClick = onOpenTtsSettings,
            // Ends the session; with no session left, bottomPill goes to NONE and the
            // pill leaves on its own - nothing else to hide here.
            onClose = { tts.stop() }
        )
    }

    // Rings spreading from the pill whenever the bottom edge swaps between Reader
    // Preferences and read-aloud controls. Not on first composition, and not when the
    // swap is to NONE (nothing arrives to ripple around).
    WaterRippleWave(
        trigger = bottomPill,
        enabled = bottomPill != ReaderBottomPill.NONE,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(240.dp)
    )
    }
}

// The read-aloud controls bar: stop the session, open TTS settings, skip to the
// previous/next chunk, and play/pause. Shown by ReaderScreen only in immersive mode
// while a session exists (see readerBottomPill); the speaker icon in the top bar is what
// starts one. Laid out to match
// the reference screenshot in uiux.md's playback-controls entry (close/settings on
// one side, skip/play/skip on the other) minus its "n of total" scrub readout -
// TtsProgressStub below is where that's meant to slot in later, not omitted outright.
@Composable
private fun TtsControlsBar(
    isSpeaking: Boolean,
    isPaused: Boolean,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSettingsClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(50),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Stops/ends the session outright (ChapterTtsState.stop(), which tears
                // down the service's queue) and hides this bar - distinct from
                // play/pause, which leaves the session intact.
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Stop reading aloud",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                // Opens settings/tts (TtsSettingsScreen) via onOpenTtsSettings -
                // ReaderScreen's own onOpenTtsSettings parameter, wired by MainActivity.
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Read-aloud settings",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            TtsProgressStub()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onSkipPrevious) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous chunk",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                // The one control in this bar that isn't an IconButton - sized and
                // filled to stand out as the primary action, same "biggest circle in
                // the row" treatment the reference screenshot gives it.
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onPlayPause),
                    color = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isSpeaking && !isPaused) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isSpeaking && !isPaused) "Pause reading aloud" else "Play reading aloud",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onSkipNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next chunk",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

// Stand-in for the "n of total" scrub/progress readout in the uiux.md reference
// design - a deliberate no-op for now, not an oversight. A chunk isn't a stable unit
// to show a position in (chunk count depends on TTS_MAX_CHUNK_CHARS in
// TtsPlaybackService, not on anything a reader would recognize as a "page"); rung 3
// of docs/arkarium/implementation/tts-playback-controls.md is where this gets a real
// value. Left as an explicit call site rather than omitted outright so wiring it in
// later is a one-line change here, not a rediscovery of where it goes.
@Composable
private fun TtsProgressStub() {}

// Previous/Next row shown both above and below the chapter body (see Stage 3 in
// AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md). A missing neighbor renders its button disabled
// rather than hiding it, so the layout doesn't jump between the top and bottom copies
// of this row depending on which end of the chapter list you're at.
//
// Royal Road-style exception: at the very end of a fiction (no next chapter), the
// "Next Chapter" slot has nothing left to do, so - when there's a linked author to
// send the reader to - it becomes an "About the Author ›" button instead of just
// sitting there disabled. hasNext still wins whenever there IS a next chapter, and a
// fiction with no linked author (onAuthorClick == null) keeps today's disabled-button
// behavior unchanged.
@Composable
private fun ChapterNavRow(
    hasPrevious: Boolean,
    hasNext: Boolean,
    textColor: Color,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAuthorClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onPrevious, enabled = hasPrevious) {
            Text("‹ Previous Chapter")
        }
        if (!hasNext && onAuthorClick != null) {
            OutlinedButton(onClick = onAuthorClick) {
                Text("About the Author ›")
            }
        } else {
            OutlinedButton(onClick = onNext, enabled = hasNext) {
                Text("Next Chapter ›")
            }
        }
    }
}

// Truncation point for the bio excerpt shown here, vs the full bio the Stage 2
// AuthorPageScreen shows - matches the doc's "shown both truncated (chapter card) and
// in full (author page)" note on author.json's bio field.
private const val BIO_EXCERPT_LENGTH = 160

// Centered scene/chapter separator for a <chapter break> tag. Empty tags
// ("<chapter break></chapter break>", the common case - most chapter breaks don't
// carry their own label) fall back to a plain glyph divider; text between the tags is
// shown centered instead of the surrounding body's justified alignment.
@Composable
private fun ChapterBreakDivider(label: String, textColor: Color) {
    Text(
        text = label.ifBlank { "• • •" },
        style = MaterialTheme.typography.titleMedium,
        color = textColor.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
    )
}

// <author> callout: shows the fiction's linked author - same ReaderAuthorAvatar used
// by AboutAuthorCard below, plus their name - so a mid-chapter note reads as coming
// from a person, not a generic banner. Underneath that, indented like a tab under the
// name/avatar row, the "🖋️ A Note from the Author." line, then the note's own text
// inside the box. Falls back to a placeholder avatar and "The Author" when this
// fiction has no linked AuthorEntity (same "no author.json, no identity" behavior
// AboutAuthorCard already has) rather than failing to render the note at all.
@Composable
private fun AuthorNoteCallout(author: AuthorEntity?, body: String, textColor: Color) {
    if (body.isBlank()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReaderAuthorAvatar(avatarUrl = author?.avatarUri, modifier = Modifier.size(28.dp))
                Text(
                    author?.name ?: "The Author",
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                "🖋️ A Note from the Author.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp)
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// <news> callout - same card shape as AuthorNoteCallout above, but generic (no author
// identity attached): icon + heading, same emoji-glyph convention
// ReaderAuthorAvatar/ReaderCoverThumbnail already use elsewhere on this screen rather
// than pulling in new icon assets. A blank body (e.g. "<news></news>" with nothing
// inside) renders nothing rather than an empty card.
@Composable
private fun ChapterCalloutCard(icon: String, heading: String, body: String, textColor: Color) {
    if (body.isBlank()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
                Text(
                    heading,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun AboutAuthorCard(
    author: AuthorEntity,
    backgroundColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderAuthorAvatar(avatarUrl = author.avatarUri, modifier = Modifier.size(48.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    "About the Author",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f)
                )
                Text(
                    author.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (!author.bio.isNullOrBlank()) {
                    Text(
                        author.bio.take(BIO_EXCERPT_LENGTH).let {
                            if (author.bio.length > BIO_EXCERPT_LENGTH) "$it…" else it
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderCoverThumbnail(coverUri: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Same missing-image placeholder pattern as ReaderAuthorAvatar / the rest of
            // the app - see bugs.md Bug 3b. The caller (MainActivity) is responsible for
            // resolving arc cover -> fiction cover -> null before this is reached, so a
            // null here genuinely means neither exists, not that resolution was skipped.
            Text("📕", fontSize = 20.sp)
        }
    }
}

@Composable
private fun ReaderAuthorAvatar(avatarUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Same emoji-placeholder pattern as AuthorPageScreen's AuthorAvatar / other
            // missing-image fallbacks across the app.
            Text("🖋️", fontSize = 18.sp)
        }
    }
}
