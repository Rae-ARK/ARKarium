# Robustness: cancellation, silent failure, duplicate state, input handling

> **Kind:** proposal · **Status:** all fixed in code · **Verified:** 2026-11-07 @ 9cc65b9
> **Origin:** [`engineering-review.md`](engineering-review.md) F6–F8, F11, F18, F20.

### F6. Cancellation swallowed in the new-chapter worker — fixed

**Severity:** Medium. **Tags:** V.
**Where:** `data/NewChapterCheckWorker.kt`, `doWork()`.

**Was:** each novel's check ran inside `try { checkOneNovel(...) } catch (e:
Exception) { /* report and move on */ }`. On the JVM, `CancellationException`
is an `IllegalStateException`, so a bare `catch (e: Exception)` catches it too
(`engineering-review.md` C2 — a source claiming otherwise was wrong). That
meant a cancelled worker (WorkManager stopping it because constraints were no
longer met, or the process being torn down) kept looping through the
remaining novels and hitting the network instead of actually stopping.

**Fixed (commit 5426a3d):** the per-novel catch now rethrows
`CancellationException` before falling through to the broad `catch (e:
Exception)`, so cancellation propagates instead of being treated as "one
novel's check failed."

### F7. Unreadable chapter rendered as a silent empty chapter — fixed

**Severity:** Medium. **Tags:** V.
**Where:** `data/ChapterContentRepository.kt`, `TextChapterContentRepository.getTextContent()`.

**Was:** `context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
?: ByteArray(0)` was wrapped in `catch (e: Exception) { ByteArray(0) }`. A
lost SAF permission, a provider hiccup, or the underlying file having moved
or been deleted all produced the same result as a genuinely empty chapter
file: blank reader content, with nothing to tell the two cases apart or let
the user retry.

**Fixed, first pass (commit 5426a3d):** the catch rethrew
`CancellationException` and otherwise threw a new `ChapterReadException`
instead of returning empty bytes; the reader showed an error string in place
of the chapter body. That string was still handed to `ReaderScreen` as if it
were chapter text, and there was no retry.

**Fixed, remainder:** `ChapterContentRepository.getTextContent` now returns a
sealed `ChapterLoadResult` (`Ok` / `Failed`), still rethrowing
`CancellationException`; `ChapterReadException` is removed. On `Failed`,
`MainActivity` shows `ui/ChapterLoadErrorScreen` ("Try again" re-runs the
load in place, "Back" leaves the reader) instead of `ReaderScreen`, so
read-aloud and progress-saving never see error text as content.

### F8. `MainActivity` and the worker each build their own `AppDatabase` — fixed

**Severity:** Low. **Tags:** V, E (Room's own singleton guidance not re-checked this pass).
**Where:** `MainActivity.kt:493` (`db = AppDatabase.create(this)`),
`data/NewChapterCheckWorker.kt:40` (`val db = AppDatabase.create(applicationContext)`).

Room's own guidance is that a `RoomDatabase` should be a per-process
singleton — each `Room.databaseBuilder(...).build()` call opens its own
connection and keeps its own in-memory cache, so two live instances in the
same process (Activity open with the worker running concurrently) don't see
each other's writes until the underlying SQLite file is re-read, and double
the connection overhead for no benefit. `NewChapterCheckWorker`'s own comment
explains why it can't just borrow `MainActivity`'s instance (a worker can run
long after, or without, the Activity existing in-process) — the fix is a
small `object`-held singleton inside `AppDatabase.create()` itself (build once
per process, return the cached instance on subsequent calls), not sharing a
reference across Activity/Worker boundaries.

**Fixed:** `AppDatabase.create()` now returns that per-process instance.

### F11. `splitForTts` can cut mid-surrogate-pair, and only breaks on ASCII punctuation — fixed

**Severity:** Low. **Tags:** V (code read), I (CJK/emoji breakage not run on a device).
**Where:** `playback/TtsPlaybackService.kt`, `splitForTts()` (was `ui/Tts.kt` when this was written).

The hard cut at `TTS_MAX_CHUNK_CHARS` (3800) operates on `String` indices,
which are UTF-16 code units; a surrogate pair (most emoji, some rare CJK
characters) straddling that boundary gets split in half, producing two
invalid half-characters. The break search only looks for `\n . ! ?`. CJK
sentence punctuation (`。` `！` `？`) isn't in that set even though
`ScannerImpl` already has CJK-aware arc-folder patterns elsewhere, so a
CJK-text chapter has no recognized break point at all and gets hard-cut mid
sentence regardless of a fitting chunk boundary nearby. `.` also matches
non-sentence-ending periods ("3.14", "Mr.", an ellipsis's first dot), which
can chunk a sentence mid-thought even in English text.

**Direction:** `java.text.BreakIterator.getSentenceInstance(locale)` is
ICU-backed, locale-aware sentence segmentation already in the Android
platform (no new dependency) and handles all three cases above. `splitForTts`
is a pure function, so it's cheap to cover with unit tests asserting: no
chunk exceeds the limit, no text is lost or duplicated across the returned
chunks, and a CJK-only chapter actually produces more than one chunk when it
exceeds the limit.

**Fixed:** `splitForTts` packs whole `BreakIterator` sentences into each
chunk and only hard-cuts a single sentence that is longer than the limit
itself, at the last whitespace or CJK comma in the window, never between the
two halves of a surrogate pair. `SplitForTtsTest` covers the three properties
above plus decimal points and emoji. One limit worth knowing: `BreakIterator`
has no abbreviation list, so "Mr. Smith" can still be a sentence boundary.
That only matters when it happens to land on a chunk edge, and a break there
is harmless for speech. Not run on a device.

### F18. `WebViewScreen` enables JS and DOM storage, and follows any link in-WebView — fixed (links)

**Severity:** Low. **Tags:** V.
**Where:** `ui/WebViewScreen.kt`.

`settings.javaScriptEnabled = true` and `settings.domStorageEnabled = true`
are both on, and `webViewClient = object : WebViewClientCompat() {}` doesn't
override `shouldOverrideUrlLoading`, so the default behavior applies: every
link tap navigates within this WebView, on whatever host it points to, not
just the author's own site the screen was opened to show.

**Direction:** override `shouldOverrideUrlLoading` to allow navigation only
within the author's own host and send anything else out to the system
browser via an `Intent`, so a link embedded in author-controlled page content
can't pivot the user to an arbitrary site while still appearing to be inside
ARKarium.

**Fixed (links):** `shouldOverrideUrlLoading` keeps a tap inside the WebView only
when its host matches the page the screen was opened to (`isSameSite`, ignoring
`www.` and case). Any other `http(s)` or `mailto:` link goes to the system via
`ACTION_VIEW`; any other scheme (`intent:`, `file:`, `javascript:`) is dropped.
JS and DOM storage stay on deliberately: the author's site needs them, and
with navigation restricted to that site the exposure is the site's own code.
`WebViewLinkPolicyTest` covers `isSameSite`.

### F20. Some icons have `contentDescription = null` with no check that they're decorative — audited

**Severity:** Low. **Tags:** V.
**Where:** at least one occurrence each in `AuthorPageScreen.kt`,
`FictionBrowseScreen.kt`, `HomeScreen.kt`, `LegalDocumentScreen.kt`,
`LibrarySettingsScreen.kt`, `MetadataMatchDialog.kt`, `NovelDetailScreen.kt`,
`ReaderScreen.kt`, `SettingsScreen.kt`, `SplashScreen.kt`, `SyncDialogs.kt`,
`ThemeSettingsScreen.kt`, `TtsSettingsScreen.kt`.

`contentDescription = null` is correct for a purely decorative icon sitting
next to its own text label, but it hasn't been audited screen-by-screen to
confirm every occurrence is actually decorative rather than the icon being
the only indicator of what a control does (an icon-only button with no
adjacent label has no accessible name at all with `null`).

**Direction:** a pass through each listed file, screen reader on, checking
every icon-only `IconButton`/`Icon` specifically; give each a real
`contentDescription` unless it sits directly beside visible text that already
says the same thing.

**Audited:** all 32 `contentDescription = null` sites across those files were
read. 29 are decorative: an icon beside its own text label, or a cover or
avatar image beside the title it belongs to. Left as `null`. Fixed:

- `NovelDetailScreen`'s enlarged-cover dialog: the image is the only content,
  now "Enlarged cover".
- `ReaderScreen`'s title row under the top bar: the row's only text is the
  fiction's name, so it gained `onClickLabel = "Back to fiction"`.
- `NovelDetailScreen`'s notify row: merged into one accessibility node so a
  disabled (not-synced) row doesn't leave an unlabeled switch.
- Arc covers: a TalkBack custom action "View cover full-size", since a
  double-tap is TalkBack's "activate" gesture and can never reach the app's own
  double-tap-to-enlarge.

Not done: nobody has run this with a screen reader on a device, which this
entry's **Direction** called for. It was done by reading each site in context.
