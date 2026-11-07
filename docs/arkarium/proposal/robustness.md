# Robustness: cancellation, silent failure, duplicate state, input handling

> **Kind:** proposal · **Status:** F6/F7 fixed in code, rest open · **Verified:** 2026-11-07 @ 5426a3d
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

**Fixed (commit 5426a3d):** the catch rethrows `CancellationException` and
otherwise throws a new `ChapterReadException` instead of returning empty
bytes. The one call site (`MainActivity.kt`, the reader's `LaunchedEffect`)
now catches `ChapterReadException` and shows "Couldn't load this chapter.
Pull to refresh or try again later." instead of silently rendering nothing.

**Still open:** this is a minimal fix (an error string in place of the
chapter body), not the sealed `Ok`/`Failed` result type the original review
suggested, and there's no retry action yet — only the message. A proper fix
would give `ChapterContentRepository` a sealed return type so the reader can
offer a real retry button rather than requiring the user to navigate away and
back.

### F8. `MainActivity` and the worker each build their own `AppDatabase`

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

### F11. `splitForTts` can cut mid-surrogate-pair, and only breaks on ASCII punctuation

**Severity:** Low. **Tags:** V (code read), I (CJK/emoji breakage not run on a device).
**Where:** `ui/Tts.kt`, `splitForTts()`.

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

### F18. `WebViewScreen` enables JS and DOM storage, and follows any link in-WebView

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

### F20. Some icons have `contentDescription = null` with no check that they're decorative

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
