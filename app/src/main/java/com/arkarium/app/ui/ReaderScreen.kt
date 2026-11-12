package com.arkarium.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import com.arkarium.app.util.WordLookup
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
// (Stage 3.5, see docs/foundational/design-notes.md) to reach the reader's window for
// FLAG_KEEP_SCREEN_ON; a silent no-op (window stays null, flag never set) is a safer
// failure mode here than crashing on a bad cast.
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Full-screen-in-portrait bug (reported: landscape looked right, portrait
// didn't; fixed here - see architecture.md's "Reading and local state",
// `reader_fullscreen` bullet, for the lesson this taught, per
// docs/proposal/bugs/README.md's "fixed bugs are deleted, the
// lesson moves to foundational/" rule). This used to take two *independent*
// switches, `edgeToEdge` and a landscape-only `drawIntoCutout`, on the theory
// that the cutout only needs SHORT_EDGES to fix a side-of-screen letterbox in
// landscape, and "doesn't matter" in portrait since the status bar is hidden
// anyway. That theory is backwards. Per Android's own display-cutout guidance,
// LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT already renders into the cutout in
// portrait *while the status bar is showing* (the tall status bar covers it) -
// but "in landscape or fullscreen mode, your app window is letterboxed so that
// none of your content displays in the cutout area" (emphasis the point this
// missed: fullscreen, not just landscape). So the exact moment this app hides
// the status bar for immersive reading - the one time DEFAULT's portrait
// carve-out no longer applies - is also the moment portrait was left on
// DEFAULT, leaving the top strip where the status bar used to be as dead,
// letterboxed space instead of content.
//
// Two follow-up bugs, both fixed here too:
//
// 1. "Exit immersive in portrait and the notch padding + lowered top bar
//    stay." `edgeToEdge` and `drawIntoCutout` are no longer the same single
//    switch - Compose's `windowInsetsPadding(WindowInsets.displayCutout)`
//    reads insets that the platform only recomputes and redispatches when
//    something explicitly asks it to; changing `window.attributes.
//    layoutInDisplayCutoutMode` by itself does not trigger that redispatch
//    (confirmed against AndroidX's own WindowInsets.getDisplayCutout() note
//    that the dispatched value depends on live redelivery, not a one-time
//    read, and against reports of the same "Compose doesn't see
//    layoutInDisplayCutoutMode changes made after setContent" gap). So the
//    cutout inset Compose was still padding by after leaving immersive mode
//    was the *last* one delivered - from while SHORT_EDGES was active -
//    which is exactly the stuck padding/lowered-top-bar symptom. Fixed by
//    calling `ViewCompat.requestApplyInsets` every time this function
//    changes the attribute, forcing a fresh dispatch so Compose's padding
//    (and everything else reading WindowInsets.displayCutout) catches up
//    immediately instead of carrying the previous mode's value.
// 2. "Enable full-screen, rotate to landscape without going immersive - the
//    notch still blacks out a letterboxed strip instead of blending in."
//    Per the Android guidance quoted above, DEFAULT letterboxes in
//    *landscape* even when the window isn't otherwise full-screen (status/
//    nav bars showing) - only `immersiveFullscreen` was ever in that
//    letterboxed state before, since only that case flips the cutout mode at
//    all, but a reader who has the full-screen setting on and simply rotates
//    without tapping into immersive mode hits the same letterboxing. Rather
//    than key `drawIntoCutout` on `immersiveFullscreen` alone, it is now
//    `immersiveFullscreen || (fullscreenEnabled && isLandscape)` - the same
//    root-Column background (see the call site) already fills whatever the
//    cutout inset carves out, so widening when that inset applies is enough:
//    it paints the reading background into the gap instead of leaving it
//    black, with no separate "paint the cutout" code needed. `edgeToEdge`
//    (and hiding the system bars) is unaffected and stays tied to
//    `immersiveFullscreen` only - this is strictly about which parts of the
//    window the content is allowed to draw under, not about hiding chrome.
// 3. "Full-screen setting off, rotate to landscape - same dead strip as bug
//    2, just with the setting off instead of on." The `fullscreenEnabled &&
//    isLandscape` half of bug 2's fix meant landscape only got pulled out of
//    DEFAULT while the setting was *also* on; with it off, `drawIntoCutout`
//    (by then simplified to plain `fullscreenEnabled`, since the
//    `immersiveFullscreen ||` half was always redundant once this effect
//    stopped gating hide/show on anything but `fullscreenEnabled`) went back
//    to `false` in landscape too, and DEFAULT letterboxes landscape
//    regardless of the full-screen setting - see the quoted guidance above.
//    So this is the one remaining combination the previous two fixes didn't
//    reach. `drawIntoCutout` (unconditional `true` at the call site below -
//    see that assignment's own doc comment for why it widened past
//    `fullscreenEnabled || isLandscape` to cover portrait too) covers it the
//    same way bug 2 covered "setting on, rotate" - and this time `edgeToEdge`
//    has to widen alongside it rather
//    than staying on `fullscreenEnabled` alone: Android's own guidance on
//    `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` is explicit that an app
//    should "ensure [it] is configured to display content edge to edge"
//    before rendering into the cutout at all, and SHORT_EDGES paired with a
//    non-edge-to-edge window is simply undocumented territory. That doesn't
//    reopen bug 1 or 2's "stuck gap"/letterboxing either, because this only
//    changes which *layout mode* the window is in - not whether the system
//    bars are hidden, which stays on `fullscreenEnabled` alone just above.
//    A still-visible status bar under a now-edge-to-edge window in landscape
//    is exactly the case the Reader TopAppBar below is already built for: it
//    is the one TopAppBar in this app that does *not* zero out its own
//    `windowInsets` (see its call site's own note), specifically so it can
//    self-pad below a real, visible status bar the same way it already does
//    during full-screen's swipe-to-reveal transient bars - this just reaches
//    that same code path from a different trigger (orientation instead of a
//    swipe), with the root Column's own `displayCutout`-only padding still
//    covering the cutout exactly as it always has, independent of either.
//
// layoutInDisplayCutoutMode is API 28+; below that there is no cutout API and
// the default behavior is all there is.
// Not private: landscape-tablet.md's cutout fix reuses this from MainActivity so
// every route - not just Reader - stops letterboxing the display cutout. Reader's own
// DisposableEffect(fullscreenEnabled, isLandscape) below still owns applying this
// *while a reader route is on screen*; MainActivity's own effect (see its
// renderMainContent) only re-applies the edge-to-edge/cutout-drawing default once
// `currentRoute` is no longer a reader route, so the two never fight over the same
// frame - see that effect's own doc comment for why the ordering works out.
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
    // See bug 1 above: without this, a mode change made here can sit unseen by
    // Compose's own insets reads until something unrelated forces a relayout.
    ViewCompat.requestApplyInsets(window.decorView)
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
    onOpenReaderSettings: () -> Unit = {},
    // Rung 4 fix (docs/implementation/tts-playback-controls.md): on API 33+,
    // POST_NOTIFICATIONS is required for *any* notification to actually post, including
    // a foreground service's - TtsPlaybackService.startForeground() does not fail when
    // it's missing, it just posts nothing, which is why the transport buttons silently
    // never appeared. The only existing runtime request for this permission was wired
    // to the "Notify new chapters" toggle, so a reader who never used that never got
    // asked. Called right alongside tts.speak() below, best-effort - it does not gate
    // starting playback, since the session itself works either way and the OS will show
    // the notification on a later update once/if the permission is granted. No-op by
    // default so this stays source-compatible with any other call site, same as
    // onOpenTtsSettings/onOpenReaderSettings above.
    onRequestNotificationPermission: () -> Unit = {},
    // Continue-Reading-loses-progress fix (see the lifecycle DisposableEffect and
    // BackHandler further down, near currentAnchor()): onBack/onBackToFiction/
    // onPrevious/onNext were the *only* places progress ever got saved, and all four
    // are in-app taps. The system/gesture Back action never called any of them - it
    // just let NavController pop its back stack on its own - and nothing at all ran
    // when the Activity was paused or the process was killed in the background (the
    // OS reclaiming memory while the app is backgrounded, a plain screen-off, or
    // `MainActivity` simply being recreated). A reader who used gesture-nav Back, or
    // who left the app any way other than tapping the in-app arrow/Previous/Next and
    // got swept away by any of those, had whatever they'd read since their last tap
    // silently discarded - "Continue Reading" would reopen at the last *tapped* exit,
    // not the last *read* position, which is what "it gets lost" and "restarting the
    // app resets it" were describing. onPositionChanged is the fix: a no-op-by-default
    // callback the caller wires to the same per-chapter save onBack already uses, but
    // without any navigation attached, so it's safe to call opportunistically - on
    // every Activity pause and right before a system Back is allowed to proceed -
    // rather than only at an explicit in-app exit.
    onPositionChanged: (ReaderPosition) -> Unit = {},
    // Continue-Reading-loses-progress fix, part 2 (see the BackHandler near
    // currentAnchor()): the system/gesture Back action bypassed the TopAppBar's own
    // Back arrow entirely - NavController installs its own OnBackPressedCallback that
    // pops its back stack directly, so system Back never ran onBack and never saved
    // anything. Deliberately a separate callback from onBack rather than reusing it:
    // onBack always navigates to Home (see its own doc comment - an explicit, existing
    // design choice for the in-app arrow), while system/gesture Back is expected to go
    // wherever the back stack naturally leads (NovelDetail, Home, whatever this reader
    // was opened from) - conflating the two would silently change where gesture-nav
    // Back lands. No-op by default so this stays source-compatible with any other call
    // site, same as onBack's sibling callbacks.
    onSystemBack: (ReaderPosition) -> Unit = {}
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
    // Stage 3.4 (see docs/foundational/design-notes.md): reassigned every recomposition
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
    // completionFraction = 1f: the reader finished the chapter by ear, so this is
    // unambiguously a completed chapter regardless of where the scroll happens to
    // sit (see ReaderPosition's doc comment).
    tts.onChapterFinished = { onNext?.invoke(ReaderPosition(content.length, "", 1f)) }
    // Stage 3.5 (see docs/foundational/design-notes.md): keeps the screen on for exactly
    // the duration of active read-aloud, gated by tts_keep_screen_on (off by default,
    // same reasoning as auto-continue above). Direct PreferencesManager access, same
    // pattern as everywhere else these four TTS keys are read - see
    // docs/foundational/design-notes.md's "Settled decisions" for why TTS settings don't get a
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
    val keepScreenOnEnabled by prefsManager.ttsKeepScreenOn.collectAsStateWithLifecycle(initialValue = false)
    // reader-settings.md's Screen on section: a second, reader-level
    // keep-screen-on, independent of the TTS one above. The two combine
    // ("reader option on, or read-aloud speaking with its own option on")
    // rather than one clearing the other's flag - either condition alone is
    // enough to keep the flag set, and the flag only clears once *both* are
    // false. Plain FLAG_KEEP_SCREEN_ON, not the idle-timer variant the doc
    // flagged as the better-but-bigger option for someone who falls asleep
    // reading - the owner picked the plain flag for this pass.
    val readerKeepScreenOnEnabled by prefsManager.readerKeepScreenOn.collectAsStateWithLifecycle(initialValue = false)
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
    val fullscreenEnabled by prefsManager.readerFullscreen.collectAsStateWithLifecycle(initialValue = false)
    // Whether bare "***"-style lines are chapter breaks; both parses below key on it.
    val breakLinesEnabled by prefsManager.readerBreakLines.collectAsStateWithLifecycle(initialValue = true)

    // reader-settings.md's Brightness section, as amended by the owner: a
    // window-level screenBrightness override for the reader's lifetime, restoring
    // "follow the system" (-1f, BRIGHTNESS_OVERRIDE_NONE) on dispose. Both the
    // opt-in and the level are set on the Reader settings page now (the in-reader
    // pill panel that used to hold a session-only slider is gone), so this just
    // applies whatever's stored while the reader is open and clears it on exit.
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
    //
    // `settingsSeeded` flips once that read has landed. Until then the text is laid out
    // with placeholder size/spacing/family (and with the *initial* values of the
    // break-line and full-screen flags below), then reflows when the real ones arrive -
    // so a position restored against that first layout is a pixel offset into text that
    // is about to move. That is the same drift rung 14 set out to remove, reintroduced
    // by the reader's own startup order for anyone whose reader settings aren't the
    // defaults. The restore below waits for this flag.
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
    // and navigation bars (not just ARKarium's own chrome) for as long as the
    // setting is on, restoring them on every exit path - Back, Previous/Next,
    // navigating to settings/tts mid-read-aloud, and leaving the reader
    // entirely - since all of those tear down this composition the same way,
    // which is what onDispose below runs for. transient-bars-by-swipe
    // (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) lets a reader still pull the
    // bars back temporarily with an edge swipe instead of being fully locked
    // out.
    //
    // PREVIOUS BUG (the one this replaces): this used to be gated on
    // `fullscreenEnabled && !showControls.value` - i.e. the system bars only
    // hid once the reader was *also* put into tap-to-hide-chrome ("immersive")
    // mode. That made the switch in Reader settings look broken: turning it
    // on and going back to a chapter changed nothing until the reader also
    // discovered the separate, undocumented tap gesture, and even then
    // `showControls` is `rememberSaveable` *per back-stack entry* - Previous/
    // Next replace the reader's route with a brand-new entry
    // (`popUpTo(...) { inclusive = true }` in MainActivity), which has no
    // saved state of its own, so every page turn silently reset showControls
    // to `true` and dropped straight back out of full-screen. Tying this
    // effect to `fullscreenEnabled` alone - not `showControls` - fixes both:
    // the setting now takes effect the moment it's turned on (and the moment
    // the reader is opened with it already on), and it survives Previous/
    // Next and the tap gesture alike, since neither one is in its key list
    // anymore. ARKarium's own top bar/chrome still follows `showControls`
    // exactly as before (see the tap handler and TopAppBar below) - only the
    // *system* bars' visibility moved off of it.
    //
    // Display cutout (notch / camera hole): full-screen, in *either*
    // orientation, opts the window into drawing under the cutout (SHORT_EDGES) and
    // into edge-to-edge (see applyWindowLayout's own doc comment - this used to be
    // landscape-only, which was a bug: portrait stayed on DEFAULT while full-screen,
    // so the vacated status-bar strip at the top was letterboxed instead of filled).
    // The root column below re-pads by the cutout's own insets (now all sides, not
    // just horizontal - the cutout sits on a side edge in landscape but at the top in
    // portrait) so text still never sits under the notch itself; that inset is zero
    // whenever the window isn't drawing into the cutout, so this costs nothing outside
    // full-screen mode. isLandscape is still a key so rotating while full-screen is on
    // re-runs the effect and keeps drawIntoCutout correct as the device turns.
    // requestApplyInsets inside applyWindowLayout is what makes that rotation - and
    // turning the setting off - actually show up instead of leaving Compose padding by
    // a stale inset.
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
        // Landscape-with-full-screen-off gap bug: `edgeToEdge`/`drawIntoCutout` used to be
        // `fullscreenEnabled` alone, so turning the full-screen *setting* off put the
        // window straight back on `LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT`. Per Android's own
        // display-cutout guidance (developer.android.com/guide/topics/display-cutout):
        // DEFAULT "renders into the cutout area when the display cutout is contained in a
        // system bar. Otherwise... content may be letterboxed when displayed in landscape
        // mode" - and that letterboxing is keyed on orientation alone, independent of
        // whether the window is edge-to-edge or a normal `decorFitsSystemWindows(true)`
        // layout. So the one combination this didn't cover - full-screen off, rotated to
        // landscape - still hit DEFAULT's landscape letterbox: the OS shrank the window
        // around the cutout instead of the Column's own `windowInsetsPadding(displayCutout)`
        // below ever getting a chance to pad around it, leaving the reported dead strip
        // between the real status bar and this screen's own TopAppBar. MainActivity's
        // `renderMainContent` effect already sidesteps exactly this for every *other* route
        // by applying `edgeToEdge = true, drawIntoCutout = true` unconditionally (see its own
        // "landscape-tablet.md's cutout fix" comment) - Reader was the one screen still
        // gating both behind its own setting instead of matching that baseline.
        //
        // Portrait-with-full-screen-off gap bug (reported: a vacant strip between the
        // real status bar and this screen's TopAppBar, specifically with the reader's own
        // full-screen setting off). The landscape fix above deliberately stayed
        // `fullscreenEnabled || isLandscape` rather than plain `true` - portrait was left on
        // `DEFAULT` because the display-cutout guidance quoted above says DEFAULT does *not*
        // letterbox portrait while the status bar is showing (the cutout is "contained in a
        // system bar"), so portrait-off looked like it didn't need the same widening. The gap
        // this reported is a different inset than the cutout, though - `WindowCompat.
        // setDecorFitsSystemWindows(window, true)` (what `edgeToEdge = false` below resolves
        // to) is relied on here to make the status-bar inset Compose reads resolve to zero,
        // since the framework is nominally handling that reservation outside Compose
        // entirely - but that is exactly the "entirely platform/OEM-dependent, not guaranteed
        // by the AndroidX inset APIs" territory `architecture.md`'s own "Reading and local
        // state" lesson on this already warns about for the cutout case, and it does not hold
        // consistently for the status-bar inset either: `WindowInsetsCompat` dispatch down the
        // Activity's content view is not reliably consumed by `decorFitsSystemWindows(true)`
        // alone on every API level/OEM skin this app's `minSdk 24..compileSdk 34` range spans,
        // so the TopAppBar's own default inset (see its call site's own note - it
        // deliberately does *not* zero its `windowInsets`, specifically to self-pad below a
        // real, visible status bar) can still report the real status-bar height even while
        // non-edge-to-edge, landing on top of whatever gap the system already reserved at the
        // window level - the reported vacant strip is that double reservation, not a single
        // one. Dropping `isLandscape` from this condition - unconditional `true`, same as
        // every non-reader route already gets - removes the one remaining case (portrait,
        // full-screen off) that still depended on that non-guaranteed zero-inset behavior:
        // Reader is now edge-to-edge any time it's on screen, period, and the TopAppBar's
        // already-proven self-pad-below-the-status-bar path (the same one landscape-off and
        // full-screen's swipe-to-reveal transient bars already exercise) is the only thing
        // ever responsible for the gap under the status bar, with nothing at the window level
        // reserving it a second time. This is purely a layout-mode change, not a visibility
        // one: system-bar hide/show above stays keyed on `fullscreenEnabled` alone, so this
        // does not start hiding the status bar in portrait - it only lets the window draw
        // under it (and under the cutout, same as landscape already does) so Compose, not the
        // platform, is the single source of truth for the gap's size. That is safe because
        // the TopAppBar below is deliberately the one TopAppBar in this app that does *not*
        // zero out its `windowInsets` (every other screen's does, relying on the single outer
        // Scaffold padding instead) - its default already resolves to the *system bars'* own
        // inset (`TopAppBarDefaults.windowInsets`, Material3's `systemBarsForVisualComponents`),
        // which is a different inset than the Column's `WindowInsets.displayCutout` pad. So an
        // edge-to-edge window with a still-visible status bar pads itself below that real
        // status bar through the TopAppBar's own inset exactly as it already does during
        // full-screen's swipe-to-reveal state and landscape-off, while the Column keeps
        // handling only the cutout; neither source ever reads the other's inset, so there is
        // still nothing to double-pad - unlike relying on the window itself to also reserve
        // that space, which is what produced the double reservation this fixes.
        val drawIntoCutout = true
        if (window != null) {
            applyWindowLayout(window, edgeToEdge = drawIntoCutout, drawIntoCutout = drawIntoCutout)
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            // Bug 5 fix (docs/proposal/bugs/other/uiux-bugs.md): this used to also call
            // applyWindowLayout(window, edgeToEdge = false, drawIntoCutout = false) here,
            // racing MainActivity's own LaunchedEffect(currentRoute) - which re-applies
            // edgeToEdge = true, drawIntoCutout = true the instant currentRoute leaves
            // reader/* - for ownership of the window's edge-to-edge state. onDispose runs
            // synchronously as part of leaving composition; LaunchedEffect's coroutine
            // runs after, on its own schedule. For one or more frames the outer Scaffold
            // had already switched to contentWindowInsets = safeDrawing (padding content
            // down by the status-bar height) while the window was still non-edge-to-edge
            // from this call (the system had already pushed the whole content view down
            // by the status-bar height once) - both paddings landing in the same frame is
            // exactly the reported gap between the real status bar and the next screen's
            // TopAppBar. MainActivity's effect already has the correct unconditional
            // branch for every non-reader route, so it's left as the single owner of that
            // state; this onDispose only needs to guarantee the system bars are shown
            // again immediately; controller?.show above already does that.
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
    // Scrolling-bug fix (other/scrolling-bug.md, causes 2 and 3): the chapter body used
    // to be a plain Column + .verticalScroll(scrollState), which composes and lays out
    // every paragraph up front - on a long chapter, during this very screen's own enter
    // transition (NavMotion's 300ms slide+fade) - and had every paragraph register an
    // onGloballyPositioned callback that wrote to state (paragraphContentTop) on every
    // scroll frame. Both are gone now: the body below is a LazyColumn over readerBlocks()
    // (ReaderBlocks.kt), which only composes/lays out the blocks actually scrolled into
    // view, and position/progress are now derived from the LazyListState's own
    // index/offset bookkeeping (ReaderScrollModel, readerAnchorBlock,
    // readerBlockIndexForCharOffset, readerBlockIndexForChunkStart) instead of a
    // per-paragraph pixel map this screen had to maintain by hand.
    val listState = rememberLazyListState()

    // ReaderScreen's call site in MainActivity doesn't change when Previous/Next swaps
    // to a new chapter (it's still the same `is Screen.Reader ->` branch, just
    // re-invoked with a new Screen.Reader instance) - so `remember`-backed state above,
    // including listState, survives across chapters rather than being torn down and
    // recreated. That's the right behavior for fontSize/lineHeight/readingMode (a
    // reading preference shouldn't reset every chapter), but scroll position is
    // per-chapter: without this, landing on a new chapter kept whatever scroll offset
    // the previous chapter was left at instead of opening at the top. Reset explicitly
    // whenever the chapter actually changes.
    LaunchedEffect(chapter.id) {
        listState.scrollToItem(0)
    }

    // The flat list of lazily-composed blocks for this chapter's text (ReaderBlocks.kt) -
    // one per prose paragraph plus one per divider/callout - and the weighted model that
    // turns the LazyListState's visible-item bookkeeping into a 0f..1f reading-progress
    // fraction (ReaderScrollModel's own doc comment). Both keyed on the same inputs the
    // old paragraphOffsetList/segments were, so a chapter edit or a break-lines toggle
    // rebuilds them exactly when it used to rebuild those.
    val blocks = remember(content, breakLinesEnabled) { readerBlocks(content, breakLinesEnabled) }
    val scrollModel = remember(blocks) { ReaderScrollModel(blocks) }
    // Rung 5: each prose block's last TextLayoutResult (key: the block's rawStart), so
    // the long-press word lookup below can turn a tap position into a character offset.
    // Plain map, not state - nothing re-runs off its contents (unlike the pixel-offset
    // map this replaced, which every paragraph wrote to on every scroll frame).
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

    // Rung 6 (paragraph-highlight variant, tts-playback-controls.md): follow the
    // narration. Whenever the range being highlighted changes - a chunk boundary, or
    // now also a paragraph boundary crossed mid-chunk, via the same ttsHighlightRange
    // the highlight itself uses - scroll to the block it starts in, unless that block is
    // already comfortably near the top of the viewport (the same "don't nudge for a tiny
    // move" judgment call ttsAutoScrollTarget used to make in pixels, now made in item
    // terms: items are one real paragraph each, so index-level following no longer needs
    // the old per-line pixel offset to feel precise). Still never fights a reader who
    // scrolls away mid-paragraph; the next paragraph (or chunk, with no narrower
    // paragraph available) brings them back. Held back until reconcileTtsSession has
    // run, so a stale session for another chapter (about to be stopped above) can't
    // scroll this one.
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

    // Rung 14 (F9) - what onBack/onBackToFiction/onPrevious/onNext now save instead of
    // currentProgress()'s plain fraction: the paragraph currently at (or just above) the
    // top of the viewport, identified by its own offset into the chapter's text rather
    // than by where that happens to land on screen at the current font size and width.
    // readerAnchorBlock walks back from the item at the top of the viewport to the
    // nearest prose block (a divider/callout has no offset of its own), the same "at or
    // above the top, not past it" rule the old pixel-offset lookup used. Falls back to
    // currentProgress()'s proportional estimate only when there's no prose at all above
    // the top item (readerAnchorBlock returns null), so this never saves nothing.
    //
    // Never reports a position the reader hasn't actually reached. While a saved
    // initialPosition is still waiting to be scrolled to, the list is still at item 0 -
    // saving *that* would overwrite the real saved position with the top of the chapter,
    // which is how a quick background/rotate/back right after opening used to lose
    // "where I left off". Until the restore lands, the position to report is the one
    // being restored.
    fun currentAnchor(): ReaderPosition {
        if (!positionRestored && initialPosition != null) return initialPosition
        val topItemIndex = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.offset >= 0 }?.index
            ?: listState.firstVisibleItemIndex
        val anchor = readerAnchorBlock(blocks, topItemIndex - READER_LEADING_ITEMS)
        val charOffset = anchor?.rawStart
            ?: (currentProgress() * content.length).toInt().coerceIn(0, content.length)
        // completionFraction is the real scroll fraction (see ReaderPosition's doc
        // comment on the regression this fixes) - independent of charOffset, which is
        // the resume anchor (top-of-viewport paragraph), not a completion measure.
        return ReaderPosition(charOffset, ReaderPosition.snippetFrom(content, charOffset), currentProgress())
    }

    // Continue-Reading-loses-progress fix, part 1: save on every Activity pause, not
    // just on an explicit in-app exit tap. ON_PAUSE (not ON_STOP) because it's the
    // last callback guaranteed to run before the process can be killed in the
    // background - screen-off, Home/Recents, an incoming call, or the OS reclaiming
    // memory all pass through it, and it fires well before a config change finishes
    // tearing this composition down too. onDispose also calls it once more, covering
    // the plain in-composition exits that *don't* flow through onBack/onPrevious/
    // onNext - e.g. the app process dying between ON_PAUSE and this composable
    // actually leaving composition - and because re-saving the same or a
    // further-along anchor on an ordinary exit is harmless (ReadingProgressDao.upsert
    // is keyed one row per novel, so this never creates a duplicate or stale-wins
    // race against onBack's own save, which always runs after this one on a tap).
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

    // Continue-Reading-loses-progress fix, part 2: hands the anchor to onSystemBack
    // (see its own doc comment) instead of calling the platform dispatcher again from
    // inside this callback - re-invoking onBackPressedDispatcher.onBackPressed() here
    // would just call this same BackHandler right back (it's still the topmost
    // enabled callback until this lambda returns), looping forever instead of
    // actually leaving. The caller's onSystemBack does the real navigation
    // (`navController.popBackStack()`), same as NavController's own default
    // OnBackPressedCallback would have, just with a save first.
    BackHandler(enabled = true) {
        onSystemBack(currentAnchor())
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
            // Pads by the cutout's own insets on every side - zero unless the
            // full-screen block above let the window draw into the cutout, in which
            // case the background (set just above) already covers it and this keeps
            // the text and chrome clear of the notch itself. All sides, not just
            // horizontal: the cutout sits on a side edge in landscape but at the top
            // in portrait, and portrait immersive now draws into it too (the
            // fullscreen-in-portrait fix above), so a horizontal-only pad would leave
            // the first line or two of text under the notch in portrait.
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        // Top bar
        //
        // Deliberately the one TopAppBar in this app that does NOT pass
        // `windowInsets = WindowInsets(0, 0, 0, 0)` - every screen under
        // ui/ does (HomeScreen, SettingsScreen, NovelDetailScreen, etc.),
        // relying instead on MainActivity's single outer Scaffold to consume
        // the top inset once via `innerPadding` and zeroing their own
        // TopAppBar so it isn't padded a second time. Reader can't use that
        // same recipe: MainActivity's Scaffold deliberately sets
        // `contentWindowInsets = WindowInsets(0, 0, 0, 0)` for reader routes
        // specifically (see that call site's own comment) so Reader can
        // manage its insets itself instead of the cutout being reserved
        // twice - which means nothing upstream of this Column ever consumes
        // the status-bar inset for Reader. Left at its Material3 default
        // (`TopAppBarDefaults.windowInsets`, the *system bars'* own top+
        // horizontal inset - a different inset than the Column's own
        // `displayCutout` pad above, so the two never double up), this
        // TopAppBar pads itself below the status bar whenever one is both
        // visible and the window is edge-to-edge under it: full-screen's
        // swipe-to-reveal transient bars, and now also landscape with
        // full-screen off (applyWindowLayout's own doc comment, fix 3). When
        // neither applies the window isn't edge-to-edge at all, the system
        // reserves the status bar's space outside Compose entirely, and this
        // inset - like the Column's cutout one - resolves to zero on its
        // own, so there's nothing to zero out here even in that case.
        //
        // Jank fix (docs/proposal/bugs/other/uiux-bugs.md #8): this used to be a bare
        // `if (showControls.value) { TopAppBar(...) }` with no animation at all, while the
        // footer Column below - the screen's other `showControls`-gated widget - already wraps
        // itself in `.animateContentSize()` specifically so its own appear/disappear doesn't
        // "jump instantly" (see that Column's own comment). Tapping to toggle chrome therefore
        // drove two different motions off the same state change in the same frame: the footer
        // smoothly animating its height while the scrollable body's *top* edge snapped up or
        // down instantly the moment the TopAppBar itself was added to or removed from
        // composition - worst in portrait specifically, since portrait is exactly where the top
        // bar sits flush under the status bar/cutout with nothing else above it to mask a
        // one-frame jump (landscape's cutout is a side strip the top bar barely touches).
        // Wrapping the same way the footer already does - a Column that stays in composition
        // with `.animateContentSize()` on it, conditionally composing TopAppBar inside - gives
        // both edges of the chrome the identical default animation spec, so they move in step
        // instead of one popping and the other easing.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                // Immersive-with-full-screen-off gap bug (reported: "full-screen toggle off,
                // content draws behind the status bar" - worst in landscape, where the
                // Column's own `displayCutout` pad above lands on a side edge instead of the
                // top, so it never incidentally covers for this the way it occasionally does
                // in portrait on a notched device). `applyWindowLayout` above now makes Reader
                // edge-to-edge unconditionally (fix 3 in its own doc comment), so with chrome
                // showing, the TopAppBar's own un-zeroed `windowInsets` (see its own note
                // below) is the sole thing reserving space for a real, visible status bar -
                // that comment already states as much. What it didn't cover: tapping to hide
                // chrome (`showControls.value = false`, ARKarium's own immersive mode) removes
                // the TopAppBar from composition - and with it, the only remaining consumer of
                // that inset - while the full-screen *setting* being off means the real system
                // status bar never actually hides (see the `DisposableEffect` above: hide/show
                // is keyed on `fullscreenEnabled` alone, independent of `showControls`). So
                // content was left drawing straight under a status bar that was still on
                // screen. Reserving the same inset here exactly in that gap - chrome hidden,
                // status bar still showing - restores what the TopAppBar would have padded by;
                // gated so it only applies in that one state, this never stacks with the
                // TopAppBar's own pad once chrome (and the TopAppBar) comes back.
                .then(
                    if (!showControls.value && !fullscreenEnabled) {
                        Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                )
        ) {
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
                                    else -> {
                                        onRequestNotificationPermission()
                                        tts.speak(context, chapter.title, content, chapter.id, coverUri)
                                    }
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
        }

        // Scrolling-bug fix (other/scrolling-bug.md, causes 2 and 3) - this used to be a
        // plain Column + .verticalScroll(scrollState) with every paragraph composed and
        // laid out up front; see the comment on `listState` above for why it's a
        // LazyColumn now. The header (breadcrumb/title/top nav row) and footer (bottom
        // nav row/author card/trailing gap) are each a single item, matching
        // READER_LEADING_ITEMS (ReaderBlocks.kt) - everything in currentAnchor()/
        // currentProgress() that converts a `blocks` index to a LazyColumn item index
        // assumes exactly one leading item, so a second header item must not be added
        // here without updating that constant too.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Fiction title / "back to fiction" breadcrumb + arc name, matching
                    // Royal Road's chapter-page top block. Only rendered when the caller
                    // has a title to show - existing callers that don't pass novelTitle
                    // see no change at all.
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
                                    // The row's only visible text is the fiction's title,
                                    // which on its own doesn't say that activating it goes
                                    // back to the fiction.
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

                    // Previous/Next above the chapter body, mirroring the row below it -
                    // Royal Road shows this pair on both ends of the chapter.
                    ChapterNavRow(
                        hasPrevious = onPrevious != null,
                        hasNext = onNext != null,
                        textColor = textColor,
                        onPrevious = { onPrevious?.invoke(currentAnchor()) },
                        onNext = { onNext?.invoke(currentAnchor()) }
                    )
                }
            }

            // Chapter content with enhanced typography, one LazyColumn item per block
            // (ReaderBlocks.kt): inline <chapter break>/<author>/<news> tags (see
            // data/ChapterMarkup.kt) render as their own centered divider / "A Note from
            // the Author" / "News" callouts instead of printing the raw tag text, and
            // running prose is split further on blank lines into one item per actual
            // paragraph - see readerBlocks()'s own doc comment for why that split, not
            // just one item per markup-delimited segment, is what makes this lazy at all.
            // The 40.dp horizontal/vertical padding below reproduces the old shared
            // Surface+Column(padding 20.dp) wrapper's combined inset without wrapping
            // every item in its own copy of that (functionally invisible, since the
            // Surface's color was always the same as the surrounding background) card.
            itemsIndexed(blocks, key = { index, _ -> index }) { index, block ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .padding(
                            top = when {
                                index == 0 -> 36.dp
                                block is ReaderBlock.Prose && block.blankLinesBefore > 0 ->
                                    (block.blankLinesBefore * 12).dp
                                else -> 0.dp
                            }
                        )
                ) {
                    when (block) {
                        is ReaderBlock.Prose -> {
                            val rawStart = block.rawStart
                            // Rung 6 (paragraph-highlight variant): the paragraph being
                            // read aloud, shaded in place - or rung 5's whole chunk when
                            // there's no narrower paragraph range to use (see
                            // ttsHighlightRange). Plain String (exactly the old
                            // rendering) unless this block actually contains part of
                            // that range.
                            val highlight = if (ttsFollowing) {
                                val (rangeStart, rangeEnd) = ttsHighlightRange(
                                    paragraphStart = tts.currentParagraphStart,
                                    paragraphEnd = tts.currentParagraphEnd,
                                    chunkStart = tts.currentChunkStart,
                                    chunkEnd = tts.currentChunkEnd
                                )
                                ttsHighlightWithin(
                                    blockStart = rawStart,
                                    blockLength = block.text.length,
                                    chunkStart = rangeStart,
                                    chunkEnd = rangeEnd
                                )
                            } else null
                            val shownText = remember(block.text, highlight, textColor) {
                                buildAnnotatedString {
                                    append(block.text)
                                    if (highlight != null) {
                                        addStyle(
                                            SpanStyle(background = textColor.copy(alpha = 0.16f)),
                                            highlight.start,
                                            highlight.endExclusive
                                        )
                                    }
                                }
                            }
                            Text(
                                text = shownText,
                                onTextLayout = { layout -> paragraphLayouts[rawStart] = layout },
                                style = TextStyle(
                                    fontSize = fontSize.value.sp,
                                    lineHeight = (fontSize.value * lineHeight.value).sp,
                                    color = textColor,
                                    fontFamily = resolveFontFamily(fontFamilyChoice.value),
                                    letterSpacing = extraLetterSpacing(fontFamilyChoice.value),
                                    textAlign = TextAlign.Justify
                                ),
                                modifier = Modifier
                                    // Long-press-a-word lookup: this used to fire on a
                                    // plain tap, which - since paragraph text covers
                                    // nearly the whole reading area - meant a tap almost
                                    // anywhere in a chapter opened a dictionary/translate
                                    // app instead of doing the one thing every other tap
                                    // on this screen does (toggle showControls).
                                    // detectTapGestures' own pointerInput consumes the
                                    // gesture before it can bubble up to the LazyColumn's
                                    // clickable above, so a plain tap on text was silently
                                    // swallowed into a lookup (or a no-op on whitespace/
                                    // punctuation) and never toggled the controls the way
                                    // tapping the margin did - normal reading (tap to
                                    // show/hide the top bar and Reader Preferences pill
                                    // while turning pages) was broken on every word. onTap
                                    // now matches the LazyColumn's own toggle so tapping
                                    // text behaves exactly like tapping the margin; only
                                    // onLongPress - a deliberate, can't-happen-by-accident
                                    // gesture - does the lookup. paragraphLayouts[rawStart]
                                    // is written by onTextLayout above, so by the time
                                    // either gesture can land the layout for offset math
                                    // is already there; a long-press that somehow lands
                                    // before the first layout pass, or on whitespace/
                                    // punctuation (WordLookup.wordAt returns null), is a
                                    // silent no-op rather than falling through to
                                    // anything else.
                                    .pointerInput(rawStart, block.text) {
                                        detectTapGestures(
                                            onTap = { showControls.value = !showControls.value },
                                            onLongPress = { pressOffset ->
                                                val layout = paragraphLayouts[rawStart]
                                                val charIndex = layout?.getOffsetForPosition(pressOffset)
                                                val word = charIndex?.let { WordLookup.wordAt(block.text, it) }
                                                if (word != null) WordLookup.lookup(context, word)
                                            }
                                        )
                                    }
                            )
                        }
                        is ReaderBlock.Break -> ChapterBreakDivider(label = block.label, textColor = textColor)
                        is ReaderBlock.AuthorNote -> AuthorNoteCallout(author = author, body = block.text, textColor = textColor)
                        is ReaderBlock.News -> ChapterCalloutCard(
                            icon = "📰",
                            heading = "News",
                            body = block.text,
                            textColor = textColor
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ChapterNavRow(
                        hasPrevious = onPrevious != null,
                        hasNext = onNext != null,
                        textColor = textColor,
                        onPrevious = { onPrevious?.invoke(currentAnchor()) },
                        onNext = { onNext?.invoke(currentAnchor()) },
                        // Only offered where there's actually somewhere to send the
                        // reader - the top copy of this row (above the chapter body) is
                        // left as a plain disabled button at the end of a fiction, since
                        // "About the Author" as the very first thing on the page would
                        // read oddly before they've even reached the content.
                        onAuthorClick = author?.let { { onAuthorClick() } }
                    )

                    // "About the author" card - only rendered when this fiction actually
                    // has a linked author (see NovelEntity.authorId /
                    // AUTHOR_PAGE_AND_CHAPTER_REDESIGN.md). No author.json means no
                    // card, same as today's "no metadata.json" behavior elsewhere in
                    // the scanner.
                    if (author != null) {
                        AboutAuthorCard(
                            author = author,
                            backgroundColor = backgroundColor,
                            textColor = textColor,
                            onClick = onAuthorClick
                        )
                    }

                    // Small trailing gap so the last line of the chapter doesn't sit
                    // flush against the footer below it.
                    Box(modifier = Modifier.padding(bottom = 24.dp))
                }
            }
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
                .let { base ->
                    // Nothing in this Column is ever composed while showControls is
                    // false (see readerBottomPill: PREFERENCES is only returned when
                    // !immersive, and the progress readout below is itself gated on
                    // showControls.value) - so the padding that gives its visible
                    // content breathing room has no reason to exist when there's no
                    // content to pad. Without this gate, an empty Column still
                    // reserved 16dp top + 16dp bottom from its own padding modifier,
                    // which is exactly the unwanted bottom margin seen in full
                    // immersive mode.
                    if (showControls.value) base.padding(horizontal = 16.dp, vertical = 16.dp) else base
                }
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
            //
            // Scrolling-bug fix (other/scrolling-bug.md, cause 1): this used to read
            // currentProgress() - which reads scrollState.value/maxValue - directly
            // here, inline in ReaderScreen's own Column/Box. Column and Box don't
            // introduce a recomposition-scope boundary, so that read was attributed to
            // ReaderScreen itself: every scroll offset change invalidated the whole
            // screen, re-running the per-paragraph loop below (828ish) and all its
            // onGloballyPositioned/onTextLayout lambdas on every scroll frame while
            // the controls were visible. Pushed the read into ProgressReadout's own
            // derivedStateOf instead: ProgressReadout is its own composable, so the
            // scroll read is scoped to just that Text, and derivedStateOf means it
            // only actually recomposes when the rounded percent or page number
            // changes (at most ~100 times per chapter), not once per scroll tick.
            if (showControls.value) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ProgressReadout(
                        progress = { currentProgress() },
                        estimatedTotalPages = estimatedTotalPages,
                        textColor = textColor
                    )
                }
            }
        }
    }

    // The read-aloud controls pill (rungs 1-2 of docs/implementation/
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
            currentChunkIndex = tts.currentChunkIndex,
            totalChunks = tts.totalChunks,
            onPlayPause = {
                when {
                    tts.isPaused -> tts.resume()
                    tts.isSpeaking -> tts.pause()
                    else -> {
                        onRequestNotificationPermission()
                        tts.speak(context, chapter.title, content, chapter.id, coverUri)
                    }
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

// Scroll-driven, so split out of ReaderScreen into its own recomposition scope on
// purpose (see the scrolling-bug fix comment at the one call site in ReaderScreen).
// `progress` is a lambda rather than a Float so the scrollState read it ultimately
// performs (via ReaderScreen's currentProgress()) happens inside this composable's
// own derivedStateOf, not in the caller - that's what keeps the invalidation scoped
// to just this Text instead of bubbling up to whatever composed this.
@Composable
private fun ProgressReadout(
    progress: () -> Float,
    estimatedTotalPages: Int,
    textColor: Color
) {
    val progressPercent by remember { derivedStateOf { (progress() * 100).toInt() } }
    val currentPage by remember {
        derivedStateOf { (progress() * estimatedTotalPages).toInt().coerceIn(1, estimatedTotalPages) }
    }
    Text(
        "$progressPercent% • page $currentPage of $estimatedTotalPages",
        style = MaterialTheme.typography.labelSmall,
        color = textColor.copy(alpha = 0.7f)
    )
}

// The read-aloud controls bar: stop the session, open TTS settings, skip to the
// previous/next chunk, and play/pause. Shown by ReaderScreen only in immersive mode
// while a session exists (see readerBottomPill); the speaker icon in the top bar is what
// starts one. Laid out to match
// the reference screenshot in uiux.md's playback-controls entry (close/settings on
// one side, skip/play/skip on the other), with its "n of total" scrub readout filled
// in at chunk granularity by TtsProgressIndicator below (rung 3).
@Composable
private fun TtsControlsBar(
    isSpeaking: Boolean,
    isPaused: Boolean,
    currentChunkIndex: Int,
    totalChunks: Int,
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

            TtsProgressIndicator(currentChunkIndex = currentChunkIndex, totalChunks = totalChunks)

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

// Rung 3 of docs/implementation/tts-playback-controls.md: the "n of total"
// scrub/progress readout from the uiux.md reference design, filled in at chunk
// granularity - "chunk N of M" rather than a page number, per that rung's own
// "done when" (a chunk isn't a stable reader-facing unit; chunk count depends on
// TTS_MAX_CHUNK_CHARS in TtsPlaybackService, not on anything a reader would
// recognize as a "page" - see that doc for the page-estimate alternative this
// leaves for later). Renders nothing with no session (totalChunks == 0), same
// "nothing to show" gate TtsControlsBar's own visibility already uses one level up.
@Composable
private fun TtsProgressIndicator(currentChunkIndex: Int, totalChunks: Int) {
    val label = ttsChunkProgressLabel(currentChunkIndex, totalChunks) ?: return
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
    )
}

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
