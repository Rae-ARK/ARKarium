package com.arkarium.app.ui.reader

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkarium.app.data.AuthorEntity
import com.arkarium.app.data.ChapterEntity
import com.arkarium.app.data.ReaderPosition
import com.arkarium.app.ui.ChapterTtsState
import com.arkarium.app.ui.ReaderBlock
import com.arkarium.app.ui.ReaderBottomPill
import com.arkarium.app.ui.RippleReveal
import com.arkarium.app.ui.components.CoverThumbnail
import com.arkarium.app.ui.ttsHighlightRange
import com.arkarium.app.ui.ttsHighlightWithin
import com.arkarium.app.util.WordLookup

// The reader's chrome (top bar), chapter body (LazyColumn) and footer (Reader
// Preferences panel/pill + progress readout). Pulled out of ReaderScreen (now a thin
// host - see that file) as the "page content" slice of the former god-composable; the
// end-of-chapter cards it used to render inline now live in their own LazyListScope
// item, readerChapterEndItem (ReaderChapterEnd.kt). All parameters below are exactly
// the state/callbacks ReaderScreen already owned - this split changes no behavior.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderPageContent(
    chapter: ChapterEntity,
    content: String,
    novelTitle: String?,
    arcTitle: String?,
    coverUri: String?,
    author: AuthorEntity?,
    backgroundColor: Color,
    textColor: Color,
    showControls: MutableState<Boolean>,
    showPreferences: MutableState<Boolean>,
    fullscreenEnabled: Boolean,
    tts: ChapterTtsState,
    ttsFollowing: Boolean,
    context: Context,
    listState: LazyListState,
    blocks: List<ReaderBlock>,
    paragraphLayouts: HashMap<Int, TextLayoutResult>,
    fontSize: MutableFloatState,
    lineHeight: MutableFloatState,
    fontFamilyChoice: MutableState<ReaderFontFamily>,
    readingMode: MutableState<ReadingMode>,
    bottomPill: ReaderBottomPill,
    estimatedTotalPages: Int,
    onBack: (ReaderPosition) -> Unit,
    onBackToFiction: (ReaderPosition) -> Unit,
    onPrevious: ((ReaderPosition) -> Unit)?,
    onNext: ((ReaderPosition) -> Unit)?,
    onAuthorClick: () -> Unit,
    onOpenReaderSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    currentAnchor: () -> ReaderPosition,
    currentProgress: () -> Float
) {
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
            // AnimatedVisibility here only fades (fadeIn/fadeOut, no expand/shrink) -
            // the outer Column's own .animateContentSize() above already owns the
            // height/size part of this transition (see its comment). Layering
            // AnimatedVisibility's default expandVertically/shrinkVertically on top of
            // that would animate the same height change twice, at two different rates.
            // Exit faster than enter, matching NavMotion's own rule (Material pairs a
            // longer decelerate-entrance with a shorter accelerate-exit) - see
            // animation-and-polish.md F16.
            AnimatedVisibility(
                visible = showControls.value,
                enter = fadeIn(animationSpec = tween(210, delayMillis = 90)),
                exit = fadeOut(animationSpec = tween(90))
            ) {
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
                            CoverThumbnail(url = coverUri, modifier = Modifier.size(width = 40.dp, height = 56.dp))
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

            readerChapterEndItem(
                novelTitle = novelTitle,
                author = author,
                backgroundColor = backgroundColor,
                textColor = textColor,
                onPrevious = onPrevious,
                onNext = onNext,
                onAuthorClick = onAuthorClick,
                currentAnchor = currentAnchor
            )
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
            // Fade only (see the matching AnimatedVisibility on the top bar above for
            // why): this Column's own .animateContentSize() already owns the height
            // change as the readout appears/disappears.
            AnimatedVisibility(
                visible = showControls.value,
                enter = fadeIn(animationSpec = tween(210, delayMillis = 90)),
                exit = fadeOut(animationSpec = tween(90))
            ) {
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
internal fun ChapterNavRow(
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
// ReaderAuthorAvatar/CoverThumbnail already use elsewhere on this screen rather
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
