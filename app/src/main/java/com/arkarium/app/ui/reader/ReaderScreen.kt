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

    // animateColorAsState (animation-and-polish.md F16: "Reading mode switch ... Snaps ->
    // animateColorAsState on the background, ~200ms") - Light/Sepia/Dark used to replace
    // backgroundColor/textColor outright on the same frame readingMode.value changed, a
    // hard cut exactly where a reader's eyes are already focused. Animating both through
    // the same 200ms tween keeps text legible (both colors cross-fade together, so
    // contrast never dips mid-transition) without re-laying-out the chapter body - only
    // the color, not font size or line spacing, animates, per F16's own rule about not
    // animating anything that forces a relayout.
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
